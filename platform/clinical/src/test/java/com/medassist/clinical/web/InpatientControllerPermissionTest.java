package com.medassist.clinical.web;

import com.medassist.clinical.service.BedReservationService;
import com.medassist.clinical.service.DischargeCheckService;
import com.medassist.clinical.service.InpatientFinanceService;
import com.medassist.clinical.service.InpatientService;
import com.medassist.clinical.service.NursingService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.service.WardDischargeCheckService;
import com.medassist.clinical.web.ClinicalDtos.BedReserveReq;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.ClinicalDtos.NursingCorrectionReq;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接口级权限矩阵（不依赖 Spring 上下文，直接驱动 Controller）。
 *
 * <p>为什么需要这层测试：业务服务的单测不经过 Controller，权限串写错（例如把
 * {@code inpatient:nursing:read} 写成 {@code inpatient:read}）单测全绿但线上越权。
 */
class InpatientControllerPermissionTest {

    private InpatientService service;
    private InpatientFinanceService financeService;
    private NursingService nursingService;
    private DischargeCheckService checkService;
    private WardDischargeCheckService wardCheckService;
    private BedReservationService reservationService;
    private VisitAccessGuard guard;
    private InpatientController controller;

    private final UUID admissionId = UUID.randomUUID();
    private final UUID wardId = UUID.randomUUID();
    /** 本类只测权限点，数据范围用全院（数据范围本身由 VisitAccessGuardTest 覆盖）。 */
    private final DataScope scope = DataScope.all("admin01", true);

    @BeforeEach
    void setUp() {
        service = mock(InpatientService.class);
        financeService = mock(InpatientFinanceService.class);
        nursingService = mock(NursingService.class);
        checkService = mock(DischargeCheckService.class);
        wardCheckService = mock(WardDischargeCheckService.class);
        reservationService = mock(BedReservationService.class);
        guard = mock(VisitAccessGuard.class);
        controller = new InpatientController(service, financeService, nursingService, checkService,
                wardCheckService, reservationService, guard);
    }

