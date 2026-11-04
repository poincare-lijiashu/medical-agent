package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.CampusFilters;
import com.medassist.common.time.BusinessTime;
import com.medassist.material.domain.MaterialModel.DailyIssueTotal;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialReportDtos.IssueTrendPoint;
import com.medassist.material.web.MaterialReportDtos.IssueTrendView;
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
 * 物资报表（G15）：出库日趋势（按出库时刻归日，缺日补 0）。
 *
 * <p>口径：单数取出库单、件数取明细数量合计、金额取出库总额；默认最近 14 天，
 * 单次最多 92 天，未来日期拒绝。
 */
@Service
public class MaterialReportService {

    private final MaterialRepository repo;

    public MaterialReportService(MaterialRepository repo) {
        this.repo = repo;
    }

    public IssueTrendView issueTrend(LocalDate from, LocalDate to) {
        return issueTrend(from, to, java.util.Set.of());
    }

    /** 出库日趋势（G50）：按院区收敛，空集合表示跨院区角色。 */
    public IssueTrendView issueTrend(LocalDate from, LocalDate to,
                                     java.util.Set<java.util.UUID> campusIds) {
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
        OffsetDateTime fromTs = start.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toTs = end.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        Map<LocalDate, DailyIssueTotal> byDay = repo.dailyIssueTotals(fromTs, toTs, zone.getId(),
                        CampusFilters.csv(campusIds))
                .stream()
                .collect(Collectors.toMap(DailyIssueTotal::day, Function.identity(),
                        (a, b) -> a));

        List<IssueTrendPoint> points = new ArrayList<>((int) days);
        long totalIssueCount = 0;
        long totalItemQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            DailyIssueTotal row = byDay.get(day);
            int issues = row == null ? 0 : row.issueCount();
            int quantity = row == null ? 0 : row.itemQuantity();
            BigDecimal amount = row == null ? BigDecimal.ZERO : row.totalAmount();
            points.add(new IssueTrendPoint(day, issues, quantity, amount));
            totalIssueCount += issues;
            totalItemQuantity += quantity;
            totalAmount = totalAmount.add(amount);
        }
        return new IssueTrendView(start, end, points, totalIssueCount, totalItemQuantity,
                totalAmount);
    }
}
