package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.GroupSimulation;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroup;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroupRule;
import com.medassist.insurance.domain.InsuranceModel.PaymentScheme;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.CreateSchemeReq;
import com.medassist.insurance.web.InsuranceDtos.GroupImportResult;
import com.medassist.insurance.web.InsuranceDtos.GroupReq;
import com.medassist.insurance.web.InsuranceDtos.RuleImportResult;
import com.medassist.insurance.web.InsuranceDtos.RuleReq;
import com.medassist.insurance.web.InsuranceDtos.SimulateReq;
import com.medassist.insurance.web.InsuranceDtos.UpdateSchemeReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * DRG/DIP 支付方案配置与院内**模拟预分组**（N5 第二批 C）。
 *
 * <p>边界（必须与省局真实分组区分）：
 * <ul>
 *   <li>权重/分值/费率来自省局下发，本服务只做院内配置、校验与测算；</li>
 *   <li>入组规则是"ICD 前缀 + 优先级"的简化规则，用于预算测算与病案质控提示，
 *       **不是**官方 MDC/ADRG 分组器；</li>
 *   <li>每次模拟固化输入、命中规则、权重、费率与支付标准，可追溯"当时为什么算出这个数"；</li>
 *   <li>配置错误（命中的分组已停用）不静默改走其它分组，而是给出未入组原因——避免错误口径被掩盖。</li>
 * </ul>
 */
@Service
public class PaymentGroupingService {

    private static final int MAX_HISTORY = 100;

    private final InsuranceRepository repo;
    private final ObjectMapper objectMapper;

    public PaymentGroupingService(InsuranceRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 方案

    public List<PaymentScheme> listSchemes() {
        return repo.listSchemes();
    }

    @Transactional
    public PaymentScheme createScheme(CreateSchemeReq req, String actor) {
        String code = req.code().trim();
        if (repo.findScheme(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "支付方案已存在：" + code);
        }
        BigDecimal rate = req.rateAmount().setScale(4, RoundingMode.HALF_UP);
        PaymentScheme scheme = new PaymentScheme(code, req.name().trim(), req.type(),
                rate, req.rateEffectiveFrom(), req.enabled() == null || req.enabled(),
                0L, actor, Instant.now());
        repo.insertScheme(scheme);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("type", scheme.type());
        detail.put("rateAmount", rate.toPlainString());
        detail.put("rateEffectiveFrom", scheme.rateEffectiveFrom().toString());
        audit(actor, "SCHEME_CREATE", code, detail);
        return repo.findScheme(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_SCHEME_NOT_FOUND));
    }

