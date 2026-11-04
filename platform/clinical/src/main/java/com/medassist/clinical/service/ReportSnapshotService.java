package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.DailyAdmissionTotal;
import com.medassist.clinical.domain.ClinicalModel.DailyInHospitalTotal;
import com.medassist.clinical.domain.ClinicalModel.OutpatientPaidSummary;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.SnapshotCaptureView;
import com.medassist.clinical.web.ClinicalDtos.SnapshotListView;
import com.medassist.clinical.web.ClinicalDtos.SnapshotView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运营日快照（G17）。
 *
 * <p>把门诊实收与住院周转的当日聚合固化到 {@code report_snapshot}，
 * 供驾驶舱历史回看、趋势对比与后续订阅推送使用；明细表仍是对账的唯一事实源。
 *
 * <p>幂等：同一 (日期, 指标) 只有一行，定时任务重复执行或手动补采都只是覆盖。
 * 口径与 G13/G16 趋势接口完全一致（同一批仓储方法），避免"快照数与页面数"两套口径。
 */
@Service
public class ReportSnapshotService {

    public static final String OUTPATIENT_PAID_AMOUNT = "OUTPATIENT_PAID_AMOUNT";
    public static final String OUTPATIENT_VISIT_COUNT = "OUTPATIENT_VISIT_COUNT";
    public static final String OUTPATIENT_PAID_CHARGE_COUNT = "OUTPATIENT_PAID_CHARGE_COUNT";
    public static final String OUTPATIENT_REFUND_AMOUNT = "OUTPATIENT_REFUND_AMOUNT";
    public static final String INPATIENT_ADMITTED_COUNT = "INPATIENT_ADMITTED_COUNT";
    public static final String INPATIENT_DISCHARGED_COUNT = "INPATIENT_DISCHARGED_COUNT";
    public static final String INPATIENT_IN_HOSPITAL_COUNT = "INPATIENT_IN_HOSPITAL_COUNT";
    public static final String INPATIENT_AVG_STAY_DAYS = "INPATIENT_AVG_STAY_DAYS";
    public static final String BED_USABLE_COUNT = "BED_USABLE_COUNT";
    public static final String BED_OCCUPANCY_PERCENT = "BED_OCCUPANCY_PERCENT";

    private static final int DEFAULT_QUERY_DAYS = 7;
    private static final int MAX_QUERY_DAYS = 92;

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public ReportSnapshotService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 采集指定业务日的快照；日期为空默认昨天（定时任务的自然口径）。 */
    public SnapshotCaptureView capture(LocalDate day, String actor, String source) {
        LocalDate today = BusinessTime.today();
        LocalDate target = day == null ? today.minusDays(1) : day;
        if (target.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能采集未来日期的快照");
        }
        String mode = "SCHEDULED".equals(source) ? "SCHEDULED" : "MANUAL";
        var zone = BusinessTime.zone();
        OffsetDateTime fromTs = target.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toTs = target.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        OutpatientPaidSummary outpatient = repo.outpatientPaidSummary(fromTs, toTs);
        DailyAdmissionTotal admitted = repo.dailyAdmissionTrend(fromTs, toTs, zone.getId())
                .stream().findFirst().orElse(null);
        DailyInHospitalTotal inHospital = repo.dailyInHospitalTrend(fromTs, toTs, zone.getId())
                .stream().findFirst().orElse(null);
        int usableBeds = repo.countUsableBeds();
        int inHospitalCount = inHospital == null ? 0 : inHospital.inHospitalCount();
        BigDecimal occupancy = usableBeds == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(inHospitalCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(usableBeds), 1, RoundingMode.HALF_UP);

        List<MetricValue> metrics = new ArrayList<>(10);
        metrics.add(new MetricValue(OUTPATIENT_PAID_AMOUNT, outpatient.totalPaid(), null));
        metrics.add(new MetricValue(OUTPATIENT_VISIT_COUNT,
                BigDecimal.valueOf(outpatient.visitCount()), null));
        metrics.add(new MetricValue(OUTPATIENT_PAID_CHARGE_COUNT,
                BigDecimal.valueOf(outpatient.paidChargeCount()), null));
        metrics.add(new MetricValue(OUTPATIENT_REFUND_AMOUNT, outpatient.refundedAmount(), null));
        metrics.add(new MetricValue(INPATIENT_ADMITTED_COUNT,
                BigDecimal.valueOf(admitted == null ? 0 : admitted.admittedCount()), null));
        metrics.add(new MetricValue(INPATIENT_DISCHARGED_COUNT,
                BigDecimal.valueOf(admitted == null ? 0 : admitted.dischargedCount()), null));
        metrics.add(new MetricValue(INPATIENT_IN_HOSPITAL_COUNT,
                BigDecimal.valueOf(inHospitalCount), null));
        metrics.add(new MetricValue(INPATIENT_AVG_STAY_DAYS,
                admitted == null ? BigDecimal.ZERO
                        : admitted.avgStayDays().setScale(2, RoundingMode.HALF_UP), null));
        metrics.add(new MetricValue(BED_USABLE_COUNT, BigDecimal.valueOf(usableBeds), null));
        metrics.add(new MetricValue(BED_OCCUPANCY_PERCENT, occupancy,
                json(Map.of("usableBeds", usableBeds, "inHospital", inHospitalCount))));

        for (MetricValue m : metrics) {
            repo.upsertReportSnapshot(UUID.randomUUID(), target, m.metric(), m.value(),
                    m.detail(), mode);
        }
        audit(actor, "REPORT_SNAPSHOT_CAPTURE", target.toString(), Map.of(
                "metrics", metrics.size(), "source", mode));
        return new SnapshotCaptureView(target, metrics.size(), mode);
    }

    /** 查询快照；默认最近 7 天，最多 92 天。 */
    public SnapshotListView list(LocalDate from, LocalDate to, String metric) {
        LocalDate today = BusinessTime.today();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(DEFAULT_QUERY_DAYS - 1) : from;
        if (end.isAfter(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查询未来日期的快照");
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_QUERY_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次快照查询最多 92 天，请缩短区间后重试");
        }
        List<SnapshotView> rows = repo.listReportSnapshots(start, end, metric).stream()
                .map(s -> new SnapshotView(s.snapshotDate(), s.metric(), s.value(), s.detail(),
                        s.source(), s.updatedAt()))
                .toList();
        return new SnapshotListView(start, end, rows);
    }

    private String json(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(detail));
        } catch (JacksonException e) {
            return null;
        }
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "REPORT_SNAPSHOT", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "REPORT_SNAPSHOT", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }

    private record MetricValue(String metric, BigDecimal value, String detail) {
    }
}
