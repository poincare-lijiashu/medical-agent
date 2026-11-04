package com.medassist.clinical.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 临床核心领域模型（不可变记录）。
 *
 * <p>约定：
 * <ul>
 *   <li>状态一律用字符串常量 + {@code CHECK} 约束双保险，枚举只用于服务层判断；</li>
 *   <li>{@code version} 为乐观锁版本号，所有更新必须带 CAS；</li>
 *   <li>金额统一 {@link BigDecimal}（禁止 double，避免 0.1+0.2 类误差进收费单）。</li>
 * </ul>
 */
public final class ClinicalModel {

    private ClinicalModel() {
    }

    /** 科室。 */
    public record Dept(UUID id, String deptCode, String name, String type, boolean enabled) {
    }

    /** 收费项目（价格唯一真源）。 */
    public record ChargeItemDef(UUID id, String itemCode, String name, String category,
                                String unit, BigDecimal price, boolean enabled) {
    }

    /** 号源。 */
    public record Slot(UUID id, UUID deptId, String doctorId, LocalDate workDate, String period,
                       int total, int booked, long version) {
        public boolean full() {
            return booked >= total;
        }
    }

    /** 就诊。 */
    public record Visit(UUID id, String visitNo, UUID patientId, String patientNo,
                        UUID deptId, String doctorId, UUID slotId, String visitType,
                        String status, LocalDate visitDate, long version) {

        public static final String REGISTERED = "REGISTERED";
        public static final String IN_TREATMENT = "IN_TREATMENT";
        public static final String FINISHED = "FINISHED";
        public static final String CANCELLED = "CANCELLED";

        public boolean active() {
            return REGISTERED.equals(status) || IN_TREATMENT.equals(status);
        }
    }

    /** 诊断字典（ICD-10）。 */
    public record DiagnosisDef(UUID id, String code, String name, String category,
                               boolean enabled, long version) {
    }

    /**
     * 就诊诊断。
     *
     * <p>{@code name} 是下达时的名称快照（字典改名不影响历史诊断）；
     * 一个就诊最多一条主诊断，由部分唯一索引在数据库层兜底。
     */
    public record VisitDiagnosis(UUID id, UUID visitId, String code, String name,
                                 boolean primaryFlag, int sortNo, String diagnosedBy,
                                 Instant diagnosedAt, long version) {
    }

    /**
     * 医嘱。
     *
     * <p>数量口径（B2，必须与 {@link OrderFrequency} / 按日计费保持一致）：
     * <ul>
     *   <li>临时医嘱（TEMP）：{@code quantity} = 本次总量，{@code amount} = 本次金额；</li>
     *   <li>长期医嘱（LONG）：{@code quantity} = <b>每日总量</b>，{@code amount} = 每日金额，
     *       {@code frequencyTimes} 为每日执行次数快照（护理按次数执行，计费仍按天一次）。</li>
     * </ul>
     */
    public record VisitOrder(UUID id, String orderNo, UUID visitId, String orderType,
                             String itemCode, String itemName, String dose, String frequency,
                             int frequencyTimes,
                             int quantity, String unit, BigDecimal unitPrice, BigDecimal amount,
                             String status, String orderedBy, long version,
                             String orderMode, LocalDate startDate, LocalDate stopDate,
                             String transferStatus, String externalType, UUID externalId,
                             String externalNo, String transferError) {

        public static final String PLACED = "PLACED";
        public static final String CHARGED = "CHARGED";
        public static final String CANCELLED = "CANCELLED";

        public static final String TEMP = "TEMP";
        public static final String LONG = "LONG";

        /** 无需转送（非药品医嘱）。 */
        public static final String TRANSFER_NONE = "NONE";
        /** 已登记转送，等待下游确认（开嘱事务内即置为该状态）。 */
        public static final String TRANSFER_PENDING = "PENDING";
        /** 下游已生成单据（药房处方）。 */
        public static final String TRANSFERRED = "TRANSFERRED";
        /** 转送失败，可重试（医嘱本身仍然有效）。 */
        public static final String TRANSFER_FAILED = "FAILED";

        public boolean longTerm() {
            return LONG.equals(orderMode);
        }

        public boolean drug() {
            return "DRUG".equals(orderType);
        }

        public boolean transferred() {
            return TRANSFERRED.equals(transferStatus);
        }

        /** 返回仅转送状态不同的副本（开嘱事务内把药品医嘱标记为 PENDING 后回给前端）。 */
        public VisitOrder withTransferStatus(String nextTransferStatus) {
            return new VisitOrder(id, orderNo, visitId, orderType, itemCode, itemName, dose, frequency,
                    frequencyTimes, quantity, unit, unitPrice, amount, status, orderedBy, version,
                    orderMode, startDate, stopDate, nextTransferStatus, externalType, externalId,
                    externalNo, transferError);
        }

        /** 某天是否应计费（长嘱在 start~stop 之间有效；停嘱当天仍计费）。 */
        public boolean billableOn(LocalDate date) {
            if (!longTerm() || !PLACED.equals(status)) {
                return false;
            }
            if (startDate != null && date.isBefore(startDate)) {
                return false;
            }
            return stopDate == null || !date.isAfter(stopDate);
        }
    }