    @Transactional
    public PaymentScheme updateScheme(String code, UpdateSchemeReq req, String actor) {
        PaymentScheme before = requireScheme(code);
        BigDecimal rate = req.rateAmount().setScale(4, RoundingMode.HALF_UP);
        boolean enabled = req.enabled() == null || req.enabled();
        if (!repo.updateScheme(before.code(), req.name().trim(), rate, req.rateEffectiveFrom(),
                enabled, req.version(), actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, "支付方案已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", before.code());
        detail.put("before", schemeSnapshot(before));
        detail.put("after", schemeSnapshot(new PaymentScheme(before.code(), req.name().trim(),
                before.type(), rate, req.rateEffectiveFrom(), enabled, req.version() + 1,
                actor, Instant.now())));
        audit(actor, "SCHEME_UPDATE", before.code(), detail);
        return repo.findScheme(before.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_SCHEME_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 分组

    public List<PaymentGroup> listGroups(String schemeCode, Boolean enabled) {
        return repo.listGroups(requireScheme(schemeCode).code(), enabled);
    }

    /**
     * 导入分组（以文件为准）：内容未变的分组不写库、不升版本，重复导入同一份文件不产生噪音审计。
     */
    @Transactional
    public GroupImportResult importGroups(String schemeCode, List<GroupReq> reqs, String actor) {
        PaymentScheme scheme = requireEnabledScheme(schemeCode);
        Set<String> seen = new HashSet<>();
        for (GroupReq req : reqs) {
            String code = req.groupCode().trim();
            if (!seen.add(code)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "分组编码重复：" + code);
            }
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (GroupReq req : reqs) {
            String code = req.groupCode().trim();
            BigDecimal weight = req.weight().setScale(4, RoundingMode.HALF_UP);
            boolean enabled = req.enabled() == null || req.enabled();
            PaymentGroup before = repo.findGroup(scheme.code(), code).orElse(null);
            if (before != null && before.groupName().equals(req.groupName().trim())
                    && before.weight().compareTo(weight) == 0 && before.enabled() == enabled) {
                unchanged++;
                continue;
            }
            PaymentGroup after = new PaymentGroup(scheme.code(), code, req.groupName().trim(),
                    weight, enabled, before == null ? 0L : before.version(), Instant.now());
            repo.upsertGroup(after);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("schemeCode", scheme.code());
            detail.put("groupCode", code);
            detail.put("before", before == null ? null : groupSnapshot(before));
            detail.put("after", groupSnapshot(after));
            audit(actor, "GROUP_UPSERT", scheme.code() + "/" + code, detail);
            if (before == null) {
                created++;
            } else {
                updated++;
            }
        }
        audit(actor, "GROUP_IMPORT", scheme.code(), Map.of(
                "total", reqs.size(), "created", created, "updated", updated,
                "unchanged", unchanged));
        return new GroupImportResult(reqs.size(), created, updated, unchanged);
    }

    // ---------------------------------------------------------------- 入组规则

    public List<PaymentGroupRule> listRules(String schemeCode, Boolean enabled) {
        return repo.listRules(requireScheme(schemeCode).code(), enabled);
    }

    @Transactional
    public RuleImportResult importRules(String schemeCode, List<RuleReq> reqs, String actor) {
        PaymentScheme scheme = requireEnabledScheme(schemeCode);
        Set<Integer> priorities = new HashSet<>();
        for (RuleReq req : reqs) {
            if (!priorities.add(req.priority())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "规则优先级重复：" + req.priority());
            }
        }
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (RuleReq req : reqs) {
            String matchType = req.matchType();
            String diag = normalizePrefix(req.diagPrefix());
            String proc = normalizePrefix(req.procPrefix());
            validateRuleShape(matchType, diag, proc);
            String groupCode = req.groupCode().trim();
            PaymentGroup group = repo.findGroup(scheme.code(), groupCode)
                    .filter(PaymentGroup::enabled)
                    .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "分组不存在或已停用：" + groupCode));
            boolean enabled = req.enabled() == null || req.enabled();
            PaymentGroupRule before = repo.findRule(scheme.code(), req.priority()).orElse(null);
            if (before != null && before.matchType().equals(matchType)
                    && same(before.diagPrefix(), diag) && same(before.procPrefix(), proc)
                    && before.groupCode().equals(group.groupCode()) && before.enabled() == enabled) {
                unchanged++;
                continue;
            }
            PaymentGroupRule after = new PaymentGroupRule(UUID.randomUUID(), scheme.code(),
                    req.priority(), matchType, diag, proc, group.groupCode(), enabled, Instant.now());
            repo.upsertRule(after);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("schemeCode", scheme.code());
            detail.put("priority", req.priority());
            detail.put("before", before == null ? null : ruleSnapshot(before));
            detail.put("after", ruleSnapshot(after));
            audit(actor, "RULE_UPSERT", scheme.code() + "/" + req.priority(), detail);
            if (before == null) {
                created++;
            } else {
                updated++;
            }
        }
        audit(actor, "RULE_IMPORT", scheme.code(), Map.of(
                "total", reqs.size(), "created", created, "updated", updated,
                "unchanged", unchanged));
        return new RuleImportResult(reqs.size(), created, updated, unchanged);
    }

