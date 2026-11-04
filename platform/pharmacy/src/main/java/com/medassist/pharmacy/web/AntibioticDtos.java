package com.medassist.pharmacy.web;

import com.medassist.pharmacy.domain.PharmacyModel.AntibioticApproval;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 抗菌药物专项契约（G33）。
 *
 * <p>分级口径来自药品字典 {@code antibiotic_level}：1=非限制、2=限制、3=特殊使用级。
 * 特殊使用级必须完成专项审批后才能由药师审核通过或发药，审批人不得是开方医师。
 */
public final class AntibioticDtos {

    private AntibioticDtos() {
    }

    /** 审批：approve=false 为不批准，必须写理由。 */
    public record ApprovalReq(@NotNull Boolean approve, @Size(max = 200) String note) {
    }

    /** 处方专项审批状态：requiredLevel=0 表示该处方不含抗菌药物。 */
    public record ApprovalView(int requiredLevel, AntibioticApproval approval) {
    }

    public record LevelStat(int level, long rxCount, long quantity) {
    }

    public record DoctorStat(String prescriber, long rxCount, long quantity) {
    }

    public record DrugStat(String drugCode, String drugName, int level, long rxCount,
                           long quantity) {
    }

    public record OverLimitRow(UUID prescriptionId, String prescriptionNo, String patientNo,
                               String prescriber, String drugName, Integer maxDays,
                               Instant createdAt) {
    }

    public record PendingRow(UUID prescriptionId, String prescriptionNo, String patientNo,
                             String prescriber, String status, String drugSummary,
                             Instant createdAt) {
    }

    /**
     * 抗菌药物统计（G33）：按开方时间过滤，默认最近 30 天。
     *
     * @param prophylaxisDays 预防用药疗程提醒阈值（天）
     */
    public record StatsView(LocalDate from, LocalDate to, int prophylaxisDays, long rxCount,
                            long specialRxCount, int pendingCount, List<LevelStat> levels,
                            List<DoctorStat> doctors, List<DrugStat> drugs,
                            List<OverLimitRow> overLimit) {
    }
}
