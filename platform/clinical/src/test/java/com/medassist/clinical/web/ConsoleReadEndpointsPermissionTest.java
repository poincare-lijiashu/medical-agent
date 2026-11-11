package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.service.BedReservationService;
import com.medassist.clinical.service.DischargeCheckService;
import com.medassist.clinical.service.HandoverService;
import com.medassist.clinical.service.InpatientFinanceService;
import com.medassist.clinical.service.InpatientService;
import com.medassist.clinical.service.NursingService;
import com.medassist.clinical.service.ReferenceService;
import com.medassist.clinical.service.RegistrationService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.service.WardDischargeCheckService;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制台新增只读接口的权限矩阵。
 *
 * <p>这三个接口（科室、收费项目、就诊/病区列表）只用于界面选择，但同样
 * 会暴露患者与主数据信息，因此必须和既有读接口使用同一套权限点；
 * 本测试防止后续重构把权限串写错而单测仍全绿。
 */
class ConsoleReadEndpointsPermissionTest {

    private ReferenceService referenceService;
    private ReferenceController referenceController;
    private RegistrationService registrationService;
    private RegistrationController registrationController;
    private InpatientController inpatientController;

    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        referenceService = mock(ReferenceService.class);
        referenceController = new ReferenceController(referenceService);

        registrationService = mock(RegistrationService.class);
        registrationController = new RegistrationController(registrationService, mock(VisitAccessGuard.class));

        inpatientController = new InpatientController(
                mock(InpatientService.class), mock(InpatientFinanceService.class), mock(NursingService.class),
                mock(DischargeCheckService.class), mock(WardDischargeCheckService.class),
                mock(BedReservationService.class), mock(HandoverService.class),
                mock(VisitAccessGuard.class));
    }

    @Test
    @DisplayName("科室列表：缺少 clinical:visit:read 时 403，且不触达服务层")
    void departmentsRequirePermission() {
        assertThatThrownBy(() -> referenceController.departments("empi:patient:read"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(referenceService, never()).listDepartments();
    }

    @Test
    @DisplayName("科室列表：有权限时返回服务层数据")
    void departmentsPassThrough() {
        when(referenceService.listDepartments())
                .thenReturn(List.of(new Dept(UUID.randomUUID(), "OPD", "门诊", "OUTPATIENT", true)));

        var resp = referenceController.departments("clinical:visit:read");

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).deptCode()).isEqualTo("OPD");
    }

    @Test
    @DisplayName("收费项目检索：缺少权限时 403")
    void chargeItemsRequirePermission() {
        assertThatThrownBy(() -> referenceController.chargeItems("amox", null, 50, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(referenceService, never()).searchChargeItems(anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("收费项目检索：参数透传，返回价格供展示")
    void chargeItemsPassThrough() {
        when(referenceService.searchChargeItems("AMOX", "DRUG", 20))
                .thenReturn(List.of(new ChargeItemDef(UUID.randomUUID(), "DRUG-AMOX-025", "阿莫西林",
                        "DRUG", "盒", new BigDecimal("12.50"), true)));

        var resp = referenceController.chargeItems("AMOX", "DRUG", 20, "clinical:visit:read");

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).price()).isEqualByComparingTo("12.50");
    }

    @Test
    @DisplayName("患者就诊列表：缺少权限时 403，且不触达服务层")
    void visitsRequirePermission() {
        assertThatThrownBy(() -> registrationController.listByPatient(patientId, 20, "inpatient:read",
                DataScope.all("admin01", true)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(registrationService, never()).listByPatient(patientId, 20, java.util.Set.of(), null);
    }

    @Test
    @DisplayName("患者就诊列表：有权限时按患者返回")
    void visitsPassThrough() {
        Visit visit = new Visit(UUID.randomUUID(), "V20261102001", patientId, "P0001",
                UUID.randomUUID(), "D0001", null, "OUTPATIENT", "IN_TREATMENT",
                LocalDate.of(2026, 11, 2), 0L);
        when(registrationService.listByPatient(patientId, 20, java.util.Set.of(), null))
                .thenReturn(List.of(visit));

        var resp = registrationController.listByPatient(patientId, 20, "clinical:visit:read",
                DataScope.all("admin01", true));

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).visitNo()).isEqualTo("V20261102001");
    }

    @Test
    @DisplayName("患者就诊列表：单科室账号把科室集合下推服务层（G74）")
    void visitsPushDeptFilterDown() {
        UUID deptId = UUID.randomUUID();
        DataScope scope = DataScope.ofDepartments("doctor01", java.util.Set.of(deptId), false);
        when(registrationService.listByPatient(patientId, 20, java.util.Set.of(),
                java.util.Set.of(deptId))).thenReturn(List.of());

        var resp = registrationController.listByPatient(patientId, 20, "clinical:visit:read", scope);

        assertThat(resp.data()).isEmpty();
        verify(registrationService).listByPatient(patientId, 20, java.util.Set.of(),
                java.util.Set.of(deptId));
    }

    @Test
    @DisplayName("病区列表：缺少 inpatient:read 时 403")
    void wardsRequirePermission() {
        assertThatThrownBy(() -> inpatientController.listWards("clinical:visit:read",
                DataScope.all("admin01", true)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("病区列表：有权限时返回病区")
    void wardsPassThrough() {
        InpatientService service = mock(InpatientService.class);
        when(service.listWards(java.util.Set.of(), null)).thenReturn(List.of(
                new Ward(UUID.randomUUID(), "W01", "一病区", UUID.randomUUID(), true)));
        InpatientController controller = new InpatientController(
                service, mock(InpatientFinanceService.class), mock(NursingService.class),
                mock(DischargeCheckService.class), mock(WardDischargeCheckService.class),
                mock(BedReservationService.class), mock(HandoverService.class),
                mock(VisitAccessGuard.class));

        var resp = controller.listWards("inpatient:read", DataScope.all("admin01", true));

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).code()).isEqualTo("W01");
    }

    @Test
    @DisplayName("病区列表：单科室账号把科室集合下推服务层（G74）")
    void wardsPushDeptFilterDown() {
        UUID deptId = UUID.randomUUID();
        DataScope scope = DataScope.ofDepartments("doctor01", java.util.Set.of(deptId), false);
        InpatientService service = mock(InpatientService.class);
        when(service.listWards(java.util.Set.of(), java.util.Set.of(deptId))).thenReturn(List.of());
        InpatientController controller = new InpatientController(
                service, mock(InpatientFinanceService.class), mock(NursingService.class),
                mock(DischargeCheckService.class), mock(WardDischargeCheckService.class),
                mock(BedReservationService.class), mock(HandoverService.class),
                mock(VisitAccessGuard.class));

        var resp = controller.listWards("inpatient:read", scope);

        assertThat(resp.data()).isEmpty();
        verify(service).listWards(java.util.Set.of(), java.util.Set.of(deptId));
    }
}
