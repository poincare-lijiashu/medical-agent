package com.medassist.material.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.material.service.MaterialReportSnapshotService;
import com.medassist.material.web.MaterialReportDtos.SnapshotCaptureView;
import com.medassist.material.web.MaterialReportDtos.SnapshotListView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 物资日快照 API（G18）。
 *
 * <p>读：药师/护士/质控/管理员；写（手动补采）：仅管理员。
 */
@RestController
@RequestMapping("/material/reports")
public class MaterialReportSnapshotController {

    private static final String READ = "material:report:snapshot:read";
    private static final String WRITE = "material:report:snapshot:write";

    private final MaterialReportSnapshotService service;

    public MaterialReportSnapshotController(MaterialReportSnapshotService service) {
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