    private void validateRuleShape(String matchType, String diag, String proc) {
        boolean ok = switch (matchType) {
            case PaymentGroupRule.DIAGNOSIS -> diag != null && proc == null;
            case PaymentGroupRule.PROCEDURE -> proc != null && diag == null;
            case PaymentGroupRule.DIAG_AND_PROC -> diag != null && proc != null;
            default -> false;
        };
        if (!ok) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "规则类型与匹配前缀不匹配：DIAGNOSIS 只要诊断前缀、PROCEDURE 只要手术前缀、"
                            + "DIAG_AND_PROC 两者都要");
        }
    }

    // ---------------------------------------------------------------- 模拟预分组

    /**
     * 模拟预分组：按优先级取首个命中规则，计算"权重 × 费率"。
     *
     * <p>未命中或命中的分组已停用时同样落库（matched=false + reason），
     * 保证质控能发现"有病案但入不了组"的情况，而不是查不到记录。
     */
    @Transactional
    public GroupSimulation simulate(SimulateReq req, String actor) {
        PaymentScheme scheme = requireEnabledScheme(req.schemeCode());
        List<String> diagnoses = normalizeCodes(req.diagnoses());
        List<String> procedures = normalizeCodes(req.procedures());

        PaymentGroup matched = null;
        String reason = null;
        for (PaymentGroupRule rule : repo.listRules(scheme.code(), true)) {
            if (!matches(rule, diagnoses, procedures)) {
                continue;
            }
            PaymentGroup group = repo.findGroup(scheme.code(), rule.groupCode()).orElse(null);
            if (group == null || !group.enabled()) {
                // 配置错误必须显式暴露，不能悄悄改走低优先级分组
                reason = "优先级 " + rule.priority() + " 的规则命中了不存在或已停用的分组："
                        + rule.groupCode();
                break;
            }
            matched = group;
            reason = "命中优先级 " + rule.priority() + " 的规则";
            break;
        }
        if (matched == null && reason == null) {
            reason = "未命中任何入组规则";
        }
        BigDecimal payment = matched == null ? null
                : matched.weight().multiply(scheme.rateAmount()).setScale(2, RoundingMode.HALF_UP);
        String diagnosesJson = toJson(diagnoses);
        String proceduresJson = toJson(procedures);
        GroupSimulation simulation = new GroupSimulation(UUID.randomUUID(), req.visitId(),
                scheme.code(), matched == null ? null : matched.groupCode(), matched != null,
                reason, matched == null ? null : matched.weight(), scheme.rateAmount(), payment,
                diagnosesJson, proceduresJson, inputHash(scheme.code(), diagnoses, procedures),
                actor, Instant.now());
        repo.insertSimulation(simulation);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("visitId", req.visitId().toString());
        detail.put("schemeCode", scheme.code());
        detail.put("groupCode", simulation.groupCode());
        detail.put("matched", simulation.matched());
        detail.put("paymentStandard", payment == null ? null : payment.toPlainString());
        detail.put("inputHash", simulation.inputHash());
        audit(actor, "GROUP_SIMULATE", req.visitId().toString(), detail);
        return simulation;
    }

    /**
     * 最近一次模拟结果。
     *
     * <p>返回值含就诊级诊断/手术编码（轻度 PHI），读取动作留痕：与病历检索同口径（第四轮 SF-15）。
     */
    public GroupSimulation latestSimulation(UUID visitId, String actor) {
        GroupSimulation simulation = repo.findLatestSimulation(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_GROUP_NOT_FOUND,
                        "该就诊暂无分组模拟记录"));
        auditSimView(actor, visitId, 1);
        return simulation;
    }

    public List<GroupSimulation> simulations(UUID visitId, Integer limit, String actor) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, MAX_HISTORY);
        List<GroupSimulation> history = repo.listSimulations(visitId, size);
        auditSimView(actor, visitId, history.size());
        return history;
    }

    private void auditSimView(String actor, UUID visitId, int count) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("visitId", visitId.toString());
        detail.put("count", count);
        audit(actor, "GROUP_SIM_VIEW", visitId.toString(), detail);
    }

    private boolean matches(PaymentGroupRule rule, List<String> diagnoses, List<String> procedures) {
        boolean diagHit = rule.diagPrefix() == null || diagnoses.stream()
                .anyMatch(code -> code.startsWith(rule.diagPrefix()));
        boolean procHit = rule.procPrefix() == null || procedures.stream()
                .anyMatch(code -> code.startsWith(rule.procPrefix()));
        return diagHit && procHit;
    }

    /** 编码归一化：去空白、转大写、去重、排序（保证 input_hash 可复现）。 */
    private static List<String> normalizeCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (String code : codes) {
            if (code == null) {
                continue;
            }
            String value = code.trim().toUpperCase(Locale.ROOT);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        return prefix.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---------------------------------------------------------------- 内部

    private PaymentScheme requireScheme(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.INSURANCE_SCHEME_NOT_FOUND);
        }
        return repo.findScheme(code.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_SCHEME_NOT_FOUND));
    }

    private PaymentScheme requireEnabledScheme(String code) {
        PaymentScheme scheme = requireScheme(code);
        if (!scheme.enabled()) {
            throw new BusinessException(ErrorCode.INSURANCE_SCHEME_INVALID,
                    "支付方案已停用：" + scheme.code());
        }
        return scheme;
    }

    private static Map<String, Object> schemeSnapshot(PaymentScheme s) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", s.name());
        snapshot.put("type", s.type());
        snapshot.put("rateAmount", s.rateAmount() == null ? null : s.rateAmount().toPlainString());
        snapshot.put("rateEffectiveFrom", s.rateEffectiveFrom() == null
                ? null : s.rateEffectiveFrom().toString());
        snapshot.put("enabled", s.enabled());
        return snapshot;
    }

    private static Map<String, Object> groupSnapshot(PaymentGroup g) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("groupName", g.groupName());
        snapshot.put("weight", g.weight() == null ? null : g.weight().toPlainString());
        snapshot.put("enabled", g.enabled());
        return snapshot;
    }

    private static Map<String, Object> ruleSnapshot(PaymentGroupRule r) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("matchType", r.matchType());
        snapshot.put("diagPrefix", r.diagPrefix());
        snapshot.put("procPrefix", r.procPrefix());
        snapshot.put("groupCode", r.groupCode());
        snapshot.put("enabled", r.enabled());
        return snapshot;
    }

    private String toJson(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes);
        } catch (JacksonException e) {
            throw new IllegalStateException("编码列表序列化失败", e);
        }
    }

    private static String inputHash(String schemeCode, List<String> diagnoses, List<String> procedures) {
        String canonical = schemeCode + "|" + String.join(",", diagnoses)
                + "|" + String.join(",", procedures);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "PAYMENT_GROUPING", targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "PAYMENT_GROUPING", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
