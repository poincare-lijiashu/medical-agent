package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.CodingCorrection;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HomepageCoding;
import com.medassist.emr.domain.EmrModel.IcdDiagnosis;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.CodingDiagnosisReq;
import com.medassist.emr.web.EmrDtos.CorrectionReasonReq;
import com.medassist.emr.web.EmrDtos.CreateCorrectionReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 病案编码更正：编码完成后才可申请、同一首页仅一张待审批、四眼审批、审批回写首页快照。
 */
class CodingCorrectionServiceTest {

    private EmrRepository repo;
    private HomepageCodingService service;

    private final UUID homepageId = UUID.randomUUID();
    private final UUID correctionId = UUID.randomUUID();
    private final UUID submitterId = UUID.randomUUID();

    private final IcdDiagnosis dx = new IcdDiagnosis("I10.x00", "原发性高血压",
            "IX 循环系统疾病", "I10-I15", true, 0L);

    private static final String PROPOSED =
            "[{\"code\":\"I10.x00\",\"name\":\"原发性高血压\",\"primary\":true}]";

    private final CodingCorrection submitted = new CodingCorrection(correctionId,
            "CC20261104000001", homepageId, CodingCorrection.SUBMITTED, "主诊断选错",
            PROPOSED, "[]", null, "coder01", null, null, null, null, null, null, 0L,
            Instant.now());

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        service = new HomepageCodingService(repo, JsonMapper.builder().build());
        when(repo.findIcdDiagnoses(any())).thenReturn(List.of(dx));
        when(repo.findIcdProcedures(any())).thenReturn(List.of());
        when(repo.nextCorrectionNo()).thenReturn("CC20261104000001");
        when(repo.insertCorrection(any(), any())).thenReturn(true);
        when(repo.findCorrection(correctionId)).thenReturn(Optional.of(submitted));
    }

    @Test
    @DisplayName("申请更正：编码未完成 → 409")
    void submitRejectsUncodedHomepage() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));
        when(repo.findCoding(homepageId)).thenReturn(Optional.of(
                coding(HomepageCoding.CODING)));

        assertThatThrownBy(() -> service.submitCorrection(homepageId, request(), "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_CORRECTION_NOT_CODED);
        verify(repo, never()).insertCorrection(any(), any());
    }

    @Test
    @DisplayName("申请更正：已归档首页 → 409")
    void submitRejectsArchivedHomepage() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.ARCHIVED, 2)));

        assertThatThrownBy(() -> service.submitCorrection(homepageId, request(), "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID);
    }

    @Test
    @DisplayName("申请更正：同一首页已有待审批申请 → 422")
    void submitRejectsDuplicatePending() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));
        when(repo.findCoding(homepageId)).thenReturn(Optional.of(coding(HomepageCoding.CODED)));
        when(repo.insertCorrection(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.submitCorrection(homepageId, request(), "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_CORRECTION_INVALID);
    }

    @Test
    @DisplayName("申请更正：字典校验后落单，名称由服务端回填")
    void submitPersistsValidatedContent() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 0)));
        when(repo.findCoding(homepageId)).thenReturn(Optional.of(coding(HomepageCoding.CODED)));
        when(repo.findCorrection(any())).thenReturn(Optional.of(submitted));

        var view = service.submitCorrection(homepageId, request(), "coder01");

        assertThat(view.status()).isEqualTo(CodingCorrection.SUBMITTED);
        assertThat(view.diagnoses()).hasSize(1);
        assertThat(view.diagnoses().get(0).get("name")).isEqualTo("原发性高血压");
        verify(repo).insertCorrection(any(), any());
    }

    @Test
    @DisplayName("审批更正：申请人自审 → 403")
    void approveRejectsSelfReview() {
        UUID requesterId = UUID.randomUUID();
        when(repo.lockCorrection(correctionId)).thenReturn(Optional.of(submitted));
        when(repo.isCorrectionRequesterIdMissing(correctionId)).thenReturn(false);
        when(repo.isCorrectionRequestedBy(correctionId, requesterId)).thenReturn(true);

        assertThatThrownBy(() -> service.approveCorrection(correctionId, null, "coder01-rebuilt",
                requesterId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_CORRECTION_SELF_REVIEW);
        verify(repo, never()).applyCodedSnapshot(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("G55：历史更正申请缺 requester_id 时拒绝审批（fail-closed）")
    void approveRejectsMissingRequesterId() {
        when(repo.lockCorrection(correctionId)).thenReturn(Optional.of(submitted));
        when(repo.isCorrectionRequesterIdMissing(correctionId)).thenReturn(true);

        assertThatThrownBy(() -> service.approveCorrection(correctionId, null, "qc01",
                UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_CORRECTION_SELF_REVIEW);
        verify(repo, never()).applyCodedSnapshot(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("审批更正：回写首页快照并记录审批人")
    void approveAppliesSnapshot() {
        Homepage homepage = homepage(Homepage.SUBMITTED, 3);
        when(repo.lockCorrection(correctionId)).thenReturn(Optional.of(submitted));
        when(repo.lockHomepage(homepageId)).thenReturn(Optional.of(homepage));
        when(repo.applyCodedSnapshot(homepageId, PROPOSED, "[]", homepage.coder(), 3L))
                .thenReturn(true);
        when(repo.reviewCorrection(correctionId, 0L, "qc02", true, "核对无误"))
                .thenReturn(true);
        CodingCorrection approved = new CodingCorrection(correctionId,
                submitted.correctionNo(), homepageId, CodingCorrection.APPROVED, submitted.reason(),
                PROPOSED, "[]", null, "coder01", "qc02", Instant.now(), "核对无误",
                null, null, null, 1L, submitted.createdAt());
        when(repo.findCorrection(correctionId)).thenReturn(Optional.of(approved));

        var view = service.approveCorrection(correctionId, reviewReq(), "qc02");

        assertThat(view.status()).isEqualTo(CodingCorrection.APPROVED);
        verify(repo).applyCodedSnapshot(homepageId, PROPOSED, "[]", homepage.coder(), 3L);
        verify(repo).reviewCorrection(correctionId, 0L, "qc02", true, "核对无误");
    }

    @Test
    @DisplayName("审批更正：已归档首页不再回写 → 409")
    void approveRejectsArchivedHomepage() {
        when(repo.lockCorrection(correctionId)).thenReturn(Optional.of(submitted));
        when(repo.lockHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.ARCHIVED, 5)));

        assertThatThrownBy(() -> service.approveCorrection(correctionId, null, "qc02"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID);
        verify(repo, never()).applyCodedSnapshot(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("审批更正：首页版本冲突 → 409")
    void approveRejectsSnapshotConflict() {
        when(repo.lockCorrection(correctionId)).thenReturn(Optional.of(submitted));
        when(repo.lockHomepage(homepageId)).thenReturn(Optional.of(homepage(Homepage.SUBMITTED, 3)));
        when(repo.applyCodedSnapshot(any(), any(), any(), any(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.approveCorrection(correctionId, null, "qc02"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("驳回更正：必须写原因")
    void rejectRequiresReason() {
        when(repo.lockCorrection(correctionId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.rejectCorrection(correctionId,
                new CorrectionReasonReq(" "), "qc02"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_CORRECTION_REASON_REQUIRED);
    }

    @Test
    @DisplayName("撤回更正：必须写原因，且不回写首页")
    void cancelRequiresReasonAndSkipsSnapshot() {
        when(repo.lockCorrection(correctionId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.cancelCorrection(correctionId,
                new CorrectionReasonReq(""), "coder01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CODING_CORRECTION_CANCEL_REASON_REQUIRED);

        when(repo.cancelCorrection(correctionId, 0L, "coder01", "重复提交")).thenReturn(true);
        CodingCorrection cancelled = new CodingCorrection(correctionId,
                submitted.correctionNo(), homepageId, CodingCorrection.CANCELLED,
                submitted.reason(), PROPOSED, "[]", null, "coder01", null, null, null,
                "coder01", Instant.now(), "重复提交", 1L, submitted.createdAt());
        when(repo.findCorrection(correctionId)).thenReturn(Optional.of(cancelled));

        var view = service.cancelCorrection(correctionId,
                new CorrectionReasonReq("重复提交"), "coder01");

        assertThat(view.status()).isEqualTo(CodingCorrection.CANCELLED);
        verify(repo, never()).applyCodedSnapshot(any(), any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("更正列表：首页不存在 → 404")
    void listRejectsUnknownHomepage() {
        when(repo.findHomepage(homepageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.corrections(homepageId, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_HOMEPAGE_NOT_FOUND);
    }

    private CreateCorrectionReq request() {
        return new CreateCorrectionReq("主诊断选错",
                List.of(new CodingDiagnosisReq("I10.x00", true)), List.of(), null);
    }

    private com.medassist.emr.web.EmrDtos.ReviewCorrectionReq reviewReq() {
        return new com.medassist.emr.web.EmrDtos.ReviewCorrectionReq("核对无误");
    }

    private HomepageCoding coding(String status) {
        return new HomepageCoding(UUID.randomUUID(), homepageId, status, "coder01",
                HomepageCoding.CODED.equals(status) ? Instant.now() : null, null, PROPOSED,
                "[]", 1L, Instant.now(), Instant.now());
    }

    private Homepage homepage(String status, long version) {
        boolean submitted = Homepage.SUBMITTED.equals(status);
        return new Homepage(homepageId, "HP20261103000001", UUID.randomUUID(), null,
                UUID.randomUUID(), "P20261030000001", "张三", "M", LocalDate.of(1980, 1, 1), 46,
                "V001", UUID.randomUUID(), "内科", null, "一病区", "01",
                Instant.now().minusSeconds(86400 * 5), Instant.now().minusSeconds(3600), 5,
                "李医生", null, null, null, null, "NONE", null, null, null, null, null,
                0, 0, false, false, null, null, "coder01",
                "[]", "[]", "{}", new BigDecimal("100.00"), status,
                submitted ? "admin01" : null, submitted ? submitterId : null,
                submitted ? Instant.now() : null, null, null, version, Instant.now());
    }
}
