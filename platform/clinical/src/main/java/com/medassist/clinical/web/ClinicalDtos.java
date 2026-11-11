package com.medassist.clinical.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 临床核心请求/响应契约。
 *
 * <p>约束：请求体只接受"业务意图"（患者、科室、医嘱项目与数量），
 * **不接受金额、单价、状态**——价格来自主数据、状态由服务端状态机推进，
 * 这是收费类系统的防篡改底线。
 */
public final class ClinicalDtos {

    private ClinicalDtos() {
    }

    /** 建号源（排班）。 */
    public record CreateSlotReq(
            @NotNull UUID deptId,
            @NotBlank String doctorId,
            @NotNull LocalDate workDate,
            @NotBlank String period,
            @Min(1) int total) {
    }

    /** 挂号。 */
    public record RegisterReq(
            @NotNull UUID patientId,
            @NotNull UUID deptId,
            @NotBlank String doctorId,
            UUID slotId,
            String visitType,
            LocalDate visitDate) {
    }

    /** 一条医嘱。{@code orderMode} 可空：默认 TEMP（临时医嘱）；住院可传 LONG（长嘱）。 */
    public record OrderLine(
            @NotBlank String itemCode,
            @Min(1) int quantity,
            String dose,
            String frequency,
            String orderMode) {
    }

    /** 批量开立医嘱。 */
    public record PlaceOrdersReq(@NotEmpty List<@Valid OrderLine> items) {
    }

    /** 频次词表项（前端下拉与接口说明用；服务端为唯一真源）。 */
    public record FrequencyView(String code, String label, int timesPerDay, boolean daily) {
    }

    /** 长嘱按日请领请求：date 可空（默认今天，不允许未来）。 */
    public record DailyDispenseReq(LocalDate date) {
    }

    /** 单条医嘱的请领结果（失败不影响其它医嘱，由病区按行处理）。 */
    public record DailyDispenseItem(UUID orderId, String orderNo, String itemCode, String itemName,
                                    int quantity, UUID prescriptionId, String prescriptionNo,
                                    boolean duplicate, String status, String error) {
    }

    /** 按日请领汇总。 */
    public record DailyDispenseResult(LocalDate date, int requested, int created, int duplicate,
                                      int failed, List<DailyDispenseItem> items) {
    }

    /** 病区收药确认（按 dispenseId 幂等；数量不得超过当日请领数量）。 */
    public record WardReceiptReq(@NotNull UUID dispenseId, @NotNull LocalDate supplyDate,
                                 @Min(1) int quantity) {
    }

    /** 收药记录视图。 */
    public record WardReceiptView(UUID id, UUID dispenseId, UUID orderId, LocalDate supplyDate,
                                  int quantity, String status, String receivedBy) {
    }

    /**
     * 病区退药请求：{@code returnId} 由调用方生成，作为幂等键。
     *
     * <p>为什么不用"数量 + 时间"做幂等：护士站重试/双击必须回到同一条退药记录，
     * 只有客户端 UUID 能保证"这次退药"的唯一性。
     */
    public record WardReturnReq(@NotNull UUID returnId, @Min(1) int quantity, String reason) {
    }

    /** 退药结果视图（含该收药单的最新可退余额）。 */
    public record WardReturnView(UUID id, UUID receiptId, UUID orderId, LocalDate supplyDate,
                                 int quantity, int receiptQuantity, int returnedQuantity,
                                 int stockBalance, String receiptStatus, String returnedBy) {
    }

    /** 病区备药看板的一行：请领 / 处方 / 收药 / 护理执行 / 退药五段状态。 */
    public record DispenseBoardItem(UUID orderId, String orderNo, String itemCode, String itemName,
                                    int dailyQuantity, String frequency, int frequencyTimes,
                                    String requestStatus, UUID prescriptionId,
                                    String prescriptionNo, String requestError,
                                    int receivedQuantity, int returnedQuantity, int stockBalance,
                                    String nursingStatus, boolean returnable, String returnHint) {
    }

    /** 病区备药看板（按就诊 + 日期）。 */
    public record DispenseBoard(LocalDate date, int ordered, int requested, int received,
                                int failed, List<DispenseBoardItem> items) {
    }