    /**
     * 医嘱转送补偿任务（停嘱/撤销后下游作废失败时登记）。
     *
     * <p>医疗决定必须成功、下游作废尽力而为：主事务只落医疗状态，作废失败进本表，
     * 由 worker 重试（下游按 orderId 幂等）。
     */
    public record TransferTask(UUID id, UUID orderId, UUID visitId, String action, String channel,
                               String reason, String actor, String status, int attempts,
                               String lastError, Instant createdAt) {

        public static final String CANCEL = "CANCEL";

        public static final String PENDING = "PENDING";
        public static final String DONE = "DONE";
        public static final String FAILED = "FAILED";
    }

    /** 手术操作字典（ICD-9-CM-3）：停用后不可新选，历史记录保留名称快照。 */
    public record OperationCatalog(String code, String name, String category, boolean enabled,
                                   long version) {
    }

    /**
     * 手术记录（手术麻醉域第一批）。
     *
     * <p>状态机：{@code PLANNED → IN_PROGRESS → COMPLETED}，任一非终态可 {@code CANCELLED}。
     * 病案首页手术操作与 DRG 分组只取 {@code COMPLETED} 记录。
     */
    public record SurgeryCase(UUID id, String caseNo, UUID visitId, UUID admissionId,
                              UUID patientId, String operationCode, String operationName,
                              Instant plannedAt, Instant startedAt, Instant endedAt,
                              String surgeon, String assistant, String anesthesiaType,
                              String asaGrade, String incisionLevel, String status,
                              String cancelReason, String createdBy, long version,
                              Instant createdAt) {

        public static final String PLANNED = "PLANNED";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String COMPLETED = "COMPLETED";
        public static final String CANCELLED = "CANCELLED";

        public boolean completed() {
            return COMPLETED.equals(status);
        }
    }

    /** 手术间（排台资源）。 */
    public record OperatingRoom(UUID id, String code, String name, UUID deptId, String location,
                                String status, long version, Instant createdAt) {

        public static final String ACTIVE = "ACTIVE";
        public static final String MAINTENANCE = "MAINTENANCE";
        public static final String INACTIVE = "INACTIVE";

        public boolean bookable() {
            return ACTIVE.equals(status);
        }
    }

    /**
     * 排台：一台手术占用一个手术间的一段时间。
     *
     * <p>{@code sequence} 是当天该手术间的接台顺序，用于手术间看板排序。
     */
    public record SurgerySchedule(UUID id, UUID surgeryCaseId, UUID roomId, Instant plannedStart,
                                  Instant plannedEnd, int sequence, String status,
                                  String cancelReason, String createdBy, long version,
                                  Instant createdAt) {

        public static final String SCHEDULED = "SCHEDULED";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String COMPLETED = "COMPLETED";
        public static final String CANCELLED = "CANCELLED";
    }

    /** 麻醉记录单（一台手术一份，签署后冻结）。 */
    public record AnesthesiaRecord(UUID id, UUID surgeryCaseId, String method, String asaGrade,
                                   String anesthesiologist, String airway, Instant startedAt,
                                   Instant endedAt, String notes, String status, String signedBy,
                                   Instant signedAt, String createdBy, long version,
                                   Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String SIGNED = "SIGNED";
    }

    /** 术中生命体征（按时间点追加）。 */
    public record AnesthesiaVital(UUID id, UUID recordId, Instant recordedAt, Integer heartRate,
                                  Integer sbp, Integer dbp, Integer spo2, BigDecimal etco2,
                                  BigDecimal temperature, String note, Instant createdAt) {
    }

    /** 术中护理记录（签署后冻结）。 */
    public record IntraopNursing(UUID id, UUID surgeryCaseId, String scrubNurse,
                                 String circulatingNurse, String patientPosition, String skinPrep,
                                 Instant tourniquetStart, Instant tourniquetEnd, int specimenCount,
                                 String notes, String status, String signedBy, Instant signedAt,
                                 String createdBy, long version, Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String SIGNED = "SIGNED";
    }

    /** 术前讨论记录（一台手术一份，签署后冻结）。 */
    public record PreopDiscussion(UUID id, UUID surgeryCaseId, Instant discussionAt,
                                  String moderator, String participants, String diagnosisSummary,
                                  String procedurePlan, String risks, String anesthesiaPlan,
                                  String bloodPreparation, String status, String signedBy,
                                  Instant signedAt, String createdBy, long version,
                                  Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String SIGNED = "SIGNED";
    }

    /**
     * 知情同意书（插入即已签署事实；写错只能作废后重签）。
     *
     * <p>{@code revokedAt} 非空表示该份已作废，不再计入"术前同意书齐全"。
     */
    public record SurgeryConsent(UUID id, UUID surgeryCaseId, String consentType,
                                 String signerName, String signerRelation, Instant signedAt,
                                 String witness, String notes, Instant revokedAt,
                                 String revokeReason, String createdBy, long version,
                                 Instant createdAt) {

        public static final String SURGERY = "SURGERY";
        public static final String ANESTHESIA = "ANESTHESIA";
        public static final String BLOOD_TRANSFUSION = "BLOOD_TRANSFUSION";
        public static final String SPECIAL = "SPECIAL";

        public boolean active() {
            return revokedAt == null;
        }
    }

