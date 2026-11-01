package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.client.ClinicalClient;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.CreateHomepageReq;
import com.medassist.emr.web.EmrDtos.HomepageProcedureReq;
import com.medassist.emr.web.EmrDtos.UpdateHomepageReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 病案首页核心规则：只对已出院住院生成、快照固化、提交完整性校验、四眼归档。
 */
class HomepageServiceTest {

    private EmrRepository repo;
    private ClinicalClient clinicalClient;
    private PatientClient patientClient;
    private HomepageService service;

    private final UUID visitId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID admissionId = UUID.randomUUID();
    private final Instant admittedAt = Instant.parse("2026-11-01T02:00:00Z");
    private final Instant dischargedAt = Instant.parse("2026-11-03T02:00:00Z");

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        clinicalClient = mock(ClinicalClient.class);
        patientClient = mock(PatientClient.class);
        service = new HomepageService(repo, clinicalClient, patientClient,
                JsonMapper.builder().build());
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P20261101000001", "张三"));
        when(repo.nextHomepageNo()).thenReturn("HP20261101000001");
    }

    @Test
    @DisplayName("门诊就诊不能生成病案首页")
    void createRejectsOutpatient() {
        when(clinicalClient.homepageSource(visitId))
                .thenReturn(source("OUTPATIENT", null, null, List.of()));

        assertThatThrownBy(() -> service.create(new CreateHomepageReq(visitId), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID));
        verify(repo, never()).insertHomepage(any());
    }

    @Test
    @DisplayName("在院患者不能生成病案首页")
    void createRejectsInHospital() {
        when(clinicalClient.homepageSource(visitId))
                .thenReturn(source("INPATIENT", "IN_HOSPITAL", null, List.of()));

        assertThatThrownBy(() -> service.create(new CreateHomepageReq(visitId), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID));
        verify(repo, never()).insertHomepage(any());
    }

    @Test
    @DisplayName("生成首页：费用按类别补齐并两位小数，合计=明细之和，住院天数按业务日计算")
    void createSnapshotsFeesAndDiagnoses() {
        when(clinicalClient.homepageSource(visitId))
                .thenReturn(source("INPATIENT", "DISCHARGED", dischargedAt, List.of(
                        new ClinicalClient.Diagnosis("J18.900", "肺炎", true),
                        new ClinicalClient.Diagnosis("I10.x00", "高血压病", false))));
        when(repo.findHomepageByVisit(visitId)).thenReturn(Optional.empty());
        AtomicReference<Homepage> inserted = new AtomicReference<>();
        doAnswer(inv -> {
            inserted.set(inv.getArgument(0));
            return null;
        }).when(repo).insertHomepage(any());
        when(repo.findHomepage(any())).thenAnswer(inv -> Optional.of(inserted.get()));

        service.create(new CreateHomepageReq(visitId), "admin01");

        Homepage saved = inserted.get();
        assertThat(saved).isNotNull();
        assertThat(saved.status()).isEqualTo(Homepage.DRAFT);
        assertThat(saved.stayDays()).isEqualTo(2);
        assertThat(saved.totalAmount()).isEqualByComparingTo("268.00");
        assertThat(saved.feeSummary()).contains("\"EXAM\":268.00").contains("\"DRUG\":0.00");
        assertThat(saved.diagnoses()).contains("J18.900").contains("\"primary\":true");
        assertThat(saved.patientName()).isEqualTo("张三");
        assertThat(saved.dischargeMode()).isNull();
        assertThat(saved.readmitPlan()).isEqualTo("NONE");
        assertThat(saved.attendingDoctor()).isEqualTo("doctor01");
        verify(repo).logChange(eq("admin01"), eq("HOMEPAGE_CREATE"), eq("HOMEPAGE"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("同一就诊重复生成被拒绝")
    void createRejectsDuplicate() {
        when(clinicalClient.homepageSource(visitId))
                .thenReturn(source("INPATIENT", "DISCHARGED", dischargedAt, List.of()));
        when(repo.findHomepageByVisit(visitId)).thenReturn(Optional.of(homepage(Homepage.DRAFT)));

        assertThatThrownBy(() -> service.create(new CreateHomepageReq(visitId), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).insertHomepage(any());
    }

    @Test
    @DisplayName("提交前校验：离院方式/医师签名/主诊断缺失时一次性列出全部缺失项")
    void submitListsAllMissingFields() {
        Homepage draft = homepage(Homepage.DRAFT);
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.submit(draft.id(), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_INCOMPLETE);
                    assertThat(e.getMessage()).contains("离院方式")
                            .contains("主任医师签名")
                            .contains("住院医师签名")
                            .contains("出院诊断");
                });
        verify(repo, never()).submitHomepage(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("主诊断不唯一同样阻断提交")
    void submitRequiresExactlyOnePrimary() {
        Homepage draft = homepage(Homepage.DRAFT);
        Homepage withTwoPrimaries = new Homepage(draft.id(), draft.homepageNo(), draft.visitId(),
                draft.admissionId(), draft.patientId(), draft.patientNo(), draft.patientName(),
                draft.visitNo(), draft.deptId(), draft.deptName(), draft.wardId(), draft.wardName(),
                draft.bedNo(), draft.admittedAt(), draft.dischargedAt(), draft.stayDays(),
                draft.attendingDoctor(), draft.admissionWay(), "BY_DOCTOR", "NONE", null, null,
                null, null, null, 0, 0, false, false, "chief01", "resident01", null,
                "[{\"code\":\"J18.900\",\"name\":\"肺炎\",\"primary\":true},"
                        + "{\"code\":\"I10.x00\",\"name\":\"高血压病\",\"primary\":true}]",
                "[]", "{\"EXAM\":268.00}", new BigDecimal("268.00"), Homepage.DRAFT,
                null, null, null, null, 0L, null);
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(withTwoPrimaries));

        assertThatThrownBy(() -> service.submit(draft.id(), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("唯一主诊断"));
    }

    @Test
    @DisplayName("完整草稿可提交，走 CAS 并留审计")
    void submitSucceedsWhenComplete() {
        Homepage draft = completeDraft();
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(repo.submitHomepage(draft.id(), "admin01", draft.version())).thenReturn(true);
        when(repo.findHomepage(draft.id())).thenReturn(Optional.of(draft));

        service.submit(draft.id(), "admin01");

        verify(repo).submitHomepage(draft.id(), "admin01", draft.version());
        verify(repo).logChange(eq("admin01"), eq("HOMEPAGE_SUBMIT"), eq("HOMEPAGE"),
                eq(draft.id().toString()), anyString());
    }

    @Test
    @DisplayName("四眼归档：提交人不得归档自己的首页")
    void archiveRejectsSubmitter() {
        Homepage submitted = submittedHomepage("admin01");
        when(repo.lockHomepage(submitted.id())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.archive(submitted.id(), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID));
        verify(repo, never()).archiveHomepage(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("他人归档成功")
    void archiveSucceedsByDifferentActor() {
        Homepage submitted = submittedHomepage("admin01");
        when(repo.lockHomepage(submitted.id())).thenReturn(Optional.of(submitted));
        when(repo.archiveHomepage(submitted.id(), "qc01", submitted.version())).thenReturn(true);
        when(repo.findHomepage(submitted.id())).thenReturn(Optional.of(submitted));

        service.archive(submitted.id(), "qc01");

        verify(repo).archiveHomepage(submitted.id(), "qc01", submitted.version());
    }

    @Test
    @DisplayName("抢救成功次数不得大于抢救次数")
    void updateRejectsRescueMismatch() {
        Homepage draft = homepage(Homepage.DRAFT);
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));

        UpdateHomepageReq req = new UpdateHomepageReq(0L, null, null, null, null, null, null,
                null, null, null, null, null, null, 2, 3, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.update(draft.id(), req, "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(repo, never()).updateHomepageDraft(any(), any(), anyLong());
    }

    @Test
    @DisplayName("补录手术操作与离院方式后落库（CAS 版本号）")
    void updatePersistsEditableFields() {
        Homepage draft = homepage(Homepage.DRAFT);
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(repo.updateHomepageDraft(eq(draft.id()), any(), eq(0L))).thenReturn(true);
        when(repo.findHomepage(draft.id())).thenReturn(Optional.of(draft));

        UpdateHomepageReq req = new UpdateHomepageReq(0L, "呼吸内科", "二病区", "B2", "doctor01",
                "EMERGENCY", "by_doctor", null, "MARRIED", "职员", "汉族", "A", "POSITIVE",
                1, 1, true, false, "chief01", "resident01", "coder01",
                List.of(new HomepageProcedureReq("88.0100", "肺穿刺活检", null, "doctor01",
                        "局部麻醉", "II")));

        service.update(draft.id(), req, "admin01");

        ArgumentCaptor<Homepage> captor = ArgumentCaptor.forClass(Homepage.class);
        verify(repo).updateHomepageDraft(eq(draft.id()), captor.capture(), eq(0L));
        Homepage saved = captor.getValue();
        assertThat(saved.dischargeMode()).isEqualTo("BY_DOCTOR");
        assertThat(saved.deptName()).isEqualTo("呼吸内科");
        assertThat(saved.admissionWay()).isEqualTo("EMERGENCY");
        assertThat(saved.procedures()).contains("88.0100").contains("肺穿刺活检");
        assertThat(saved.rescueSuccessCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("住院天数：当天入出院按 1 天")
    void stayDaysSameDay() {
        assertThat(HomepageService.stayDays(admittedAt, admittedAt)).isEqualTo(1);
        assertThat(HomepageService.stayDays(admittedAt,
                admittedAt.plusSeconds(48 * 3600))).isEqualTo(2);
    }

    @Test
    @DisplayName("打印视图：未归档带水印，费用按中文类别展示")
    void printContainsWatermarkAndFees() {
        Homepage draft = completeDraft();
        String html = HomepagePrintRenderer.render(draft,
                List.of(Map.of("code", "J18.900", "name", "肺炎", "primary", true)),
                List.of(),
                Map.of("EXAM", new BigDecimal("268.00")));
        assertThat(html).contains("NOT ARCHIVED").contains("HP20261101000001")
                .contains("检查检验费").contains("268.00").contains("医嘱离院");
    }

    // ---------------------------------------------------------------- fixtures

    private Homepage submittedHomepage(String submittedBy) {
        Homepage base = completeDraft();
        return new Homepage(base.id(), base.homepageNo(), base.visitId(), base.admissionId(),
                base.patientId(), base.patientNo(), base.patientName(), base.visitNo(),
                base.deptId(), base.deptName(), base.wardId(), base.wardName(), base.bedNo(),
                base.admittedAt(), base.dischargedAt(), base.stayDays(), base.attendingDoctor(),
                base.admissionWay(), base.dischargeMode(), base.readmitPlan(), base.maritalStatus(),
                base.occupation(), base.nation(), base.bloodType(), base.rhFactor(),
                base.rescueCount(), base.rescueSuccessCount(), base.criticalCase(),
                base.hospitalInfection(), base.chiefPhysician(), base.residentPhysician(),
                base.coder(), base.diagnoses(), base.procedures(), base.feeSummary(),
                base.totalAmount(), Homepage.SUBMITTED, submittedBy, Instant.now(),
                null, null, 7L, Instant.now());
    }

    private Homepage completeDraft() {
        Homepage base = homepage(Homepage.DRAFT);
        return new Homepage(base.id(), base.homepageNo(), base.visitId(), base.admissionId(),
                base.patientId(), base.patientNo(), base.patientName(), base.visitNo(),
                base.deptId(), base.deptName(), base.wardId(), base.wardName(), base.bedNo(),
                base.admittedAt(), base.dischargedAt(), base.stayDays(), base.attendingDoctor(),
                "OUTPATIENT", "BY_DOCTOR", "NONE", null, null, null, null, null, 0, 0,
                false, false, "chief01", "resident01", "coder01",
                "[{\"code\":\"J18.900\",\"name\":\"肺炎\",\"primary\":true}]",
                "[]", "{\"EXAM\":268.00}", new BigDecimal("268.00"), Homepage.DRAFT,
                null, null, null, null, 0L, Instant.now());
    }

    private Homepage homepage(String status) {
        return new Homepage(UUID.randomUUID(), "HP20261101000001", visitId, admissionId, patientId,
                "P20261101000001", "张三", "V001", UUID.randomUUID(), "内科", UUID.randomUUID(),
                "一病区", "A1", admittedAt, dischargedAt, 2, "doctor01", null, null, "NONE",
                null, null, null, null, null, 0, 0, false, false, null, null, null,
                "[]", "[]", "{\"EXAM\":268.00}", new BigDecimal("268.00"), status,
                null, null, null, null, 0L, Instant.now());
    }

    private ClinicalClient.HomepageSource source(String visitType, String admissionStatus,
                                                 Instant discharged,
                                                 List<ClinicalClient.Diagnosis> diagnoses) {
        boolean outpatient = "OUTPATIENT".equals(visitType);
        return new ClinicalClient.HomepageSource(visitId, "V001", visitType, "FINISHED",
                patientId, "P20261101000001", UUID.randomUUID(), "内科",
                outpatient ? null : admissionId,
                outpatient ? null : "A001",
                admissionStatus, admittedAt, discharged, UUID.randomUUID(), "一病区",
                UUID.randomUUID(), "A1", "doctor01", diagnoses,
                Map.of("EXAM", new BigDecimal("268.00")), new BigDecimal("268.00"));
    }
}