    /** 病区二级库存单行（余额 = 收药 - 退药）。 */
    public record WardStockItem(String drugCode, String drugName, int receivedQuantity,
                                int returnedQuantity, int balance) {
    }

    /** 病区二级库存（按就诊汇总，跨日期累计；余额为 0 的药品不返回）。 */
    public record WardStockView(UUID visitId, int totalBalance, List<WardStockItem> items,
                                List<WardStockTxnView> recentTxns) {
    }

    /** 库存流水视图。 */
    public record WardStockTxnView(UUID id, String drugCode, String drugName, int quantity,
                                   String txnType, LocalDate supplyDate, UUID orderId,
                                   String operator, java.time.Instant createdAt) {
    }

    /** 停嘱。 */
    public record StopOrderReq(String reason) {
    }

    /**
     * 下达诊断。
     *
     * <p>{@code primary=true} 表示主诊断；一个就诊只能有一条主诊断（已有主诊断时 409）。
     */
    public record AddDiagnosisReq(
            @NotBlank String code,
            Boolean primary) {
    }

    /** 结算（收费）。 */
    public record SettleReq(@NotBlank String paymentMethod) {
    }

    /** 费用明细视图。 */
    public record ChargeItemView(UUID orderId, String itemCode, String itemName,
                                 String category, BigDecimal amount) {
    }

    /** 收费单视图（含明细，便于收费窗口一次打印）。 */
    public record ChargeView(UUID id, String chargeNo, UUID visitId, BigDecimal totalAmount,
                             String status, String paymentMethod, Instant paidAt,
                             List<ChargeItemView> items) {
    }

    /** 待收费汇总（收费前预览）。 */
    public record BillingPreview(BigDecimal chargedTotal, BigDecimal pendingAmount,
                                 int pendingOrderCount, ChargeView charge) {
    }

    // ---------------------------------------------------------------- 住院（ADT）

    /** 新建病区。 */
    public record CreateWardReq(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull UUID deptId) {
    }

    /** 批量建床（同一病区）。 */
    public record CreateBedsReq(@NotEmpty List<@NotBlank String> bedNos) {
    }

    /** 入院登记（bedId 可空：先登记后分床）。 */
    public record AdmitReq(
            @NotNull UUID patientId,
            @NotNull UUID wardId,
            UUID bedId,
            @NotBlank String attendingDoctor) {
    }

    /** 分床 / 转床。 */
    public record AssignBedReq(
            @NotNull UUID bedId,
            String reason) {
    }

    /**
     * 床位预留创建。
     *
     * @param patientId 可空：空表示机动预留（不绑定患者）
     * @param reservedUntil 到期时间；缺省由服务端按 24 小时兜底
     */
    public record BedReserveReq(
            @NotNull UUID bedId,
            UUID patientId,
            @Size(max = 40) String patientNo,
            @Size(max = 200) String reason,
            Instant reservedUntil) {
    }

    /** 解除床位预留（原因可选，用于交接追溯）。 */
    public record BedReleaseReq(@Size(max = 200) String reason) {
    }

    /**
     * 床位预留视图。
     *
     * @param expired 到期但后台任务尚未回收时为 true（界面应提示“即将释放”而不是当可用）
     */
    public record BedReservationView(
            UUID id, UUID bedId, String bedNo, UUID wardId,
            UUID patientId, String patientNo, String reason, String reservedBy,
            Instant reservedUntil, Instant createdAt, boolean expired) {
    }

    /**
     * 转科：目标科室 + 目标床位 + 理由。
     *
     * <p>{@code bedId} 在患者当前已占床时必填：转科必须同时落到目标科室的病区床位，
     * 否则原床位释放后患者无处可去，新科室也无法按病区收治。未分床的患者可不传。
     */
    public record TransferVisitReq(
            @NotNull UUID toDeptId,
            UUID bedId,
            @NotBlank @Size(max = 500) String reason) {
    }

    /** 住院登记视图（含就诊号，便于与医嘱/收费对照）。 */
    public record AdmissionView(UUID id, String admissionNo, UUID visitId, String visitNo,
                                UUID patientId, String patientNo, UUID wardId, String wardName,
                                UUID bedId, String bedNo, String attendingDoctor, String status,
                                java.time.Instant admittedAt, java.time.Instant dischargedAt,
                                LocalDate plannedDischargeDate) {
    }

