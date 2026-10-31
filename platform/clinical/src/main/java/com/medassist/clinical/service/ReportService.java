package com.medassist.clinical.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.clinical.domain.ReportModel.CategoryAmount;
import com.medassist.clinical.domain.ReportModel.DeptRevenue;
import com.medassist.clinical.domain.ReportModel.OperationsReport;
import com.medassist.clinical.domain.ReportModel.VisitCount;
import com.medassist.clinical.repo.ReportRepository;
import com.medassist.clinical.repo.ReportRepository.ChargeStatusSum;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运营指标（N7 第一批）。
 *
 * <p>取值口径见 {@link OperationsReport}。期间上限 366 天：报表按"区间"查，超长区间应由 BI 侧
 * 预聚合，而不是让在线库扫全表（与报盘分批同思路）。
 */
@Service
public class ReportService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_PERIOD_DAYS = 366;

    private final ReportRepository repo;

    public ReportService(ReportRepository repo) {
        this.repo = repo;
    }

    public OperationsReport operations(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请指定统计期间（from/to）");
        }
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期");
        }
        if (from.plusDays(MAX_PERIOD_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次统计期间不得超过 " + MAX_PERIOD_DAYS + " 天，请分段查询");
        }
        OffsetDateTime fromTs = from.atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        OffsetDateTime toTs = to.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime();

        List<VisitCount> counts = repo.visitCounts(from, to);
        int outpatient = 0;
        int emergency = 0;
        int inpatient = 0;
        int cancelled = 0;
        for (VisitCount c : counts) {
            if ("CANCELLED".equals(c.status())) {
                cancelled += c.count();
                continue;
            }
            switch (c.visitType()) {
                case "OUTPATIENT" -> outpatient += c.count();
                case "EMERGENCY" -> emergency += c.count();
                case "INPATIENT" -> inpatient += c.count();
                default -> {
                    // 未知就诊类型不计入任何人次（新增类型时在此显式补分支，避免口径漂移）
                }
            }
        }

        BigDecimal total = money(repo.totalCharge(fromTs, toTs));
        List<CategoryAmount> categories = repo.chargeByCategory(fromTs, toTs);
        BigDecimal drug = categories.stream()
                .filter(c -> "DRUG".equals(c.category()))
                .map(CategoryAmount::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal unpaid = BigDecimal.ZERO;
        BigDecimal refunded = BigDecimal.ZERO;
        for (ChargeStatusSum s : repo.chargeByStatus(fromTs, toTs)) {
            switch (s.status()) {
                case "PAID" -> paid = paid.add(s.total());
                case "UNPAID" -> unpaid = unpaid.add(s.total());
                case "REFUNDED" -> refunded = refunded.add(s.total());
                default -> {
                    // 新增收费状态时在此显式补分支
                }
            }
        }

        long days = to.toEpochDay() - from.toEpochDay() + 1;
        BigDecimal avgDaily = days <= 0 ? BigDecimal.ZERO
                : total.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        BigDecimal ratio = total.signum() == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : drug.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);

        List<DeptRevenue> depts = repo.revenueByDept(fromTs, toTs);
        return new OperationsReport(from, to, outpatient, emergency, inpatient, cancelled,
                money(total), money(paid), money(unpaid), money(refunded), money(drug), ratio,
                avgDaily, categories, depts);
    }

    private static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
