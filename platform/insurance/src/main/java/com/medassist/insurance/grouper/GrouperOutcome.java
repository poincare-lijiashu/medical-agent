package com.medassist.insurance.grouper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 分组结果（引擎输出）：不含费率与落库信息。
 *
 * @param trace 命中轨迹（MDC→ADRG→CC/MCC→DRG，或 DIP 病种匹配），按顺序可解释
 */
public record GrouperOutcome(String type, String mdcCode, String adrgCode, String drgCode,
                             String dipCode, String severity, BigDecimal weight, BigDecimal score,
                             boolean matched, String reason, List<Map<String, Object>> trace) {
}