    /**
     * 设置/清除预计出院日。
     *
     * <p>{@code date} 为 null 表示清除计划；病区排班、超期统计都以业务日期为准。
     */
    public record PlannedDischargeReq(LocalDate date) {
    }

    /** 按日计费结果（当日新计金额 + 收费单累计 + 因未执行而跳过的医嘱）。 */
    public record DailyBillResult(LocalDate date, int orderCount, BigDecimal billedAmount,
                                  BigDecimal chargeTotal, List<String> skippedOrderNos) {
    }

    /**
     * 按日计费红冲请求（P1-1）。
     *
     * <p>必须带原因 + 显式二次确认：红冲直接改变患者应收金额，误操作只能靠审计追溯。
     */
    public record DailyReverseReq(
            @NotNull UUID orderId,
            LocalDate serviceDate,
            @NotBlank @jakarta.validation.constraints.Size(max = 200, message = "红冲原因不超过 200 字")
            String reason,
            @NotNull Boolean confirmed) {
    }

    /** 按日计费红冲结果；{@code idempotent=true} 表示该日此前已冲过（重复调用安全）。 */
    public record DailyReversalResult(UUID orderId, LocalDate serviceDate, BigDecimal reversedAmount,
                                      boolean idempotent, String reason) {
    }

    /** 预交金操作（amount 必须为正；退还时服务端取负）。 */
    public record DepositReq(
            @NotNull @jakarta.validation.constraints.DecimalMin(value = "0.01", message = "金额必须大于 0")
            BigDecimal amount,
            @NotBlank String method,
            String remark) {
    }

    /** 预交金余额与流水。 */
    public record DepositView(BigDecimal balance, List<com.medassist.clinical.domain.ClinicalModel.Deposit> items) {
    }

    // ---------------------------------------------------------------- 住院费用看板 / 欠费预警

    /**
     * 住院费用看板。
     *
     * <p>口径：{@code billedTotal} 为全部收费单累计（含已结与未结）；
     * {@code dailyLongAmount} 为"当日应计未计"的长期医嘱金额（已按日计费的、被护理标记
     * 未执行的都不计入）；{@code shortfall = max(0, 未结 + 待计 + 当日未计长嘱 − 预交金余额)}，
     * 大于 0 即欠费预警。
     */
    public record InpatientFinanceView(
            UUID admissionId, String admissionNo, UUID visitId, String visitNo,
            String patientNo, UUID wardId, String wardName, UUID bedId, String bedNo, String status,
            java.time.Instant admittedAt,
            BigDecimal billedTotal, BigDecimal settledTotal, BigDecimal unpaidTotal,
            BigDecimal depositBalance,
            BigDecimal pendingAmount, int pendingOrderCount,
            BigDecimal dailyLongAmount, int dailyLongOrderCount,
            BigDecimal todayBilledAmount,
            BigDecimal shortfall, boolean warning) {
    }

    /** 欠费预警条目（仅返回 shortfall &gt; 0 的在院患者）。 */
    public record InpatientAlert(UUID admissionId, String admissionNo, String patientNo,
                                 UUID bedId, String bedNo, BigDecimal unpaidTotal,
                                 BigDecimal pendingAmount, BigDecimal depositBalance,
                                 BigDecimal shortfall) {
    }

    // ---------------------------------------------------------------- 出院前体检

    /**
     * 出院前一致性体检（只读）。
     *
     * <p>为什么需要：出院接口遇到第一个问题就返回失败，医护只能"试一次 → 被拒 → 去处理 → 再试"，
     * 反复往返且看不到还剩几项。体检把与出院门禁**同一口径**的阻断项一次性列出，
     * 并补充"建议处理项"（未停长嘱、未完成出院带药）与"未核查项"（病历归档未接入本服务）。
     *
     * <p>口径一致性：{@code ready} 仅由阻断项决定，且阻断项集合必须与
     * {@code InpatientService#discharge} 的校验一一对应；建议项即使存在也不改变 ready——
     * 否则界面说"不能出院"而接口实际放行，比不展示更糟。
     */
    public record DischargeCheckView(
            UUID admissionId, String admissionNo, UUID visitId, String visitNo, String patientNo,
            String status, boolean ready,
            int blockerCount, int warningCount, int uncheckedCount,
            Instant checkedAt,
            List<DischargeCheckItem> items) {
    }

