package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.PreopDiscussion;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryConsent;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PreopDtos.ConsentReq;
import com.medassist.clinical.web.PreopDtos.PreopReq;
import com.medassist.clinical.web.PreopDtos.PreopSummary;
import com.medassist.clinical.web.PreopDtos.RevokeConsentReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 术前管理：讨论只能在计划态、签署需三要素、同意书作废留原因、开台门槛清单。 */
class PreopServiceTest {

    private ClinicalRepository repo;
    private PreopService service;

    private final UUID caseId = UUID.randomUUID();
    private final UUID preopId = UUID.randomUUID();
    private final UUID consentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        service = new PreopService(repo);
    }

    private SurgeryCase surgery(String status) {
        return new SurgeryCase(caseId, "OP20261102000001", UUID.randomUUID(), null,
                UUID.randomUUID(), "47.0901", "阑尾切除术", Instant.now(), null, null,
                "doctor01", null, "GENERAL", "II", "II", status, null, "doctor01", 0L,
                Instant.now());
    }

    private PreopDiscussion preop(String status, String moderator, String participants,
                                  String plan, long version) {
        return new PreopDiscussion(preopId, caseId, Instant.now(), moderator, participants,
                null, plan, null, null, null, status,
                PreopDiscussion.SIGNED.equals(status) ? "doctor01" : null,
                PreopDiscussion.SIGNED.equals(status) ? Instant.now() : null,
                "doctor01", version, Instant.now());
    }

    private SurgeryConsent consent(String type, boolean active) {
        return new SurgeryConsent(consentId, caseId, type, "张三", "本人", Instant.now(),
                "nurse01", null, active ? null : Instant.now(), active ? null : "写错姓名",
                "doctor01", 0L, Instant.now());
    }

    // ---------------------------------------------------------------- 术前讨论

    @Test
    @DisplayName("写术前讨论：手术已开始 → 422（术前讨论只能术前写）")
    void saveDiscussionRejectsStartedCase() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.IN_PROGRESS)));

        assertThatThrownBy(() -> service.saveDiscussion(caseId,
                new PreopReq(null, "doctor01", "doctor02,doctor03", null, "方案", null, null, null,
                        null), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertPreop(any());
    }

    @Test
    @DisplayName("写术前讨论：首次保存落 DRAFT")
    void saveDiscussionCreates() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopByCase(caseId)).thenReturn(Optional.empty());

        PreopDiscussion created = service.saveDiscussion(caseId,
                new PreopReq(Instant.now(), " doctor01 ", " doctor02 ", null, "腹腔镜阑尾切除", null,
                        null, null, null), "doctor01");

        assertThat(created.status()).isEqualTo(PreopDiscussion.DRAFT);
        assertThat(created.moderator()).isEqualTo("doctor01");
        verify(repo).insertPreop(any());
    }

    @Test
    @DisplayName("写术前讨论：已签署 → 409")
    void saveDiscussionRejectsSigned() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopByCase(caseId)).thenReturn(Optional.of(
                preop(PreopDiscussion.SIGNED, "doctor01", "doctor02", "方案", 2L)));

        assertThatThrownBy(() -> service.saveDiscussion(caseId,
                new PreopReq(null, "doctor01", "doctor02", null, "方案", null, null, null, 2L),
                "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).updatePreopDraft(any(), anyLong());
    }

    @Test
    @DisplayName("写术前讨论：更新草稿不带 version → 422")
    void saveDiscussionRequiresVersion() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopByCase(caseId)).thenReturn(Optional.of(
                preop(PreopDiscussion.DRAFT, "doctor01", "doctor02", "方案", 1L)));

        assertThatThrownBy(() -> service.saveDiscussion(caseId,
                new PreopReq(null, "doctor01", "doctor02", null, "方案", null, null, null, null),
                "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("签署术前讨论：缺主持人/参加人/手术方案 → 422")
    void signDiscussionRequiresContent() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopByCase(caseId)).thenReturn(Optional.of(
                preop(PreopDiscussion.DRAFT, null, "doctor02", null, 0L)));

        assertThatThrownBy(() -> service.signDiscussion(caseId, 0L, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getMessage()).contains("主持人").contains("手术方案");
                });
        verify(repo, never()).signPreop(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("签署术前讨论：内容齐全 → 签名成功")
    void signDiscussionOk() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findPreopByCase(caseId))
                .thenReturn(Optional.of(preop(PreopDiscussion.DRAFT, "doctor01", "doctor02",
                                "腹腔镜阑尾切除", 0L)),
                        Optional.of(preop(PreopDiscussion.SIGNED, "doctor01", "doctor02",
                                "腹腔镜阑尾切除", 1L)));
        when(repo.signPreop(preopId, "doctor01", 0L)).thenReturn(true);

        PreopDiscussion signed = service.signDiscussion(caseId, 0L, "doctor01");

        assertThat(signed.status()).isEqualTo(PreopDiscussion.SIGNED);
        verify(repo).signPreop(preopId, "doctor01", 0L);
    }

    // ---------------------------------------------------------------- 知情同意

    @Test
    @DisplayName("新增同意书：手术已结束 → 422")
    void addConsentRejectsCompletedCase() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.COMPLETED)));

        assertThatThrownBy(() -> service.addConsent(caseId,
                new ConsentReq("SURGERY", "张三", "本人", null, null, null), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).insertConsent(any());
    }

    @Test
    @DisplayName("新增同意书：同类型已有有效记录 → 409（必须作废后重签）")
    void addConsentRejectsDuplicateType() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findActiveConsent(caseId, "SURGERY"))
                .thenReturn(Optional.of(consent("SURGERY", true)));

        assertThatThrownBy(() -> service.addConsent(caseId,
                new ConsentReq("surgery", "张三", "本人", null, null, null), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).insertConsent(any());
    }

    @Test
    @DisplayName("新增同意书：类型归一化为大写，签署时间缺省为当前")
    void addConsentNormalizesType() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.findActiveConsent(caseId, "ANESTHESIA")).thenReturn(Optional.empty());

        SurgeryConsent created = service.addConsent(caseId,
                new ConsentReq("anesthesia", "李四", "配偶", null, "nurse01", null), "doctor01");

        assertThat(created.consentType()).isEqualTo("ANESTHESIA");
        assertThat(created.signedAt()).isNotNull();
        assertThat(created.active()).isTrue();
        verify(repo).insertConsent(any());
    }

    @Test
    @DisplayName("作废同意书：原因为空 → 422")
    void revokeConsentRequiresReason() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));

        assertThatThrownBy(() -> service.revokeConsent(caseId, consentId,
                new RevokeConsentReq(0L, "  "), "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).revokeConsent(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("作废同意书：成功后可重签同类型")
    void revokeConsentOk() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.listConsentsByCase(caseId))
                .thenReturn(List.of(consent("SURGERY", true)))
                .thenReturn(List.of(consent("SURGERY", false)));
        when(repo.revokeConsent(consentId, "姓名写错", 0L)).thenReturn(true);

        SurgeryConsent revoked = service.revokeConsent(caseId, consentId,
                new RevokeConsentReq(0L, "姓名写错"), "doctor01");

        assertThat(revoked.active()).isFalse();
        assertThat(revoked.revokeReason()).isEqualTo("写错姓名");
        verify(repo).revokeConsent(consentId, "姓名写错", 0L);
    }

    // ---------------------------------------------------------------- 术前核查清单

    @Test
    @DisplayName("术前清单：缺两类同意书 → 不可开台，缺失项齐全")
    void summaryMissingConsents() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.listConsentsByCase(caseId)).thenReturn(List.of());
        when(repo.findPreopByCase(caseId)).thenReturn(Optional.empty());

        PreopSummary summary = service.summary(caseId);

        assertThat(summary.missingConsents()).containsExactly("SURGERY", "ANESTHESIA");
        assertThat(summary.readyForSurgery()).isFalse();
        assertThat(summary.discussion()).isNull();
    }

    @Test
    @DisplayName("术前清单：两类同意书齐全 + 计划态 → 可开台")
    void summaryReadyForSurgery() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.listConsentsByCase(caseId)).thenReturn(List.of(
                consent("SURGERY", true), consent("ANESTHESIA", true)));
        when(repo.findPreopByCase(caseId)).thenReturn(Optional.of(
                preop(PreopDiscussion.SIGNED, "doctor01", "doctor02", "方案", 1L)));

        PreopSummary summary = service.summary(caseId);

        assertThat(summary.missingConsents()).isEmpty();
        assertThat(summary.readyForSurgery()).isTrue();
        assertThat(summary.discussion().status()).isEqualTo(PreopDiscussion.SIGNED);
    }

    @Test
    @DisplayName("术前清单：已作废的同意书不计入齐全")
    void summaryIgnoresRevokedConsent() {
        when(repo.findSurgeryCase(caseId)).thenReturn(Optional.of(surgery(SurgeryCase.PLANNED)));
        when(repo.listConsentsByCase(caseId)).thenReturn(List.of(
                consent("SURGERY", false), consent("ANESTHESIA", true)));
        when(repo.findPreopByCase(caseId)).thenReturn(Optional.empty());

        PreopSummary summary = service.summary(caseId);

        assertThat(summary.missingConsents()).containsExactly("SURGERY");
        assertThat(summary.readyForSurgery()).isFalse();
    }
}
