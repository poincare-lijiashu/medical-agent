package com.medassist.pharmacy.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 药事报表契约（G15 趋势 / G18 日快照）。
 */
public final class PharmacyReportDtos {

    private PharmacyReportDtos() {
    }

    public record DispenseTrendPoint(LocalDate date, int dispenseCount, BigDecimal amount) {
    }

    public record DispenseTrendView(LocalDate from, LocalDate to,
                                    List<DispenseTrendPoint> points,
                                    long totalCount, BigDecimal totalAmount) {
    }

    public record SnapshotView(LocalDate date, String metric, BigDecimal value, String detail,
                               String source, Instant updatedAt) {
    }

    public record SnapshotListView(LocalDate from, LocalDate to, List<SnapshotView> snapshots) {
    }

    public record SnapshotCaptureView(LocalDate date, int metrics, String source) {
    }
}