    /**
     * 体检条目。
     *
     * @param code       稳定机器码（前端与测试按码断言，不解析中文）
     * @param domain     ADMISSION / ORDER / BILLING / PHARMACY / EMR
     * @param severity   BLOCKER（服务端会拒绝出院）/ WARNING（建议处理）/ INFO（仅提示）
     * @param result     PASS / FAIL / UNKNOWN；UNKNOWN 表示本服务无法核查，绝不冒充 PASS
     * @param title      条目名（人可读）
     * @param detail     说明（含条数与金额）
     * @param nextAction 下一步建议；无需处理时为空
     * @param count      涉及条数（可空）
     * @param amount     涉及金额（可空）
     */
    public record DischargeCheckItem(
            String code, String domain, String severity, String result,
            String title, String detail, String nextAction,
            Integer count, BigDecimal amount) {
    }

    // ---------------------------------------------------------------- 病区出院预检（批量）

    /**
     * 病区出院预检（只读批量）。
     *
     * <p>与单患者体检**同一口径**，但只回机器码与条数：一个病区几十人，
     * 护士站要的是"谁还走不了、卡在哪一项"，金额留在单个患者页（那里单独校验费用权限）。
     *
     * @param emrChecked false 表示病历归档未核查（EMR 未接入/不可达），界面必须明说
     */
    public record WardDischargeReadinessView(
            UUID wardId, String wardName, Instant checkedAt,
            int total, int readyCount, int blockedCount, boolean emrChecked,
            int plannedTodayCount, int overdueCount,
            List<WardDischargeReadiness> items) {
    }

    /**
     * 病区预检条目。
     *
     * @param blockers 阻断项机器码（与单患者体检同码，前端按码翻译）
     * @param warnings 建议项机器码
     * @param plannedDischargeDate 预计出院日（未设置时为 null）
     * @param overdueDays 超期天数（计划日早于今天且仍在院时为正；否则 0）
     */
    public record WardDischargeReadiness(
            UUID admissionId, String admissionNo, String patientNo,
            UUID bedId, String bedNo, String status, boolean ready,
            int blockerCount, int warningCount,
            List<String> blockers, List<String> warnings,
            LocalDate plannedDischargeDate, int overdueDays) {
    }

    /**
     * 出院预检汇总（全局待办角标 / 驾驶舱用）。
     *
     * @param wardCount 当前账号可见病区数
     * @param total     在院患者数（只算 IN_HOSPITAL，不含登记未入科）
     * @param blocked   出院受阻人数（账单/医嘱三类阻断；不含“状态异常”）
     * @param ready     可出院人数（total − blocked）
     * @param overdue   超期未出院人数（预计出院日已过且仍在院）
     * @param plannedToday 今日计划出院人数
     * @param wards     按病区下钻（只含有在院患者的病区，受阻多的在前）
     */
    public record DischargeReadinessSummary(int wardCount, int total, int blocked, int ready,
                                            int overdue, int plannedToday,
                                            List<WardDischargeCount> wards) {
    }

    /**
     * 汇总里的病区一行：驾驶舱据此列出"哪个病区压着人"。
     *
     * @param total   该病区在院患者数
     * @param blocked 该病区出院受阻人数
     * @param ready   该病区可出院人数（total − blocked）
     * @param overdue 该病区超期未出院人数
     * @param plannedToday 该病区今日计划出院人数
     */
    public record WardDischargeCount(UUID wardId, String name, int total, int blocked, int ready,
                                     int overdue, int plannedToday) {
    }

    // ---------------------------------------------------------------- 护理执行记录

    /** 护理执行记录提交（execDate 缺省为当天）。 */
    public record NursingExecutionReq(
            @NotNull UUID orderId,
            LocalDate execDate,
            @NotBlank String status,
            String note) {
    }

    /** 护理执行记录视图。 */
    public record NursingExecutionView(UUID id, UUID admissionId, UUID orderId, String orderNo,
                                       String itemName, LocalDate execDate, String status,
                                       String executor, String note, java.time.Instant createdAt) {
    }