    /**
     * 手术分级（一台手术一条）。
     *
     * <p>分级与审批状态在数据库层强绑定：1~2 级只能 {@code NOT_REQUIRED}，
     * 3~4 级只能在 {@code PENDING / APPROVED / REJECTED} 之间流转。
     * 三、四级手术未审批通过不得开台（由 {@code SurgeryService} 强制校验）。
     */
    public record SurgeryLevel(UUID id, UUID surgeryCaseId, int level, String approvalStatus,
                               String appliedBy, Instant appliedAt, String reviewedBy,
                               Instant reviewedAt, String rejectReason, String createdBy,
                               long version, Instant createdAt) {

        public static final String NOT_REQUIRED = "NOT_REQUIRED";
        public static final String PENDING = "PENDING";
        public static final String APPROVED = "APPROVED";
        public static final String REJECTED = "REJECTED";

        public static final int MIN_LEVEL = 1;
        public static final int MAX_LEVEL = 4;
        /** 三级及以上手术需要审批。 */
        public static final int APPROVAL_THRESHOLD = 3;

        /** 该分级下审批状态应该是"免审批"还是"待审批"。 */
        public static String initialStatus(int level) {
            return level >= APPROVAL_THRESHOLD ? PENDING : NOT_REQUIRED;
        }

        /** 是否满足开台条件（免审批，或三级以上且已审批通过）。 */
        public boolean clearedForStart() {
            return level < APPROVAL_THRESHOLD ? NOT_REQUIRED.equals(approvalStatus)
                    : APPROVED.equals(approvalStatus);
        }
    }

    /**
     * 术前访视与麻醉评估（一台手术一份，签署后冻结）。
     *
     * <p>与术前讨论的分工：讨论关注"手术怎么做"，访视关注"患者能不能耐受麻醉"。
     * 结论 {@code UNFIT} 时禁止开台（由 SurgeryService 校验）。
     */
    public record PreopVisit(UUID id, UUID surgeryCaseId, Instant visitedAt, String visitedBy,
                             String anesthesiaPlan, String asaGrade, String mallampati,
                             String airwayNote, String dentition, String anesthesiaHistory,
                             String allergyHistory, Integer fastingSolidHours,
                             Integer fastingClearHours, String conclusion, String riskNote,
                             String status, String signedBy, Instant signedAt, String createdBy,
                             long version, Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String SIGNED = "SIGNED";

        public static final String FIT = "FIT";
        public static final String FIT_WITH_CAUTION = "FIT_WITH_CAUTION";
        public static final String UNFIT = "UNFIT";

        public boolean signed() {
            return SIGNED.equals(status);
        }

        public boolean unfit() {
            return UNFIT.equals(conclusion);
        }
    }

    /**
     * 手术间利用率的"每日一行"聚合（仓储层投影，不落表）。
     *
     * @param occupiedMinutes 落在开放时段内的排台占用分钟（已取消排台不计）
     * @param turnoverMinutes 接台间隙合计：相邻两台（未取消）之间的空闲分钟
     */
    public record RoomDayUsage(Instant dayStart, int scheduledCount, int completedCount,
                               int cancelledCount, long occupiedMinutes, long turnoverMinutes,
                               int turnoverCount) {
    }

    /** 批量利用率的一行：房间 + 该房间某天的聚合（一次 SQL 取全部房间，避免逐房间查询）。 */
    public record RoomDayUsageRow(UUID roomId, RoomDayUsage usage) {
    }

    /**
     * 排台看板的一行：排台 + 手术病例 + 就诊 + 分级审批，一次 JOIN 取全。
     *
     * <p>替代「只返回 surgeryCaseId、前端再逐条补全」的旧契约：
     * 看板要回答「哪间空着、下一台是什么、谁做、几级」，这些字段必须一次带回来，
     * 否则前端只能显示「第 N 台 + 病例号」，护士还得点进去才知道是什么手术。
     *
     * <p>字段来源：{@code surgery_schedule} / {@code surgery_case} / {@code visit} /
     * {@code operating_room} / {@code surgery_level}（LEFT JOIN，未分级时为 null）。
     */
    public record SurgeryBoardRow(
            UUID scheduleId,
            UUID roomId,
            String roomCode,
            String roomName,
            String roomLocation,
            UUID surgeryCaseId,
            String caseNo,
            UUID visitId,
            String visitNo,
            String patientNo,
            String operationCode,
            String operationName,
            String surgeon,
            String assistant,
            String anesthesiaType,
            String asaGrade,
            String incisionLevel,
            String caseStatus,
            String scheduleStatus,
            Integer sequence,
            Instant plannedStart,
            Instant plannedEnd,
            String cancelReason,
            long scheduleVersion,
            Integer surgeryLevel,
            String levelApprovalStatus) {
    }

    /**
     * 手术收费打包规则：术式类别 / 麻醉方式 / 耗材 → 收费项目。
     *
     * <p>规则只描述"计什么、计几份"，**价格永远取 md_charge_item**，
     * 避免同一条收费项目在规则表里被写成两个价（物价只有一个真源）。
     */
    public record SurgeryChargeRule(UUID id, String ruleType, String matchValue, String itemCode,
                                    BigDecimal quantity, int sortNo, boolean enabled,
                                    long version) {

        /** 按术式类别（operation_catalog.category）匹配。 */
        public static final String OPERATION_CATEGORY = "OPERATION_CATEGORY";
        /** 按麻醉方式（SurgeryCase.anesthesiaType）匹配。 */
        public static final String ANESTHESIA_METHOD = "ANESTHESIA_METHOD";
        /** 每台手术固定耗材。 */
        public static final String MATERIAL = "MATERIAL";

        /** 术式类别无规则时的兜底匹配值。 */
        public static final String DEFAULT_MATCH = "DEFAULT";
        /** 耗材规则的匹配值（所有手术适用）。 */
        public static final String ALL = "ALL";
    }

    /** 收费单。 */
    public record Charge(UUID id, String chargeNo, UUID visitId, BigDecimal totalAmount,
                         String status, String paymentMethod, Instant paidAt, long version) {

        public static final String UNPAID = "UNPAID";
        public static final String PAID = "PAID";
        public static final String REFUNDED = "REFUNDED";
    }

