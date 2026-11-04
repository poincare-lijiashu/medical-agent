package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.client.ClinicalClient;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.ChangeRequest;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.QcResult;
import com.medassist.emr.domain.EmrModel.QcRule;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.domain.EmrModel.Template;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.AmendReq;
import com.medassist.emr.web.EmrDtos.ChangeRequestReq;
import com.medassist.emr.web.EmrDtos.CreateRecordReq;
import com.medassist.emr.web.EmrDtos.HashVerify;
import com.medassist.emr.web.EmrDtos.QcReport;
import com.medassist.emr.web.EmrDtos.RecordView;
import com.medassist.emr.web.EmrDtos.ReviewReq;
import com.medassist.emr.web.EmrDtos.SaveContentReq;
import com.medassist.emr.web.EmrDtos.SubmitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 病历核心规则：质控阻断提交、签名哈希、状态机、修订留痕与重新签名。
 */
class RecordServiceTest {

    private EmrRepository repo;
    private PatientClient patientClient;
    private ClinicalClient clinicalClient;
    private CaSignatureService caSignatureService;
    private RecordService service;

    private final UUID recordId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    private static final String SCHEMA = """
            [{"key":"chiefComplaint","label":"主诉","type":"TEXT","required":true},
             {"key":"physicalExam","label":"体格检查","type":"TEXTAREA","required":true},
             {"key":"initialDiagnosis","label":"初步诊断","type":"TEXTAREA","required":true}]
            """;

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        patientClient = mock(PatientClient.class);
        clinicalClient = mock(ClinicalClient.class);
        caSignatureService = mock(CaSignatureService.class);
        service = new RecordService(repo, patientClient, clinicalClient, caSignatureService,
                JsonMapper.builder().build());
        when(patientClient.requireActive(patientId)).thenReturn(new PatientClient.PatientRef(
                patientId, "P20261031000001", "张三", "MALE", java.time.LocalDate.of(1980, 5, 20)));
        when(repo.nextRecordNo()).thenReturn("MR20261031000001");
        when(repo.findTemplateByCode("TPL-ADMISSION")).thenReturn(Optional.of(template()));
        when(repo.listSignatures(any())).thenReturn(List.of());
        when(repo.listAmendments(any())).thenReturn(List.of());
        when(repo.listQcResults(any(), anyInt())).thenReturn(List.of());
    }

    private Template template() {
        return new Template(UUID.randomUUID(), "TPL-ADMISSION", "入院记录（通用）",
                "ADMISSION_NOTE", "INPATIENT", SCHEMA, true, 0L);
    }

    private MedicalRecord record(String status, int revision, long version, String contentJson) {
        return new MedicalRecord(recordId, "MR20261031000001", patientId, "P20261031000001",
                null, null, null, "ADMISSION_NOTE", "TPL-ADMISSION", "入院记录",
                contentJson, "主诉：发热\n体格检查：咽红\n初步诊断：急性上呼吸道感染\n",
                "doctor01", status, revision, null, null, null, null, version, Instant.now());
    }

    private List<QcRule> rules() {
        return List.of(
                new QcRule(UUID.randomUUID(), "QC-ADM-CC", "主诉不得为空", "ADMISSION_NOTE",
                        QcRule.REQUIRED_FIELD, "chiefComplaint", null, QcRule.FATAL, "主诉为必填项", true),
                new QcRule(UUID.randomUUID(), "QC-ADM-DX", "初步诊断不少于 10 字", "ADMISSION_NOTE",
                        QcRule.MIN_LENGTH, "initialDiagnosis", 10, QcRule.MAJOR, "初步诊断过于简略", true));
    }

    private QcRule timelinessRule(int hours) {
        return new QcRule(UUID.randomUUID(), "QC-ADM-TIME", "入院记录 24 小时内完成",
                "ADMISSION_NOTE", QcRule.TIMELINESS, null, hours, QcRule.MAJOR,
                "入院记录应在患者入院后 24 小时内完成", true);
    }

    // ---------------------------------------------------------------- 渲染与哈希

    @Test
    @DisplayName("渲染：按模板顺序输出标签，模板外字段按 key 排序追加")
    void renderIsDeterministic() {
        String text = service.render(SCHEMA, Map.of(
                "initialDiagnosis", "急性上呼吸道感染", "chiefComplaint", "发热", "extra", "X"));

        assertThat(text).isEqualTo(
                "主诉：发热\n体格检查：\n初步诊断：急性上呼吸道感染\nextra：X\n");
        assertThat(service.render(SCHEMA, Map.of("chiefComplaint", "发热",
                "initialDiagnosis", "急性上呼吸道感染", "extra", "X"))).isEqualTo(text);
    }

    @Test
    @DisplayName("哈希：同样内容得到同样 SHA-256")
    void hashIsStable() {
        assertThat(RecordService.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    // ---------------------------------------------------------------- 创建

    @Test
    @DisplayName("创建：按模板带出文书类型，状态 DRAFT、revision 1")
    void createDraft() {
        when(repo.findRecord(any())).thenAnswer(inv -> Optional.of(
                record(MedicalRecord.DRAFT, 1, 0L, "{\"chiefComplaint\":\"发热\"}")));

        RecordView view = service.create(new CreateRecordReq(patientId, null, null, null,
                "TPL-ADMISSION", null, Map.of("chiefComplaint", "发热")), "doctor01");

        assertThat(view.record().status()).isEqualTo(MedicalRecord.DRAFT);
        assertThat(view.record().revision()).isEqualTo(1);
        assertThat(view.record().docType()).isEqualTo("ADMISSION_NOTE");
        verify(repo).insertRecord(any(MedicalRecord.class), org.mockito.ArgumentMatchers.isNull(),
                eq(com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID));
        verify(repo).logChange(eq("doctor01"), eq("RECORD_CREATE"), eq("RECORD"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("创建：模板停用时拒绝")
    void createRejectsDisabledTemplate() {
        when(repo.findTemplateByCode("TPL-ADMISSION")).thenReturn(Optional.of(
                new Template(UUID.randomUUID(), "TPL-ADMISSION", "入院记录", "ADMISSION_NOTE",
                        "INPATIENT", SCHEMA, false, 1L)));

        assertThatThrownBy(() -> service.create(new CreateRecordReq(patientId, null, null, null,
                "TPL-ADMISSION", null, Map.of()), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_TEMPLATE_NOT_FOUND);
    }

    @Test
    @DisplayName("保存：已提交病历不能再改正文（必须走修订）")
    void saveRejectsNonDraft() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 2L, "{}")));

        assertThatThrownBy(() -> service.save(recordId,
                new SaveContentReq(Map.of("chiefComplaint", "x"), 2L), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_RECORD_STATUS_INVALID);
    }

    // ---------------------------------------------------------------- 质控

    @Test
    @DisplayName("提交：FATAL 质控未通过时 409，且不落库")
    void submitBlockedByFatalQc() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 1L, "{\"chiefComplaint\":\"\"}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(rules());

        assertThatThrownBy(() -> service.submit(recordId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("主诉");
        verify(repo, never()).updateStatus(any(), any(), any(), anyLong());
        verify(repo, never()).insertQcResult(any());
    }

    @Test
    @DisplayName("提交：只有 MAJOR 未通过时放行（记明细，不阻断）")
    void submitPassesWithMajorOnly() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(record(MedicalRecord.DRAFT, 1, 1L,
                "{\"chiefComplaint\":\"发热\",\"physicalExam\":\"咽红\",\"initialDiagnosis\":\"上感\"}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(rules());
        when(repo.updateStatus(recordId, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, 1L))
                .thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 2L, "{}")));

        SubmitResult result = service.submit(recordId, "doctor01");

        assertThat(result.qc().passed()).isTrue();
        assertThat(result.qc().majorCount()).isEqualTo(1);
        // 两条规则都落质控快照（通过与否都记录，便于追溯"当时为什么能提交"）
        verify(repo, org.mockito.Mockito.times(2)).insertQcResult(any(QcResult.class));
        verify(repo).updateStatus(recordId, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, 1L);
    }

    @Test
    @DisplayName("质控预览：不落库")
    void previewQcDoesNotPersist() {
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 1L, "{\"chiefComplaint\":\"发热\"}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(rules());

        QcReport report = service.previewQc(recordId);

        assertThat(report.fatalCount()).isZero();
        assertThat(report.majorCount()).isEqualTo(1);
        verify(repo, never()).insertQcResult(any());
    }

    // ---------------------------------------------------------------- 时限质控

    @Test
    @DisplayName("时限质控：入院超过 24 小时记 MAJOR（不阻断提交）")
    void timelinessViolationIsMajor() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 1L, "{}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(List.of(timelinessRule(24)));
        when(repo.visitStartedAt(recordId)).thenReturn(Optional.of(
                Instant.now().minus(java.time.Duration.ofHours(30))));
        when(repo.updateStatus(recordId, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, 1L))
                .thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 2L, "{}")));

        SubmitResult result = service.submit(recordId, "doctor01");

        assertThat(result.qc().passed()).isTrue();
        assertThat(result.qc().majorCount()).isEqualTo(1);
        assertThat(result.qc().results().get(0).message()).contains("已 30 小时");
    }

    @Test
    @DisplayName("时限质控：入院 2 小时内提交通过")
    void timelinessWithinWindowPasses() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 1L, "{}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(List.of(timelinessRule(24)));
        when(repo.visitStartedAt(recordId)).thenReturn(Optional.of(
                Instant.now().minus(java.time.Duration.ofHours(2))));
        when(repo.updateStatus(recordId, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, 1L))
                .thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 2L, "{}")));

        SubmitResult result = service.submit(recordId, "doctor01");

        assertThat(result.qc().passed()).isTrue();
        assertThat(result.qc().majorCount()).isZero();
        assertThat(result.qc().results()).hasSize(1);
        assertThat(result.qc().results().get(0).passed()).isTrue();
    }

    @Test
    @DisplayName("时限质控：无入院信息时落 NOT_EVALUATED 待核查项（不再静默跳过）")
    void timelinessNotEvaluatedWithoutAdmission() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 1L, "{}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(List.of(timelinessRule(24)));
        when(repo.visitStartedAt(recordId)).thenReturn(Optional.empty());
        when(repo.updateStatus(recordId, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, 1L))
                .thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 2L, "{}")));

        SubmitResult result = service.submit(recordId, "doctor01");

        assertThat(result.qc().passed()).isTrue();
        assertThat(result.qc().minorCount()).isEqualTo(1);
        assertThat(result.qc().results()).hasSize(1);
        assertThat(result.qc().results().get(0).ruleCode())
                .isEqualTo("QC-ADM-TIME" + RecordService.NOT_EVALUATED_SUFFIX);
        assertThat(result.qc().results().get(0).passed()).isFalse();
        assertThat(result.qc().results().get(0).message()).contains("未评估");
    }

    // ---------------------------------------------------------------- 签名 / 归档

    @Test
    @DisplayName("签名：仅 SUBMITTED 可签，落签名记录与哈希")
    void signRequiresSubmitted() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 3L, "{}")));
        when(repo.signRecord(eq(recordId), eq("doctor01"), anyString(), eq(3L))).thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 4L, "{}")));

        service.sign(recordId, "doctor01");

        verify(repo).signRecord(eq(recordId), eq("doctor01"), anyString(), eq(3L));
        verify(repo).insertSignature(any());
        verify(repo).logChange(eq("doctor01"), eq("RECORD_SIGN"), eq("RECORD"),
                eq(recordId.toString()), anyString());
    }

    @Test
    @DisplayName("签名：草稿状态拒绝")
    void signRejectsDraft() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 0L, "{}")));

        assertThatThrownBy(() -> service.sign(recordId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_RECORD_STATUS_INVALID);
    }

    @Test
    @DisplayName("归档：仅 SIGNED 可归档")
    void archiveRequiresSigned() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 2L, "{}")));

        assertThatThrownBy(() -> service.archive(recordId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_RECORD_STATUS_INVALID);
    }

    // ---------------------------------------------------------------- 修订

    @Test
    @DisplayName("修订：已归档病历拒绝修订")
    void amendRejectsArchived() {
        MedicalRecord archived = new MedicalRecord(recordId, "MR1", patientId, "P1", null, null,
                null, "ADMISSION_NOTE", "TPL-ADMISSION", "入院记录", "{}", "text",
                "doctor01", MedicalRecord.ARCHIVED, 1, "doctor01", Instant.now(), "hash",
                Instant.now(), 5L, Instant.now());
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.amend(recordId,
                new AmendReq(Map.of("chiefComplaint", "x"), "补正"), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已归档");
    }

    @Test
    @DisplayName("修订：签名后修订先留旧版本、revision+1 并清空签名")
    void amendKeepsHistory() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 4L, "{\"chiefComplaint\":\"发热\"}")));
        when(repo.applyAmendment(eq(recordId), anyString(), anyString(), eq(4L),
                eq(MedicalRecord.SIGNED))).thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 2, 5L, "{}")));

        service.amend(recordId, new AmendReq(Map.of("chiefComplaint", "发热伴咳嗽"), "补充主诉"), "doctor01");

        verify(repo).insertAmendment(any(Amendment.class));
        verify(repo).applyAmendment(eq(recordId), anyString(), anyString(), eq(4L),
                eq(MedicalRecord.SIGNED));
        verify(repo).logChange(eq("doctor01"), eq("RECORD_AMEND"), eq("RECORD"),
                eq(recordId.toString()), anyString());
    }

    // ---------------------------------------------------------------- 哈希校验

    @Test
    @DisplayName("哈希校验：内容未变则 valid=true")
    void verifyDetectsUntampered() {
        String text = "主诉：发热\n";
        MedicalRecord signed = new MedicalRecord(recordId, "MR1", patientId, "P1", null, null,
                null, "ADMISSION_NOTE", "TPL-ADMISSION", "入院记录", "{}", text,
                "doctor01", MedicalRecord.SIGNED, 1, "doctor01", Instant.now(),
                RecordService.sha256(text), null, 4L, Instant.now());
        when(repo.findRecord(recordId)).thenReturn(Optional.of(signed));

        HashVerify verify = service.verify(recordId);

        assertThat(verify.valid()).isTrue();
        assertThat(verify.storedHash()).isEqualTo(verify.recomputedHash());
    }

    @Test
    @DisplayName("哈希校验：内容被改则 valid=false")
    void verifyDetectsTampering() {
        MedicalRecord tampered = new MedicalRecord(recordId, "MR1", patientId, "P1", null, null,
                null, "ADMISSION_NOTE", "TPL-ADMISSION", "入院记录", "{}", "主诉：被改过\n",
                "doctor01", MedicalRecord.SIGNED, 1, "doctor01", Instant.now(),
                RecordService.sha256("主诉：原始\n"), null, 4L, Instant.now());
        when(repo.findRecord(recordId)).thenReturn(Optional.of(tampered));

        HashVerify verify = service.verify(recordId);

        assertThat(verify.valid()).isFalse();
    }

    // ---------------------------------------------------------------- 三级签名

    @Test
    @DisplayName("三级签名：跳级签名被拒绝")
    void countersignRejectsSkippingLevel() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 4L, "{}")));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.sign(recordId, RecordSignature.ATTENDING, "chief01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_LEVEL_INVALID);
        verify(repo, never()).countersign(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("三级签名：书写医师签完后可由主治加签，内容哈希沿用")
    void countersignSucceedsAfterAuthor() {
        MedicalRecord signed = record(MedicalRecord.SIGNED, 1, 5L, "{}");
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(signed));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(1);
        when(repo.countersign(eq(recordId), eq("attending01"), anyString(), eq(5L))).thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(signed));

        service.sign(recordId, RecordSignature.ATTENDING, "attending01");

        verify(repo).countersign(eq(recordId), eq("attending01"), anyString(), eq(5L));
        verify(repo).insertSignature(any(RecordSignature.class));
        verify(repo).logChange(eq("attending01"), eq("RECORD_SIGN"), eq("RECORD"),
                eq(recordId.toString()), anyString());
    }

    @Test
    @DisplayName("归档：未达到文书要求的签名级别时拒绝")
    void archiveBlockedBySignPolicy() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 5L, "{}")));
        when(repo.requiredSignLevel("ADMISSION_NOTE")).thenReturn(Optional.of(2));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(1);

        assertThatThrownBy(() -> service.archive(recordId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_LEVEL_INVALID);
        verify(repo, never()).archiveRecord(any(), anyLong());
    }

    @Test
    @DisplayName("归档：达到要求级别后放行")
    void archivePassesAtRequiredLevel() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 5L, "{}")));
        when(repo.requiredSignLevel("ADMISSION_NOTE")).thenReturn(Optional.of(2));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(2);
        when(repo.archiveRecord(recordId, 5L)).thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(archivedRecord()));

        service.archive(recordId, "chief01");

        verify(repo).archiveRecord(recordId, 5L);
    }

    // ---------------------------------------------------------------- 归档后修改申请

    @Test
    @DisplayName("病案修改申请：未归档病历拒绝走申请流程")
    void changeRequestRequiresArchived() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 5L, "{}")));

        assertThatThrownBy(() -> service.requestChange(recordId,
                new ChangeRequestReq(Map.of("chiefComplaint", "发热"), "补充主诉", null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_RECORD_STATUS_INVALID);
        verify(repo, never()).insertChangeRequest(any());
    }

    @Test
    @DisplayName("病案修改申请：已有待审批申请时拒绝重复提交")
    void changeRequestRejectsDuplicatePending() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(archivedRecord()));
        when(repo.hasPendingChangeRequest(recordId)).thenReturn(true);

        assertThatThrownBy(() -> service.requestChange(recordId,
                new ChangeRequestReq(Map.of("chiefComplaint", "发热"), "补充主诉", null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_CHANGE_REQUEST_INVALID);
        verify(repo, never()).insertChangeRequest(any());
    }

    @Test
    @DisplayName("病案修改申请：申请人不得审批自己的申请（四眼原则）")
    void approveRejectsSelfReview() {
        when(repo.lockChangeRequest(reqId)).thenReturn(Optional.of(pendingRequest("doctor01")));

        assertThatThrownBy(() -> service.approveChange(recordId, reqId,
                new ReviewReq("同意"), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_CHANGE_REQUEST_INVALID);
        verify(repo, never()).applyAmendment(any(), anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("病案修改申请：审批通过后留档旧版本、revision+1 并回到 SUBMITTED")
    void approveAppliesChange() {
        when(repo.lockChangeRequest(reqId)).thenReturn(Optional.of(pendingRequest("doctor01")));
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(archivedRecord()));
        when(repo.applyAmendment(eq(recordId), anyString(), anyString(), eq(6L),
                eq(MedicalRecord.ARCHIVED))).thenReturn(true);
        when(repo.reviewChangeRequest(eq(reqId), eq(ChangeRequest.APPROVED), eq("qc01"),
                any(), eq(2))).thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 2, 7L, "{}")));

        service.approveChange(recordId, reqId, new ReviewReq("同意补正"), "qc01");

        verify(repo).insertAmendment(any(Amendment.class));
        verify(repo).applyAmendment(eq(recordId), anyString(), anyString(), eq(6L),
                eq(MedicalRecord.ARCHIVED));
        verify(repo).reviewChangeRequest(eq(reqId), eq(ChangeRequest.APPROVED), eq("qc01"),
                any(), eq(2));
    }

    @Test
    @DisplayName("病案修改申请：驳回只改申请状态，不动病历正文")
    void rejectKeepsRecordUntouched() {
        ChangeRequest pending = pendingRequest("doctor01");
        ChangeRequest rejected = new ChangeRequest(reqId, recordId, "MR20261031000001",
                "补充诊断依据", "{}", "text", ChangeRequest.REJECTED, "doctor01",
                Instant.now(), "qc01", Instant.now(), "依据不足", null, null);
        when(repo.lockChangeRequest(reqId)).thenReturn(Optional.of(pending), Optional.of(rejected));
        when(repo.reviewChangeRequest(eq(reqId), eq(ChangeRequest.REJECTED), eq("qc01"),
                eq("依据不足"), org.mockito.ArgumentMatchers.isNull())).thenReturn(true);

        ChangeRequest result = service.rejectChange(recordId, reqId,
                new ReviewReq("依据不足"), "qc01");

        assertThat(result.status()).isEqualTo(ChangeRequest.REJECTED);
        verify(repo, never()).applyAmendment(any(), anyString(), anyString(), anyLong(), anyString());
        verify(repo, never()).insertAmendment(any());
    }

    // ---------------------------------------------------------------- 签署人身份（MF-02）

    @Test
    @DisplayName("签名：非书写医师不能代签 AUTHOR")
    void nonAuthorCannotSignAuthor() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 3L, "{}")));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(0);

        assertThatThrownBy(() -> service.sign(recordId, RecordSignature.AUTHOR, "other01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_LEVEL_INVALID);
        verify(repo, never()).signRecord(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("签名：书写医师本人不能给自己加签主治")
    void authorCannotCountersignAttending() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 4L, "{}")));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(1);

        assertThatThrownBy(() -> service.sign(recordId, RecordSignature.ATTENDING, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_LEVEL_INVALID);
        verify(repo, never()).countersign(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("签名：同一人不能连签两级（主治签完不能再签主任）")
    void previousSignerCannotSignNextLevel() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 5L, "{}")));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(2);
        when(repo.listSignatures(recordId)).thenReturn(List.of(
                new RecordSignature(UUID.randomUUID(), recordId, 1, "doctor01",
                        RecordSignature.AUTHOR, Instant.now(), "h1"),
                new RecordSignature(UUID.randomUUID(), recordId, 1, "attending01",
                        RecordSignature.ATTENDING, Instant.now(), "h1")));

        assertThatThrownBy(() -> service.sign(recordId, RecordSignature.CHIEF, "attending01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_LEVEL_INVALID);
        verify(repo, never()).countersign(any(), anyString(), anyString(), anyLong());
    }

    // ---------------------------------------------------------------- CAS 冲突（MF-17）

    @Test
    @DisplayName("签名：CAS 失败返回 409，不落签名记录")
    void signCasConflict() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 3L, "{}")));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(0);
        when(repo.signRecord(eq(recordId), eq("doctor01"), anyString(), eq(3L)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.sign(recordId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).insertSignature(any(RecordSignature.class));
    }

    @Test
    @DisplayName("归档：CAS 失败返回 409")
    void archiveCasConflict() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 5L, "{}")));
        when(repo.requiredSignLevel("ADMISSION_NOTE")).thenReturn(Optional.of(1));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(1);
        when(repo.archiveRecord(recordId, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.archive(recordId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 时限边界（MF-04）

    @Test
    @DisplayName("时限：8 小时 59 分必须判为超时（不得按 toHours 截断放宽）")
    void timelinessStrictBoundary() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 1L, "{}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(List.of(timelinessRule(8)));
        when(repo.visitStartedAt(recordId)).thenReturn(Optional.of(
                Instant.now().minus(java.time.Duration.ofMinutes(8 * 60 + 59))));
        when(repo.updateStatus(recordId, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, 1L))
                .thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 2L, "{}")));

        SubmitResult result = service.submit(recordId, "doctor01");

        assertThat(result.qc().majorCount()).isEqualTo(1);
        assertThat(result.qc().results().get(0).passed()).isFalse();
        assertThat(result.qc().results().get(0).message()).contains("8 小时");
    }

    // ---------------------------------------------------------------- CDR 检索

    @Test
    @DisplayName("检索：关键词同时生成模糊模式与前缀模式，通配符被转义且留痕")
    void searchBuildsPatternAndPrefix() {
        service.search(" MR2026_01 ", null, null, "signed", null, null, 0, "doctor01");

        verify(repo).searchRecords("%MR2026\\_01%", "MR2026\\_01%", null, null, "SIGNED",
                null, null, 50, "");
        verify(repo).logChange(eq("doctor01"), eq("RECORD_SEARCH"), eq("MEDICAL_RECORD"),
                eq("search"), anyString());
    }

    @Test
    @DisplayName("检索：无关键词时不触发文本与前缀匹配，limit 上限 200")
    void searchWithoutKeyword() {
        service.search("   ", null, null, null, null, null, 500, "doctor01");

        verify(repo).searchRecords(null, null, null, null, null, null, null, 200, "");
    }

    @Test
    @DisplayName("打印：留痕记录病历号与状态")
    void printWritesAudit() {
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 1L, "{}")));

        String html = service.printHtml(recordId, "doctor01");

        assertThat(html).contains("未归档");
        verify(repo).logChange(eq("doctor01"), eq("RECORD_PRINT"), eq("MEDICAL_RECORD"),
                eq(recordId.toString()), anyString());
    }

    @Test
    @DisplayName("检索：文书类型大小写不敏感（S-22）")
    void searchNormalizesDocType() {
        service.search("x", null, " admission_note ", null, null, null, 10, "doctor01");

        verify(repo).searchRecords(anyString(), anyString(), any(), eq("ADMISSION_NOTE"),
                any(), any(), any(), eq(10), eq(""));
    }

    @Test
    @DisplayName("检索：关键词超过 200 字符直接 422，不触库也不留痕（N-08）")
    void searchRejectsTooLongKeyword() {
        String tooLong = "x".repeat(RecordService.MAX_KEYWORD_LENGTH + 1);

        assertThatThrownBy(() -> service.search(tooLong, null, null, null, null, null, 50, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).searchRecords(any(), any(), any(), any(), any(), any(), any(), anyInt(), any());
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("检索：恰好 200 字符仍放行（边界）")
    void searchAcceptsBoundaryKeywordLength() {
        String atLimit = "x".repeat(RecordService.MAX_KEYWORD_LENGTH);

        service.search(atLimit, null, null, null, null, null, 50, "doctor01");

        verify(repo).searchRecords("%" + atLimit + "%", atLimit + "%", null, null, null,
                null, null, 50, "");
    }

    @Test
    @DisplayName("时限质控：阈值取规则配置（首程 8 小时），不写死 24")
    void timelinessUsesRuleThreshold() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 1, 1L, "{}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(List.of(timelinessRule(8)));
        when(repo.visitStartedAt(recordId)).thenReturn(Optional.of(
                Instant.now().minus(java.time.Duration.ofHours(9))));
        when(repo.updateStatus(recordId, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, 1L))
                .thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 1, 2L, "{}")));

        SubmitResult result = service.submit(recordId, "doctor01");

        assertThat(result.qc().majorCount()).isEqualTo(1);
        assertThat(result.qc().results().get(0).message()).contains("已 9 小时");
    }

    @Test
    @DisplayName("签名：三级签名写到 CHIEF（归档策略 3 级的场景）")
    void countersignChiefAtLevel3() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 7L, "{}")));
        when(repo.currentSignLevel(recordId, 1)).thenReturn(2);
        when(repo.listSignatures(recordId)).thenReturn(List.of(
                new RecordSignature(UUID.randomUUID(), recordId, 1, "doctor01",
                        RecordSignature.AUTHOR, Instant.now(), "h1"),
                new RecordSignature(UUID.randomUUID(), recordId, 1, "attending01",
                        RecordSignature.ATTENDING, Instant.now(), "h1")));
        when(repo.countersign(eq(recordId), eq("chief01"), anyString(), eq(7L))).thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 8L, "{}")));

        service.sign(recordId, RecordSignature.CHIEF, "chief01");

        verify(repo).countersign(eq(recordId), eq("chief01"), anyString(), eq(7L));
        verify(repo).insertSignature(org.mockito.ArgumentMatchers.argThat(
                s -> RecordSignature.CHIEF.equals(s.signType()) && "chief01".equals(s.signer())));
    }

    // ------------------------------------------------ 责任链 / 完整性 / 就诊归属（S-01 / S-04 / S-09）

    @Test
    @DisplayName("修订：留痕 author 恒为书写人，不取最后签名人（S-01）")
    void amendmentAuthorIsTheWriter() {
        MedicalRecord signed = new MedicalRecord(recordId, "MR20261031000001", patientId,
                "P20261031000001", null, null, null, "ADMISSION_NOTE", "TPL-ADMISSION",
                "入院记录", "{}", "主诉：发热\n", "doctor01", MedicalRecord.SIGNED, 1,
                "chief01", Instant.now(), RecordService.sha256("主诉：发热\n"), null, 3L, Instant.now());
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(signed));
        when(repo.findTemplateByCode("TPL-ADMISSION")).thenReturn(Optional.of(template()));
        when(repo.applyAmendment(eq(recordId), anyString(), anyString(), eq(3L),
                eq(MedicalRecord.SIGNED))).thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 2, 4L, "{}")));

        service.amend(recordId, new AmendReq(Map.of("chiefComplaint", "发热"), "补充现病史"),
                "doctor01");

        ArgumentCaptor<Amendment> captor = ArgumentCaptor.forClass(Amendment.class);
        verify(repo).insertAmendment(captor.capture());
        assertThat(captor.getValue().author()).isEqualTo("doctor01");
    }

    @Test
    @DisplayName("时限质控：修订重提交（revision>1）不再重复评估（S-03）")
    void timelinessSkippedOnRevision() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.DRAFT, 2, 5L, "{\"chiefComplaint\":\"发热\"}")));
        when(repo.listQcRules("ADMISSION_NOTE")).thenReturn(List.of(timelinessRule(24)));
        when(repo.updateStatus(recordId, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, 5L))
                .thenReturn(true);
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SUBMITTED, 2, 6L, "{}")));

        SubmitResult result = service.submit(recordId, "doctor01");

        assertThat(result.qc().results()).isEmpty();
        assertThat(result.qc().passed()).isTrue();
    }

    @Test
    @DisplayName("创建：就诊号不属于该患者时拒绝（S-04）")
    void createRejectsVisitOfAnotherPatient() {
        when(repo.findTemplateByCode("TPL-ADMISSION")).thenReturn(Optional.of(template()));
        when(clinicalClient.visit(any())).thenReturn(Optional.of(
                new ClinicalClient.VisitRef(UUID.randomUUID(), null)));

        CreateRecordReq req = new CreateRecordReq(patientId, UUID.randomUUID(), null, null,
                "TPL-ADMISSION", "入院记录", Map.of("chiefComplaint", "发热"));

        assertThatThrownBy(() -> service.create(req, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertRecord(any(), any(), any());
    }

    @Test
    @DisplayName("完整性校验：签名行哈希与正文不一致时报出（S-09）")
    void verifyReportsSignatureMismatch() {
        when(repo.findRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 3L, "{}")));
        when(repo.listSignatures(recordId)).thenReturn(List.of(
                new RecordSignature(UUID.randomUUID(), recordId, 1, "doctor01",
                        RecordSignature.AUTHOR, Instant.now(), "deadbeef")));

        HashVerify verify = service.verify(recordId);

        assertThat(verify.signaturesValid()).isFalse();
        assertThat(verify.signatureMismatches()).containsExactly("AUTHOR:doctor01");
    }

    // ---------------------------------------------------------------- 测试夹具

    private final UUID reqId = UUID.randomUUID();

    private MedicalRecord archivedRecord() {
        return new MedicalRecord(recordId, "MR20261031000001", patientId, "P20261031000001",
                null, null, null, "ADMISSION_NOTE", "TPL-ADMISSION", "入院记录", "{}",
                "主诉：发热\n", "doctor01", MedicalRecord.ARCHIVED, 1, "chief01", Instant.now(),
                RecordService.sha256("主诉：发热\n"), Instant.now(), 6L, Instant.now());
    }

    private ChangeRequest pendingRequest(String requester) {
        return new ChangeRequest(reqId, recordId, "MR20261031000001", "补充诊断依据",
                "{\"initialDiagnosis\":\"急性咽炎\"}", "初步诊断：急性咽炎\n",
                ChangeRequest.PENDING, requester, Instant.now(), null, null, null, null, null);
    }

    // ------------------------------------------------ 修改申请闭环（S-02 / S-05 / S-06）

    @Test
    @DisplayName("归档：未配置签名策略时 fail-closed，不按 1 级放行（S-05）")
    void archiveRejectsWhenPolicyMissing() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 1, 5L, "{}")));
        when(repo.requiredSignLevel("ADMISSION_NOTE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archive(recordId, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_SIGN_LEVEL_INVALID);
        verify(repo, never()).archiveRecord(any(), anyLong());
    }

    @Test
    @DisplayName("归档：由申请产生的版本，归档审计带上申请单号（S-02）")
    void archiveAuditCarriesChangeRequestId() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(
                record(MedicalRecord.SIGNED, 2, 8L, "{}")));
        when(repo.requiredSignLevel("ADMISSION_NOTE")).thenReturn(Optional.of(1));
        when(repo.currentSignLevel(recordId, 2)).thenReturn(1);
        when(repo.archiveRecord(recordId, 8L)).thenReturn(true);
        when(repo.findAmendmentByRevision(recordId, 2)).thenReturn(Optional.of(
                new Amendment(UUID.randomUUID(), recordId, 1, "{}", "text", "hash", "doctor01",
                        "审批通过", reqId, Instant.now())));
        when(repo.findRecord(recordId)).thenReturn(Optional.of(archivedRecord()));

        service.archive(recordId, "qc01");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).logChange(eq("qc01"), eq("RECORD_ARCHIVE"), eq("RECORD"),
                eq(recordId.toString()), detail.capture());
        assertThat(detail.getValue()).contains(reqId.toString());
    }

    @Test
    @DisplayName("撤回：申请人本人可撤回待审批申请（S-06）")
    void withdrawByRequester() {
        ChangeRequest withdrawn = new ChangeRequest(reqId, recordId, "MR20261031000001",
                "补充诊断依据", "{}", "text", ChangeRequest.WITHDRAWN, "doctor01",
                Instant.now(), "doctor01", Instant.now(), "申请人撤回", null, null);
        when(repo.lockChangeRequest(reqId)).thenReturn(Optional.of(pendingRequest("doctor01")),
                Optional.of(withdrawn));
        when(repo.withdrawChangeRequest(reqId, "doctor01")).thenReturn(true);

        ChangeRequest result = service.withdrawChange(recordId, reqId, "doctor01");

        assertThat(result.status()).isEqualTo(ChangeRequest.WITHDRAWN);
        verify(repo).logChange(eq("doctor01"), eq("RECORD_CHANGE_WITHDRAW"), eq("RECORD"),
                eq(recordId.toString()), anyString());
    }

    @Test
    @DisplayName("撤回：非申请人不能撤回他人申请")
    void withdrawRejectsOtherUser() {
        when(repo.lockChangeRequest(reqId)).thenReturn(Optional.of(pendingRequest("doctor01")));

        assertThatThrownBy(() -> service.withdrawChange(recordId, reqId, "qc01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_CHANGE_REQUEST_INVALID);
        verify(repo, never()).withdrawChangeRequest(any(), anyString());
    }

    @Test
    @DisplayName("重提：只能关联已驳回/已撤回的同病历申请（S-06）")
    void resubmitMustReferenceClosedRequest() {
        when(repo.lockRecord(recordId)).thenReturn(Optional.of(archivedRecord()));
        when(repo.lockChangeRequest(reqId)).thenReturn(Optional.of(pendingRequest("doctor01")));

        assertThatThrownBy(() -> service.requestChange(recordId,
                new ChangeRequestReq(Map.of("chiefComplaint", "发热"), "补充主诉", reqId),
                "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.EMR_CHANGE_REQUEST_INVALID);
        verify(repo, never()).insertChangeRequest(any());
    }
}
