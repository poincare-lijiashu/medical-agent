package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    /** 四眼校验按账号主键判定（MF-13）：提交人与归档人必须是不同的 subject。 */
    private static final UUID SUBMITTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ARCHIVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        repo = mock(EmrRepository.class);
        clinicalClient = mock(ClinicalClient.class);
        patientClient = mock(PatientClient.class);
        service = new HomepageService(repo, clinicalClient, patientClient,
                JsonMapper.builder().build());
        when(patientClient.requireExists(patientId)).thenReturn(new PatientClient.PatientRef(
                patientId, "P20261101000001", "张三", "MALE", LocalDate.of(1980, 5, 20)));
        when(patientClient.requireActive(patientId)).thenReturn(new PatientClient.PatientRef(
                patientId, "P20261101000001", "张三", "MALE", LocalDate.of(1980, 5, 20)));
        when(repo.nextHomepageNo()).thenReturn("HP20261101000001");
    }

    @Test
    @DisplayName("门诊就诊不能生成病案首页")
    void createRejectsOutpatient() {
        when(clinicalClient.homepageSource(visitId))
                .thenReturn(source("OUTPATIENT", null, null, List.of()));

        assertThatThrownBy(() -> service.create(new CreateHomepageReq(visitId), "admin01",
                DataScope.all("admin01", true)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID));
        verify(repo, never()).insertHomepage(any(), any());
    }

    @Test
    @DisplayName("跨科室生成首页被拒绝（科室不可见与来源非法同一处理）")
    void createRejectsOtherDepartment() {
        ClinicalClient.HomepageSource src = source("INPATIENT", "DISCHARGED", dischargedAt, List.of());
        when(clinicalClient.homepageSource(visitId)).thenReturn(src);

        assertThatThrownBy(() -> service.create(new CreateHomepageReq(visitId), "doctor02",
                DataScope.ofDepartment("doctor02", UUID.randomUUID(), true)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID));
        verify(repo, never()).insertHomepage(any(), any());
    }

    @Test
    @DisplayName("在院患者不能生成病案首页")
    void createRejectsInHospital() {
        when(clinicalClient.homepageSource(visitId))
                .thenReturn(source("INPATIENT", "IN_HOSPITAL", null, List.of()));

        assertThatThrownBy(() -> service.create(new CreateHomepageReq(visitId), "admin01",
                DataScope.all("admin01", true)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID));
        verify(repo, never()).insertHomepage(any(), any());
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
        }).when(repo).insertHomepage(any(), any());
        when(repo.findHomepage(any())).thenAnswer(inv -> Optional.of(inserted.get()));

        service.create(new CreateHomepageReq(visitId), "admin01", DataScope.all("admin01", true));

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

        assertThatThrownBy(() -> service.create(new CreateHomepageReq(visitId), "admin01",
                DataScope.all("admin01", true)))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).insertHomepage(any(), any());
    }

    @Test
    @DisplayName("提交前校验：离院方式/医师签名/主诊断缺失时一次性列出全部缺失项")
    void submitListsAllMissingFields() {
        Homepage draft = homepage(Homepage.DRAFT);
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.submit(draft.id(), "admin01", SUBMITTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_INCOMPLETE);
                    assertThat(e.getMessage()).contains("离院方式")
                            .contains("主任医师签名")
                            .contains("住院医师签名")
                            .contains("出院诊断");
                });
        verify(repo, never()).submitHomepage(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("主诊断不唯一同样阻断提交")
    void submitRequiresExactlyOnePrimary() {
        Homepage draft = homepage(Homepage.DRAFT);
        Homepage withTwoPrimaries = new Homepage(draft.id(), draft.homepageNo(), draft.visitId(),
                draft.admissionId(), draft.patientId(), draft.patientNo(), draft.patientName(),
                draft.patientSex(), draft.birthDate(), draft.admitAge(),
                draft.visitNo(), draft.deptId(), draft.deptName(), draft.wardId(), draft.wardName(),
                draft.bedNo(), draft.admittedAt(), draft.dischargedAt(), draft.stayDays(),
                draft.attendingDoctor(), draft.admissionWay(), "BY_DOCTOR", null, null,
                "NONE", null, null,
                null, null, null, 0, 0, false, false, "chief01", "resident01", null,
                "[{\"code\":\"J18.900\",\"name\":\"肺炎\",\"primary\":true},"
                        + "{\"code\":\"I10.x00\",\"name\":\"高血压病\",\"primary\":true}]",
                "[]", "{\"EXAM\":268.00}", new BigDecimal("268.00"), Homepage.DRAFT,
                null, null, null, null, null, 0L, null);
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(withTwoPrimaries));

        assertThatThrownBy(() -> service.submit(draft.id(), "admin01", SUBMITTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getMessage()).contains("唯一主诊断"));
    }

    @Test
    @DisplayName("完整草稿可提交，走 CAS 并留审计")
    void submitSucceedsWhenComplete() {
        Homepage draft = completeDraft();
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(repo.submitHomepage(draft.id(), "admin01", SUBMITTER_ID, draft.version())).thenReturn(true);
        when(repo.findHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(clinicalClient.homepageSource(visitId)).thenReturn(source("INPATIENT", "DISCHARGED",
                dischargedAt, List.of(new ClinicalClient.Diagnosis("J18.900", "肺炎", true))));

        service.submit(draft.id(), "admin01", SUBMITTER_ID);

        verify(repo).submitHomepage(draft.id(), "admin01", SUBMITTER_ID, draft.version());
        verify(repo).logChange(eq("admin01"), eq("HOMEPAGE_SUBMIT"), eq("HOMEPAGE"),
                eq(draft.id().toString()), anyString());
    }

    @Test
    @DisplayName("提交前指纹校验：费用已变化则拒绝，要求先重新生成（MF-05）")
    void submitRejectsStaleSnapshot() {
        Homepage draft = completeDraft();
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));
        // 出院后又发生了退费/补记费用：当前临床合计与首页快照不一致
        ClinicalClient.HomepageSource changed = new ClinicalClient.HomepageSource(visitId, "V001",
                "INPATIENT", "FINISHED", patientId, "P20261101000001", UUID.randomUUID(), "内科",
                admissionId, "A001", "DISCHARGED", admittedAt, dischargedAt, UUID.randomUUID(),
                "一病区", UUID.randomUUID(), "A1", "doctor01",
                List.of(new ClinicalClient.Diagnosis("J18.900", "肺炎", true)), List.of(),
                Map.of("EXAM", new BigDecimal("300.00")), new BigDecimal("300.00"));
        when(clinicalClient.homepageSource(visitId)).thenReturn(changed);

        assertThatThrownBy(() -> service.submit(draft.id(), "admin01", SUBMITTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).submitHomepage(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("提交前指纹校验：临床已完成手术未带入首页 → 409（不能丢手术，DRG 会失真）")
    void submitRejectsMissingSurgicalProcedure() {
        Homepage draft = completeDraft();
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(clinicalClient.homepageSource(visitId)).thenReturn(sourceWithProcedures(
                List.of(new ClinicalClient.Procedure("47.0901", "阑尾切除术", "2026-11-01",
                        "doctor01", "GENERAL", "II"))));

        assertThatThrownBy(() -> service.submit(draft.id(), "admin01", SUBMITTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        verify(repo, never()).submitHomepage(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("提交前指纹校验：首页额外补录外院手术允许提交（只校验临床手术不丢）")
    void submitAllowsExtraManualProcedure() {
        Homepage draft = draftWithProcedures("[{\"code\":\"47.0901\",\"name\":\"阑尾切除术\","
                + "\"date\":\"2026-11-01\",\"surgeon\":\"doctor01\",\"anesthesia\":\"GENERAL\","
                + "\"incisionLevel\":\"II\"},{\"code\":\"88.0100\",\"name\":\"外院穿刺\","
                + "\"date\":null,\"surgeon\":\"doctor01\",\"anesthesia\":\"LOCAL\","
                + "\"incisionLevel\":\"II\"}]");
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(repo.submitHomepage(draft.id(), "admin01", SUBMITTER_ID, draft.version()))
                .thenReturn(true);
        when(repo.findHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(clinicalClient.homepageSource(visitId)).thenReturn(sourceWithProcedures(
                List.of(new ClinicalClient.Procedure("47.0901", "阑尾切除术", "2026-11-01",
                        "doctor01", "GENERAL", "II"))));

        service.submit(draft.id(), "admin01", SUBMITTER_ID);

        verify(repo).submitHomepage(draft.id(), "admin01", SUBMITTER_ID, draft.version());
    }

    @Test
    @DisplayName("重新生成：草稿态按当前临床数据刷新快照并留痕（MF-05）")
    void recalculateRefreshesDraftSnapshot() {
        Homepage draft = homepage(Homepage.DRAFT);
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(repo.recalculateHomepage(eq(draft.id()), eq(0L), anyString(), anyString(),
                anyString(), any(), any(), any(), anyInt())).thenReturn(true);
        when(repo.findHomepage(draft.id())).thenReturn(Optional.of(draft));
        when(clinicalClient.homepageSource(visitId)).thenReturn(source("INPATIENT", "DISCHARGED",
                dischargedAt, List.of(new ClinicalClient.Diagnosis("J18.900", "肺炎", true))));

        service.recalculate(draft.id(), "admin01");

        verify(repo).recalculateHomepage(eq(draft.id()), eq(0L), anyString(), anyString(),
                anyString(), eq(new BigDecimal("268.00")), any(), any(), anyInt());
        verify(repo).logChange(eq("admin01"), eq("HOMEPAGE_RECALCULATE"), eq("HOMEPAGE"),
                eq(draft.id().toString()), anyString());
    }

    @Test
    @DisplayName("重新生成：已提交的首页不允许重算（读取在前，但仍以加锁后的状态为准）")
    void recalculateRejectsSubmitted() {
        Homepage submitted = submittedHomepage("admin01");
        when(repo.findHomepage(submitted.id())).thenReturn(Optional.of(submitted));
        when(repo.lockHomepage(submitted.id())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.recalculate(submitted.id(), "qc01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID));
        verify(repo, never()).recalculateHomepage(any(), anyLong(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("四眼归档：缺少提交人账号主键（历史数据）时拒绝，不能 fail-open")
    void archiveRejectsMissingSubmitterId() {
        Homepage legacy = submittedHomepage("admin01");
        Homepage withoutId = new Homepage(legacy.id(), legacy.homepageNo(), legacy.visitId(),
                legacy.admissionId(), legacy.patientId(), legacy.patientNo(), legacy.patientName(),
                legacy.patientSex(), legacy.birthDate(), legacy.admitAge(), legacy.visitNo(),
                legacy.deptId(), legacy.deptName(), legacy.wardId(), legacy.wardName(),
                legacy.bedNo(), legacy.admittedAt(), legacy.dischargedAt(), legacy.stayDays(),
                legacy.attendingDoctor(), legacy.admissionWay(), legacy.dischargeMode(),
                legacy.deathTime(), legacy.deathCause(), legacy.readmitPlan(),
                legacy.maritalStatus(), legacy.occupation(), legacy.nation(), legacy.bloodType(),
                legacy.rhFactor(), legacy.rescueCount(), legacy.rescueSuccessCount(),
                legacy.criticalCase(), legacy.hospitalInfection(), legacy.chiefPhysician(),
                legacy.residentPhysician(), legacy.coder(), legacy.diagnoses(),
                legacy.procedures(), legacy.feeSummary(), legacy.totalAmount(),
                Homepage.SUBMITTED, "admin01", null, Instant.now(), null, null, 7L, Instant.now());
        when(repo.lockHomepage(withoutId.id())).thenReturn(Optional.of(withoutId));

        assertThatThrownBy(() -> service.archive(withoutId.id(), "qc01", ARCHIVER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID);
                    assertThat(e.getMessage()).contains("提交人身份");
                });
        verify(repo, never()).archiveHomepage(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("四眼归档：缺少 X-User-Id 时拒绝归档")
    void archiveRejectsMissingActorId() {
        Homepage submitted = submittedHomepage("admin01");
        when(repo.lockHomepage(submitted.id())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.archive(submitted.id(), "qc01", null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID));
        verify(repo, never()).archiveHomepage(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("提交：缺少真实操作者（system）时拒绝")
    void submitRejectsSystemActor() {
        Homepage draft = completeDraft();
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.submit(draft.id(), "system", SUBMITTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID));
        verify(repo, never()).submitHomepage(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("四眼归档：提交人不得归档自己的首页")
    void archiveRejectsSubmitter() {
        Homepage submitted = submittedHomepage("admin01");
        when(repo.lockHomepage(submitted.id())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.archive(submitted.id(), "admin01", SUBMITTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID));
        verify(repo, never()).archiveHomepage(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("他人归档成功（须已完成编码）")
    void archiveSucceedsByDifferentActor() {
        Homepage submitted = submittedHomepage("admin01");
        when(repo.lockHomepage(submitted.id())).thenReturn(Optional.of(submitted));
        when(repo.findCoding(submitted.id())).thenReturn(Optional.of(new com.medassist.emr.domain
                .EmrModel.HomepageCoding(java.util.UUID.randomUUID(), submitted.id(),
                com.medassist.emr.domain.EmrModel.HomepageCoding.CODED, "coder01",
                java.time.Instant.now(), null, "[]", "[]", 1L, java.time.Instant.now(),
                java.time.Instant.now())));
        when(repo.archiveHomepage(submitted.id(), "qc01", submitted.version())).thenReturn(true);
        when(repo.findHomepage(submitted.id())).thenReturn(Optional.of(submitted));

        service.archive(submitted.id(), "qc01", ARCHIVER_ID);

        verify(repo).archiveHomepage(submitted.id(), "qc01", submitted.version());
    }

    @Test
    @DisplayName("归档门槛：未完成病案编码不得归档")
    void archiveRejectsUncodedHomepage() {
        Homepage submitted = submittedHomepage("admin01");
        when(repo.lockHomepage(submitted.id())).thenReturn(Optional.of(submitted));
        when(repo.findCoding(submitted.id())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.archive(submitted.id(), "qc01", ARCHIVER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HOMEPAGE_NOT_CODED);
        verify(repo, never()).archiveHomepage(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("抢救成功次数不得大于抢救次数")
    void updateRejectsRescueMismatch() {
        Homepage draft = homepage(Homepage.DRAFT);
        when(repo.lockHomepage(draft.id())).thenReturn(Optional.of(draft));

        UpdateHomepageReq req = new UpdateHomepageReq(0L, null, null, null, null, null, null,
                null, null, null, null, null, null, 2, 3, null, null, null, null, null, null,
                null, null);

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
                        "局部麻醉", "II")), null, null);

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
    @DisplayName("性别归一与入院年龄（MF-06）：历史值不炸、年龄按入院日计算")
    void sexAndAgeNormalization() {
        assertThat(HomepageService.normalizeSex("male")).isEqualTo("MALE");
        assertThat(HomepageService.normalizeSex("2")).isEqualTo("FEMALE");
        assertThat(HomepageService.normalizeSex("未知")).isEqualTo("UNKNOWN");
        assertThat(HomepageService.normalizeSex(null)).isEqualTo("UNKNOWN");

        assertThat(HomepageService.ageAt(LocalDate.of(1980, 5, 20),
                Instant.parse("2026-11-01T00:00:00Z"))).isEqualTo(46);
        assertThat(HomepageService.ageAt(null, Instant.now())).isNull();
    }

    @Test
    @DisplayName("死亡病例：缺死亡时间不能提交（MF-06）")
    void submitRequiresDeathTime() {
        Homepage death = deathDraft(null);
        when(repo.lockHomepage(death.id())).thenReturn(Optional.of(death));

        assertThatThrownBy(() -> service.submit(death.id(), "admin01", SUBMITTER_ID))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.EMR_HOMEPAGE_INCOMPLETE);
                    assertThat(e.getMessage()).contains("死亡时间");
                });
        verify(repo, never()).submitHomepage(any(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("死亡病例：补录死亡时间后可提交（死亡患者也能生成首页）")
    void submitDeathCaseWithDeathTime() {
        Homepage death = deathDraft(Instant.parse("2026-11-01T03:00:00Z"));
        when(repo.lockHomepage(death.id())).thenReturn(Optional.of(death));
        when(repo.submitHomepage(death.id(), "admin01", SUBMITTER_ID, death.version())).thenReturn(true);
        when(repo.findHomepage(death.id())).thenReturn(Optional.of(death));
        when(clinicalClient.homepageSource(visitId)).thenReturn(source("INPATIENT", "DISCHARGED",
                dischargedAt, List.of(new ClinicalClient.Diagnosis("J18.900", "肺炎", true))));

        service.submit(death.id(), "admin01", SUBMITTER_ID);

        verify(repo).submitHomepage(death.id(), "admin01", SUBMITTER_ID, death.version());
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

    /** 死亡病例草稿：死亡时间可空（用于验证提交前的必填校验）。 */
    private Homepage deathDraft(Instant deathTime) {
        Homepage base = completeDraft();
        return new Homepage(base.id(), base.homepageNo(), base.visitId(), base.admissionId(),
                base.patientId(), base.patientNo(), base.patientName(),
                base.patientSex(), base.birthDate(), base.admitAge(), base.visitNo(),
                base.deptId(), base.deptName(), base.wardId(), base.wardName(), base.bedNo(),
                base.admittedAt(), base.dischargedAt(), base.stayDays(), base.attendingDoctor(),
                base.admissionWay(), "DEATH", deathTime, "循环衰竭", "NONE", base.maritalStatus(),
                base.occupation(), base.nation(), base.bloodType(), base.rhFactor(),
                base.rescueCount(), base.rescueSuccessCount(), base.criticalCase(),
                base.hospitalInfection(), base.chiefPhysician(), base.residentPhysician(),
                base.coder(), base.diagnoses(), base.procedures(), base.feeSummary(),
                base.totalAmount(), Homepage.DRAFT, null, null, null, null, null, 0L, Instant.now());
    }

    private Homepage submittedHomepage(String submittedBy) {
        Homepage base = completeDraft();
        return new Homepage(base.id(), base.homepageNo(), base.visitId(), base.admissionId(),
                base.patientId(), base.patientNo(), base.patientName(),
                base.patientSex(), base.birthDate(), base.admitAge(), base.visitNo(),
                base.deptId(), base.deptName(), base.wardId(), base.wardName(), base.bedNo(),
                base.admittedAt(), base.dischargedAt(), base.stayDays(), base.attendingDoctor(),
                base.admissionWay(), base.dischargeMode(), base.deathTime(), base.deathCause(),
                base.readmitPlan(), base.maritalStatus(),
                base.occupation(), base.nation(), base.bloodType(), base.rhFactor(),
                base.rescueCount(), base.rescueSuccessCount(), base.criticalCase(),
                base.hospitalInfection(), base.chiefPhysician(), base.residentPhysician(),
                base.coder(), base.diagnoses(), base.procedures(), base.feeSummary(),
                base.totalAmount(), Homepage.SUBMITTED, submittedBy, SUBMITTER_ID, Instant.now(),
                null, null, 7L, Instant.now());
    }

    private Homepage completeDraft() {
        Homepage base = homepage(Homepage.DRAFT);
        return new Homepage(base.id(), base.homepageNo(), base.visitId(), base.admissionId(),
                base.patientId(), base.patientNo(), base.patientName(),
                base.patientSex(), base.birthDate(), base.admitAge(), base.visitNo(),
                base.deptId(), base.deptName(), base.wardId(), base.wardName(), base.bedNo(),
                base.admittedAt(), base.dischargedAt(), base.stayDays(), base.attendingDoctor(),
                "OUTPATIENT", "BY_DOCTOR", null, null, "NONE", null, null, null, null, null, 0, 0,
                false, false, "chief01", "resident01", "coder01",
                "[{\"code\":\"J18.900\",\"name\":\"肺炎\",\"primary\":true}]",
                "[]", "{\"EXAM\":268.00}", new BigDecimal("268.00"), Homepage.DRAFT,
                null, null, null, null, null, 0L, Instant.now());
    }

    private Homepage draftWithProcedures(String proceduresJson) {
        Homepage base = completeDraft();
        return new Homepage(base.id(), base.homepageNo(), base.visitId(), base.admissionId(),
                base.patientId(), base.patientNo(), base.patientName(),
                base.patientSex(), base.birthDate(), base.admitAge(), base.visitNo(),
                base.deptId(), base.deptName(), base.wardId(), base.wardName(), base.bedNo(),
                base.admittedAt(), base.dischargedAt(), base.stayDays(), base.attendingDoctor(),
                base.admissionWay(), base.dischargeMode(), base.deathTime(), base.deathCause(),
                base.readmitPlan(), base.maritalStatus(), base.occupation(), base.nation(),
                base.bloodType(), base.rhFactor(), base.rescueCount(), base.rescueSuccessCount(),
                base.criticalCase(), base.hospitalInfection(), base.chiefPhysician(),
                base.residentPhysician(), base.coder(), base.diagnoses(), proceduresJson,
                base.feeSummary(), base.totalAmount(), Homepage.DRAFT, null, null, null, null,
                null, 0L, Instant.now());
    }

    private Homepage homepage(String status) {
        return new Homepage(UUID.randomUUID(), "HP20261101000001", visitId, admissionId, patientId,
                "P20261101000001", "张三", "MALE", LocalDate.of(1980, 5, 20), 45,
                "V001", UUID.randomUUID(), "内科", UUID.randomUUID(),
                "一病区", "A1", admittedAt, dischargedAt, 2, "doctor01", null, null, null, null,
                "NONE", null, null, null, null, null, 0, 0, false, false, null, null, null,
                "[]", "[]", "{\"EXAM\":268.00}", new BigDecimal("268.00"), status,
                null, null, null, null, null, 0L, Instant.now());
    }

    private ClinicalClient.HomepageSource sourceWithProcedures(
            List<ClinicalClient.Procedure> procedures) {
        ClinicalClient.HomepageSource base = source("INPATIENT", "DISCHARGED", dischargedAt,
                List.of(new ClinicalClient.Diagnosis("J18.900", "肺炎", true)));
        return new ClinicalClient.HomepageSource(base.visitId(), base.visitNo(), base.visitType(),
                base.visitStatus(), base.patientId(), base.patientNo(), base.deptId(),
                base.deptName(), base.admissionId(), base.admissionNo(), base.admissionStatus(),
                base.admittedAt(), base.dischargedAt(), base.wardId(), base.wardName(),
                base.bedId(), base.bedNo(), base.attendingDoctor(), base.diagnoses(), procedures,
                base.feeSummary(), base.totalAmount());
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
                UUID.randomUUID(), "A1", "doctor01", diagnoses, List.of(),
                Map.of("EXAM", new BigDecimal("268.00")), new BigDecimal("268.00"));
    }
}
