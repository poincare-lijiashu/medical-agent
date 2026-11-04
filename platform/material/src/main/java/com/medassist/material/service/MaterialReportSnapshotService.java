package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.material.domain.MaterialModel.DailyIssueTotal;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialReportDtos.SnapshotCaptureView;
import com.medassist.material.web.MaterialReportDtos.SnapshotListView;
import com.medassist.material.web.MaterialReportDtos.SnapshotView;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 物资运营日快照（G18）。
 *
 * <p>固化出库单数、出库件数与出库金额；与 G15 趋势共用同一仓储方法。
 * 唯一键 (日期, 指标) 保证定时重跑与手动补采幂等。
 */
@Service
public class MaterialReportSnapshotService {

    public static final String ISSUE_COUNT = "ISSUE_COUNT";
    public static final String ISSUE_ITEM_QUANTITY = "ISSUE_ITEM_QUANTITY";
    public static final String ISSUE_AMOUNT = "ISSUE_AMOUNT";

    private static final int DEFAULT_QUERY_DAYS = 7;
    private static final int MAX_QUERY_DAYS = 92;

    private final MaterialRepository repo;
    private final ObjectMapper objectMapper;

    public MaterialReportSnapshotService(MaterialRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public SnapshotCaptureView capture(LocalDate day, String actor, String source) {
        LocalDate today = BusinessTime.today();
        LocalDate target = day == null ? today.minusDays(1) : day;
        if (target.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能采集未来日期的快照");
        }
        String mode = "SCHEDULED".equals(source) ? "SCHEDULED" : "MANUAL";
        var zone = BusinessTime.zone();
        OffsetDateTime fromTs = target.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toTs = target.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        DailyIssueTotal row = repo.dailyIssueTotals(fromTs, toTs, zone.getId())
                .stream().findFirst().orElse(null);
        int count = row == null ? 0 : row.issueCount();
        int quantity = row == null ? 0 : row.itemQuantity();
        BigDecimal amount = row == null ? BigDecimal.ZERO : row.totalAmount();

        repo.upsertReportSnapshot(UUID.randomUUID(), target, ISSUE_COUNT,
                BigDecimal.valueOf(count), null, mode);
        repo.upsertReportSnapshot(UUID.randomUUID(), target, ISSUE_ITEM_QUANTITY,
                BigDecimal.valueOf(quantity), null, mode);
        repo.upsertReportSnapshot(UUID.randomUUID(), target, ISSUE_AMOUNT, amount, null, mode);
        audit(actor, target, mode, 3);
        return new SnapshotCaptureView(target, 3, mode);
    }

    public SnapshotListView list(LocalDate from, LocalDate to, String metric) {
        LocalDate today = BusinessTime.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_QUERY_DAYS - 1) : from;
        if (end.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查询未来日期的快照");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_QUERY_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次快照查询最多 92 天，请缩短区间后重试");
        }
        List<SnapshotView> rows = repo.listReportSnapshots(start, end, metric).stream()
                .map(s -> new SnapshotView(s.snapshotDate(), s.metric(), s.value(), s.detail(),
                        s.source(), s.updatedAt()))
                .toList();
        return new SnapshotListView(start, end, rows);
    }

    private void audit(String actor, LocalDate day, String mode, int metrics) {
        try {
            repo.logChange(actor, "REPORT_SNAPSHOT_CAPTURE", "REPORT_SNAPSHOT",
                    day.toString(), objectMapper.writeValueAsString(
                            new LinkedHashMap<>(Map.of("metrics", metrics, "source", mode))));
        } catch (JacksonException e) {
            repo.logChange(actor, "REPORT_SNAPSHOT_CAPTURE", "REPORT_SNAPSHOT",
                    day.toString(), "{\"_detail_serialize_failed\":true}");
        }
    }
}
