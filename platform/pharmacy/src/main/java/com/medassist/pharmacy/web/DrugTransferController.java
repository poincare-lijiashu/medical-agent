package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.domain.PharmacyModel.DeptDrugStock;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransfer;
import com.medassist.pharmacy.service.DrugTransferService;
import com.medassist.pharmacy.web.DrugTransferDtos.CreateDrugTransferReq;
import com.medassist.pharmacy.web.DrugTransferDtos.DeptStockView;
import com.medassist.pharmacy.web.DrugTransferDtos.DrugTransferView;
import com.medassist.pharmacy.web.DrugTransferDtos.TransferReasonReq;
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
 * 药品科室调拨 API。
 *
 * <p>权限：查询 {@code pharmacy:read}；建单/发出/撤回 {@code pharmacy:transfer:write}；
 * 接收/拒收 {@code pharmacy:transfer:accept}（护理或药房岗位）。
 */
@RestController
@RequestMapping("/pharmacy")
public class DrugTransferController {

    private static final String READ = "pharmacy:read";
    private static final String WRITE = "pharmacy:transfer:write";
    private static final String ACCEPT = "pharmacy:transfer:accept";

    private final DrugTransferService service;

    public DrugTransferController(DrugTransferService service) {
        this.service = service;
    }

    @GetMapping("/drug-transfers")
    public ApiResponse<List<DrugTransfer>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID toDeptId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(status, toDeptId, limit));
    }

    @GetMapping("/drug-transfers/{id}")
    public ApiResponse<DrugTransferView> detail(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/drug-transfers")
    public ApiResponse<DrugTransferView> create(
            @Valid @RequestBody CreateDrugTransferReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.create(req, actor));
    }

    /** 发出：扣中心批次库存。 */
    @PostMapping("/drug-transfers/{id}/dispatch")
    public ApiResponse<DrugTransferView> dispatch(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.dispatch(id, actor));
    }

    /** 接收：计入科室二级库。 */
    @PostMapping("/drug-transfers/{id}/accept")
    public ApiResponse<DrugTransferView> accept(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ACCEPT);
        return ApiResponse.ok(service.accept(id, actor));
    }

    /** 拒收：必须写原因，库存原路退回。 */
    @PostMapping("/drug-transfers/{id}/reject")
    public ApiResponse<DrugTransferView> reject(
            @PathVariable UUID id,
            @Valid @RequestBody TransferReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, ACCEPT);
        return ApiResponse.ok(service.reject(id, req, actor));
    }

    /** 撤回：仅未发出（SUBMITTED）可撤回。 */
    @PostMapping("/drug-transfers/{id}/cancel")
    public ApiResponse<DrugTransferView> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody TransferReasonReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.cancel(id, req, actor));
    }

    /** 科室二级库余额。 */
    @GetMapping("/dept-stocks")
    public ApiResponse<DeptStockView> deptStock(
            @RequestParam UUID deptId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        List<DeptDrugStock> items = service.deptStock(deptId, keyword, limit);
        return ApiResponse.ok(new DeptStockView(deptId, items));
    }
}