    // ---------------------------------------------------------------- 门诊日结（G1）

    /** 门诊日结：科室维度（已收金额）。 */
    public record DeptPaidTotal(UUID deptId, String deptCode, String deptName,
                                int visitCount, BigDecimal amount) {
    }

    /** 门诊日结：支付方式维度（分账用）。 */
    public record MethodPaidTotal(String paymentMethod, int chargeCount, BigDecimal amount) {
    }

    /** 门诊日结：合计（笔数/金额/当日退费额）。 */
    public record OutpatientPaidSummary(int visitCount, int paidChargeCount,
                                        BigDecimal totalPaid, BigDecimal refundedAmount) {
    }

    /** 门诊趋势：单日聚合（G13；退费按退费时刻归日）。 */
    public record DailyPaidTotal(LocalDate day, int visitCount, int paidChargeCount,
                                 BigDecimal totalPaid, BigDecimal refundedAmount) {
    }

    /** 住院周转趋势：单日入/出院与出院者平均住院日（G16）。 */
    public record DailyAdmissionTotal(LocalDate day, int admittedCount, int dischargedCount,
                                      BigDecimal avgStayDays) {
    }

    /** 住院周转趋势：单日在院人数（G16；按业务日结束时刻统计）。 */
    public record DailyInHospitalTotal(LocalDate day, int inHospitalCount) {
    }

    /** 住院周转趋势：区间合计（G16；用于上一周期环比）。 */
    public record AdmissionPeriodSummary(int admittedCount, int dischargedCount) {
    }

    /** 运营日快照（G17）：单日单指标。 */
    public record ReportSnapshot(LocalDate snapshotDate, String metric, BigDecimal value,
                                 String detail, String source, Instant updatedAt) {
    }

    /** 报表订阅（G19 / G25）：多渠道；发送时段与收件人分组可选。 */
    public record ReportSubscription(UUID id, String name, String reportCode, String channel,
                                     String target, java.time.LocalTime sendTime,
                                     UUID recipientGroupId, boolean enabled, long version,
                                     String createdBy, Instant updatedAt) {

        /** 默认发送时段 00:30、不绑定分组（历史调用与测试用）。 */
        public ReportSubscription(UUID id, String name, String reportCode, String channel,
                                  String target, boolean enabled, long version,
                                  String createdBy, Instant updatedAt) {
            this(id, name, reportCode, channel, target, java.time.LocalTime.of(0, 30), null,
                    enabled, version, createdBy, updatedAt);
        }
    }

    /** 报表收件人分组（G25）：按科室/岗位维护一组邮箱。 */
    public record ReportRecipientGroup(UUID id, String code, String name, String description,
                                       boolean enabled, long version, String createdBy,
                                       Instant updatedAt) {
    }

    /** 分组成员；email 在组内唯一。 */
    public record ReportRecipientGroupMember(UUID id, UUID groupId, String email,
                                             String displayName, boolean enabled,
                                             Instant createdAt) {
    }

    /** 报表投递记录（G19）：同一订阅同一天唯一。 */
    public record ReportDelivery(UUID id, UUID subscriptionId, String subscriptionName,
                                 String reportCode, LocalDate reportDate, String status,
                                 int attempts, String lastError, Instant createdAt,
                                 Instant sentAt) {
    }

    /**
     * 电子票据推送记录（G21 / G24）。
     *
     * <p>同一票据同一平台同一报文类型唯一：开具与红冲是两条独立报文。
     * 红冲通过 {@code originalPushId} 指向原开具记录，并携带原平台票据号。
     */
    public record ReceiptPush(UUID id, UUID receiptId, String platform, String vendor,
                              String messageType, String templateVersion, UUID originalPushId,
                              String status, int attempts, String externalNo, String lastError,
                              Instant pushedAt) {

        public static final String ISSUE = "ISSUE";
        public static final String RED_FLUSH = "RED_FLUSH";
    }

    /** 电子票据推送对账行（G24）：带票据号与金额，供财务按日期对账。 */
    public record ReceiptPushLedger(UUID id, UUID receiptId, String receiptNo, BigDecimal amount,
                                    String platform, String vendor, String messageType,
                                    String templateVersion, String status, int attempts,
                                    String externalNo, String lastError, Instant createdAt,
                                    Instant pushedAt) {
    }

    /** 电子票据推送汇总（G24）：按期间统计，金额只统计开具成功报文。 */
    public record ReceiptPushSummary(long total, long sent, long failed, long pending,
                                     long redFlushCount, BigDecimal sentAmount) {
    }

    /**
     * 收费票据（G14）。
     *
     * <p>金额/支付方式为开票快照；重打只累加 {@code reprintCount}；
     * 作废必须写原因，作废后可为同一收费单换开新票。
     */
    public record ChargeReceipt(UUID id, String receiptNo, UUID chargeId, UUID visitId,
                                BigDecimal amount, String paymentMethod, String status,
                                String issuedBy, Instant issuedAt, String voidedBy,
                                Instant voidedAt, String voidReason, int reprintCount,
                                long version, Instant createdAt) {

        public static final String ISSUED = "ISSUED";
        public static final String VOID = "VOID";

        public boolean active() {
            return ISSUED.equals(status);
        }
    }

    /** 病区。 */
    public record Ward(UUID id, String code, String name, UUID deptId, boolean enabled) {
    }

