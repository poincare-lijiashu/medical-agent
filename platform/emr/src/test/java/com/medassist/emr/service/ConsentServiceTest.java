package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.client.ClinicalClient;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.ConsentRecord;
import com.medassist.emr.domain.EmrModel.ConsentTemplate;
import com.medassist.emr.repo.ConsentRepository;
import com.medassist.emr.service.CaSignatureProvider.CaSignResult;
import com.medassist.emr.web.ConsentDtos.ConsentView;
import com.medassist.emr.web.ConsentDtos.CreateConsentReq;
import com.medassist.emr.web.ConsentDtos.CreateTemplateReq;
import com.medassist.emr.web.ConsentDtos.RevokeReq;
import com.medassist.emr.web.ConsentDtos.SignReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知情同意书规则：模板发布门槛、签署人必填、CA 签名、撤销原因、内容哈希校验。
 */
class ConsentServiceTest {

    private ConsentRepository repo;
    private PatientClient patientClient;
    private ClinicalClient clinicalClient;
    private CaSignatureProvider caProvider;
    private ConsentService service;

    private final UUID consentId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();
    private final UUID templateId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ConsentRepository.class);
        patientClient = mock(PatientClient.class);
        clinicalClient = mock(ClinicalClient.class);
        caProvider = mock(CaSignatureProvider.class);
        service = new ConsentService(repo, patientClient, clinicalClient, caProvider,
                JsonMapper.builder().build());
        when(patientClient.requireActive(patientId)).thenReturn(new PatientClient.PatientRef(
                patientId, "P20261104000001", "张三", "MALE", LocalDate.of(1980, 5, 20)));
        when(clinicalClient.visit(visitId)).thenReturn(Optional.of(
                new ClinicalClient.VisitRef(patientId, null)));
    }

    // ---------------------------------------------------------------- templates

    @Test
    @DisplayName("模板：编码重复拒绝，新建为 DRAFT")
    void createTemplateRejectsDuplicate() {
        when(repo.findTemplateByCode("CONSENT-SURGERY")).thenReturn(Optional.of(template(
                ConsentTemplate.PUBLISHED)));

        assertThatThrownBy(() -> service.createTemplate(new CreateTemplateReq(
                "CONSENT-SURGERY", "手术同意书", "SURGERY", "内容", null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertTemplate(any());
    }

    @Test
    @DisplayName("模板：只有 DRAFT 可以发布，CAS 失败返回 409")
    void publishRequiresDraftAndVersion() {
        when(repo.findTemplate(templateId)).thenReturn(Optional.of(template(
                ConsentTemplate.PUBLISHED)));

        assertThatThrownBy(() -> service.publishTemplate(templateId, 0L, "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSENT_TEMPLATE_STATUS_INVALID);

        when(repo.findTemplate(templateId)).thenReturn(Optional.of(template(ConsentTemplate.DRAFT)));
        when(repo.updateTemplateStatus(templateId, ConsentTemplate.DRAFT,
                ConsentTemplate.PUBLISHED, 3L)).thenReturn(false);
        assertThatThrownBy(() -> service.publishTemplate(templateId, 3L, "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- create

    @Test
    @DisplayName("创建：模板未发布拒绝，已发布则固化正文与哈希")
    void createRejectsUnpublishedTemplate() {
        when(repo.findTemplateByCode("CONSENT-SURGERY"))
                .thenReturn(Optional.of(template(ConsentTemplate.DRAFT)));

        assertThatThrownBy(() -> service.create(new CreateConsentReq(
                patientId, visitId, "CONSENT-SURGERY", "阑尾切除术"), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSENT_TEMPLATE_NOT_FOUND);
        verify(repo, never()).insertConsent(any());
    }

    @Test
    @DisplayName("创建：就诊不属于该患者 → 422")
    void createRejectsVisitOwnershipMismatch() {
        when(repo.findTemplateByCode("CONSENT-SURGERY"))
                .thenReturn(Optional.of(template(ConsentTemplate.PUBLISHED)));
        when(clinicalClient.visit(visitId)).thenReturn(Optional.of(
                new ClinicalClient.VisitRef(UUID.randomUUID(), null)));

        assertThatThrownBy(() -> service.create(new CreateConsentReq(
                patientId, visitId, "CONSENT-SURGERY", "阑尾切除术"), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("创建：渲染模板占位符并计算 SHA-256")
    void createSnapshotsContentAndHash() {
        when(repo.findTemplateByCode("CONSENT-SURGERY"))
                .thenReturn(Optional.of(template(ConsentTemplate.PUBLISHED)));
        when(repo.nextConsentNo()).thenReturn("IC20261104000001");
        AtomicReference<ConsentRecord> saved = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return null;
        }).when(repo).insertConsent(any(ConsentRecord.class));
        when(repo.findConsent(any())).thenAnswer(inv -> Optional.of(saved.get()));

        ConsentView view = service.create(new CreateConsentReq(patientId, visitId,
                "CONSENT-SURGERY", "阑尾切除术"), "doctor01");

        ConsentRecord record = saved.get();
        assertThat(record.status()).isEqualTo(ConsentRecord.DRAFT);
        assertThat(record.contentText()).contains("张三").contains("阑尾切除术")
                .doesNotContain("{{");
        assertThat(record.contentHash()).isEqualTo(RecordService.sha256(record.contentText()));
        assertThat(view.consentNo()).isEqualTo("IC20261104000001");
    }

    // ---------------------------------------------------------------- sign

    @Test
    @DisplayName("签署：缺少签署人/关系 → 47424，且不写库")
    void signRequiresSigner() {
        when(repo.lockConsent(consentId)).thenReturn(Optional.of(draft()));

        assertThatThrownBy(() -> service.sign(consentId,
                new SignReq(" ", "本人", null, "doctor01", false, 0L), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSENT_SIGNER_REQUIRED);
        verify(repo, never()).signConsent(any(), anyLong(), anyString(), anyString(), any(),
                anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("签署：非草稿 → 409")
    void signRejectsNonDraft() {
        ConsentRecord signed = consent(ConsentRecord.SIGNED);
        when(repo.lockConsent(consentId)).thenReturn(Optional.of(signed));

        assertThatThrownBy(() -> service.sign(consentId,
                new SignReq("李四", "配偶", "王五", "doctor01", false, 0L), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSENT_STATUS_INVALID);
    }

    @Test
    @DisplayName("签署：PAPER 记录签署人与时间，不产生证书字段")
    void signPaper() {
        when(repo.lockConsent(consentId)).thenReturn(Optional.of(draft()));
        when(repo.signConsent(eq(consentId), eq(0L), eq("李四"), eq("配偶"), eq("王五"),
                eq("doctor01"), any(Instant.class), eq(ConsentRecord.SIGN_PAPER),
                isNull(), isNull(), isNull())).thenReturn(true);
        when(repo.findConsent(consentId)).thenReturn(Optional.of(consent(ConsentRecord.SIGNED)));

        ConsentView view = service.sign(consentId,
                new SignReq("李四", "配偶", "王五", "doctor01", false, 0L), "doctor01");

        assertThat(view.status()).isEqualTo(ConsentRecord.SIGNED);
        verify(caProvider, never()).sign(any());
    }

    @Test
    @DisplayName("签署：CA 模式保存证书序列号/算法/签名值")
    void signWithCa() {
        when(repo.lockConsent(consentId)).thenReturn(Optional.of(draft()));
        Instant signedAt = Instant.parse("2026-11-04T08:00:00Z");
        when(caProvider.sign(any())).thenReturn(new CaSignResult("MOCK-李四", "SIG",
                "HMAC-SHA256", signedAt));
        when(repo.signConsent(eq(consentId), eq(0L), eq("李四"), eq("配偶"), isNull(),
                eq("doctor01"), eq(signedAt), eq(ConsentRecord.SIGN_CA), eq("MOCK-李四"),
                eq("SIG"), eq("HMAC-SHA256"))).thenReturn(true);
        when(repo.findConsent(consentId)).thenReturn(Optional.of(consent(ConsentRecord.SIGNED)));

        service.sign(consentId,
                new SignReq("李四", "配偶", null, "doctor01", true, 0L), "doctor01");

        verify(caProvider).sign(any());
    }

    @Test
    @DisplayName("签署：CAS 未命中 → 409")
    void signCasConflict() {
        when(repo.lockConsent(consentId)).thenReturn(Optional.of(draft()));
        when(repo.signConsent(any(), anyLong(), anyString(), anyString(), any(), anyString(),
                any(), anyString(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.sign(consentId,
                new SignReq("李四", "配偶", null, "doctor01", false, 0L), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- revoke / verify

    @Test
    @DisplayName("撤销：原因必填；只有已签署可撤销")
    void revokeRules() {
        assertThatThrownBy(() -> service.revoke(consentId, new RevokeReq(" ", 0L), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSENT_REVOKE_REASON_REQUIRED);

        when(repo.lockConsent(consentId)).thenReturn(Optional.of(draft()));
        assertThatThrownBy(() -> service.revoke(consentId, new RevokeReq("患者反悔", 0L), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSENT_STATUS_INVALID);
    }

    @Test
    @DisplayName("验签：正文哈希不一致 → 47426")
    void verifyDetectsHashMismatch() {
        ConsentRecord tampered = new ConsentRecord(consentId, "IC1", templateId,
                "CONSENT-SURGERY", 1, "手术知情同意书", patientId, "P1", "张三", visitId,
                "阑尾切除术", "被篡改的正文", "deadbeef", ConsentRecord.SIGNED, "李四",
                "配偶", null, "doctor01", Instant.now(), ConsentRecord.SIGN_PAPER,
                null, null, null, null, null, null, "doctor01", Instant.now(), Instant.now(), 1L);
        when(repo.findConsent(consentId)).thenReturn(Optional.of(tampered));

        assertThatThrownBy(() -> service.verify(consentId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSENT_SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("列表：非法状态 → 422")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list(null, "BAD", 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- helpers

    private ConsentTemplate template(String status) {
        return new ConsentTemplate(templateId, "CONSENT-SURGERY", "手术知情同意书",
                "SURGERY", "患者：{{patientName}} 操作：{{procedureName}} 医师：{{doctorName}}",
                1, status, "admin01", Instant.now(), Instant.now(), 0L);
    }

    private ConsentRecord draft() {
        return consent(ConsentRecord.DRAFT);
    }

    private ConsentRecord consent(String status) {
        Instant now = Instant.now();
        return new ConsentRecord(consentId, "IC20261104000001", templateId, "CONSENT-SURGERY",
                1, "手术知情同意书", patientId, "P20261104000001", "张三", visitId,
                "阑尾切除术", "患者：张三 操作：阑尾切除术", "hash-1", status, "李四",
                "配偶", "王五", "doctor01", now, ConsentRecord.SIGN_PAPER,
                null, null, null, null, null, null, "doctor01", now, now, 0L);
    }
}
