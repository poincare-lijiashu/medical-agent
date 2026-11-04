package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.AdmissionPeriodSummary;
import com.medassist.clinical.domain.ClinicalModel.DailyAdmissionTotal;
import com.medassist.clinical.domain.ClinicalModel.DailyInHospitalTotal;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.InpatientTrendPoint;
import com.medassist.clinical.web.ClinicalDtos.InpatientTrendView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 住院周转报表（G16）。
 *
 * <p>口径：
 * <ul>
 *   <li>入院数按 {@code admitted_at} 归日、出院数按 {@code discharged_at} 归日；</li>
 *   <li>在院人数取每个业务日结束时刻的快照（入院早于次日零点且未出院或出院不早于次日零点）；</li>
 *   <li>平均住院日只统计当日出院者，单位天；区间平均按出院人数加权。</li>
 * </ul>
 *
 * <p>区间规则与门诊趋势一致：默认最近 14 天（含今天），单次最多 92 天，
 * 拒绝未来日期与倒置区间，缺日补 0。
 */
@Service
public class InpatientReportService {

    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_DAYS = 92;

    private final ClinicalRepository repo;

    public InpatientReportService(ClinicalRepository repo) {
        this.repo = repo;
    }

    public InpatientTrendView trend(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1) : from;
        if (end.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查询未来日期的趋势");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次趋势查询最多 92 天，请缩短区间后重试");
        }

        var zone = BusinessTime.zone();
        OffsetDateTime fromTs = start.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toTs = end.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        Map<LocalDate, DailyAdmissionTotal> byDay = repo
                .dailyAdmissionTrend(fromTs, toTs, zone.getId()).stream()
                .collect(Collectors.toMap(DailyAdmissionTotal::day, Function.identity(),
                        (a, b) -> a));
        Map<LocalDate, DailyInHospitalTotal> inHospitalByDay = repo
                .dailyInHospitalTrend(fromTs, toTs, zone.getId()).stream()
                .collect(Collectors.toMap(DailyInHospitalTotal::day, Function.identity(),
                        (a, b) -> a));

        List<InpatientTrendPoint> points = new ArrayList<>((int) days);
        int totalAdmitted = 0;
        int totalDischarged = 0;
        BigDecimal weightedStayDays = BigDecimal.ZERO;
        int currentInHospital = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            DailyAdmissionTotal row = byDay.get(day);
            DailyInHospitalTotal inHospital = inHospitalByDay.get(day);
            int admitted = row == null ? 0 : row.admittedCount();
            int discharged = row == null ? 0 : row.dischargedCount();
            BigDecimal avgStay = row == null ? BigDecimal.ZERO : row.avgStayDays();
            int inHospitalCount = inHospital == null ? 0 : inHospital.inHospitalCount();
            points.add(new InpatientTrendPoint(day, admitted, discharged, inHospitalCount,
                    scale(avgStay)));
            totalAdmitted += admitted;
            totalDischarged += discharged;
            weightedStayDays = weightedStayDays.add(avgStay.multiply(BigDecimal.valueOf(discharged)));
            currentInHospital = inHospitalCount;
        }

        BigDecimal avgStayDays = totalDischarged == 0 ? BigDecimal.ZERO
                : weightedStayDays.divide(BigDecimal.valueOf(totalDischarged), 2, RoundingMode.HALF_UP);
        int usableBeds = repo.countUsableBeds();
        BigDecimal occupancy = usableBeds == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(currentInHospital)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(usableBeds), 1, RoundingMode.HALF_UP);

        // 环比：紧邻当前区间之前、等长的上一周期
        LocalDate prevEnd = start.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(days - 1);
        AdmissionPeriodSummary prev = repo.admissionPeriodSummary(
                prevStart.atStartOfDay(zone).toOffsetDateTime(),
                start.atStartOfDay(zone).toOffsetDateTime());

        return new InpatientTrendView(start, end, points, totalAdmitted, totalDischarged,
                currentInHospital, usableBeds, occupancy, avgStayDays,
                prev.admittedCount(), prev.dischargedCount());
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
