package com.medassist.pharmacy.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 药事报表契约（G15）：发药日趋势，缺日由服务层补 0。
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
}
