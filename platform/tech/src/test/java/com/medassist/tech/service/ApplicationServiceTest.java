package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.client.PatientClient;
import com.medassist.tech.domain.TechModel.Application;
import com.medassist.tech.domain.TechModel.ExamItem;
import com.medassist.tech.domain.TechModel.Specimen;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.TechDtos.ApplicationView;
import com.medassist.tech.web.TechDtos.CollectSpecimenReq;
import com.medassist.tech.web.TechDtos.CreateApplicationReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
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
 * 申请与标本状态机：LAB 必须先采集/接收；拒收作废申请；危急值未闭环不得关闭。
 */
class ApplicationServiceTest {

    private TechRepository repo;
    private PatientClient patientClient;
    private ApplicationService service;

    private final UUID appId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final String labCode = "EXM-CBC";
    private final String examCode = "EXM-CT-CHEST";

    private final ExamItem labItem = new ExamItem(UUID.randomUUID(), labCode, "血常规", "LAB", "全血", true, 0L);
    private final ExamItem examItem = new ExamItem(UUID.randomUUID(), examCode, "胸部CT平扫", "EXAM", null, true, 0L);

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        patientClient = mock(PatientClient.class);
        service = new ApplicationService(repo, patientClient, JsonMapper.builder().build());
        when(patientClient.requireActive(patientId))
                .thenReturn(new PatientClient.PatientRef(patientId, "P20261031000001", "张三"));
        when(repo.nextApplicationNo()).thenReturn("AP20261031000001");
        when(repo.findApplication(any())).thenAnswer(inv -> Optional.of(app(Application.APPLIED, 0L)));
    }

    private Application app(String status, long version) {
        return new Application(appId, "AP20261031000001", patientId, "P20261031000001",
                null, null, null, "doctor01", "LAB", labCode, "血常规", null, "ROUTINE",
                null, "全血", status, version, Instant.now());
    }

    private CreateApplicationReq labReq() {
        return new CreateApplicationReq(patientId, null, null, null, labCode, null, "URGENT", "发热待查");
    }

    @Test
    @DisplayName("开单：检验项目带出标本类型，状态 APPLIED")
    void createLabApplication() {
        when(repo.findExamItemByCode(labCode)).thenReturn(Optional.of(labItem));
        when(repo.findApplication(any())).thenAnswer(inv -> Optional.of(new Application(
                inv.getArgument(0), "AP20261031000001", patientId, "P20261031000001",
                null, null, null, "doctor01", "LAB", labCode, "血常规", null, "URGENT",
                "发热待查", "全血", Application.APPLIED, 0L, Instant.now())));

        ApplicationView view = service.create(labReq(), "doctor01");

        assertThat(view.application().status()).isEqualTo(Application.APPLIED);
        assertThat(view.application().specimenType()).isEqualTo("全血");
        assertThat(view.application().priority()).isEqualTo("URGENT");
        assertThat(view.application().clinicalDiag()).isEqualTo("发热待查");
        verify(repo).insertApplication(any(Application.class));
        verify(repo).logChange(eq("doctor01"), eq("APP_CREATE"), eq("APPLICATION"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("开单：项目不存在或停用 → 404")
    void createRejectsUnknownItem() {
        when(repo.findExamItemByCode("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateApplicationReq(patientId, null, null, null,
                "NOPE", null, null, null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_EXAM_ITEM_NOT_FOUND);
        verify(repo, never()).insertApplication(any());
    }

    @Test
    @DisplayName("取消：只有 APPLIED 可取消")
    void cancelRequiresApplied() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.IN_PROGRESS, 2L)));

        assertThatThrownBy(() -> service.cancel(appId, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_APPLICATION_STATUS_INVALID);
    }

    @Test
    @DisplayName("采集标本：检查类申请没有标本，直接拒绝")
    void collectRejectsExamCategory() {
        Application exam = new Application(appId, "AP1", patientId, "P1", null, null, null,
                "doctor01", "EXAM", examCode, "胸部CT平扫", null, "ROUTINE", null, null,
                Application.APPLIED, 0L, Instant.now());
        when(repo.lockApplication(appId)).thenReturn(Optional.of(exam));

        assertThatThrownBy(() -> service.collectSpecimen(appId, new CollectSpecimenReq(null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有标本");
        verify(repo, never()).insertSpecimen(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("采集标本：生成条码并把申请推进到 SPECIMEN_COLLECTED")
    void collectAdvancesStatus() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.APPLIED, 0L)));
        when(repo.nextBarcode()).thenReturn("BC2026103100000001");
        when(repo.updateApplicationStatus(appId, Application.APPLIED,
                Application.SPECIMEN_COLLECTED, 0L)).thenReturn(true);

        service.collectSpecimen(appId, new CollectSpecimenReq(null), "nurse01");

        verify(repo).insertSpecimen(any(), eq(appId), eq("BC2026103100000001"), eq("全血"), eq("nurse01"));
        verify(repo).updateApplicationStatus(appId, Application.APPLIED,
                Application.SPECIMEN_COLLECTED, 0L);
    }

    @Test
    @DisplayName("采集标本：重复采集被数据库唯一键拦截（409）")
    void collectRejectsDuplicate() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.APPLIED, 0L)));
        when(repo.nextBarcode()).thenReturn("BC2");
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("ux_specimen_application"))
                .when(repo).insertSpecimen(any(), any(), anyString(), anyString(), anyString());

        assertThatThrownBy(() -> service.collectSpecimen(appId, new CollectSpecimenReq(null), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复采集");
    }

    @Test
    @DisplayName("拒收标本：作废申请（需重新开单），并记录拒收原因")
    void rejectSpecimenCancelsApplication() {
        Specimen specimen = new Specimen(UUID.randomUUID(), appId, "BC1", "全血",
                Specimen.COLLECTED, "nurse01", Instant.now(), null, null, null, 0L);
        when(repo.lockApplication(appId)).thenReturn(Optional.of(
                app(Application.SPECIMEN_COLLECTED, 1L)));
        when(repo.findSpecimenByApplication(appId)).thenReturn(Optional.of(specimen));
        when(repo.receiveSpecimen(eq(specimen.id()), eq(Specimen.REJECTED), eq("tech01"), eq(0L),
                eq("溶血"))).thenReturn(true);
        when(repo.updateApplicationStatus(appId, Application.SPECIMEN_COLLECTED,
                Application.CANCELLED, 1L)).thenReturn(true);

        service.receiveSpecimen(appId, false, "溶血", "tech01");

        verify(repo).updateApplicationStatus(appId, Application.SPECIMEN_COLLECTED,
                Application.CANCELLED, 1L);
        verify(repo).logChange(eq("tech01"), eq("SPECIMEN_REJECT"), eq("APPLICATION"),
                eq(appId.toString()), anyString());
    }

    @Test
    @DisplayName("开始执行：检验申请未接收标本时拒绝")
    void startRejectsLabNotReceived() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(
                app(Application.SPECIMEN_COLLECTED, 1L)));

        assertThatThrownBy(() -> service.start(appId, "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_APPLICATION_STATUS_INVALID);
    }

    @Test
    @DisplayName("关闭申请：存在未闭环危急值时拒绝")
    void closeRejectsOpenCritical() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.REPORTED, 4L)));
        when(repo.countOpenCritical(appId)).thenReturn(1);

        assertThatThrownBy(() -> service.close(appId, "tech01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未闭环");
        verify(repo, never()).updateApplicationStatus(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("关闭申请：危急值全部闭环后 CLOSED")
    void closeSucceedsWhenNoOpenCritical() {
        when(repo.lockApplication(appId)).thenReturn(Optional.of(app(Application.REPORTED, 4L)));
        when(repo.countOpenCritical(appId)).thenReturn(0);
        when(repo.updateApplicationStatus(appId, Application.REPORTED, Application.CLOSED, 4L))
                .thenReturn(true);

        service.close(appId, "tech01");

        verify(repo).updateApplicationStatus(appId, Application.REPORTED, Application.CLOSED, 4L);
    }
}
