package com.medassist.pharmacy.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.pharmacy.service.PharmacyReportSnapshotService;
import com.medassist.pharmacy.web.PharmacyReportDtos.SnapshotCaptureView;
import com.medassist.pharmacy.web.PharmacyReportDtos.SnapshotListView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 药事日快照 API（G18）。
 *
 * <p>读：药师/质控/管理员；写（手动补采）：仅管理员。日常采集由定时任务完成。
 */
@RestController
@RequestMapping("/pharmacy/reports")
public class PharmacyReportSnapshotController {

    private static final String READ = "pharmacy:report:snapshot:read";
    private static final String WRITE = "pharmacy:report:snapshot:write";

    private final PharmacyReportSnapshotService service;

    public PharmacyReportSnapshotController(PharmacyReportSnapshotService service) {
        this.service = service;
    }

    @GetMapping("/snapshots")
    public ApiResponse<SnapshotListView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String metric,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(from, to, metric));
    }

    @PostMapping("/snapshots")
    public ApiResponse<SnapshotCaptureView> capture(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.capture(date, actor, "MANUAL"));
    }
}
