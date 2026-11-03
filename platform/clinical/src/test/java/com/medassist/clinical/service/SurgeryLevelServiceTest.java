package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryLevel;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 手术分级规则：1~2 级免审批、3~4 级必须审批、四眼原则、CAS、开台门槛。
 */
class SurgeryLevelServiceTest {

    private ClinicalRepository repo;
    private SurgeryLevelService service;

    private final UUID caseId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID levelId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new SurgeryLevelService(repo);
        // 写路径（setLevel/approve/reject）现在用 lockSurgeryCase 与开台共用一把行锁；
        // 测试里让它与只读查询返回同一份数据，避免 19 处 stub 重复写两遍。
        when(repo.lockSurgeryCase(any())).thenAnswer(inv ->
                repo.findSurgeryCase(inv.getArgument(0)));
    }

    private SurgeryCase surgery(String status) {
        return new SurgeryCase(caseId, "OP20261101000001", visitId, null, UUID.randomUUID(),
                "47.0901", "阑尾切除术", Instant.now(), null, null, "doctor01", null,
                "GENERAL", "II", "II", status, null, "doctor01", 0L, Instant.now());
    }

    private SurgeryLevel level(int level, String status, String appliedBy, long version) {
        return new SurgeryLevel(levelId, caseId, level, status, appliedBy,
                Instant.parse("2026-11-01T01:00:00Z"), null, null, null, appliedBy, version,
                Instant.parse("2026-11-01T01:00:00Z"));
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @DisplayName("查询分级：未分级返回 null（未分级是正常状态，不是错误）")
    void getReturnsNullWhenAbsent() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId)).thenReturn(Optional.empty());

        assertThat(service.get(caseId)).isNull();
    }

    @Test
    @DisplayName("查询分级：手术记录不存在 → 404")
    void getRejectsUnknownCase() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- 提交分级

    @Test
    @DisplayName("首次提交 1 级：自动免审批并留审计")
    void setLevelOneIsAutoApproved() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(level(1, SurgeryLevel.NOT_REQUIRED, "doctor01", 0L)));

        SurgeryLevel result = service.setLevel(caseId, 1, null, "doctor01");

        ArgumentCaptor<SurgeryLevel> captor = ArgumentCaptor.forClass(SurgeryLevel.class);
        verify(repo).insertSurgeryLevel(captor.capture());
        assertThat(captor.getValue().level()).isEqualTo(1);
        assertThat(captor.getValue().approvalStatus()).isEqualTo(SurgeryLevel.NOT_REQUIRED);
        assertThat(captor.getValue().appliedBy()).isEqualTo("doctor01");
        assertThat(result.approvalStatus()).isEqualTo(SurgeryLevel.NOT_REQUIRED);
        verify(repo).logChange(eq("doctor01"), eq("SURGERY_LEVEL_SET"), eq("SURGERY_LEVEL"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("首次提交 3 级：置为待审批（PENDING）")
    void setLevelThreeIsPending() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 0L)));

        SurgeryLevel result = service.setLevel(caseId, 3, null, "doctor01");

        assertThat(result.level()).isEqualTo(3);
        assertThat(result.approvalStatus()).isEqualTo(SurgeryLevel.PENDING);
    }

    @Test
    @DisplayName("提交分级：手术已开始 → 422（分级只能在开台前确定）")
    void setLevelRejectsStartedSurgery() {
        when(repo.findSurgeryCase(caseId))
                .thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));

        assertThatThrownBy(() -> service.setLevel(caseId, 1, null, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertSurgeryLevel(any());
    }

    @Test
    @DisplayName("提交分级：超出 1~4 → 422")
    void setLevelRejectsOutOfRange() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.setLevel(caseId, 5, null, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("提交分级：同级申请审批中 → 409（防止改写申请人绕过四眼原则）")
    void setLevelRejectsDuplicatePending() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 0L)));

        assertThatThrownBy(() -> service.setLevel(caseId, 3, 0L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).resetSurgeryLevel(any(), anyInt(), anyString(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("提交分级：已审批通过的同一分级不能重复提交 → 409")
    void setLevelRejectsApprovedSameLevel() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.APPROVED, "doctor01", 1L)));

        assertThatThrownBy(() -> service.setLevel(caseId, 3, 1L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("调整分级：缺 version → 422，CAS 失败 → 409")
    void setLevelRequiresVersionThenCas() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(4, SurgeryLevel.APPROVED, "doctor01", 2L)));

        assertThatThrownBy(() -> service.setLevel(caseId, 3, null, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        when(repo.resetSurgeryLevel(eq(caseId), eq(3), eq(SurgeryLevel.PENDING), eq("doctor01"),
                eq(2L))).thenReturn(false);
        assertThatThrownBy(() -> service.setLevel(caseId, 3, 2L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("被驳回后可按原分级重新申请（清空审批结论，重新进入待审批）")
    void setLevelAllowsReapplyAfterReject() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.REJECTED, "doctor01", 1L)))
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 2L)));
        when(repo.resetSurgeryLevel(caseId, 3, SurgeryLevel.PENDING, "doctor01", 1L))
                .thenReturn(true);

        SurgeryLevel result = service.setLevel(caseId, 3, 1L, "doctor01");

        assertThat(result.approvalStatus()).isEqualTo(SurgeryLevel.PENDING);
        verify(repo).logChange(eq("doctor01"), eq("SURGERY_LEVEL_SET"), eq("SURGERY_LEVEL"),
                anyString(), anyString());
    }

    // ---------------------------------------------------------------- 审批

    @Test
    @DisplayName("审批：无分级记录 → 404")
    void approveRequiresRecord() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(caseId, 0L, "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("审批：一、二级手术无需审批 → 422")
    void approveRejectsLowLevel() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(2, SurgeryLevel.NOT_REQUIRED, "doctor01", 0L)));

        assertThatThrownBy(() -> service.approve(caseId, 0L, "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("审批：非待审批状态 → 409")
    void approveRejectsNonPending() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.APPROVED, "doctor01", 1L)));

        assertThatThrownBy(() -> service.approve(caseId, 1L, "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("审批：四眼原则——申请人不能审批自己的申请 → 422")
    void approveRejectsSelfReview() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 0L)));

        assertThatThrownBy(() -> service.approve(caseId, 0L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("四眼原则");
                });
        verify(repo, never()).approveSurgeryLevel(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("审批：CAS 失败 → 409")
    void approveCasConflict() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(4, SurgeryLevel.PENDING, "doctor01", 3L)));
        when(repo.approveSurgeryLevel(caseId, "qc01", 3L)).thenReturn(false);

        assertThatThrownBy(() -> service.approve(caseId, 3L, "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("审批通过：落审批人并留审计")
    void approveOk() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 0L)))
                .thenReturn(Optional.of(level(3, SurgeryLevel.APPROVED, "doctor01", 1L)));
        when(repo.approveSurgeryLevel(caseId, "qc01", 0L)).thenReturn(true);

        SurgeryLevel result = service.approve(caseId, 0L, "qc01");

        assertThat(result.approvalStatus()).isEqualTo(SurgeryLevel.APPROVED);
        verify(repo).logChange(eq("qc01"), eq("SURGERY_LEVEL_APPROVE"), eq("SURGERY_LEVEL"),
                anyString(), anyString());
    }

    // ---------------------------------------------------------------- 驳回

    @Test
    @DisplayName("驳回：必须写原因 → 422")
    void rejectRequiresReason() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 0L)));

        assertThatThrownBy(() -> service.reject(caseId, 0L, "   ", "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).rejectSurgeryLevel(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("驳回：同样受四眼原则约束 → 422")
    void rejectRejectsSelfReview() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(4, SurgeryLevel.PENDING, "qc01", 0L)));

        assertThatThrownBy(() -> service.reject(caseId, 0L, "资料不全", "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("驳回成功：状态 REJECTED 并留原因与审计")
    void rejectOk() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 0L)))
                .thenReturn(Optional.of(new SurgeryLevel(levelId, caseId, 3,
                        SurgeryLevel.REJECTED, "doctor01", Instant.now(), "qc01", Instant.now(),
                        "资料不全", "doctor01", 1L, Instant.now())));
        when(repo.rejectSurgeryLevel(caseId, "资料不全", "qc01", 0L)).thenReturn(true);

        SurgeryLevel result = service.reject(caseId, 0L, "资料不全", "qc01");

        assertThat(result.approvalStatus()).isEqualTo(SurgeryLevel.REJECTED);
        assertThat(result.rejectReason()).isEqualTo("资料不全");
        verify(repo).logChange(eq("qc01"), eq("SURGERY_LEVEL_REJECT"), eq("SURGERY_LEVEL"),
                anyString(), anyString());
    }

    // ---------------------------------------------------------------- 开台门槛

    @Test
    @DisplayName("开台门槛：未分级 → 422")
    void startGateRejectsMissingRecord() {
        when(repo.findSurgeryLevel(caseId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireStartApproved(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("未分级");
                });
    }

    @Test
    @DisplayName("开台门槛：三级审批中 → 422，提示等待审批")
    void startGateRejectsPending() {
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 0L)));

        assertThatThrownBy(() -> service.requireStartApproved(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("审批");
                });
    }

    @Test
    @DisplayName("开台门槛：已驳回 → 422，带出驳回原因")
    void startGateRejectsRejected() {
        when(repo.findSurgeryLevel(caseId)).thenReturn(Optional.of(new SurgeryLevel(levelId,
                caseId, 3, SurgeryLevel.REJECTED, "doctor01", Instant.now(), "qc01",
                Instant.now(), "资料不全", "doctor01", 1L, Instant.now())));

        assertThatThrownBy(() -> service.requireStartApproved(caseId))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("资料不全");
                });
    }

    @Test
    @DisplayName("开台门槛：一级免审批、四级已审批 → 放行")
    void startGateAllowsCleared() {
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(1, SurgeryLevel.NOT_REQUIRED, "doctor01", 0L)))
                .thenReturn(Optional.of(level(4, SurgeryLevel.APPROVED, "doctor01", 1L)));

        assertThatCode(() -> service.requireStartApproved(caseId)).doesNotThrowAnyException();
        assertThatCode(() -> service.requireStartApproved(caseId)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 并发护栏

    @Test
    @DisplayName("并发护栏：改分级必须持手术行锁（与开台同一锁域，防 TOCTOU）")
    void setLevelTakesRowLock() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(level(1, SurgeryLevel.NOT_REQUIRED, "doctor01", 0L)));

        service.setLevel(caseId, 1, null, "doctor01");

        // 没有这把锁，"开台读分级 → 改分级提交 → 开台落状态" 之间会漏过审批门槛
        verify(repo).lockSurgeryCase(caseId);
    }

    @Test
    @DisplayName("并发护栏：审批也必须持手术行锁")
    void approveTakesRowLock() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findSurgeryLevel(caseId))
                .thenReturn(Optional.of(level(3, SurgeryLevel.PENDING, "doctor01", 0L)))
                .thenReturn(Optional.of(level(3, SurgeryLevel.APPROVED, "doctor01", 1L)));
        when(repo.approveSurgeryLevel(caseId, "qc01", 0L)).thenReturn(true);

        service.approve(caseId, 0L, "qc01");

        verify(repo).lockSurgeryCase(caseId);
    }
}
