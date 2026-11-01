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
                         List<ChargeLine> lines) {

        public ChargeSummary(UUID visitId, UUID patientId, String patientNo, List<ChargeLine> lines) {
            this(visitId, patientId, patientNo, null, lines);
        }
    }

    /** 诊断（ICD-10）。 */
    record Diagnosis(String code, String name, boolean primary) {
    }

    /** 就诊诊断汇总；无诊断时为空列表。 */
    record DiagnosisSummary(UUID visitId, List<Diagnosis> diagnoses) {
    }

    ChargeSummary charges(UUID visitId);

    /**
     * 拉取就诊诊断（结算清单头的主诊断与其他诊断）。
     *
     * <p>临床不可达时与 {@link #charges} 一样抛 503：清单缺诊断会导致报盘退回。
     */
    DiagnosisSummary diagnoses(UUID visitId);
}
