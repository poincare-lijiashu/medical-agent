package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.AdmissionPeriodSummary;
import com.medassist.clinical.domain.ClinicalModel.DeptPaidTotal;
import com.medassist.clinical.domain.ClinicalModel.WardAdmissionRow;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.OpsInsightDtos.DeptInsight;
import com.medassist.clinical.web.OpsInsightDtos.InpatientInsight;
import com.medassist.clinical.web.OpsInsightDtos.OpsInsightView;
import com.medassist.clinical.web.OpsInsightDtos.OutpatientInsight;
import com.medassist.clinical.web.OpsInsightDtos.WardInsight;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营洞察（G27）：同比 + 科室/病区下钻。
 *
 * <p>口径：
 * <ul>
 *   <li>门诊按收款日聚合，按科室下钻；同比取去年同月同日区间（本轮不处理闰年边界差异）；</li>
 *   <li>住院按入院/出院时间聚合，按病区下钻，并带病区所属科室；</li>
 *   <li>同比百分比 =（本期 − 去年同期）/ 去年同期 × 100，保留 1 位小数；
 *       去年同期为 0 时返回 null，避免出现"无限增长"的误导数字；</li>
 *   <li>区间默认最近 14 天、最长 92 天，未来日期拒绝。</li>
 * </ul>
 */
@Service
public class OpsInsightService {

    private static final int DEFAULT_DAYS = 14;
    private static final int MAX_DAYS = 92;

    private final ClinicalRepository repo;

    public OpsInsightService(ClinicalRepository repo) {
        this.repo = repo;
    }

    public OpsInsightView insight(LocalDate from, LocalDate to) {
        LocalDate today = BusinessTime.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_DAYS - 1) : from;
        if (end.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查询未来日期的运营洞察");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次查询最多 " + MAX_DAYS + " 天，请缩短区间后重试");
        }
        LocalDate lastYearStart = start.minusYears(1);
        LocalDate lastYearEnd = end.minusYears(1);

        OutpatientInsight outpatient = outpatient(start, end, lastYearStart, lastYearEnd);
        InpatientInsight inpatient = inpatient(start, end, lastYearStart, lastYearEnd);
        return new OpsInsightView(start, end, lastYearStart, lastYearEnd, outpatient, inpatient);
    }

    // ---------------------------------------------------------------- 门诊

    private OutpatientInsight outpatient(LocalDate start, LocalDate end, LocalDate lyStart,
                                         LocalDate lyEnd) {
        List<DeptPaidTotal> current = repo.outpatientPaidByDept(startOf(start), startOf(end.plusDays(1)));
        List<DeptPaidTotal> lastYear = repo.outpatientPaidByDept(startOf(lyStart), startOf(lyEnd.plusDays(1)));
        Map<String, DeptPaidTotal> lastYearByCode = new LinkedHashMap<>();
        for (DeptPaidTotal row : lastYear) {
            lastYearByCode.put(row.deptCode(), row);
        }

        List<DeptInsight> byDept = new ArrayList<>(current.size());
        long totalVisits = 0;
        long lyTotalVisits = 0;
        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal lyTotalPaid = BigDecimal.ZERO;
        for (DeptPaidTotal row : current) {
            DeptPaidTotal ly = lastYearByCode.get(row.deptCode());
            BigDecimal lyAmount = ly == null ? BigDecimal.ZERO : ly.amount();
            byDept.add(new DeptInsight(row.deptCode(), row.deptName(), row.visitCount(),
                    money(row.amount()), money(lyAmount), yoy(row.amount(), lyAmount)));
            totalVisits += row.visitCount();
            totalPaid = totalPaid.add(row.amount());
            if (ly != null) {
                lyTotalVisits += ly.visitCount();
                lyTotalPaid = lyTotalPaid.add(ly.amount());
            }
        }
        return new OutpatientInsight(totalVisits, money(totalPaid), lyTotalVisits,
                money(lyTotalPaid), yoy(totalPaid, lyTotalPaid), byDept);
    }

    // ---------------------------------------------------------------- 住院

    private InpatientInsight inpatient(LocalDate start, LocalDate end, LocalDate lyStart,
                                       LocalDate lyEnd) {
        AdmissionPeriodSummary current = repo.admissionPeriodSummary(startOf(start),
                startOf(end.plusDays(1)));
        AdmissionPeriodSummary lastYear = repo.admissionPeriodSummary(startOf(lyStart),
                startOf(lyEnd.plusDays(1)));
        List<WardAdmissionRow> currentWards = repo.admissionByWard(startOf(start),
                startOf(end.plusDays(1)));
        List<WardAdmissionRow> lastYearWards = repo.admissionByWard(startOf(lyStart),
                startOf(lyEnd.plusDays(1)));
        Map<String, WardAdmissionRow> lyByWard = new LinkedHashMap<>();
        for (WardAdmissionRow row : lastYearWards) {
            lyByWard.put(row.wardCode(), row);
        }

        List<WardInsight> byWard = new ArrayList<>(currentWards.size());
        for (WardAdmissionRow row : currentWards) {
            WardAdmissionRow ly = lyByWard.get(row.wardCode());
            long lyAdmitted = ly == null ? 0 : ly.admitted();
            byWard.add(new WardInsight(row.wardCode(), row.wardName(), row.deptName(),
                    row.admitted(), row.discharged(), lyAdmitted,
                    yoy(row.admitted(), lyAdmitted)));
        }
        return new InpatientInsight(current.admittedCount(), current.dischargedCount(),
                lastYear.admittedCount(), lastYear.dischargedCount(),
                yoy(current.admittedCount(), lastYear.admittedCount()), byWard);
    }

    // ---------------------------------------------------------------- 内部

    private static OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal yoy(BigDecimal current, BigDecimal lastYear) {
        if (lastYear == null || lastYear.signum() == 0) {
            return null;
        }
        return current.subtract(lastYear)
                .multiply(BigDecimal.valueOf(100))
                .divide(lastYear, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal yoy(long current, long lastYear) {
        return yoy(BigDecimal.valueOf(current), BigDecimal.valueOf(lastYear));
    }
}
