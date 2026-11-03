package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.VisitTransfer;
import com.medassist.clinical.service.VisitTransferService;
import com.medassist.clinical.web.ClinicalDtos.TransferVisitReq;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 转科 API：变更就诊的主诊科室，并留下 append-only 的转科记录。
 *
 * <p>权限点复用就诊的两个既有权限：
 * <ul>
 *   <li>{@code clinical:visit:read}——查转科记录（新主诊科室、会诊期只读科室）；</li>
 *   <li>{@code clinical:visit:treat}——发起转科（当前主诊科室的医师）。</li>
 * </ul>
 * 不新增权限点：能接诊/结束就诊的人，才应该能决定这位患者转到哪个科。
 */
@RestController
@RequestMapping("/clinical")
public class VisitTransferController {

    private static final String READ = "clinical:visit:read";
    private static final String TREAT = "clinical:visit:treat";

    private final VisitTransferService service;

    public VisitTransferController(VisitTransferService service) {
        this.service = service;
    }

    /** 转科：目标科室 + 目标床位（在院已分床时必填）+ 理由。 */
    @PostMapping("/visits/{visitId}/transfers")
    public ApiResponse<VisitTransfer> transfer(
            @PathVariable UUID visitId,
            @Valid @RequestBody TransferVisitReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, TREAT);
        return ApiResponse.ok(service.transfer(scope, visitId, req, actor));
    }

    /** 该就诊的历次转科（按时间倒序）。 */
    @GetMapping("/visits/{visitId}/transfers")
    public ApiResponse<List<VisitTransfer>> list(
            @PathVariable UUID visitId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(scope, visitId));
    }
}
