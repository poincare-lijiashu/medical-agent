package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.HealthExamItem;
import com.medassist.clinical.domain.ClinicalModel.HealthExamItemCountRow;
import com.medassist.clinical.domain.ClinicalModel.HealthExamPackage;
import com.medassist.clinical.domain.ClinicalModel.HealthExamPackageItem;
import com.medassist.clinical.domain.ClinicalModel.HealthExamRegistration;
import com.medassist.clinical.domain.ClinicalModel.HealthExamReport;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.HealthExamDtos.CreatePackageReq;
import com.medassist.clinical.web.HealthExamDtos.CreateRegistrationReq;
import com.medassist.clinical.web.HealthExamDtos.ItemResultReq;
import com.medassist.clinical.web.HealthExamDtos.PackageItemReq;
import com.medassist.clinical.web.HealthExamDtos.ReportDraftReq;
import com.medassist.clinical.web.HealthExamDtos.UpdatePackageReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 体检中心：套餐快照、登记、项目结果、总检报告与取消状态机。
 */
class HealthExamServiceTest {

    private ClinicalRepository repo;
    private PatientClient patientClient;
    private HealthExamService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID packageId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(ClinicalRepository.class);
        patientClient = mock(PatientClient.class);
        service = new HealthExamService(repo, patientClient, objectMapper);
    }

    private HealthExamPackage pkg(String status, int itemCount, long version) {
        return new HealthExamPackage(packageId, "PKG-BASIC", "基础体检套餐", "演示",
                new BigDecimal("293.00"), status, itemCount, "admin01", null, null, version,
                Instant.now());
    }

    private HealthExamPackageItem packageItem(int seq, String code, BigDecimal price) {
        return new HealthExamPackageItem(UUID.randomUUID(), packageId, seq, code, "项目" + seq,
                "EXAM", deptId, "检验科", price);
    }

    private HealthExamRegistration registration(String status, long version) {
        return new HealthExamRegistration(UUID.randomUUID(), "HE20261104000001", packageId,
                "基础体检套餐", new BigDecimal("293.00"), patientId, "P001", "张三", "MALE",
                LocalDate.of(1990, 1, 1), status, null, null, null, null, "admin01", version,
                Instant.now(), Instant.now());
    }

    private HealthExamItem item(UUID registrationId, int seq, String status, long version) {
        return new HealthExamItem(UUID.randomUUID(), registrationId, seq, "EXM-CBC", "血常规",
                "EXAM", deptId, "检验科", new BigDecimal("25.00"), status, null, null, false,
                null, null, version);
    }

    private HealthExamReport report(UUID registrationId, String status, long version) {
        return new HealthExamReport(UUID.randomUUID(), "HR20261104000001", registrationId,
                "未见明显异常", "定期复查", 0, status, "admin01", null, null, version,
                Instant.now());
    }

    private void stubChargeItem(String code, String name, BigDecimal price) {
        when(repo.findChargeItem(code)).thenReturn(Optional.of(
                new ChargeItemDef(UUID.randomUUID(), code, name, "EXAM", "次", price, true)));
    }

    private void stubDept() {
        when(repo.findDept(deptId)).thenReturn(Optional.of(
                new Dept(deptId, "DEPT-LAB", "检验科", "BOTH", true)));
    }

    @Test
    @DisplayName("创建套餐：收费项目取快照并汇总总价")
    void createsPackageWithChargeItemSnapshot() {
        when(repo.healthPackageCodeExists("PKG-BASIC")).thenReturn(false);
        stubChargeItem("EXM-CBC", "血常规", new BigDecimal("25.00"));
        stubChargeItem("EXM-CT-CHEST", "胸部CT平扫", new BigDecimal("260.00"));
        stubDept();
        when(repo.findHealthPackage(any())).thenReturn(Optional.of(
                pkg(HealthExamPackage.DRAFT, 2, 0)));
        when(repo.listHealthPackageItems(any())).thenReturn(List.of());

        var view = service.createPackage(new CreatePackageReq("pkg-basic", "基础体检套餐", "演示",
                List.of(new PackageItemReq("exm-cbc", deptId),
                        new PackageItemReq("exm-ct-chest", null))), "admin01");

        assertThat(view.pkg().packageCode()).isEqualTo("PKG-BASIC");
        verify(repo, org.mockito.Mockito.times(2)).insertHealthPackageItem(any());
    }

    @Test
    @DisplayName("创建套餐：编码重复 409、项目重复/停用 422")
    void validatesPackageItems() {
        when(repo.healthPackageCodeExists("PKG-BASIC")).thenReturn(true);
        assertThatThrownBy(() -> service.createPackage(new CreatePackageReq("PKG-BASIC", "套餐",
                null, List.of(new PackageItemReq("EXM-CBC", null))), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_PACKAGE_DUPLICATE);

        when(repo.healthPackageCodeExists("PKG-NEW")).thenReturn(false);
        stubChargeItem("EXM-CBC", "血常规", new BigDecimal("25.00"));
        assertThatThrownBy(() -> service.createPackage(new CreatePackageReq("PKG-NEW", "套餐",
                null, List.of(new PackageItemReq("EXM-CBC", null),
                        new PackageItemReq("EXM-CBC", null))), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_PACKAGE_INVALID);

        when(repo.findChargeItem("NOT-EXIST")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPackage(new CreatePackageReq("PKG-NEW2", "套餐",
                null, List.of(new PackageItemReq("NOT-EXIST", null))), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_PACKAGE_INVALID);
    }

    @Test
    @DisplayName("套餐更新/发布：仅草稿可改；空套餐不能发布")
    void packageStateMachine() {
        HealthExamPackage published = pkg(HealthExamPackage.PUBLISHED, 1, 1);
        when(repo.lockHealthPackage(packageId)).thenReturn(Optional.of(published));
        assertThatThrownBy(() -> service.updatePackage(packageId,
                new UpdatePackageReq("改名", null, List.of(new PackageItemReq("EXM-CBC", null))),
                "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_PACKAGE_STATUS_INVALID);

        HealthExamPackage draftEmpty = pkg(HealthExamPackage.DRAFT, 0, 0);
        when(repo.lockHealthPackage(packageId)).thenReturn(Optional.of(draftEmpty));
        assertThatThrownBy(() -> service.publishPackage(packageId, "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_PACKAGE_INVALID);

        HealthExamPackage draft = pkg(HealthExamPackage.DRAFT, 2, 0);
        when(repo.lockHealthPackage(packageId)).thenReturn(Optional.of(draft));
        when(repo.publishHealthPackage(packageId, "admin01", 0)).thenReturn(true);
        when(repo.findHealthPackage(packageId)).thenReturn(Optional.of(
                pkg(HealthExamPackage.PUBLISHED, 2, 1)));
        when(repo.listHealthPackageItems(packageId)).thenReturn(List.of());
        assertThat(service.publishPackage(packageId, "admin01").pkg().status())
                .isEqualTo(HealthExamPackage.PUBLISHED);
    }

    @Test
    @DisplayName("体检登记：套餐必须已发布，按套餐项目生成快照")
    void createsRegistrationSnapshot() {
        when(repo.findHealthPackage(packageId)).thenReturn(Optional.of(
                pkg(HealthExamPackage.DISABLED, 1, 1)));
        assertThatThrownBy(() -> service.createRegistration(
                new CreateRegistrationReq(packageId, patientId, null), "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_PACKAGE_STATUS_INVALID);

        when(repo.findHealthPackage(packageId)).thenReturn(Optional.of(
                pkg(HealthExamPackage.PUBLISHED, 2, 1)));
        when(repo.listHealthPackageItems(packageId)).thenReturn(List.of(
                packageItem(1, "EXM-CBC", new BigDecimal("25.00")),
                packageItem(2, "EXM-CT-CHEST", new BigDecimal("260.00"))));
        when(patientClient.requireProfile(patientId)).thenReturn(
                new PatientClient.PatientProfile(patientId, "P001", "张三", "MALE",
                        LocalDate.of(1990, 1, 1)));
        when(repo.nextHealthExamNo()).thenReturn("HE20261104000001");
        when(repo.findHealthRegistration(any())).thenReturn(Optional.of(
                registration(HealthExamRegistration.REGISTERED, 0)));
        when(repo.listHealthExamItems(any())).thenReturn(List.of());

        var view = service.createRegistration(
                new CreateRegistrationReq(packageId, patientId, "单位体检"), "admin01");
        assertThat(view.registration().status()).isEqualTo(HealthExamRegistration.REGISTERED);
        verify(repo, org.mockito.Mockito.times(2)).insertHealthExamItem(any());
    }

    @Test
    @DisplayName("项目结果：完成需结论、未检需原因；最后一项完成自动转待总检")
    void recordsItemResultAndFinishesExam() {
        HealthExamRegistration reg = registration(HealthExamRegistration.REGISTERED, 0);
        HealthExamItem pending = item(reg.id(), 1, HealthExamItem.PENDING, 0);
        when(repo.lockHealthRegistration(reg.id())).thenReturn(Optional.of(reg));
        when(repo.findHealthExamItem(pending.id())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.recordItemResult(reg.id(), pending.id(),
                new ItemResultReq("COMPLETED", null, null, null), "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_ITEM_RESULT_INVALID);
        assertThatThrownBy(() -> service.recordItemResult(reg.id(), pending.id(),
                new ItemResultReq("SKIPPED", null, null, null), "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_ITEM_RESULT_INVALID);

        when(repo.updateHealthExamItemResult(eq(pending.id()), eq("COMPLETED"), eq("正常"),
                isNull(), eq(false), eq("tech01"), eq(0L))).thenReturn(true);
        when(repo.countHealthExamItems(reg.id()))
                .thenReturn(new HealthExamItemCountRow(1, 0, 1, 0, 0));
        when(repo.finishHealthExam(reg.id(), 0)).thenReturn(true);
        when(repo.findHealthRegistration(reg.id())).thenReturn(Optional.of(
                registration(HealthExamRegistration.EXAM_DONE, 1)));

        service.recordItemResult(reg.id(), pending.id(),
                new ItemResultReq("COMPLETED", "正常", null, false), "tech01");

        verify(repo).finishHealthExam(reg.id(), 0);
        verify(repo, never()).startHealthRegistration(any(), eq(0L));
    }

    @Test
    @DisplayName("总检：项目未完成不能出报告；草稿后出具 FINAL 并转 REPORTED")
    void reportDraftAndFinalize() {
        HealthExamRegistration inProgress = registration(HealthExamRegistration.IN_PROGRESS, 1);
        when(repo.lockHealthRegistration(inProgress.id())).thenReturn(Optional.of(inProgress));
        assertThatThrownBy(() -> service.saveReportDraft(inProgress.id(),
                new ReportDraftReq("未见异常", null), "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_REPORT_INCOMPLETE);

        HealthExamRegistration examDone = registration(HealthExamRegistration.EXAM_DONE, 2);
        when(repo.lockHealthRegistration(examDone.id())).thenReturn(Optional.of(examDone));
        when(repo.countHealthExamItems(examDone.id()))
                .thenReturn(new HealthExamItemCountRow(1, 0, 1, 0, 1));
        when(repo.lockHealthReportByRegistration(examDone.id())).thenReturn(Optional.empty());
        when(repo.nextHealthReportNo()).thenReturn("HR20261104000001");
        when(repo.findHealthRegistration(examDone.id())).thenReturn(Optional.of(examDone));
        when(repo.findHealthReportByRegistration(examDone.id())).thenReturn(Optional.of(
                report(examDone.id(), HealthExamReport.DRAFT, 0)));

        service.saveReportDraft(examDone.id(), new ReportDraftReq("未见明显异常", "定期复查"),
                "doctor01");
        verify(repo).insertHealthReport(any());

        HealthExamReport draft = report(examDone.id(), HealthExamReport.DRAFT, 0);
        when(repo.lockHealthReportByRegistration(examDone.id())).thenReturn(Optional.of(draft));
        when(repo.finalizeHealthReport(draft.id(), "doctor01", 0)).thenReturn(true);
        when(repo.markHealthReported(examDone.id(), 2)).thenReturn(true);
        when(repo.findHealthReportByRegistration(examDone.id())).thenReturn(Optional.of(
                report(examDone.id(), HealthExamReport.FINAL, 1)));

        service.finalizeReport(examDone.id(), "doctor01");
        verify(repo).finalizeHealthReport(draft.id(), "doctor01", 0);
        verify(repo).markHealthReported(examDone.id(), 2);
    }

    @Test
    @DisplayName("取消登记：原因必填，已有报告不能取消")
    void cancelRegistrationRules() {
        HealthExamRegistration reg = registration(HealthExamRegistration.REGISTERED, 0);
        when(repo.lockHealthRegistration(reg.id())).thenReturn(Optional.of(reg));
        assertThatThrownBy(() -> service.cancelRegistration(reg.id(), " ", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_REGISTRATION_CANCEL_REASON_REQUIRED);

        when(repo.findHealthReportByRegistration(reg.id())).thenReturn(Optional.of(
                report(reg.id(), HealthExamReport.DRAFT, 0)));
        assertThatThrownBy(() -> service.cancelRegistration(reg.id(), "患者放弃", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.HEALTH_REGISTRATION_STATUS_INVALID);
    }

    @Test
    @DisplayName("查询：状态非法与区间倒置 → 422")
    void validatesLists() {
        LocalDate today = BusinessTime.today();
        assertThatThrownBy(() -> service.listPackages("UNKNOWN", null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.listRegistrations("UNKNOWN", today, today, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.listRegistrations(null, today, today.minusDays(1), 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).listHealthRegistrations(any(), any(), any(), anyInt());
    }
}
