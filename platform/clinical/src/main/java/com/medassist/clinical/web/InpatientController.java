package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.service.InpatientFinanceService;
import com.medassist.clinical.service.InpatientService;
import com.medassist.clinical.service.NursingService;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.AdmitReq;
import com.medassist.clinical.web.ClinicalDtos.AssignBedReq;
import com.medassist.clinical.web.ClinicalDtos.CreateBedsReq;
import com.medassist.clinical.web.ClinicalDtos.CreateWardReq;
import com.medassist.clinical.web.ClinicalDtos.DepositReq;
import com.medassist.clinical.web.ClinicalDtos.DepositView;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.ClinicalDtos.InpatientFinanceView;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionReq;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
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
 *   <li>{@code inpatient:admission:discharge}——出院办理（医师/住院处）；</li>
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

    public InpatientController(InpatientService service, InpatientFinanceService financeService,
                               NursingService nursingService) {
        this.service = service;
        this.financeService = financeService;
        this.nursingService = nursingService;
    }

    @PostMapping("/wards")
    public ApiResponse<Ward> createWard(
            @Valid @RequestBody CreateWardReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WARD_MANAGE);
        return ApiResponse.ok(service.createWard(req, actor));
    }

    @PostMapping("/wards/{wardId}/beds")
    public ApiResponse<List<Bed>> createBeds(
            @PathVariable UUID wardId,
            @Valid @RequestBody CreateBedsReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WARD_MANAGE);
        return ApiResponse.ok(service.createBeds(wardId, req, actor));
    }

    @GetMapping("/wards/{wardId}/beds")
    public ApiResponse<List<Bed>> listBeds(
            @PathVariable UUID wardId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listBeds(wardId));
    }

    @GetMapping("/wards/{wardId}/admissions")
    public ApiResponse<List<AdmissionView>> listAdmissions(
            @PathVariable UUID wardId,
            @RequestParam(required = false) String status,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listByWard(wardId, status));
    }

    @PostMapping("/inpatient/admissions")
    public ApiResponse<AdmissionView> admit(
            @Valid @RequestBody AdmitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ADMIT);
        return ApiResponse.ok(service.admit(req, actor));
    }

    @GetMapping("/inpatient/admissions/{id}")
    public ApiResponse<AdmissionView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/inpatient/admissions/{id}/bed")
    public ApiResponse<AdmissionView> assignBed(
            @PathVariable UUID id,
            @Valid @RequestBody AssignBedReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, BED_ASSIGN);
        return ApiResponse.ok(service.assignBed(id, req, actor));
    }

    @PostMapping("/inpatient/admissions/{id}/discharge")
    public ApiResponse<AdmissionView> discharge(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DISCHARGE);
        return ApiResponse.ok(service.discharge(id, actor));
    }

    /** 缴纳预交金。 */
    @PostMapping("/inpatient/admissions/{id}/deposits")
    public ApiResponse<DepositView> deposit(
            @PathVariable UUID id,
            @Valid @RequestBody DepositReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DEPOSIT_MANAGE);
        return ApiResponse.ok(service.deposit(id, req.amount(), req.method(), req.remark(), actor));
    }

    /** 退还预交金（不得超过余额）。 */
    @PostMapping("/inpatient/admissions/{id}/refunds")
    public ApiResponse<DepositView> refund(
            @PathVariable UUID id,
            @Valid @RequestBody DepositReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, DEPOSIT_MANAGE);
        return ApiResponse.ok(service.refund(id, req.amount(), req.method(), req.remark(), actor));
    }

    /** 预交金余额与流水。 */
    @GetMapping("/inpatient/admissions/{id}/deposits")
    public ApiResponse<DepositView> deposits(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.balance(id));
    }

    // ---------------------------------------------------------------- 费用看板 / 欠费预警

    /** 住院费用看板：已计/已结/未结/待计/当日未计长嘱/预交金余额/欠费缺口。 */
    @GetMapping("/inpatient/admissions/{id}/finance")
    public ApiResponse<InpatientFinanceView> finance(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, FINANCE_READ);
        return ApiResponse.ok(financeService.finance(id, actor));
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
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, FINANCE_READ);
        if (wardId == null && !Permissions.has(permissions, FINANCE_READ_ALL)) {
            throw new com.medassist.common.exception.BusinessException(
                    com.medassist.common.api.ErrorCode.VALIDATION_FAILED,
                    "查询全院欠费预警需要 inpatient:finance:read:all 权限；请指定 wardId 查询本病区");
        }
        return ApiResponse.ok(financeService.alerts(wardId, actor));
    }

    // ---------------------------------------------------------------- 护理执行记录

    /** 记录长嘱执行（EXECUTED / REFUSED / OMITTED），同日同医嘱只能记录一次。 */
    @PostMapping("/inpatient/admissions/{id}/nursing-executions")
    public ApiResponse<NursingExecutionView> executeNursing(
            @PathVariable UUID id,
            @Valid @RequestBody NursingExecutionReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, NURSING_EXECUTE);
        return ApiResponse.ok(nursingService.record(id, req, actor));
    }

    /** 护理执行记录列表（date 为空返回全部；属护理文书，限医护与管理员）。 */
    @GetMapping("/inpatient/admissions/{id}/nursing-executions")
    public ApiResponse<List<NursingExecutionView>> nursingExecutions(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, NURSING_READ);
        return ApiResponse.ok(nursingService.list(id, date));
    }
}
