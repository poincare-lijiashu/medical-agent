package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.DailyDispenseTotal;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PharmacyReportDtos.DispenseTrendPoint;
import com.medassist.pharmacy.web.PharmacyReportDtos.DispenseTrendView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 药事报表（G15）：发药日趋势（按发药时刻归日，缺日补 0）。
 *
 * <p>口径：只统计已发药（含部分退回）的发药单；默认最近 14 天，单次最多 92 天，
 * 未来日期拒绝——报表不能出现"还没发生"的业务量。
 */
@Service
public class PharmacyReportService {

    private final PharmacyRepository repo;

    public PharmacyReportService(PharmacyRepository repo) {
        this.repo = repo;
    }

    public DispenseTrendView dispenseTrend(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(13) : from;
        if (end.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查询未来日期的趋势");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > 92) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次趋势查询最多 92 天，请缩短区间后重试");
        }
        var zone = BusinessTime.zone();
        Map<LocalDate, DailyDispenseTotal> byDay = repo.dailyDispenseTotals(
                        start.atStartOfDay(zone).toOffsetDateTime(),
                        end.plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                        zone.getId())
                .stream()
                .collect(Collectors.toMap(DailyDispenseTotal::day, Function.identity(),
                        (a, b) -> a));

        List<DispenseTrendPoint> points = new ArrayList<>((int) days);
        long totalCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            DailyDispenseTotal row = byDay.get(day);
            int count = row == null ? 0 : row.dispenseCount();
            BigDecimal amount = row == null ? BigDecimal.ZERO : row.amount();
            points.add(new DispenseTrendPoint(day, count, amount));
            totalCount += count;
            totalAmount = totalAmount.add(amount);
        }
        return new DispenseTrendView(start, end, points, totalCount, totalAmount);
    }
}
