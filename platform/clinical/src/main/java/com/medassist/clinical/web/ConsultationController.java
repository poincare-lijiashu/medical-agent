package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Consultation;
import com.medassist.clinical.service.ConsultationService;
import com.medassist.clinical.web.ConsultationDtos.CancelConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.CompleteConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.CreateConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.RejectConsultationReq;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 会诊 API：申请、受理、拒绝、完成、撤回。
 *
 * <p>会诊是跨科室只读授权的唯一来源：{@code REQUESTED / ACCEPTED} 期间，受邀科室的医师
 * 可以读该就诊的详情、医嘱与诊断；一旦拒绝、完成或撤回，授权立即收回。
 * 读权限判定见 {@code VisitAccessGuard#requireRead}。
 *
 * <p>权限点：
 * <ul>
 *   <li>{@code clinical:consultation:read}——查收件箱与按就诊查会诊；</li>
 *   <li>{@code clinical:consultation:write}——发起、受理、拒绝、完成、撤回。</li>
 * </ul>
 */
@RestController
@RequestMapping("/clinical")
public class ConsultationController {

    private static final String READ = "clinical:consultation:read";
    private static final String WRITE = "clinical:consultation:write";

    private final ConsultationService service;

    public ConsultationController(ConsultationService service) {
        this.service = service;
    }

    /** 会诊收件箱：受邀科室看自己的，全院角色看全部。 */
    @GetMapping("/consultations")
    public ApiResponse<List<Consultation>> inbox(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.inbox(scope, status, limit).stream()
                .map(c -> PhiMask.consultation(c, scope))
                .toList());
    }

    /** 某就诊的全部会诊记录（主诊科室、受邀科室、全院角色可见）。 */
    @GetMapping("/visits/{visitId}/consultations")
    public ApiResponse<List<Consultation>> listByVisit(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listByVisit(scope, visitId).stream()
                .map(c -> PhiMask.consultation(c, scope))
                .toList());
    }

    /** 发起会诊（主诊科室）。 */
    @PostMapping("/visits/{visitId}/consultations")
    public ApiResponse<Consultation> request(
            @PathVariable UUID visitId,
            @Valid @RequestBody CreateConsultationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(PhiMask.consultation(
                service.request(scope, visitId, req, actor), scope));
    }

    /** 受理（受邀科室）：受理后只读授权生效。 */
    @PostMapping("/consultations/{id}/accept")
    public ApiResponse<Consultation> accept(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(PhiMask.consultation(service.accept(scope, id, actor), scope));
    }

    /** 拒绝（受邀科室，必须写理由）：拒绝的同时只读授权不生效。 */
    @PostMapping("/consultations/{id}/reject")
    public ApiResponse<Consultation> reject(
            @PathVariable UUID id,
            @Valid @RequestBody RejectConsultationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(PhiMask.consultation(
                service.reject(scope, id, req, actor), scope));
    }

    /** 完成会诊（受邀科室）：填写会诊意见，完成后只读授权收回。 */
    @PostMapping("/consultations/{id}/complete")
    public ApiResponse<Consultation> complete(
            @PathVariable UUID id,
            @Valid @RequestBody CompleteConsultationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(PhiMask.consultation(
                service.complete(scope, id, req, actor), scope));
    }

    /** 撤回（主诊科室）：转院、病情变化或重复申请时终止授权。 */
    @PostMapping("/consultations/{id}/cancel")
    public ApiResponse<Consultation> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelConsultationReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(PhiMask.consultation(
                service.cancel(scope, id, req, actor), scope));
    }
}
