package com.medassist.insurance.grouper;

import com.medassist.insurance.domain.InsuranceModel.GrouperAdrgRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcExclusion;
import com.medassist.insurance.domain.InsuranceModel.GrouperDipCatalog;
import com.medassist.insurance.domain.InsuranceModel.GrouperDrgDef;
import com.medassist.insurance.domain.InsuranceModel.GrouperMdcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperRuleSet;
import com.medassist.insurance.domain.InsuranceModel.PaymentScheme;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 本地可配置分组引擎（§263）：MDC → ADRG → CC/MCC → DRG，或 DIP 病种匹配。
 *
 * <p>与 V7 的"ICD 前缀 + 优先级"模拟不同，这里表达的是支付分组层级：
 * <ol>
 *   <li><b>MDC</b>：主诊断落在规则区间内（字典序 [from, to]）；</li>
 *   <li><b>ADRG</b>：同 MDC 内按 priority 取首个命中（手术/诊断/两者）；</li>
 *   <li><b>严重程度</b>：次要诊断命中 MCC 规则优先于 CC；命中排除表的不计入；</li>
 *   <li><b>DRG</b>：ADRG + 严重程度取细分组权重；缺该严重程度定义时回落到 NONE，
 *       连 NONE 都没有则显式未入组（不猜一个相邻组）。</li>
 * </ol>
 *
 * <p>规则来自院方导入的规则包，引擎本身不含任何国家/省局规则数据；
 * 未命中原因必须写清楚，便于病案质控定位"是编码问题还是规则包缺项"。
 */
public final class LocalGrouperEngine {

    public GrouperOutcome group(GrouperRuleSet ruleSet, GrouperRuleSetBundle bundle,
                                GrouperInput input) {
        List<Map<String, Object>> trace = new ArrayList<>();
        if (PaymentScheme.DIP.equals(ruleSet.type())) {
            return groupDip(input, bundle, trace);
        }
        return groupDrg(input, bundle, trace);
    }

    // ---------------------------------------------------------------- DRG

    private GrouperOutcome groupDrg(GrouperInput input, GrouperRuleSetBundle bundle,
                                    List<Map<String, Object>> trace) {
        String principal = input.principalDiagnosis();
        List<String> secondary = input.secondaryDiagnoses();
        List<String> procedures = input.procedures();

        GrouperMdcRule mdc = bundle.mdcRules().stream()
                .filter(GrouperMdcRule::enabled)
                .filter(rule -> inRange(principal, rule.diagFrom(), rule.diagTo()))
                .findFirst().orElse(null);
        if (mdc == null) {
            trace.add(step("MDC", Map.of("matched", false, "principal", principal)));
            return unmatched(PaymentScheme.DRG,
                    "主诊断 " + principal + " 不在该规则包的 MDC 归并区间内", trace);
        }
        Map<String, Object> mdcStep = step("MDC", Map.of("matched", true,
                "mdcCode", mdc.mdcCode(), "mdcName", mdc.mdcName(),
                "diagFrom", mdc.diagFrom(), "diagTo", mdc.diagTo()));
        trace.add(mdcStep);

        GrouperAdrgRule adrg = bundle.adrgRules().stream()
                .filter(GrouperAdrgRule::enabled)
                .filter(rule -> Objects.equals(rule.mdcCode(), mdc.mdcCode()))
                .filter(rule -> matches(rule, principal, secondary, procedures))
                .findFirst().orElse(null);
        if (adrg == null) {
            trace.add(step("ADRG", Map.of("matched", false, "mdcCode", mdc.mdcCode())));
            return unmatched(PaymentScheme.DRG,
                    "MDC " + mdc.mdcCode() + " 内没有 ADRG 规则命中（检查手术/诊断编码）", trace);
        }
        trace.add(step("ADRG", Map.of("matched", true, "adrgCode", adrg.adrgCode(),
                "adrgName", adrg.adrgName(), "matchType", adrg.matchType())));

        String severity = severity(bundle, principal, secondary, trace);

        GrouperDrgDef def = findDrg(bundle, adrg.adrgCode(), severity);
        String effectiveSeverity = severity;
        if (def == null && !"NONE".equals(severity)) {
            def = findDrg(bundle, adrg.adrgCode(), "NONE");
            if (def != null) {
                effectiveSeverity = "NONE";
                trace.add(step("SEVERITY_FALLBACK", Map.of("requested", severity,
                        "applied", "NONE", "reason", "该 ADRG 未配置对应严重程度细分组")));
            }
        }
        if (def == null) {
            trace.add(step("DRG", Map.of("matched", false, "adrgCode", adrg.adrgCode(),
                    "severity", severity)));
            return unmatched(PaymentScheme.DRG,
                    "ADRG " + adrg.adrgCode() + " 缺少 DRG 细分组定义（规则包不完整）", trace);
        }
        trace.add(step("DRG", Map.of("matched", true, "drgCode", def.drgCode(),
                "drgName", def.drgName(), "severity", effectiveSeverity,
                "weight", def.weight().toPlainString())));

        return new GrouperOutcome(PaymentScheme.DRG, mdc.mdcCode(), adrg.adrgCode(),
                def.drgCode(), null, effectiveSeverity, def.weight(), null, true,
                "命中 MDC " + mdc.mdcCode() + " / ADRG " + adrg.adrgCode()
                        + " / DRG " + def.drgCode(), trace);
    }