    /** 护理班次字典（G26）。 */
    public record NursingShift(UUID id, String code, String name,
                               java.time.LocalTime startTime, java.time.LocalTime endTime,
                               boolean crossMidnight, boolean enabled, long version) {
    }

    /** 护理排班明细（G26）：DRAFT → PUBLISHED → CANCELLED。 */
    public record NursingSchedule(UUID id, UUID deptId, String deptName, String shiftCode,
                                  String shiftName, LocalDate workDate, String nurseName,
                                  String nurseNo, String status, String note, String createdBy,
                                  Instant createdAt, String cancelledBy, Instant cancelledAt,
                                  String cancelReason, long version) {

        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String CANCELLED = "CANCELLED";
    }

    /** 护士工作量（G26）：统计周期内班次数与夜班数。 */
    public record NursingWorkloadRow(String nurseName, String nurseNo, long shifts,
                                     long nightShifts) {
    }

    /** 每日每班次覆盖人数（G26）。 */
    public record NursingCoverageRow(LocalDate workDate, String shiftCode, long count) {
    }

    /** 病区入出量（G27）：按病区+科室聚合，用于同比与下钻。 */
    public record WardAdmissionRow(String wardCode, String wardName, String deptName,
                                   long admitted, long discharged) {
    }

    /** 床位。 */
    public record Bed(UUID id, UUID wardId, String bedNo, String status, long version) {

        public static final String FREE = "FREE";
        public static final String OCCUPIED = "OCCUPIED";
        public static final String RESERVED = "RESERVED";
        public static final String DISABLED = "DISABLED";

        public boolean free() {
            return FREE.equals(status);
        }
    }

    /** 住院登记。 */
    public record Admission(UUID id, String admissionNo, UUID visitId, UUID patientId,
                            String patientNo, UUID wardId, UUID bedId, String attendingDoctor,
                            String status, Instant admittedAt, Instant dischargedAt, long version) {

        public static final String ADMITTED = "ADMITTED";
        public static final String IN_HOSPITAL = "IN_HOSPITAL";
        public static final String DISCHARGED = "DISCHARGED";
        public static final String CANCELLED = "CANCELLED";

        public boolean active() {
            return ADMITTED.equals(status) || IN_HOSPITAL.equals(status);
        }
    }

    /** 预交金流水（DEPOSIT 正数 / REFUND 负数）。 */
    public record Deposit(UUID id, UUID admissionId, BigDecimal amount, String kind,
                          String method, String operator, String remark, Instant createdAt) {

        public static final String DEPOSIT = "DEPOSIT";
        public static final String REFUND = "REFUND";
        /** 结算抵扣（负数流水）。 */
        public static final String SETTLE = "SETTLE";
    }

    /**
     * 护理执行记录（长嘱逐日执行，追加式）。
     *
     * <p>按日计费时以本记录核对：REFUSED / OMITTED 的医嘱当日不计费；
     * 尚无记录时按"已执行"计费，兼容护理站尚未上线的科室。
     */
    public record NursingExecution(UUID id, UUID admissionId, UUID visitId, UUID orderId,
                                   LocalDate execDate, String status, String executor,
                                   String note, Instant createdAt) {

        public static final String EXECUTED = "EXECUTED";
        public static final String REFUSED = "REFUSED";
        public static final String OMITTED = "OMITTED";

        public boolean executed() {
            return EXECUTED.equals(status);
        }
    }

    /**
     * 护理执行更正（追加式：一条执行记录只允许更正一次）。
     *
     * <p>为什么不覆盖原记录：执行记录是"当日是否收费"的原始依据，改写历史会让
     * 计费口径无法追溯；更正作为独立事实保留 from/to 与原因、操作人。
     */
    public record NursingExecutionCorrection(UUID id, UUID executionId, UUID admissionId,
                                             UUID visitId, UUID orderId, LocalDate execDate,
                                             String fromStatus, String toStatus, String reason,
                                             String correctedBy, Instant createdAt) {
    }

    /**
     * 费用明细行（只读投影）。
     *
     * <p>供医保域生成结算清单时读取；不在此做任何金额计算，避免两份计费口径。
     */
    public record ChargeLine(String chargeNo, String itemCode, String itemName,
                             String category, BigDecimal amount, String chargeStatus) {
        public static final String UNPAID = "UNPAID";
    }

    /**
     * 药房发药费用关联（N2 收尾）。
     *
     * <p>退药冲减的幂等依据：{@code refunded(已冲减) + pending(待财务退费) + done(已退费)}
     * 三者只增不减，调用方传"累计已退金额"，重复提交不会重复冲账。
     */
    public record PharmacyChargeLink(UUID dispenseItemId, UUID visitId, UUID chargeId,
                                     UUID chargeItemId, String drugCode, String drugName,
                                     BigDecimal amount, BigDecimal refundedAmount,
                                     BigDecimal refundPendingAmount, BigDecimal refundDoneAmount) {

        /** 已处理过的退费总额（含已冲减、待退费、已退费）。 */
        public BigDecimal handledRefund() {
            return refundedAmount.add(refundPendingAmount).add(refundDoneAmount);
        }
    }

    /**
     * 医嘱计费模式的退药冲减（B3）。
     *
     * <p>钱是在结算时按医嘱一次性收的，因此退药必须回到"这笔医嘱"上冲减：
     * {@code applied}（未结算直接冲减，结算时扣减）、{@code pending}（已结算待财务退费）、
     * {@code done}（财务已退）。三者只增不减，调用方传"累计已退金额"，重试天然幂等。
     */
    public record OrderRefund(UUID id, UUID orderId, UUID dispenseItemId, UUID visitId,
                              BigDecimal amount, BigDecimal appliedAmount,
                              BigDecimal pendingAmount, BigDecimal doneAmount) {

        public BigDecimal handled() {
            return appliedAmount.add(pendingAmount).add(doneAmount);
        }
    }

