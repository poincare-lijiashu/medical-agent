package com.medassist.clinical.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 运营洞察：同比与科室/病区下钻（G27）。 */
public final class OpsInsightDtos {

    private OpsInsightDtos() {
    }

    public record OpsInsightView(LocalDate from, LocalDate to, LocalDate lastYearFrom,
                                 LocalDate lastYearTo, OutpatientInsight outpatient,
                                 InpatientInsight inpatient) {
    }

    /** 门诊：本期/去年同期合计 + 科室下钻；yoyPercent 为 null 表示去年同期无基数。 */
    public record OutpatientInsight(long visits, BigDecimal paidAmount, long lastYearVisits,
                                    BigDecimal lastYearPaidAmount, BigDecimal paidYoyPercent,
                                    List<DeptInsight> byDept) {
    }

    public record DeptInsight(String deptCode, String deptName, long visits,
                              BigDecimal paidAmount, BigDecimal lastYearPaidAmount,
                              BigDecimal yoyPercent) {
    }

    /** 住院：本期/去年同期入出量 + 病区下钻。 */
    public record InpatientInsight(long admitted, long discharged, long lastYearAdmitted,
                                   long lastYearDischarged, BigDecimal admittedYoyPercent,
                                   List<WardInsight> byWard) {
    }

    public record WardInsight(String wardCode, String wardName, String deptName, long admitted,
                              long discharged, long lastYearAdmitted, BigDecimal yoyPercent) {
    }
}
