package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.QcResult;
import com.medassist.emr.domain.EmrModel.QcRule;
import com.medassist.emr.domain.EmrModel.Template;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.AmendReq;
import com.medassist.emr.web.EmrDtos.CreateRecordReq;
import com.medassist.emr.web.EmrDtos.HashVerify;
import com.medassist.emr.web.EmrDtos.QcReport;
import com.medassist.emr.web.EmrDtos.RecordView;
import com.medassist.emr.web.EmrDtos.SaveContentReq;
import com.medassist.emr.web.EmrDtos.SubmitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
        service = new RecordService(repo, patientClient, JsonMapper.builder().build());
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P20261031000001", "张三"));
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
        verify(repo).insertRecord(any(MedicalRecord.class));
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
}
