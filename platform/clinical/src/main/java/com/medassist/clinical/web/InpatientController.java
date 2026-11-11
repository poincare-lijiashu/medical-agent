package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.domain.ClinicalModel.WardOccupancy;
import com.medassist.clinical.service.BedReservationService;
import com.medassist.clinical.service.DischargeCheckService;
import com.medassist.clinical.service.HandoverService;
import com.medassist.clinical.service.InpatientFinanceService;
import com.medassist.clinical.service.InpatientService;
import com.medassist.clinical.service.NursingService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.clinical.service.WardDischargeCheckService;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.AdmitReq;
import com.medassist.clinical.web.ClinicalDtos.AssignBedReq;
import com.medassist.clinical.web.ClinicalDtos.BedReleaseReq;
import com.medassist.clinical.web.ClinicalDtos.BedReservationView;
import com.medassist.clinical.web.ClinicalDtos.BedReserveReq;
import com.medassist.clinical.web.ClinicalDtos.CreateBedsReq;
import com.medassist.clinical.web.ClinicalDtos.CreateWardReq;
import com.medassist.clinical.web.ClinicalDtos.DepositReq;
import com.medassist.clinical.web.ClinicalDtos.DepositView;
import com.medassist.clinical.web.ClinicalDtos.DischargeCheckView;
import com.medassist.clinical.web.ClinicalDtos.DischargeReadinessSummary;
import com.medassist.clinical.web.ClinicalDtos.HandoverSaveReq;
import com.medassist.clinical.web.ClinicalDtos.HandoverView;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.ClinicalDtos.InpatientFinanceView;
import com.medassist.clinical.web.ClinicalDtos.NursingCorrectionReq;
import com.medassist.clinical.web.ClinicalDtos.NursingCorrectionView;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionReq;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionView;
import com.medassist.clinical.web.ClinicalDtos.PlannedDischargeReq;
import com.medassist.clinical.web.ClinicalDtos.WardDischargeReadinessView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 住院 ADT API。
 *
 * <p>权限点：
 * <ul>
 *   <li>{@code inpatient:ward:manage}——病区与床位维护（护理部/管理员）；</li>
 *   <li>{@code inpatient:admission:create}——入院登记（住院处/医师）；</li>
 *   <li>{@code inpatient:bed:assign}——分床/转床（护理部）；</li>
 *   <li>{@code inpatient:admission:discharge}——出院办理与出院前体检（医师/住院处）；</li>
 *   <li>{@code inpatient:finance:read}——费用看板与欠费预警（住院处/医师/护士）；</li>
 *   <li>{@code inpatient:nursing:execute}——护理执行记录（护士）；</li>
 *   <li>{@code inpatient:read}——住院信息查询。</li>
 * </ul>
 */
@RestController
@RequestMapping("/clinical")
public class InpatientController {

    private static final String WARD_MANAGE = "inpatient:ward:manage";
    private static final String ADMIT = "inpatient:admission:create";
    private static final String BED_ASSIGN = "inpatient:bed:assign";
    private static final String DISCHARGE = "inpatient:admission:discharge";
    private static final String DEPOSIT_MANAGE = "inpatient:deposit:manage";
    private static final String FINANCE_READ = "inpatient:finance:read";
    /** 全院费用视图（可跨病区查看欠费）：默认只授予 ADMIN，按角色收紧见 auth V10。 */
    private static final String FINANCE_READ_ALL = "inpatient:finance:read:all";
    private static final String NURSING_EXECUTE = "inpatient:nursing:execute";
    private static final String NURSING_READ = "inpatient:nursing:read";
    private static final String READ = "inpatient:read";

    private final InpatientService service;
    private final InpatientFinanceService financeService;
    private final NursingService nursingService;
    private final DischargeCheckService checkService;
    private final WardDischargeCheckService wardCheckService;
    private final BedReservationService reservationService;
    private final HandoverService handoverService;
    private final VisitAccessGuard guard;