    /**
     * 长嘱药品按日请领台账（一次请领一行，{@code order_id + supply_date} 幂等）。
     *
     * <p>记录药房产出的处方引用或失败原因：药房不可用时也必须留痕，
     * 病区看板据此区分"已请领 / 请领失败 / 已收货"。
     */
    public record DailyDispenseRequest(UUID id, UUID orderId, UUID visitId, LocalDate supplyDate,
                                       int quantity, UUID prescriptionId, String prescriptionNo,
                                       String status, String errorMessage) {

        public static final String REQUESTED = "REQUESTED";
        public static final String FAILED = "FAILED";
    }

    /**
     * 病区收药确认（按 {@code dispense_id} 幂等）。
     *
     * <p>药房说发了、病区说没收到时，以这张表为准对账；{@code returnedQuantity}
     * 留给后续"未执行退药"批次使用。
     */
    public record WardMedReceipt(UUID id, UUID requestId, UUID orderId, UUID visitId,
                                 UUID dispenseId, LocalDate supplyDate, int quantity,
                                 int returnedQuantity, String status, String receivedBy) {

        public static final String RECEIVED = "RECEIVED";
        public static final String RETURNED = "RETURNED";

        /** 尚未退回的数量（病区实物库存）。 */
        public int stockBalance() {
            return quantity - returnedQuantity;
        }
    }

    /**
     * 病区退药记录（按 {@code returnId} 幂等）。
     *
     * <p>只允许退"当日护理执行 REFUSED / OMITTED"的药品：药已经吃了就不能再退，
     * 否则病区库存与药房发药记录会对不上，也会被医保飞检认定为虚记。
     */
    public record WardMedReturn(UUID id, UUID receiptId, UUID orderId, UUID visitId,
                                LocalDate supplyDate, int quantity, String reason,
                                String returnedBy) {
    }

    /**
     * 病区二级库存流水（append-only）。
     *
     * <p>{@code IN} 来自收药、{@code RETURN} 来自退药；余额永远由流水汇总得出，
     * 不允许直接改库存数字（盘点差异也走新增调整流水）。
     */
    public record WardStockTxn(UUID id, UUID visitId, UUID orderId, UUID receiptId,
                               UUID returnId, String drugCode, String drugName, int quantity,
                               String txnType, LocalDate supplyDate, String operator,
                               Instant createdAt) {

        public static final String IN = "IN";
        public static final String RETURN = "RETURN";
    }

    /** 病区二级库存余额（按药品汇总流水；余额为 0 的药品不返回）。 */
    public record WardStockBalance(String drugCode, String drugName, int receivedQuantity,
                                   int returnedQuantity) {

        public int balance() {
            return receivedQuantity - returnedQuantity;
        }
    }

    /**
     * 会诊申请：跨科室只读授权的业务事实来源。
     *
     * <p>{@link #ACTIVE_STATUSES} 内的记录会让受邀科室获得该就诊的**只读**权限；
     * 一旦拒绝、完成或撤回，授权立即收回——不做"一次会诊永久可见"。
     */
    public record Consultation(UUID id, String consultationNo, UUID visitId, UUID patientId,
                               String patientNo, UUID fromDeptId, UUID toDeptId, String reason,
                               String status, String requestedBy, Instant requestedAt,
                               String respondedBy, Instant respondedAt, String rejectedReason,
                               String completedBy, Instant completedAt, String conclusion,
                               String cancelledBy, Instant cancelledAt, String cancelledReason,
                               long version, Instant createdAt) {

        public static final String REQUESTED = "REQUESTED";
        public static final String ACCEPTED = "ACCEPTED";
        public static final String REJECTED = "REJECTED";
        public static final String COMPLETED = "COMPLETED";
        public static final String CANCELLED = "CANCELLED";

        /** 仍授予只读权限的状态。 */
        public static final java.util.Set<String> ACTIVE_STATUSES =
                java.util.Set.of(REQUESTED, ACCEPTED);

        public boolean active() {
            return ACTIVE_STATUSES.contains(status);
        }

        public boolean terminal() {
            return REJECTED.equals(status) || COMPLETED.equals(status) || CANCELLED.equals(status);
        }
    }

    /**
     * 转科记录：一次主诊科室变更。
     *
     * <p>表是 append-only 的，{@code version} 只为与其它记录保持同构，永远为 0。
     * 当前主诊科室以 {@code visit.dept_id} 为准，本表负责"从哪来、为什么来"。
     */
    public record VisitTransfer(UUID id, UUID visitId, UUID fromDeptId, UUID toDeptId,
                                String reason, String transferredBy, Instant transferredAt,
                                long version) {
    }

    /**
     * 临床路径模板（G29）。
     *
     * <p>状态机 {@code DRAFT → PUBLISHED → DISABLED}：发布后节点不可修改，
     * 已入径患者的任务按快照执行；要调整只能停用后新建版本。
     */
    public record PathwayTemplate(UUID id, String code, String name, String diagnosisCode,
                                  UUID deptId, String deptName, int dayCount, int versionNo,
                                  UUID originId, String status, String createdBy,
                                  String publishedBy, Instant publishedAt, long version,
                                  Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String DISABLED = "DISABLED";

        public boolean publishable() {
            return DRAFT.equals(status);
        }

        public boolean enrollable() {
            return PUBLISHED.equals(status);
        }
    }