    /**
     * 护理执行更正：把已记录的执行状态改为另一种（每条执行记录只允许更正一次）。
     *
     * <p>必须二次确认：把"已执行"改成"未执行/患者拒绝"会造成收费减少，
     * 属不可逆的资金影响，与红冲同口径要求 confirmed=true + 原因。
     */
    public record NursingCorrectionReq(
            @NotBlank String status,
            @NotBlank @Size(max = 200) String reason,
            @NotNull Boolean confirmed) {
    }

    /**
     * 护理执行更正结果。
     *
     * <p>{@code reversedAmount} 为本次联动红冲的金额（0 表示未产生红冲）；
     * {@code billingHint} 说明计费侧的后续动作，便于前端直接提示护士。
     */
    public record NursingCorrectionView(UUID id, UUID executionId, UUID admissionId, UUID orderId,
                                        String orderNo, String itemName, LocalDate execDate,
                                        String fromStatus, String toStatus, String reason,
                                        String correctedBy, java.time.Instant createdAt,
                                        BigDecimal reversedAmount, String billingHint) {
    }

    // ---------------------------------------------------------------- 门诊改约 / 日结（G1）

    /**
     * 门诊改约：换到另一号源。
     *
     * <p>只接受目标号源 id：日期/医生/科室由号源决定，避免客户端传入互相矛盾的组合。
     * 跨科室改约不支持（挂号费与科室主数据绑定），需要先退号再重新挂号。
     */
    public record RescheduleReq(@NotNull UUID slotId) {
    }

    /** 门诊日结（按科室/支付方式分账）。 */
    public record OutpatientDailyView(
            LocalDate date,
            int visitCount,
            int paidChargeCount,
            BigDecimal totalPaid,
            BigDecimal refundedAmount,
            List<DeptTotal> byDept,
            List<MethodTotal> byPaymentMethod) {

        public record DeptTotal(UUID deptId, String deptCode, String deptName,
                                int visitCount, BigDecimal amount) {
        }

        public record MethodTotal(String paymentMethod, int chargeCount, BigDecimal amount) {
        }
    }

    /**
     * 门诊实收趋势（G13）：按收款日聚合，缺日补 0；并返回上一等长周期的合计用于环比。
     *
     * <p>口径与日结一致：按收款时刻归日，退费单独列出、不与实收相抵。
     */
    public record OutpatientTrendView(
            LocalDate from,
            LocalDate to,
            List<OutpatientTrendPoint> points,
            long totalVisits,
            int totalPaidCharges,
            BigDecimal totalPaid,
            BigDecimal totalRefunded,
            long prevTotalVisits,
            BigDecimal prevTotalPaid) {
    }

    public record OutpatientTrendPoint(LocalDate date, int visitCount, int paidChargeCount,
                                       BigDecimal totalPaid, BigDecimal refundedAmount) {
    }

    /**
     * 住院周转趋势（G16）：按业务日聚合入/出院、在院人数与出院者平均住院日。
     *
     * <p>{@code currentInHospital} 取区间最后一天结束时刻的在院人数；
     * {@code bedOccupancyPercent} = 在院人数 / 可用床位数，保留 1 位小数。
     */
    public record InpatientTrendView(
            LocalDate from,
            LocalDate to,
            List<InpatientTrendPoint> points,
            int totalAdmitted,
            int totalDischarged,
            int currentInHospital,
            int usableBeds,
            BigDecimal bedOccupancyPercent,
            BigDecimal avgStayDays,
            int prevAdmitted,
            int prevDischarged) {
    }

    public record InpatientTrendPoint(LocalDate date, int admittedCount, int dischargedCount,
                                      int inHospitalCount, BigDecimal avgStayDays) {
    }

    /**
     * 运营日快照（G17）：单日单指标。
     *
     * <p>{@code detail} 是 JSON 文本（如床位使用率的可用床位/在院人数），无上下文时为 null；
     * {@code source} 为 SCHEDULED（定时）或 MANUAL（手动补采）。
     */
    public record SnapshotView(LocalDate date, String metric, BigDecimal value, String detail,
                               String source, Instant updatedAt) {
    }

    public record SnapshotListView(LocalDate from, LocalDate to, List<SnapshotView> snapshots) {
    }

    public record SnapshotCaptureView(LocalDate date, int metrics, String source) {
    }
}