    @Test
    @DisplayName("费用看板：缺少 inpatient:finance:read 时 403，且不触达服务层")
    void financeRequiresPermission() {
        assertThatThrownBy(() -> controller.finance(admissionId, "inpatient:read", "clerk01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(financeService, never()).finance(any(), anyString());
    }

    @Test
    @DisplayName("费用看板：有权限时透传操作者（用于审计）")
    void financePassesActor() {
        controller.finance(admissionId, "inpatient:finance:read", "clerk01", scope);

        verify(financeService).finance(admissionId, "clerk01");
    }

    @Test
    @DisplayName("欠费预警：不带 wardId 且无全院权限时 422（防横向枚举全院患者）")
    void alertsWithoutWardRequiresAllScope() {
        assertThatThrownBy(() -> controller.alerts(null, "inpatient:finance:read", "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(financeService, never()).alerts(any(), anyString());
    }

    @Test
    @DisplayName("欠费预警：带 wardId 时病区级权限即可")
    void alertsWithWardAllowed() {
        when(financeService.alerts(eq(wardId), anyString()))
                .thenReturn(List.of(new InpatientAlert(admissionId, "A1", "P1", null, "01",
                        new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10.00"))));

        var resp = controller.alerts(wardId, "inpatient:finance:read", "nurse01", scope);

        assertThat(resp.data()).hasSize(1);
        verify(financeService).alerts(wardId, "nurse01");
    }

    @Test
    @DisplayName("欠费预警：持有全院权限时可不带 wardId")
    void alertsAllScopeAllowed() {
        controller.alerts(null, "inpatient:finance:read,inpatient:finance:read:all", "admin01", scope);

        verify(financeService).alerts(null, "admin01");
    }

    @Test
    @DisplayName("护理执行列表：只有 inpatient:read 不够（属护理文书）")
    void nursingListRequiresNursingRead() {
        assertThatThrownBy(() -> controller.nursingExecutions(admissionId, null, "inpatient:read", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(nursingService, never()).list(any(), any());
    }

    @Test
    @DisplayName("护理执行列表：inpatient:nursing:read 放行")
    void nursingListAllowedWithNursingRead() {
        controller.nursingExecutions(admissionId, null, "inpatient:nursing:read", scope);

        verify(nursingService).list(admissionId, null);
    }

    @Test
    @DisplayName("记录护理执行：需要 inpatient:nursing:execute")
    void nursingExecuteRequiresPermission() {
        var req = new NursingExecutionReq(UUID.randomUUID(), null, "EXECUTED", null);

        assertThatThrownBy(() -> controller.executeNursing(admissionId, req, "inpatient:nursing:read", "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(nursingService, never()).record(any(), any(), anyString());
    }

    @Test
    @DisplayName("更正护理执行：需要 inpatient:nursing:execute（读权限不能改文书）")
    void nursingCorrectionRequiresExecutePermission() {
        var req = new NursingCorrectionReq("REFUSED", "patient refused", true);

        assertThatThrownBy(() -> controller.correctNursing(admissionId, UUID.randomUUID(), req,
                "inpatient:nursing:read", "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(nursingService, never()).correct(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("出院体检：只有 inpatient:read 不够（结论含未结金额与出院带药）")
    void dischargeCheckRequiresDischargePermission() {
        assertThatThrownBy(() -> controller.dischargeCheck(admissionId, "inpatient:read", "clerk01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(checkService, never()).check(any(), anyString());
    }

    @Test
    @DisplayName("出院体检：inpatient:admission:discharge 放行并透传操作者")
    void dischargeCheckPassesActor() {
        controller.dischargeCheck(admissionId, "inpatient:admission:discharge", "doctor01", scope);

        verify(checkService).check(admissionId, "doctor01");
    }

    @Test
    @DisplayName("病区出院预检：缺少 inpatient:read 时 403，且不触达服务层")
    void wardReadinessRequiresRead() {
        assertThatThrownBy(() -> controller.wardDischargeReadiness(wardId, null, "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(wardCheckService, never()).check(any(), anyString());
    }

    @Test
    @DisplayName("病区出院预检：inpatient:read 放行，先做病区范围校验再查")
    void wardReadinessPassesScopeAndActor() {
        controller.wardDischargeReadiness(wardId, "inpatient:read", "nurse01", scope);

        verify(guard).requireWard(scope, wardId);
        verify(wardCheckService).check(wardId, "nurse01");
    }

    @Test
    @DisplayName("出院预检汇总：缺读权限 403；有权限时按可见病区统计")
    void readinessSummaryUsesVisibleWards() {
        assertThatThrownBy(() -> controller.dischargeReadinessSummary(null, scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        var ward = new com.medassist.clinical.domain.ClinicalModel.Ward(wardId, "W01",
                "内科一病区", UUID.randomUUID(), true);
        when(service.listWards(any(), any())).thenReturn(List.of(ward));
        controller.dischargeReadinessSummary("inpatient:read", scope);

        verify(wardCheckService).summary(List.of(ward));
    }

    @Test
    @DisplayName("预计出院日：缺出院权限 403；有权限时先校验病区范围再写")
    void plannedDischargeRequiresDischargePermission() {
        var req = new com.medassist.clinical.web.ClinicalDtos.PlannedDischargeReq(
                java.time.LocalDate.now());

        assertThatThrownBy(() -> controller.planDischarge(admissionId, req,
                "inpatient:read", "doctor01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).planDischarge(any(), any(), anyString());

        controller.planDischarge(admissionId, req, "inpatient:admission:discharge", "doctor01", scope);

        verify(guard).requireAdmission(scope, admissionId);
        verify(service).planDischarge(admissionId, req.date(), "doctor01");
    }

    @Test
    @DisplayName("床位预留：读权限可查；写入需分床权限并先校验病区范围")
    void bedReservationPermissions() {
        when(reservationService.list(wardId)).thenReturn(List.of());
        controller.listBedReservations(wardId, "inpatient:read", scope);
        verify(guard).requireWard(scope, wardId);
        verify(reservationService).list(wardId);

        var req = new BedReserveReq(UUID.randomUUID(), null, null, null, null);
        assertThatThrownBy(() -> controller.reserveBed(wardId, req, "inpatient:read",
                "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(reservationService, never()).create(any(), any(), anyString());

        controller.reserveBed(wardId, req, "inpatient:bed:assign", "nurse01", scope);
        verify(reservationService).create(wardId, req, "nurse01");

        UUID reservationId = UUID.randomUUID();
        assertThatThrownBy(() -> controller.releaseBedReservation(wardId, reservationId, null,
                "inpatient:read", "nurse01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(reservationService, never()).release(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("权限头为空：提示重新登录（403，不误报 401）")
    void emptyPermissionsHeaderIsForbidden() {
        assertThatThrownBy(() -> controller.finance(admissionId, null, "clerk01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