    private static GrouperDrgDef findDrg(GrouperRuleSetBundle bundle, String adrgCode,
                                         String severity) {
        return bundle.drgDefs().stream()
                .filter(def -> Objects.equals(def.adrgCode(), adrgCode)
                        && Objects.equals(def.severity(), severity))
                .findFirst().orElse(null);
    }

    /**
     * 严重程度：MCC 优先于 CC；被排除表命中的次要诊断不计入。
     *
     * <p>排除表按"主诊断前缀 + 次要诊断前缀"匹配：同一份规则包里，
     * 主诊断已经解释了某并发症时，该次要诊断不再升级 DRG。
     */
    private static String severity(GrouperRuleSetBundle bundle, String principal,
                                   List<String> secondary, List<Map<String, Object>> trace) {
        List<String> effective = new ArrayList<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        for (String code : secondary) {
            if (code.equals(principal)) {
                continue;
            }
            GrouperCcExclusion hit = bundle.ccExclusions().stream()
                    .filter(ex -> startsWith(principal, ex.primaryDiagPrefix())
                            && startsWith(code, ex.secondaryDiagPrefix()))
                    .findFirst().orElse(null);
            if (hit == null) {
                effective.add(code);
            } else {
                excluded.add(Map.of("code", code, "primaryPrefix", hit.primaryDiagPrefix(),
                        "secondaryPrefix", hit.secondaryDiagPrefix()));
            }
        }
        List<Map<String, Object>> ccHits = new ArrayList<>();
        String severity = "NONE";
        for (var rule : bundle.ccRules()) {
            boolean hit = effective.stream().anyMatch(code -> startsWith(code, rule.diagPrefix()));
            if (!hit) {
                continue;
            }
            ccHits.add(Map.of("code", rule.diagPrefix(), "severity", rule.severity()));
            if ("MCC".equals(rule.severity())) {
                severity = "MCC";
            } else if (!"MCC".equals(severity)) {
                severity = "CC";
            }
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("step", "SEVERITY");
        step.put("severity", severity);
        step.put("ccHits", ccHits);
        step.put("excluded", excluded);
        trace.add(step);
        return severity;
    }

    // ---------------------------------------------------------------- DIP

    private GrouperOutcome groupDip(GrouperInput input, GrouperRuleSetBundle bundle,
                                    List<Map<String, Object>> trace) {
        String principal = input.principalDiagnosis();
        String primaryProcedure = input.procedures().isEmpty() ? null : input.procedures().get(0);
        List<GrouperDipCatalog> catalog = bundle.dipCatalog();

        GrouperDipCatalog match = null;
        String matchMode = null;
        if (primaryProcedure != null) {
            match = catalog.stream()
                    .filter(item -> GrouperDipCatalog.CORE.equals(item.type())
                            && Objects.equals(item.diagCode(), principal)
                            && Objects.equals(item.procCode(), primaryProcedure))
                    .findFirst().orElse(null);
            matchMode = match == null ? null : "DIAG_AND_PROC";
        }
        if (match == null) {
            match = catalog.stream()
                    .filter(item -> Objects.equals(item.diagCode(), principal)
                            && item.procCode() == null)
                    .findFirst().orElse(null);
            matchMode = match == null ? null : "DIAG_ONLY";
        }
        if (match == null) {
            // 综合病种：诊断前缀兜底，取最长匹配（最具体的规则优先）
            match = catalog.stream()
                    .filter(item -> GrouperDipCatalog.COMPOSITE.equals(item.type())
                            && startsWith(principal, item.diagCode()))
                    .max(Comparator.comparingInt(item -> item.diagCode().length()))
                    .orElse(null);
            matchMode = match == null ? null : "COMPOSITE";
        }
        if (match == null) {
            trace.add(step("DIP", Map.of("matched", false, "principal", principal,
                    "primaryProcedure", primaryProcedure == null ? "" : primaryProcedure)));
            return unmatched(PaymentScheme.DIP,
                    "主诊断 " + principal + " 无 DIP 病种匹配（核心病种与综合病种均未命中）", trace);
        }
        trace.add(step("DIP", Map.of("matched", true, "matchMode", matchMode,
                "dipCode", match.dipCode(), "dipName", match.dipName(),
                "score", match.score().toPlainString())));
        return new GrouperOutcome(PaymentScheme.DIP, null, null, null, match.dipCode(), null,
                null, match.score(), true,
                "命中 DIP 病种 " + match.dipCode() + "（" + matchMode + "）", trace);
    }

    // ---------------------------------------------------------------- 工具

    private static boolean matches(GrouperAdrgRule rule, String principal, List<String> secondary,
                                   List<String> procedures) {
        List<String> allDiagnoses = new ArrayList<>();
        allDiagnoses.add(principal);
        allDiagnoses.addAll(secondary);
        return switch (rule.matchType()) {
            case "PROCEDURE" -> procedures.stream()
                    .anyMatch(code -> startsWith(code, rule.procPrefix()));
            case "DIAGNOSIS" -> allDiagnoses.stream()
                    .anyMatch(code -> startsWith(code, rule.diagPrefix()));
            case "DIAG_AND_PROC" -> allDiagnoses.stream()
                    .anyMatch(code -> startsWith(code, rule.diagPrefix()))
                    && procedures.stream().anyMatch(code -> startsWith(code, rule.procPrefix()));
            default -> false;
        };
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && prefix != null
                && value.toUpperCase(Locale.ROOT).startsWith(prefix.toUpperCase(Locale.ROOT));
    }

    /** 字典序区间匹配：from ≤ code ≤ to（含端点，编码统一大写比较）。 */
    private static boolean inRange(String code, String from, String to) {
        if (code == null || from == null || to == null) {
            return false;
        }
        String value = code.toUpperCase(Locale.ROOT);
        return value.compareTo(from.toUpperCase(Locale.ROOT)) >= 0
                && value.compareTo(to.toUpperCase(Locale.ROOT)) <= 0;
    }

    private static Map<String, Object> step(String name, Map<String, Object> detail) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step", name);
        map.putAll(detail);
        return map;
    }

    private static GrouperOutcome unmatched(String type, String reason,
                                            List<Map<String, Object>> trace) {
        return new GrouperOutcome(type, null, null, null, null, null, null, null,
                false, reason, trace);
    }
}
