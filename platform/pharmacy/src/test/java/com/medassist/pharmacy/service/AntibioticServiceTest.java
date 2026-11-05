package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticApproval;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticDoctorStatRow;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticDrugStatRow;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticLevelStatRow;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticOverLimitRow;
import com.medassist.pharmacy.domain.PharmacyModel.AntibioticPendingRow;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.AntibioticDtos.ApprovalReq;
import com.medassist.pharmacy.web.AntibioticDtos.ApprovalView;
import com.medassist.pharmacy.web.AntibioticDtos.StatsView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抗菌药物专项（G33）：特殊使用级审批约束与统计口径。
 */
class AntibioticServiceTest {

    private PharmacyRepository repo;
    private PrescriptionAccessGuard guard;
    private AntibioticService service;

    private final UUID rxId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final DataScope scope = DataScope.all("pharm01", true);

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        guard = mock(PrescriptionAccessGuard.class);
        service = new AntibioticService(repo, JsonMapper.builder().build(), guard);
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    private Prescription rx(String status) {
        return new Prescription(rxId, "RX20261104000001", patientId, "P20261104000001", null,
                null, null, "doctor01", status, null, null, null, new BigDecimal("100.00"),
                0L, Instant.now(), Prescription.BILLING_ORDER, null, null);
    }

