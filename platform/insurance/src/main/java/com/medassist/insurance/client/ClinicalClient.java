package com.medassist.insurance.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** 临床域收费明细查询端口：结算清单只认临床的收费数据。 */
public interface ClinicalClient {

    /** 收费明细行（来自临床域，金额单位为元）。 */
    record ChargeLine(String chargeNo, String itemCode, String itemName, String category,
                      BigDecimal amount, String chargeStatus) {
    }

    /** 一次就诊的收费汇总：患者主键 + 明细行。 */
    record ChargeSummary(UUID visitId, UUID patientId, String patientNo, List<ChargeLine> lines) {
    }

    ChargeSummary charges(UUID visitId);
}
