package com.medassist.clinical.web;

import com.medassist.clinical.domain.NursingSheetModel.WardSheets;
import com.medassist.clinical.service.NursingSheetService;
import com.medassist.clinical.service.VisitAccessGuard;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 整区护理执行单（第 234 批）。
 *
 * <p>背景：护士站早交班要一次出全病区执行单。前端此前逐床请求
 * （每床医嘱 + 执行记录 共 2 次，30 床约 60 次往返），大病房出单明显偏慢，
 * 也更容易在网关限流下部分失败。本端点一次返回整区当日数据。
 *
 * <p>权限沿用护理查询 {@code inpatient:nursing:read}；院区/科室边界由
 * {@link VisitAccessGuard#requireWard} 统一判定（越权与不存在同码）。
 */
@RestController
@RequestMapping("/clinical/wards")
public class NursingSheetController {

    private static final String READ = "inpatient:nursing:read";

    private final NursingSheetService service;
    private final VisitAccessGuard guard;

    public NursingSheetController(NursingSheetService service, VisitAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    /** 病区当日护理执行单批量取数；date 缺省为当天。 */
    @GetMapping("/{wardId}/nursing-sheets")
    public ApiResponse<WardSheets> wardSheets(
            @PathVariable UUID wardId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        guard.requireWard(scope, wardId);
        return ApiResponse.ok(service.wardSheets(wardId, date));
    }
}
