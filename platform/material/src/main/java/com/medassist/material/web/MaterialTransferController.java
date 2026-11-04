package com.medassist.material.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.material.domain.MaterialModel.DeptTransfer;
import com.medassist.material.service.DeptTransferService;
import com.medassist.material.web.MaterialTransferDtos.CreateDeptTransferReq;
import com.medassist.material.web.MaterialTransferDtos.DeptTransferView;
import com.medassist.material.web.MaterialTransferDtos.TransferReasonReq;
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
 * 耗材跨科室调拨 API（科室二级库 → 科室二级库）。
 *
 * <p>权限：查询 {@code material:read}；建单/撤回 {@code material:transfer:write}；
 * 接收/拒收 {@code material:transfer:accept}（接收人不得是申请人，服务层强制）。
 */
@RestController
@RequestMapping("/material/dept-transfers")
public class MaterialTransferController {

    private static final String READ = "material:read";
    private static final String WRITE = "material:transfer:write";
    private static final String ACCEPT = "material:transfer:accept";

    private final DeptTransferService service;

    public MaterialTransferController(DeptTransferService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<DeptTransfer>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID deptId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, deptId, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<DeptTransferView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    /** 建单：按调出科室余额预校验，不扣库存。 */
    @PostMapping
    public ApiResponse<DeptTransferView> create(
            @Valid @RequestBody CreateDeptTransferReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(req, actor));
    }

    /** 接收：扣调出科室库并加调入科室库。 */
    @PostMapping("/{id}/accept")
    public ApiResponse<DeptTransferView> accept(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ACCEPT);
        return ApiResponse.ok(service.accept(id, actor));
    }

    /** 拒收：必须写原因，不动库存。 */
    @PostMapping("/{id}/reject")
    public ApiResponse<DeptTransferView> reject(
            @PathVariable UUID id,
            @Valid @RequestBody TransferReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ACCEPT);
        return ApiResponse.ok(service.reject(id, req, actor));
    }

    /** 撤回：仅待接收可撤回，必须写原因。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<DeptTransferView> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody TransferReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.cancel(id, req, actor));
    }
}
