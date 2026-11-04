package com.medassist.material.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 物资报表契约（G15 趋势 / G18 日快照）。
 */
public final class MaterialReportDtos {

    private MaterialReportDtos() {
    }

    public record IssueTrendPoint(LocalDate date, int issueCount, int itemQuantity,
                                  BigDecimal totalAmount) {
    }

    public record IssueTrendView(LocalDate from, LocalDate to, List<IssueTrendPoint> points,
                                 long totalIssueCount, long totalItemQuantity,
                                 BigDecimal totalAmount) {
    }

    public record SnapshotView(LocalDate date, String metric, BigDecimal value, String detail,
                               String source, Instant updatedAt) {
    }

    public record SnapshotListView(LocalDate from, LocalDate to, List<SnapshotView> snapshots) {
    }

    public record SnapshotCaptureView(LocalDate date, int metrics, String source) {
    }
}