    /** 路径节点模板（第 dayNo 天第 seq 项）。 */
    public record PathwayNode(UUID id, UUID templateId, int dayNo, int seq, String name,
                              String category, boolean required, String description) {

        public static final java.util.Set<String> CATEGORIES = java.util.Set.of(
                "ASSESSMENT", "ORDER", "NURSING", "EXAM", "SURGERY", "OTHER");
    }

    /**
     * 入径实例。
     *
     * <p>同一住院就诊只允许一条 {@code ACTIVE} 实例；结束或退出后写 {@code endDate}，
     * 退出必须留原因（路径变异）。
     */
    public record PathwayInstance(UUID id, String instanceNo, UUID templateId, String templateName,
                                  UUID visitId, String visitNo, UUID patientId, String patientNo,
                                  UUID deptId, String deptName, String status, int currentDay,
                                  LocalDate startDate, LocalDate endDate, String exitReason,
                                  String createdBy, long version, Instant createdAt) {

        public static final String ACTIVE = "ACTIVE";
        public static final String COMPLETED = "COMPLETED";
        public static final String EXITED = "EXITED";

        public boolean active() {
            return ACTIVE.equals(status);
        }
    }

    /** 路径任务快照：入径时从节点复制，模板后续变更不影响在途患者。 */
    public record PathwayTask(UUID id, UUID instanceId, UUID nodeId, int dayNo, int seq,
                              String name, String category, boolean required, String status,
                              String doneBy, Instant doneAt, String note) {

        public static final String PENDING = "PENDING";
        public static final String DONE = "DONE";
        public static final String SKIPPED = "SKIPPED";

        public boolean pending() {
            return PENDING.equals(status);
        }
    }

    /**
     * 出院带药单（G30）。
     *
     * <p>状态机 {@code DRAFT → SUBMITTED → DISPENSED}，DRAFT/SUBMITTED 可取消；
     * 提交时调用药房内网接口生成「发药时计价」处方，发药结果由同步接口/定时任务回写。
     */
    public record DischargeMedication(UUID id, String medicationNo, UUID admissionId, UUID visitId,
                                      String visitNo, UUID patientId, String patientNo, UUID deptId,
                                      String deptName, String status, String note,
                                      UUID prescriptionId, String prescriptionNo,
                                      BigDecimal totalAmount, Instant submittedAt,
                                      Instant dispensedAt, String cancelledBy,
                                      Instant cancelledAt, String cancelReason, String createdBy,
                                      long version, Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String SUBMITTED = "SUBMITTED";
        public static final String DISPENSED = "DISPENSED";
        public static final String CANCELLED = "CANCELLED";

        public boolean draft() {
            return DRAFT.equals(status);
        }

        public boolean submitted() {
            return SUBMITTED.equals(status);
        }

        public boolean active() {
            return DRAFT.equals(status) || SUBMITTED.equals(status);
        }
    }

    /** 出院带药明细快照（药品主数据在药房域，本表只存编码与名称）。 */
    public record DischargeMedicationItem(UUID id, UUID medicationId, int seq, String drugCode,
                                          String drugName, String spec, String unit, int quantity,
                                          String dosage, String frequency, Integer days) {
    }

    /** 路径入径统计行（按模板聚合，G32）。 */
    public record PathwayInstanceStatRow(UUID templateId, String templateCode, String templateName,
                                         int versionNo, long enrolled, long completed,
                                         long exited, long active, Double avgDays) {
    }

    /** 路径任务变异统计行（按模板聚合，G32）。 */
    public record PathwayTaskStatRow(UUID templateId, long tasks, long skipped) {
    }

    /** 路径科室统计行（G32）。 */
    public record PathwayDeptStatRow(UUID deptId, String deptName, long enrolled, long completed,
                                     long exited, long active) {
    }

    /**
     * 急诊预检分诊记录（G36）。
     *
     * <p>状态机：{@code WAITING → REGISTERED → OBSERVING → CLOSED}；
     * WAITING 可因患者未挂号自行离院 → {@code LEFT}（必须写原因）。
     * 1–2 级自动置绿色通道。
     */
    public record EmergencyTriage(UUID id, String triageNo, UUID patientId, String patientNo,
                                  String patientName, UUID deptId, String deptName, int triageLevel,
                                  String chiefComplaint, BigDecimal temperature, Integer pulse,
                                  Integer respiration, Integer systolic, Integer diastolic,
                                  Integer spo2, boolean greenChannel, String status, UUID visitId,
                                  String visitNo, String triagedBy, Instant arrivalAt,
                                  Instant triagedAt, String leaveReason, long version,
                                  Instant createdAt, Instant updatedAt) {

        public static final String WAITING = "WAITING";
        public static final String REGISTERED = "REGISTERED";
        public static final String OBSERVING = "OBSERVING";
        public static final String CLOSED = "CLOSED";
        public static final String LEFT = "LEFT";

        public boolean waiting() {
            return WAITING.equals(status);
        }

        public boolean registered() {
            return REGISTERED.equals(status);
        }

        public boolean observing() {
            return OBSERVING.equals(status);
        }

        /** 已完成挂号（含留观中与已结束）。 */
        public boolean hasVisit() {
            return REGISTERED.equals(status) || OBSERVING.equals(status)
                    || CLOSED.equals(status);
        }
    }