    @Test
    @DisplayName("审批：非特殊使用级处方 → 422，不落审批")
    void approveRejectsNonSpecialLevel() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW)));
        when(repo.maxAntibioticLevel(rxId)).thenReturn(2);

        assertCode(() -> service.approve(rxId, new ApprovalReq(true, null), "pharm01", scope),
                ErrorCode.ANTIBIOTIC_APPROVAL_INVALID);
        verify(repo, never()).upsertAntibioticApproval(any());
    }

    @Test
    @DisplayName("审批：已发药处方 → 409，不允许事后补审批")
    void approveRejectsNonApprovableStatus() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.DISPENSED)));
        when(repo.maxAntibioticLevel(rxId)).thenReturn(3);

        assertCode(() -> service.approve(rxId, new ApprovalReq(true, null), "pharm01", scope),
                ErrorCode.PRESCRIPTION_STATUS_INVALID);
        verify(repo, never()).upsertAntibioticApproval(any());
    }

    @Test
    @DisplayName("审批：审批人=开方医师 → 409（四眼原则）")
    void approveRejectsSelfApproval() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW)));
        when(repo.maxAntibioticLevel(rxId)).thenReturn(3);

        assertCode(() -> service.approve(rxId, new ApprovalReq(true, null), "doctor01", scope),
                ErrorCode.ANTIBIOTIC_SELF_APPROVAL);
        verify(repo, never()).upsertAntibioticApproval(any());
    }

    @Test
    @DisplayName("驳回：必须填写理由 → 422")
    void rejectRequiresNote() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW)));
        when(repo.maxAntibioticLevel(rxId)).thenReturn(3);

        assertCode(() -> service.approve(rxId, new ApprovalReq(false, "  "), "pharm01", scope),
                ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).upsertAntibioticApproval(any());
    }

    @Test
    @DisplayName("审批通过：记录分级/审批人并写审计")
    void approvePersistsApprovalAndAudit() {
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.PENDING_REVIEW)));
        when(repo.maxAntibioticLevel(rxId)).thenReturn(3);
        when(repo.findAntibioticApproval(rxId)).thenReturn(Optional.empty(), Optional.of(
                new AntibioticApproval(UUID.randomUUID(), rxId, 3, AntibioticApproval.APPROVED,
                        "会诊同意", "doctor01", Instant.now(), "pharm01", Instant.now(), 0L)));

        ApprovalView view = service.approve(rxId, new ApprovalReq(true, "会诊同意"), "pharm01", scope);

        ArgumentCaptor<AntibioticApproval> captor =
                ArgumentCaptor.forClass(AntibioticApproval.class);
        verify(repo).upsertAntibioticApproval(captor.capture());
        AntibioticApproval saved = captor.getValue();
        assertThat(saved.prescriptionId()).isEqualTo(rxId);
        assertThat(saved.maxLevel()).isEqualTo(3);
        assertThat(saved.status()).isEqualTo(AntibioticApproval.APPROVED);
        assertThat(saved.note()).isEqualTo("会诊同意");
        assertThat(saved.requestedBy()).isEqualTo("doctor01");
        assertThat(saved.reviewedBy()).isEqualTo("pharm01");
        assertThat(view.requiredLevel()).isEqualTo(3);
        assertThat(view.approval()).isNotNull();
        verify(repo).logChange(eq("pharm01"), eq("ANTIBIOTIC_APPROVE"), eq("PRESCRIPTION"),
                eq(rxId.toString()), any(String.class));
    }

    @Test
    @DisplayName("审批覆盖：保留原申请人，仅更新审批结论")
    void approveKeepsOriginalRequester() {
        AntibioticApproval existing = new AntibioticApproval(UUID.randomUUID(), rxId, 3,
                AntibioticApproval.REJECTED, "资料不全", "doctor01", Instant.now().minusSeconds(60),
                "pharm02", Instant.now().minusSeconds(30), 1L);
        when(repo.lockPrescription(rxId)).thenReturn(Optional.of(rx(Prescription.APPROVED)));
        when(repo.maxAntibioticLevel(rxId)).thenReturn(3);
        when(repo.findAntibioticApproval(rxId)).thenReturn(Optional.of(existing));

        service.approve(rxId, new ApprovalReq(true, null), "pharm01", scope);

        ArgumentCaptor<AntibioticApproval> captor =
                ArgumentCaptor.forClass(AntibioticApproval.class);
        verify(repo).upsertAntibioticApproval(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(existing.id());
        assertThat(captor.getValue().requestedBy()).isEqualTo("doctor01");
        assertThat(captor.getValue().requestedAt()).isEqualTo(existing.requestedAt());
        assertThat(captor.getValue().version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("统计：起始晚于截止 → 422")
    void statsRejectsInvertedRange() {
        assertCode(() -> service.stats(LocalDate.now(), LocalDate.now().minusDays(1), scope),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("统计：超过 366 天 → 422")
    void statsRejectsTooLongRange() {
        assertCode(() -> service.stats(LocalDate.now().minusDays(400), LocalDate.now(), scope),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("统计：汇总分级/医师/药品/超疗程，并以 3 天为默认阈值")
    void statsAggregatesRows() {
        when(repo.antibioticLevelStats(any(), any(), any())).thenReturn(List.of(
                new AntibioticLevelStatRow(2, 4, 12), new AntibioticLevelStatRow(3, 2, 5)));
        when(repo.antibioticDoctorStats(any(), any(), any())).thenReturn(List.of(
                new AntibioticDoctorStatRow("doctor01", 6, 17)));
        when(repo.antibioticDrugStats(any(), any(), any())).thenReturn(List.of(
                new AntibioticDrugStatRow("DRUG-ABX3", "特殊级抗菌药", 3, 2, 5)));
        when(repo.antibioticOverLimit(any(), any(), eq(3), anyInt(), any())).thenReturn(List.of(
                new AntibioticOverLimitRow(rxId, "RX20261104000001", "P1", "doctor01",
                        "特殊级抗菌药", 7, Instant.now())));
        when(repo.countAntibioticPending(any())).thenReturn(2);

        StatsView view = service.stats(null, null, scope);

        assertThat(view.rxCount()).isEqualTo(6);
        assertThat(view.specialRxCount()).isEqualTo(2);
        assertThat(view.pendingCount()).isEqualTo(2);
        assertThat(view.prophylaxisDays()).isEqualTo(3);
        assertThat(view.levels()).hasSize(2);
        assertThat(view.doctors()).hasSize(1);
        assertThat(view.drugs()).hasSize(1);
        assertThat(view.overLimit()).hasSize(1);
        assertThat(view.from()).isEqualTo(view.to().minusDays(29));
        verify(repo).antibioticOverLimit(any(OffsetDateTime.class), any(OffsetDateTime.class),
                eq(3), eq(50), any());
    }

    @Test
    @DisplayName("G59 待审列表：limit 缺省 50，上限 200；无 phi:read 时患者号掩码")
    void pendingNormalizesLimit() {
        when(repo.antibioticPending(anyInt(), any())).thenReturn(List.of(
                new AntibioticPendingRow(rxId, "RX20261104000001", "P20261104000001", "doctor01",
                        Prescription.PENDING_REVIEW, "特殊级抗菌药×2", Instant.now())));

        assertThat(service.pending(null, scope)).hasSize(1);
        verify(repo).antibioticPending(50, "");

        service.pending(9999, scope);
        verify(repo).antibioticPending(200, "");
    }

    @Test
    @DisplayName("G59：无 phi:read 权限时待审列表患者号掩码")
    void pendingMasksPatientNoWithoutPhiRead() {
        when(repo.antibioticPending(anyInt(), any())).thenReturn(List.of(
                new AntibioticPendingRow(rxId, "RX20261104000001", "P20261104000001", "doctor01",
                        Prescription.PENDING_REVIEW, "特殊级抗菌药×2", Instant.now())));

        var rows = service.pending(10, DataScope.all("pharm01", false));

        assertThat(rows.get(0).patientNo()).doesNotContain("20261104000001");
        assertThat(rows.get(0).patientNo()).contains("*");
    }

    @Test
    @DisplayName("G59：审批前经院区守卫校验（跨院区 404）")
    void approveChecksCampusGuard() {
        doThrow(new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND))
                .when(guard).require(any(), any());

        assertCode(() -> service.approve(rxId, new ApprovalReq(true, null), "pharm01", scope),
                ErrorCode.PRESCRIPTION_NOT_FOUND);
        verify(repo, never()).upsertAntibioticApproval(any());
    }
}
