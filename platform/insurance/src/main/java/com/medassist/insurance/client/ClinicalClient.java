package com.medassist.insurance.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 临床域收费明细查询端口：结算清单只认临床的收费数据。 */
public interface ClinicalClient {

    /** 收费明细行（来自临床域，金额单位为元）。 */
    record ChargeLine(String chargeNo, String itemCode, String itemName, String category,
                      BigDecimal amount, String chargeStatus) {
    }

    /**
     * 一次就诊的收费汇总：患者主键 + 业务日期 + 明细行。
     *
     * <p>{@code serviceDate} 是就诊业务日期（门诊挂号日 / 住院出院日），医保用它校验参保有效期
     * 与年度封顶归属；旧版临床接口不返回时为空，调用方回退到业务"今天"。
     */
    record ChargeSummary(UUID visitId, UUID patientId, String patientNo, LocalDate serviceDate,
                         UUID campusId, List<ChargeLine> lines) {

        public ChargeSummary(UUID visitId, UUID patientId, String patientNo, List<ChargeLine> lines) {
            this(visitId, patientId, patientNo, null, null, lines);
        }

        /** 兼容旧调用（未取院区的直接构造）：院区为空时调用方回退主院区。 */
        public ChargeSummary(UUID visitId, UUID patientId, String patientNo, LocalDate serviceDate,
                             List<ChargeLine> lines) {
            this(visitId, patientId, patientNo, serviceDate, null, lines);
        }
    }

    /** 诊断（ICD-10）。 */
    record Diagnosis(String code, String name, boolean primary) {
    }

    /** 就诊诊断汇总；无诊断时为空列表。 */
    record DiagnosisSummary(UUID visitId, List<Diagnosis> diagnoses) {
    }

    /**
     * 就诊实收汇总（三方对账用）。
     *
     * <p>{@code paidAmount} 是收费单 PAID 的金额，{@code unpaidAmount} 是应收未收，
     * {@code refundedAmount} 是已退费；{@code receiptAmount} 仅用于凭证核对。
     * 金额单位为元，口径由临床域计算，医保域只做比对，不在此重算。
     */
    record ClinicCollection(UUID visitId, LocalDate serviceDate, BigDecimal totalAmount,
                            BigDecimal paidAmount, BigDecimal unpaidAmount,
                            BigDecimal refundedAmount, BigDecimal receiptAmount,
                            int receiptCount) {
    }

    /** 期间内实收汇总；{@code truncated} 表示达到单批上限，调用方必须拒绝出结论。 */
    record Collections(List<ClinicCollection> items, boolean truncated) {
    }

    ChargeSummary charges(UUID visitId);

    /**
     * 拉取就诊诊断（结算清单头的主诊断与其他诊断）。
     *
     * <p>临床不可达时与 {@link #charges} 一样抛 503：清单缺诊断会导致报盘退回。
     */
    DiagnosisSummary diagnoses(UUID visitId);

    /**
     * 按期间拉取就诊实收汇总（三方对账的第二腿）。
     *
     * <p>临床不可达时必须抛 503，不能返回空集合——把"读不到实收"当成
     * "实收为 0"会批量制造假差异，比不做对账更危险。
     */
    Collections collections(LocalDate from, LocalDate to);
}