    /** 急诊留观/抢救床位（G36）：FREE ↔ OCCUPIED。 */
    public record EmergencyBed(UUID id, String bedNo, UUID deptId, String deptName, String area,
                               String status, UUID currentObservationId, boolean enabled,
                               long version) {

        public static final String FREE = "FREE";
        public static final String OCCUPIED = "OCCUPIED";

        public boolean free() {
            return FREE.equals(status);
        }
    }

    /** 急诊留观记录（G36）：OBSERVING → CLOSED，转归四选一。 */
    public record EmergencyObservation(UUID id, String observationNo, UUID triageId,
                                       String triageNo, UUID visitId, String visitNo,
                                       UUID patientId, String patientNo, String patientName,
                                       UUID deptId, String deptName, UUID bedId, String bedNo,
                                       String status, Instant startAt, Instant endAt,
                                       String outcome, String outcomeNote, String createdBy,
                                       String closedBy, long version, Instant createdAt) {

        public static final String OBSERVING = "OBSERVING";
        public static final String CLOSED = "CLOSED";

        public boolean observing() {
            return OBSERVING.equals(status);
        }
    }

    /** 急诊看板聚合行（G36）：当日分诊/留观与当前床位占用。 */
    public record EmergencyBoardRow(long waiting, long level1, long level2, long level3,
                                    long level4, long registered, long observing, long closedToday,
                                    long leftToday, long totalToday, long freeBeds,
                                    long occupiedBeds) {
    }

    /**
     * 体检套餐（G37）：DRAFT → PUBLISHED → DISABLED。
     * 发布后内容不可改，已登记体检按项目快照执行。
     */
    public record HealthExamPackage(UUID id, String packageCode, String name, String description,
                                    BigDecimal totalPrice, String status, int itemCount,
                                    String createdBy, String publishedBy, Instant publishedAt,
                                    long version, Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String DISABLED = "DISABLED";

        public boolean draft() {
            return DRAFT.equals(status);
        }

        public boolean published() {
            return PUBLISHED.equals(status);
        }
    }

    /** 体检套餐项目：收费项目编码 + 名称/类别/价格快照 + 可选执行科室。 */
    public record HealthExamPackageItem(UUID id, UUID packageId, int seq, String itemCode,
                                        String itemName, String itemCategory, UUID deptId,
                                        String deptName, BigDecimal price) {
    }

    /**
     * 体检登记（G37）：REGISTERED → IN_PROGRESS → EXAM_DONE → REPORTED；
     * REGISTERED/IN_PROGRESS 可取消 → CANCELLED（必须写原因）。
     */
    public record HealthExamRegistration(UUID id, String examNo, UUID packageId, String packageName,
                                         BigDecimal packagePrice, UUID patientId, String patientNo,
                                         String patientName, String gender, LocalDate birthDate,
                                         String status, String note, String cancelReason,
                                         String cancelledBy, Instant cancelledAt, String createdBy,
                                         long version, Instant createdAt, Instant updatedAt) {

        public static final String REGISTERED = "REGISTERED";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String EXAM_DONE = "EXAM_DONE";
        public static final String REPORTED = "REPORTED";
        public static final String CANCELLED = "CANCELLED";

        public boolean cancelled() {
            return CANCELLED.equals(status);
        }

        public boolean examDone() {
            return EXAM_DONE.equals(status);
        }

        public boolean reported() {
            return REPORTED.equals(status);
        }

        /** 仍可录入检查结果的状态。 */
        public boolean examinable() {
            return REGISTERED.equals(status) || IN_PROGRESS.equals(status);
        }
    }

    /** 体检项目快照：PENDING → COMPLETED / SKIPPED；未检必须写原因。 */
    public record HealthExamItem(UUID id, UUID registrationId, int seq, String itemCode,
                                 String itemName, String itemCategory, UUID deptId, String deptName,
                                 BigDecimal price, String status, String resultSummary,
                                 String resultNote, boolean abnormal, String completedBy,
                                 Instant completedAt, long version) {

        public static final String PENDING = "PENDING";
        public static final String COMPLETED = "COMPLETED";
        public static final String SKIPPED = "SKIPPED";

        public boolean pending() {
            return PENDING.equals(status);
        }
    }

    /** 总检报告：一次登记一份，DRAFT → FINAL；FINAL 后不可修改。 */
    public record HealthExamReport(UUID id, String reportNo, UUID registrationId, String conclusion,
                                   String suggestion, int abnormalCount, String status,
                                   String createdBy, String finalizedBy, Instant finalizedAt,
                                   long version, Instant createdAt) {

        public static final String DRAFT = "DRAFT";
        public static final String FINAL = "FINAL";

        public boolean finalReport() {
            return FINAL.equals(status);
        }
    }

    /** 体检项目数量聚合（判断是否可总检）。 */
    public record HealthExamItemCountRow(int total, int pending, int completed, int skipped,
                                         int abnormal) {
    }

    /** 体检登记列表行（含项目进度与报告状态）。 */
    public record HealthExamRegistrationSummary(UUID id, String examNo, String packageName,
                                                UUID patientId, String patientNo,
                                                String patientName, String gender,
                                                LocalDate birthDate, String status, int itemTotal,
                                                int itemCompleted, int abnormalCount,
                                                String reportStatus, Instant createdAt) {
    }

    /** 体检看板聚合行（G37）：当日登记/检查进度/报告与异常项目。 */
    public record HealthExamBoardRow(long registered, long inProgress, long examDone, long reported,
                                     long cancelled, long abnormalItems, long totalToday) {
    }
}
