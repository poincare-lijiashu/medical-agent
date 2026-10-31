package com.medassist.clinical.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 运营指标（N7 第一批）。
 *
 * <p>口径说明（全部取自 clinical_svc 自身数据，不跨域）：
 * <ul>
 *   <li>就诊人次按 {@code visit.visit_date}（业务日期）归属，已取消单独计数、不计入人次；</li>
 *   <li>收费金额按 {@code charge_item.created_at} 归属期间（费用发生口径）；</li>
 *   <li>实收/未结/退费按 {@code charge.status} 且以收费单创建时间归属期间；</li>
 *   <li>药占比 = 药品类费用 ÷ 期间总费用 × 100，保留 2 位（总费用为 0 时记 0）。</li>
 * </ul>
 */
public final class ReportModel {

    private ReportModel() {
    }

    /** 分类金额（药品/检查/治疗/材料/挂号/诊查）。 */
    public record CategoryAmount(String category, BigDecimal amount) {
    }

    /** 科室收入。 */
    public record DeptRevenue(String deptId, String deptName, BigDecimal amount) {
    }

    /** 运营报表。 */
    public record OperationsReport(LocalDate from, LocalDate to,
                                   int outpatientVisits, int emergencyVisits, int inpatientVisits,
                                   int cancelledVisits, BigDecimal totalCharge,
                                   BigDecimal paidAmount, BigDecimal unpaidAmount,
                                   BigDecimal refundedAmount, BigDecimal drugAmount,
                                   BigDecimal drugRatio, BigDecimal avgDailyCharge,
                                   List<CategoryAmount> categories,
                                   List<DeptRevenue> deptRevenue) {
    }

    /** 就诊人次原始计数（按类型与状态分组）。 */
    public record VisitCount(String visitType, String status, int count) {
    }
}
