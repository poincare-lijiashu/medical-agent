package com.medassist.material.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 物资报表契约（G15）：出库日趋势，缺日由服务层补 0。
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
}