    public InpatientController(InpatientService service, InpatientFinanceService financeService,
                               NursingService nursingService, DischargeCheckService checkService,
                               WardDischargeCheckService wardCheckService,
                               BedReservationService reservationService,
                               HandoverService handoverService, VisitAccessGuard guard) {
        this.service = service;
        this.financeService = financeService;
        this.nursingService = nursingService;
        this.checkService = checkService;
        this.wardCheckService = wardCheckService;
        this.reservationService = reservationService;
        this.handoverService = handoverService;
        this.guard = guard;
    }

    /**
     * 启用病区列表（控制台病区选择）：只返回当前账号科室范围内的病区。
     *
     * <p>科室条件在 SQL 层收敛（G74），不在内存里补过滤。
     */
    @GetMapping("/wards")
    public ApiResponse<List<Ward>> listWards(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listWards(scope.campusFilter(),
                scope.allDepartments() ? null : scope.deptCodes()));
    }

    /**
     * 病区占用汇总（第 188 批）：运营驾驶舱用**一次请求**代替 N 个病区 × 2 次明细请求。
     *
     * <p>背景：驾驶舱曾对 100 个病区逐个拉床位与入院（200 个请求），必然触发网关限流（429），
     * 「住院床位」整块降级为空态。权限口径与 {@link #listWards} 完全一致（院区/科室下推 SQL）。
     */
    @GetMapping("/inpatient/ward-summary")
    public ApiResponse<List<WardOccupancy>> wardSummary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.wardSummary(scope.campusFilter(),
                scope.allDepartments() ? null : scope.deptCodes()));
    }

    @PostMapping("/wards")
    public ApiResponse<Ward> createWard(
            @Valid @RequestBody CreateWardReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WARD_MANAGE);
        if (!scope.canSeeDepartment(req.deptId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能维护本科室的病区");
        }
        return ApiResponse.ok(service.createWard(req, actor, scope.campusForWrite()));
    }

    @PostMapping("/wards/{wardId}/beds")
    public ApiResponse<List<Bed>> createBeds(
            @PathVariable UUID wardId,
            @Valid @RequestBody CreateBedsReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WARD_MANAGE);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(service.createBeds(wardId, req, actor));
    }

    @GetMapping("/wards/{wardId}/beds")
    public ApiResponse<List<Bed>> listBeds(
            @PathVariable UUID wardId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(service.listBeds(wardId));
    }

    /**
     * 床位预留列表（择期入院 / 转科 / 急诊留床）。
     *
     * <p>读权限即可看：预留是床位协调信息，与出院预检同属“看看谁占着床”的范畴。
     */
    @GetMapping("/wards/{wardId}/bed-reservations")
    public ApiResponse<List<BedReservationView>> listBedReservations(
            @PathVariable UUID wardId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(reservationService.list(wardId));
    }

    /**
     * 预留床位：与分床同权限（都是护理站调度床位）。
     *
     * <p>不绑定患者时为机动预留；绑定患者后只有该患者能直接入住。
     */
    @PostMapping("/wards/{wardId}/bed-reservations")
    public ApiResponse<BedReservationView> reserveBed(
            @PathVariable UUID wardId,
            @Valid @RequestBody BedReserveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, BED_ASSIGN);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(reservationService.create(wardId, req, actor));
    }

    /** 解除预留：释放床位并留痕（原因可选）。 */
    @PostMapping("/wards/{wardId}/bed-reservations/{id}/release")
    public ApiResponse<Void> releaseBedReservation(
            @PathVariable UUID wardId,
            @PathVariable UUID id,
            @RequestBody(required = false) BedReleaseReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, BED_ASSIGN);
        guard.requireWard(scope, wardId);
        reservationService.release(wardId, id, req == null ? null : req.reason(), actor);
        return ApiResponse.ok(null);
    }

    /**
     * 交班报告（按病区 + 交班日 + 班次）。
     *
     * <p>已签字返回冻结快照；否则返回实时在院清单并叠加草稿备注。
     */
    @GetMapping("/wards/{wardId}/handover")
    public ApiResponse<HandoverView> handover(
            @PathVariable UUID wardId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate shiftDate,
            @RequestParam(required = false) String shift,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(handoverService.view(wardId, shiftDate, shift));
    }

    /**
     * 保存交班草稿。
     *
     * <p>写权限用护理执行（`inpatient:nursing:execute`）：交班备注属于护理记录；
     * 只接受逐人备注，患者与风险项由服务端生成。
     */
    @PostMapping("/wards/{wardId}/handover")
    public ApiResponse<HandoverView> saveHandover(
            @PathVariable UUID wardId,
            @Valid @RequestBody HandoverSaveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, NURSING_EXECUTE);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(handoverService.save(wardId, req, actor, false));
    }

    /** 交班签字：保存并冻结（DRAFT → SIGNED），签字后不可再改。 */
    @PostMapping("/wards/{wardId}/handover/sign")
    public ApiResponse<HandoverView> signHandover(
            @PathVariable UUID wardId,
            @Valid @RequestBody HandoverSaveReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, NURSING_EXECUTE);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(handoverService.save(wardId, req, actor, true));
    }

    @GetMapping("/wards/{wardId}/admissions")
    public ApiResponse<List<AdmissionView>> listAdmissions(
            @PathVariable UUID wardId,
            @RequestParam(required = false) String status,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(service.listByWard(wardId, status).stream()
                .map(a -> PhiMask.admission(a, scope))
                .toList());
    }

    @PostMapping("/inpatient/admissions")
    public ApiResponse<AdmissionView> admit(
            @Valid @RequestBody AdmitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, ADMIT);
        guard.requireWard(scope, req.wardId());
        return ApiResponse.ok(PhiMask.admission(service.admit(req, actor), scope));
    }

    @GetMapping("/inpatient/admissions/{id}")
    public ApiResponse<AdmissionView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(PhiMask.admission(service.detail(id), scope));
    }

    @PostMapping("/inpatient/admissions/{id}/bed")
    public ApiResponse<AdmissionView> assignBed(
            @PathVariable UUID id,
            @Valid @RequestBody AssignBedReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, BED_ASSIGN);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(PhiMask.admission(service.assignBed(id, req, actor), scope));
    }

    /**
     * 设置/清除预计出院日（出院计划）。
     *
     * <p>与出院办理同权限：它是出院协调的一部分，能改“计划哪天走”的人
     * 本就应当能看到出院结论；只做计划，不参与出院门禁。
     */
    @PostMapping("/inpatient/admissions/{id}/planned-discharge")
    public ApiResponse<AdmissionView> planDischarge(
            @PathVariable UUID id,
            @RequestBody PlannedDischargeReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, DISCHARGE);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(PhiMask.admission(
                service.planDischarge(id, req == null ? null : req.date(), actor), scope));
    }

    /**
     * 出院预检汇总（全局待办角标 / 驾驶舱）。
     *
     * <p>统计范围 = 当前账号可见病区，口径与 {@link #wardDischargeReadiness} 一致；
     * 只回计数、不写审计（角标会周期性刷新）。
     */
    @GetMapping("/inpatient/discharge-readiness/summary")
    public ApiResponse<DischargeReadinessSummary> dischargeReadinessSummary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        List<Ward> wards = service.listWards(scope.campusFilter(),
                scope.allDepartments() ? null : scope.deptCodes());
        return ApiResponse.ok(wardCheckService.summary(wards));
    }

    /**
     * 病区出院预检（只读批量）。
     *
     * <p>与单患者体检同一口径、同样的阻断项码，但**不回金额**：病区列表面向所有
     * 住院读权限角色，费用金额仍需 {@code inpatient:finance:read} 才能看。
     */
    @GetMapping("/wards/{wardId}/discharge-readiness")
    public ApiResponse<WardDischargeReadinessView> wardDischargeReadiness(
            @PathVariable UUID wardId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(wardCheckService.check(wardId, actor));
    }

    /**
     * 出院前一致性体检（只读）。
     *
     * <p>与 {@code discharge} 使用同一套门禁口径，把阻断项一次性列出；
     * 另附建议处理项与"本服务未核查"的病历归档项。
     */
    @GetMapping("/inpatient/admissions/{id}/discharge-check")
    public ApiResponse<DischargeCheckView> dischargeCheck(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, DISCHARGE);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(checkService.check(id, actor));
    }

    @PostMapping("/inpatient/admissions/{id}/discharge")
    public ApiResponse<AdmissionView> discharge(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, DISCHARGE);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(PhiMask.admission(service.discharge(id, actor), scope));
    }

    /** 缴纳预交金。 */
    @PostMapping("/inpatient/admissions/{id}/deposits")
    public ApiResponse<DepositView> deposit(
            @PathVariable UUID id,
            @Valid @RequestBody DepositReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, DEPOSIT_MANAGE);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(service.deposit(id, req.amount(), req.method(), req.remark(), actor));
    }

    /** 退还预交金（不得超过余额）。 */
    @PostMapping("/inpatient/admissions/{id}/refunds")
    public ApiResponse<DepositView> refund(
            @PathVariable UUID id,
            @Valid @RequestBody DepositReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, DEPOSIT_MANAGE);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(service.refund(id, req.amount(), req.method(), req.remark(), actor));
    }

    /** 预交金余额与流水。 */
    @GetMapping("/inpatient/admissions/{id}/deposits")
    public ApiResponse<DepositView> deposits(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(service.balance(id));
    }

    // ---------------------------------------------------------------- 费用看板 / 欠费预警

    /** 住院费用看板：已计/已结/未结/待计/当日未计长嘱/预交金余额/欠费缺口。 */
    @GetMapping("/inpatient/admissions/{id}/finance")
    public ApiResponse<InpatientFinanceView> finance(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, FINANCE_READ);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(PhiMask.finance(financeService.finance(id, actor), scope));
    }

    /**
     * 中途欠费预警：返回缺口大于 0 的在院患者，按缺口倒序。
     *
     * <p>数据范围：{@code wardId} 为空等同于"全院"，只有持有
     * {@code inpatient:finance:read:all} 的角色（默认 ADMIN）才允许；其余角色必须指定病区，
     * 否则任一护士/挂号员都能枚举全院欠费患者的床号与欠费金额。
     */
    @GetMapping("/inpatient/alerts")
    public ApiResponse<List<InpatientAlert>> alerts(
            @RequestParam(required = false) UUID wardId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, FINANCE_READ);
        if (wardId == null && !Permissions.has(permissions, FINANCE_READ_ALL)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "查询全院欠费预警需要 inpatient:finance:read:all 权限；请指定 wardId 查询本病区");
        }
        if (wardId != null) {
            guard.requireWard(scope, wardId);
        }
        return ApiResponse.ok(financeService.alerts(wardId, actor).stream()
                .map(a -> PhiMask.alert(a, scope))
                .toList());
    }

    // ---------------------------------------------------------------- 护理执行记录

    /** 记录长嘱执行（EXECUTED / REFUSED / OMITTED），同日同医嘱只能记录一次。 */
    @PostMapping("/inpatient/admissions/{id}/nursing-executions")
    public ApiResponse<NursingExecutionView> executeNursing(
            @PathVariable UUID id,
            @Valid @RequestBody NursingExecutionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, NURSING_EXECUTE);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(nursingService.record(id, req, actor));
    }

    /**
     * 更正护理执行记录：每条执行记录只允许更正一次。
     *
     * <p>「已执行 → 未执行/拒绝」会在同一事务内联动红冲该日按日计费；
     * 已结算（PAID）无法红冲时整体回滚，提示先走退费流程。
     */
    @PostMapping("/inpatient/admissions/{id}/nursing-executions/{executionId}/correction")
    public ApiResponse<NursingCorrectionView> correctNursing(
            @PathVariable UUID id,
            @PathVariable UUID executionId,
            @Valid @RequestBody NursingCorrectionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, NURSING_EXECUTE);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(nursingService.correct(id, executionId, req, actor));
    }

    /** 护理执行记录列表（date 为空返回全部；属护理文书，限医护与管理员）。 */
    @GetMapping("/inpatient/admissions/{id}/nursing-executions")
    public ApiResponse<List<NursingExecutionView>> nursingExecutions(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, NURSING_READ);
        guard.requireAdmission(scope, id);
        return ApiResponse.ok(nursingService.list(id, date));
    }
}
