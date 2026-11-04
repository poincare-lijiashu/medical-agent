package com.medassist.clinical.web;

import com.medassist.clinical.service.ReportSnapshotService;
import com.medassist.clinical.web.ClinicalDtos.SnapshotCaptureView;
import com.medassist.clinical.web.ClinicalDtos.SnapshotListView;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 运营日快照 API（G17）。
 *
 * <p>读：管理/临床岗位可看（{@code clinical:report:snapshot:read}）；
 * 写：手动补采只授予管理员（{@code clinical:report:snapshot:write}），
 * 日常采集由 {@link com.medassist.clinical.service.ReportSnapshotWorker} 定时完成。
 */
@RestController
@RequestMapping("/clinical/reports")
public class ReportSnapshotController {

    private static final String READ = "clinical:report:snapshot:read";
    private static final String WRITE = "clinical:report:snapshot:write";

    private final ReportSnapshotService service;

    public ReportSnapshotController(ReportSnapshotService service) {
        this.service = service;
    }

    /** 快照列表：默认最近 7 天，最多 92 天；metric 为空返回全部指标。 */
    @GetMapping("/snapshots")
    public ApiResponse<SnapshotListView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String metric,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.list(from, to, metric));
    }

    /**
     * 手动补采指定日期（缺省昨天）的快照。
     *
     * <p>幂等：同一天重复补采只覆盖，不会产生重复行。
     */
    @PostMapping("/snapshots")
    public ApiResponse<SnapshotCaptureView> capture(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.capture(date, actor, "MANUAL"));
    }
}
