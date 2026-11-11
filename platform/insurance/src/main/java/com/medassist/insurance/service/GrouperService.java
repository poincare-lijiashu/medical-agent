package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.insurance.client.ClinicalClient;
import com.medassist.insurance.client.EmrClient;
import com.medassist.insurance.config.GrouperProperties;
import com.medassist.insurance.domain.InsuranceModel.GrouperAdrgRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcExclusion;
import com.medassist.insurance.domain.InsuranceModel.GrouperCcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperDipCatalog;
import com.medassist.insurance.domain.InsuranceModel.GrouperDrgDef;
import com.medassist.insurance.domain.InsuranceModel.GrouperMdcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperResult;
import com.medassist.insurance.domain.InsuranceModel.GrouperRuleSet;
import com.medassist.insurance.grouper.GrouperInput;
import com.medassist.insurance.grouper.GrouperOutcome;
import com.medassist.insurance.grouper.GrouperRuleSetBundle;
import com.medassist.insurance.grouper.LocalGrouperEngine;
import com.medassist.insurance.grouper.RemoteGrouperClient;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.GrouperDtos.AdrgRuleReq;
import com.medassist.insurance.web.GrouperDtos.CcExclusionReq;
import com.medassist.insurance.web.GrouperDtos.CcRuleReq;
import com.medassist.insurance.web.GrouperDtos.DipCatalogReq;
import com.medassist.insurance.web.GrouperDtos.DrgDefReq;
import com.medassist.insurance.web.GrouperDtos.ImportRuleSetReq;
import com.medassist.insurance.web.GrouperDtos.ImportRuleSetResult;
import com.medassist.insurance.web.GrouperDtos.MdcRuleReq;
import com.medassist.insurance.web.GrouperDtos.OfficialReq;
import com.medassist.insurance.web.GrouperDtos.PreviewReq;
import com.medassist.insurance.web.GrouperDtos.ResultRef;
import com.medassist.insurance.web.GrouperDtos.SummaryView;
import com.medassist.insurance.web.GrouperDtos.VarianceView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.LocalDate;
import java.util.ArrayList;
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
 * DRG/DIP 可配置分组服务（§263）。
 *
 * <p>边界：
 * <ul>
 *   <li>规则包由院方导入（国家/省局/本地），平台不内置权威规则；</li>
 *   <li>预分组（LOCAL/REMOTE）与省局正式结果（OFFICIAL）分表存储、可比较；</li>
 *   <li>远端分组器失败默认不回落本地，避免两种口径悄悄混用；</li>
 *   <li>每次分组固化规则包版本、输入哈希与命中轨迹，可回溯"为什么是这个组"。</li>
 * </ul>
 */
@Service
public class GrouperService {

    private static final Logger log = LoggerFactory.getLogger(GrouperService.class);

    private static final Set<String> TYPES = Set.of("DRG", "DIP");
    private static final Set<String> SOURCES = Set.of("NATIONAL", "PROVINCIAL", "LOCAL", "VENDOR");
    private static final Set<String> MATCH_TYPES = Set.of("PROCEDURE", "DIAGNOSIS", "DIAG_AND_PROC");
    private static final Set<String> SEVERITIES = Set.of("NONE", "CC", "MCC");
    private static final Set<String> ENGINES = Set.of(GrouperResult.ENGINE_LOCAL,
            GrouperResult.ENGINE_REMOTE, GrouperResult.ENGINE_OFFICIAL);
    private static final int MAX_HISTORY = 100;

    private final InsuranceRepository repo;
    private final ClinicalClient clinicalClient;
    private final EmrClient emrClient;
    private final RemoteGrouperClient remoteClient;
    private final GrouperProperties properties;
    private final ObjectMapper objectMapper;
    private final LocalGrouperEngine localEngine = new LocalGrouperEngine();

    public GrouperService(InsuranceRepository repo, ClinicalClient clinicalClient,
                          EmrClient emrClient, RemoteGrouperClient remoteClient,
                          GrouperProperties properties, ObjectMapper objectMapper) {
        this.repo = repo;
        this.clinicalClient = clinicalClient;
        this.emrClient = emrClient;
        this.remoteClient = remoteClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 规则包

    public List<GrouperRuleSet> listRuleSets(String type) {
        String normalized = type == null || type.isBlank() ? null : requireType(type);
        return repo.listRuleSets(normalized);
    }

    /**
     * 导入规则包：整包校验通过才落库，激活时同 code 旧版本自动 RETIRED。
     */
    @Transactional
    public ImportRuleSetResult importRuleSet(ImportRuleSetReq req, String actor) {
        String type = requireType(req.type());
        String source = requireSource(req.source());
        String code = req.code().trim();
        String version = req.version().trim();
        if (repo.findRuleSet(code, version).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "规则包 " + code + " 版本 " + version + " 已导入，如需调整请使用新版本号");
        }
        UUID ruleSetId = UUID.randomUUID();
        List<GrouperMdcRule> mdcRules = toMdcRules(ruleSetId, req.mdcRules());
        List<GrouperAdrgRule> adrgRules = toAdrgRules(ruleSetId, req.adrgRules());
        List<GrouperDrgDef> drgDefs = toDrgDefs(ruleSetId, type, req.drgDefs());
        List<GrouperCcRule> ccRules = toCcRules(ruleSetId, req.ccRules());
        List<GrouperCcExclusion> ccExclusions = toCcExclusions(ruleSetId, req.ccExclusions());
        List<GrouperDipCatalog> dipCatalog = toDipCatalog(ruleSetId, type, req.dipCatalog());

        Set<String> mdcCodes = new HashSet<>(mdcRules.stream().map(GrouperMdcRule::mdcCode).toList());
        Set<String> adrgCodes = new HashSet<>(adrgRules.stream()
                .map(GrouperAdrgRule::adrgCode).toList());
        for (GrouperAdrgRule rule : adrgRules) {
            if (!mdcCodes.contains(rule.mdcCode())) {
                throw invalid("ADRG " + rule.adrgCode() + " 引用了不存在的 MDC " + rule.mdcCode());
            }
        }
        for (GrouperDrgDef def : drgDefs) {
            if (!adrgCodes.contains(def.adrgCode())) {
                throw invalid("DRG " + def.drgCode() + " 引用了不存在的 ADRG " + def.adrgCode());
            }
        }
        validateShape(type, mdcRules.size(), adrgRules.size(), drgDefs.size(), dipCatalog.size());

        String checksum = checksum(req, code, version, type, source);
        GrouperRuleSet ruleSet = new GrouperRuleSet(ruleSetId, code, version, req.name().trim(),
                type, req.effectiveFrom(), source, checksum, GrouperRuleSet.STATUS_DRAFT,
                actor, Instant.now(), null, null);
        repo.insertRuleSet(ruleSet);
        repo.insertMdcRules(mdcRules);
        repo.insertAdrgRules(adrgRules);
        repo.insertDrgDefs(drgDefs);
        repo.insertCcRules(ccRules);
        repo.insertCcExclusions(ccExclusions);
        repo.insertDipCatalog(dipCatalog);

        if (Boolean.TRUE.equals(req.activate())) {
            activateInternal(ruleSetId, code, actor);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", code);
        detail.put("version", version);
        detail.put("type", type);
        detail.put("source", source);
        detail.put("checksum", checksum);
        detail.put("mdcRules", mdcRules.size());
        detail.put("adrgRules", adrgRules.size());
        detail.put("drgDefs", drgDefs.size());
        detail.put("dipCatalog", dipCatalog.size());
        detail.put("activated", Boolean.TRUE.equals(req.activate()));
        audit(actor, "GROUP_RULE_IMPORT", code + "/" + version, detail);

        return new ImportRuleSetResult(ruleSetId, code, version,
                Boolean.TRUE.equals(req.activate()) ? GrouperRuleSet.STATUS_ACTIVE
                        : GrouperRuleSet.STATUS_DRAFT,
                mdcRules.size(), adrgRules.size(), drgDefs.size(), ccRules.size(),
                ccExclusions.size(), dipCatalog.size());
    }

    @Transactional
    public GrouperRuleSet activate(UUID id, String actor) {
        GrouperRuleSet ruleSet = requireRuleSet(id);
        if (GrouperRuleSet.STATUS_ACTIVE.equals(ruleSet.status())) {
            return ruleSet;
        }
        if (!GrouperRuleSet.STATUS_DRAFT.equals(ruleSet.status())) {
            throw invalid("只有 DRAFT 规则包可以激活，当前状态：" + ruleSet.status());
        }
        validateShape(ruleSet.type(), repo.listMdcRules(id).size(), repo.listAdrgRules(id).size(),
                repo.listDrgDefs(id).size(), repo.listDipCatalog(id).size());
        activateInternal(id, ruleSet.code(), actor);
        audit(actor, "GROUP_RULE_ACTIVATE", ruleSet.code() + "/" + ruleSet.version(),
                Map.of("ruleSetId", id.toString(), "type", ruleSet.type()));
        return requireRuleSet(id);
    }

    private void activateInternal(UUID id, String code, String actor) {
        repo.retireActiveRuleSets(code);
        if (!repo.activateRuleSet(id, actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, "规则包状态已变化，请刷新后重试");
        }
    }

    private void validateShape(String type, int mdcRules, int adrgRules, int drgDefs,
                               int dipCatalog) {
        if ("DRG".equals(type) && (mdcRules == 0 || adrgRules == 0 || drgDefs == 0)) {
            throw invalid("DRG 规则包至少需要 MDC、ADRG、DRG 细分组各一条");
        }
        if ("DIP".equals(type) && dipCatalog == 0) {
            throw invalid("DIP 规则包至少需要一条病种目录");
        }
    }

    // ---------------------------------------------------------------- 分组

    /**
     * 预分组：规则包 ACTIVE 才参与，LOCAL/REMOTE 由配置决定，结果固化落库。
     */
    @Transactional
    public GrouperResult preview(PreviewReq req, String actor) {
        String type = req.type() == null || req.type().isBlank() ? null : requireType(req.type());
        GrouperRuleSet ruleSet = resolveRuleSet(type, req.ruleSetCode(), req.ruleSetVersion());
        GrouperInput input = buildInput(req);

        GrouperOutcome outcome;
        String engine;
        if (GrouperProperties.REMOTE.equals(properties.engineOrDefault())) {
            try {
                outcome = remoteClient.group(ruleSet, input);
                engine = GrouperResult.ENGINE_REMOTE;
            } catch (BusinessException e) {
                if (!properties.fallbackLocalOrDefault()) {
                    throw e;
                }
                log.warn("grouper.remote.fallback ruleSet={} reason={}", ruleSet.code(),
                        e.getMessage());
                outcome = localEngine.group(ruleSet, bundle(ruleSet.id()), input);
                engine = GrouperResult.ENGINE_LOCAL;
            }
        } else {
            outcome = localEngine.group(ruleSet, bundle(ruleSet.id()), input);
            engine = GrouperResult.ENGINE_LOCAL;
        }

        BigDecimal payment = payment(req.schemeCode(), req.serviceDate(), outcome);
        GrouperResult result = new GrouperResult(UUID.randomUUID(), req.visitId(),
                ruleSet.id(), ruleSet.code(), ruleSet.version(), engine, outcome.type(),
                outcome.mdcCode(), outcome.adrgCode(), outcome.drgCode(), outcome.dipCode(),
                outcome.severity(), outcome.weight(), outcome.score(), payment,
                outcome.matched(), outcome.reason(), toJson(outcome.trace()),
                input.principalDiagnosis(), toJson(input.secondaryDiagnoses()),
                toJson(input.procedures()), inputHash(ruleSet, input), actor, Instant.now());
        repo.insertGrouperResult(result);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("visitId", req.visitId().toString());
        detail.put("ruleSet", ruleSet.code() + "/" + ruleSet.version());
        detail.put("engine", engine);
        detail.put("type", outcome.type());
        detail.put("matched", outcome.matched());
        detail.put("drgCode", outcome.drgCode());
        detail.put("dipCode", outcome.dipCode());
        detail.put("severity", outcome.severity());
        detail.put("weight", outcome.weight() == null ? null : outcome.weight().toPlainString());
        detail.put("score", outcome.score() == null ? null : outcome.score().toPlainString());
        detail.put("paymentStandard", payment == null ? null : payment.toPlainString());
        audit(actor, "GROUP_PREVIEW", req.visitId().toString(), detail);
        return result;
    }

    /**
     * 登记省局正式分组结果（人工或接口回传），只用于差异分析，不参与院内测算。
     */
    @Transactional
    public GrouperResult recordOfficial(OfficialReq req, String actor) {
        String type = requireType(req.type());
        if ((req.drgCode() == null || req.drgCode().isBlank())
                && (req.dipCode() == null || req.dipCode().isBlank())) {
            throw invalid("正式分组结果必须给出 drgCode 或 dipCode");
        }
        String principal = blankToNull(req.principalDiagnosis());
        if (principal == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "正式分组结果必须包含主诊断，否则差异分析没有基准");
        }
        List<String> diagnoses = normalizeCodes(req.diagnoses());
        List<String> procedures = normalizeCodes(req.procedures());
        String severity = blankToNull(req.severity());
        if (severity != null) {
            severity = severity.toUpperCase(Locale.ROOT);
            if (!SEVERITIES.contains(severity)) {
                throw invalid("severity 只能是 NONE / CC / MCC");
            }
        }
        List<Map<String, Object>> trace = List.of(Map.of("step", "OFFICIAL",
                "drgCode", req.drgCode() == null ? "" : req.drgCode(),
                "dipCode", req.dipCode() == null ? "" : req.dipCode()));
        GrouperResult result = new GrouperResult(UUID.randomUUID(), req.visitId(), null,
                blankToNull(req.ruleSetCode()), blankToNull(req.ruleSetVersion()),
                GrouperResult.ENGINE_OFFICIAL, type, blankToNull(req.mdcCode()),
                blankToNull(req.adrgCode()), blankToNull(req.drgCode()),
                blankToNull(req.dipCode()), severity, req.weight(), req.score(),
                req.paymentStandard(), true, blankToNull(req.reason()), toJson(trace),
                principal.toUpperCase(Locale.ROOT), toJson(diagnoses), toJson(procedures),
                inputHashOfficial(req, principal, diagnoses, procedures), actor, Instant.now());
        repo.insertGrouperResult(result);
        audit(actor, "GROUP_OFFICIAL_RECORD", req.visitId().toString(),
                Map.of("type", type, "drgCode", String.valueOf(req.drgCode()),
                        "dipCode", String.valueOf(req.dipCode())));
        return result;
    }

    public GrouperResult latest(UUID visitId, String engine, String actor) {
        String normalized = engine == null || engine.isBlank() ? null
                : engine.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && !ENGINES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "engine 只能是 LOCAL / REMOTE / OFFICIAL");
        }
        GrouperResult result = (normalized == null
                ? repo.findLatestGrouperResult(visitId, GrouperResult.ENGINE_LOCAL)
                        .or(() -> repo.findLatestGrouperResult(visitId,
                                GrouperResult.ENGINE_REMOTE))
                : repo.findLatestGrouperResult(visitId, normalized))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INSURANCE_GROUPER_RESULT_NOT_FOUND));
        audit(actor, "GROUP_RESULT_VIEW", visitId.toString(),
                Map.of("engine", String.valueOf(result.engine())));
        return result;
    }

    public List<GrouperResult> history(UUID visitId, Integer limit, String actor) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, MAX_HISTORY);
        List<GrouperResult> history = repo.listGrouperResults(visitId, size);
        audit(actor, "GROUP_RESULT_VIEW", visitId.toString(), Map.of("count", history.size()));
        return history;
    }

    /**
     * 差异分析：取最近一次院内预分组与最近一次省局正式结果，逐字段比较。
     */
    public VarianceView variance(UUID visitId, String actor) {
        GrouperResult pre = repo.findLatestGrouperResult(visitId, GrouperResult.ENGINE_LOCAL)
                .or(() -> repo.findLatestGrouperResult(visitId, GrouperResult.ENGINE_REMOTE))
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INSURANCE_GROUPER_RESULT_NOT_FOUND,
                        "该就诊暂无院内预分组结果，无法做差异分析"));
        GrouperResult official = repo.findLatestGrouperResult(visitId,
                        GrouperResult.ENGINE_OFFICIAL)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INSURANCE_GROUPER_RESULT_NOT_FOUND,
                        "该就诊暂无省局正式分组结果，无法做差异分析"));
        List<String> differences = new ArrayList<>();
        compare(differences, "type", pre.type(), official.type());
        compare(differences, "mdcCode", pre.mdcCode(), official.mdcCode());
        compare(differences, "adrgCode", pre.adrgCode(), official.adrgCode());
        compare(differences, "drgCode", pre.drgCode(), official.drgCode());
        compare(differences, "dipCode", pre.dipCode(), official.dipCode());
        compare(differences, "severity", pre.severity(), official.severity());
        compare(differences, "weight", pre.weight(), official.weight());
        compare(differences, "score", pre.score(), official.score());
        compare(differences, "paymentStandard", pre.paymentStandard(),
                official.paymentStandard());
        VarianceView view = new VarianceView(visitId, pre.type(), ref(pre), ref(official),
                differences.isEmpty(), differences);
        audit(actor, "GROUP_VARIANCE_VIEW", visitId.toString(),
                Map.of("consistent", view.consistent(), "differences", differences.size()));
        return view;
    }

    /**
     * 分组报表：最近 N 天的引擎/类型统计、入组 Top 病组与未入组原因 Top。
     *
     * <p>days 收敛到 [1, 366]、top 收敛到 [1, 50]：避免一次查询扫全表或把报表拉爆。
     */
    public SummaryView summary(int days, int top, String actor) {
        int window = Math.min(Math.max(days, 1), 366);
        int limit = Math.min(Math.max(top, 1), 50);
        Instant to = Instant.now();
        Instant from = to.minus(java.time.Duration.ofDays(window));
        SummaryView view = new SummaryView(from, to, repo.grouperEngineStats(from, to),
                repo.grouperTopGroups(from, to, limit), repo.grouperTopReasons(from, to, limit));
        audit(actor, "GROUP_REPORT_VIEW", "summary",
                Map.of("days", window, "top", limit, "engines", view.engines().size()));
        return view;
    }

    // ---------------------------------------------------------------- 内部

    private GrouperRuleSet resolveRuleSet(String type, String code, String version) {
        if (code != null && !code.isBlank() && version != null && !version.isBlank()) {
            return repo.findRuleSet(code.trim(), version.trim())
                    .filter(set -> GrouperRuleSet.STATUS_ACTIVE.equals(set.status()))
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.INSURANCE_GROUPER_RULE_SET_NOT_FOUND,
                            "规则包 " + code + " 版本 " + version + " 不存在或未激活"));
        }
        String normalizedCode = blankToNull(code);
        if (type == null) {
            if (normalizedCode == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "预分组必须指定 type 或 ruleSetCode");
            }
            return repo.findActiveByCode(normalizedCode)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.INSURANCE_GROUPER_RULE_SET_NOT_FOUND));
        }
        return repo.findActiveRuleSet(type, normalizedCode)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INSURANCE_GROUPER_RULE_SET_NOT_FOUND,
                        "没有生效中的 " + type + " 规则包" + (normalizedCode == null ? ""
                                : "（code=" + normalizedCode + "）")));
    }

    private GrouperRuleSetBundle bundle(UUID ruleSetId) {
        return new GrouperRuleSetBundle(repo.listMdcRules(ruleSetId), repo.listAdrgRules(ruleSetId),
                repo.listDrgDefs(ruleSetId), repo.listCcRules(ruleSetId),
                repo.listCcExclusions(ruleSetId), repo.listDipCatalog(ruleSetId));
    }

    private GrouperInput buildInput(PreviewReq req) {
        List<String> diagnoses;
        if (req.diagnoses() == null) {
            diagnoses = normalizeCodes(clinicalClient.diagnoses(req.visitId()).diagnoses().stream()
                    .map(ClinicalClient.Diagnosis::code).toList());
        } else {
            diagnoses = normalizeCodes(req.diagnoses());
        }
        String requested = blankToNull(req.principalDiagnosis());
        String principal = requested != null ? requested.toUpperCase(Locale.ROOT)
                : (diagnoses.isEmpty() ? null : diagnoses.get(0));
        if (principal == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "该就诊没有主诊断，无法分组（请先录入诊断或显式传入 principalDiagnosis）");
        }
        List<String> secondary = new ArrayList<>(diagnoses);
        secondary.removeIf(code -> code.equals(principal));
        List<String> procedures = req.procedures() == null
                ? normalizeCodes(emrClient.procedures(req.visitId()).codes())
                : normalizeCodes(req.procedures());
        return new GrouperInput(principal, List.copyOf(secondary), procedures);
    }

    private BigDecimal payment(String schemeCode, LocalDate serviceDate, GrouperOutcome outcome) {
        if (schemeCode == null || schemeCode.isBlank()) {
            return null;
        }
        BigDecimal basis = outcome.weight() != null ? outcome.weight() : outcome.score();
        if (basis == null) {
            return null;
        }
        LocalDate date = serviceDate == null ? BusinessTime.today() : serviceDate;
        BigDecimal rate = repo.findRateEffectiveOn(schemeCode.trim(), date)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INSURANCE_SCHEME_RATE_MISSING,
                        "所选日期 " + date + " 没有生效的费率，请先配置"))
                .rateAmount();
        return basis.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private GrouperRuleSet requireRuleSet(UUID id) {
        return repo.findRuleSet(id)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INSURANCE_GROUPER_RULE_SET_NOT_FOUND));
    }

    private static String requireType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(normalized)) {
            throw invalid("type 只能是 DRG / DIP");
        }
        return normalized;
    }

    private static String requireSource(String source) {
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(normalized)) {
            throw invalid("source 只能是 NATIONAL / PROVINCIAL / LOCAL / VENDOR");
        }
        return normalized;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INSURANCE_GROUPER_RULE_INVALID, message);
    }

    private static List<GrouperMdcRule> toMdcRules(UUID setId, List<MdcRuleReq> reqs) {
        if (reqs == null) {
            return List.of();
        }
        Set<Integer> priorities = new HashSet<>();
        List<GrouperMdcRule> rules = new ArrayList<>();
        for (MdcRuleReq req : reqs) {
            if (req.priority() <= 0 || !priorities.add(req.priority())) {
                throw invalid("MDC 规则优先级必须为正且不重复：" + req.priority());
            }
            String from = req.diagFrom().trim().toUpperCase(Locale.ROOT);
            String to = req.diagTo().trim().toUpperCase(Locale.ROOT);
            if (from.compareTo(to) > 0) {
                throw invalid("MDC " + req.mdcCode() + " 的诊断区间起止颠倒");
            }
            rules.add(new GrouperMdcRule(UUID.randomUUID(), setId, req.priority(),
                    req.mdcCode().trim(), req.mdcName().trim(), from, to,
                    req.enabled() == null || req.enabled()));
        }
        return rules;
    }

    private static List<GrouperAdrgRule> toAdrgRules(UUID setId, List<AdrgRuleReq> reqs) {
        if (reqs == null) {
            return List.of();
        }
        Set<Integer> priorities = new HashSet<>();
        List<GrouperAdrgRule> rules = new ArrayList<>();
        for (AdrgRuleReq req : reqs) {
            if (req.priority() <= 0 || !priorities.add(req.priority())) {
                throw invalid("ADRG 规则优先级必须为正且不重复：" + req.priority());
            }
            String matchType = req.matchType().trim().toUpperCase(Locale.ROOT);
            if (!MATCH_TYPES.contains(matchType)) {
                throw invalid("ADRG " + req.adrgCode() + " 的 matchType 不合法：" + matchType);
            }
            String diag = blankToNull(req.diagPrefix());
            String proc = blankToNull(req.procPrefix());
            boolean shape = switch (matchType) {
                case "PROCEDURE" -> proc != null && diag == null;
                case "DIAGNOSIS" -> diag != null && proc == null;
                default -> diag != null && proc != null;
            };
            if (!shape) {
                throw invalid("ADRG " + req.adrgCode() + " 的匹配前缀与 matchType 不匹配");
            }
            rules.add(new GrouperAdrgRule(UUID.randomUUID(), setId, req.priority(),
                    req.adrgCode().trim(), req.adrgName().trim(),
                    req.mdcCode().trim().toUpperCase(Locale.ROOT), matchType,
                    diag == null ? null : diag.toUpperCase(Locale.ROOT),
                    proc == null ? null : proc.toUpperCase(Locale.ROOT),
                    req.enabled() == null || req.enabled()));
        }
        return rules;
    }

    private static List<GrouperDrgDef> toDrgDefs(UUID setId, String type, List<DrgDefReq> reqs) {
        if (reqs == null) {
            return List.of();
        }
        if (!reqs.isEmpty() && !"DRG".equals(type)) {
            throw invalid("DIP 规则包不应包含 DRG 细分组定义");
        }
        Set<String> codes = new HashSet<>();
        List<GrouperDrgDef> defs = new ArrayList<>();
        for (DrgDefReq req : reqs) {
            String code = req.drgCode().trim();
            if (!codes.add(code)) {
                throw invalid("DRG 细分组编码重复：" + code);
            }
            String severity = req.severity().trim().toUpperCase(Locale.ROOT);
            if (!SEVERITIES.contains(severity)) {
                throw invalid("DRG " + code + " 的 severity 只能是 NONE / CC / MCC");
            }
            if (req.weight().signum() < 0) {
                throw invalid("DRG " + code + " 的权重不能为负");
            }
            defs.add(new GrouperDrgDef(setId, code, req.drgName().trim(),
                    req.adrgCode().trim().toUpperCase(Locale.ROOT), severity, req.weight()));
        }
        return defs;
    }

    private static List<GrouperCcRule> toCcRules(UUID setId, List<CcRuleReq> reqs) {
        if (reqs == null) {
            return List.of();
        }
        Set<String> keys = new HashSet<>();
        List<GrouperCcRule> rules = new ArrayList<>();
        for (CcRuleReq req : reqs) {
            String severity = req.severity().trim().toUpperCase(Locale.ROOT);
            if (!"CC".equals(severity) && !"MCC".equals(severity)) {
                throw invalid("CC 规则严重程度只能是 CC / MCC：" + severity);
            }
            String prefix = req.diagPrefix().trim().toUpperCase(Locale.ROOT);
            if (!keys.add(severity + "|" + prefix)) {
                throw invalid("CC 规则重复：" + severity + " " + prefix);
            }
            rules.add(new GrouperCcRule(UUID.randomUUID(), setId, severity, prefix));
        }
        return rules;
    }

    private static List<GrouperCcExclusion> toCcExclusions(UUID setId,
                                                           List<CcExclusionReq> reqs) {
        if (reqs == null) {
            return List.of();
        }
        Set<String> keys = new HashSet<>();
        List<GrouperCcExclusion> exclusions = new ArrayList<>();
        for (CcExclusionReq req : reqs) {
            String primary = req.primaryDiagPrefix().trim().toUpperCase(Locale.ROOT);
            String secondary = req.secondaryDiagPrefix().trim().toUpperCase(Locale.ROOT);
            if (!keys.add(primary + "|" + secondary)) {
                throw invalid("CC 排除规则重复：" + primary + " → " + secondary);
            }
            exclusions.add(new GrouperCcExclusion(UUID.randomUUID(), setId, primary, secondary));
        }
        return exclusions;
    }

    private static List<GrouperDipCatalog> toDipCatalog(UUID setId, String type,
                                                        List<DipCatalogReq> reqs) {
        if (reqs == null) {
            return List.of();
        }
        if (!reqs.isEmpty() && !"DIP".equals(type)) {
            throw invalid("DRG 规则包不应包含 DIP 病种目录");
        }
        Set<String> keys = new HashSet<>();
        List<GrouperDipCatalog> items = new ArrayList<>();
        for (DipCatalogReq req : reqs) {
            String dipType = req.type().trim().toUpperCase(Locale.ROOT);
            if (!GrouperDipCatalog.CORE.equals(dipType)
                    && !GrouperDipCatalog.COMPOSITE.equals(dipType)) {
                throw invalid("DIP 病种类型只能是 CORE / COMPOSITE：" + dipType);
            }
            String diag = req.diagCode().trim().toUpperCase(Locale.ROOT);
            String proc = blankToNull(req.procCode());
            String key = diag + "|" + (proc == null ? "-" : proc.toUpperCase(Locale.ROOT));
            if (!keys.add(key)) {
                throw invalid("DIP 病种重复：" + key);
            }
            if (req.score().signum() < 0) {
                throw invalid("DIP 病种 " + req.dipCode() + " 的分值不能为负");
            }
            items.add(new GrouperDipCatalog(UUID.randomUUID(), setId, req.dipCode().trim(),
                    req.dipName().trim(), diag,
                    proc == null ? null : proc.toUpperCase(Locale.ROOT), dipType, req.score()));
        }
        return items;
    }

    private String checksum(ImportRuleSetReq req, String code, String version, String type,
                            String source) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(code).append('|').append(version).append('|').append(req.name().trim())
                .append('|').append(type).append('|').append(req.effectiveFrom()).append('|')
                .append(source);
        appendMdc(canonical, req.mdcRules());
        appendAdrg(canonical, req.adrgRules());
        appendDrg(canonical, req.drgDefs());
        appendCc(canonical, req.ccRules());
        appendCcExclusion(canonical, req.ccExclusions());
        appendDip(canonical, req.dipCatalog());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void appendMdc(StringBuilder sb, List<MdcRuleReq> rules) {
        if (rules == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (MdcRuleReq r : rules) {
            lines.add("MDC|" + r.priority() + "|" + r.mdcCode() + "|" + r.diagFrom() + "|"
                    + r.diagTo() + "|" + (r.enabled() == null || r.enabled()));
        }
        lines.stream().sorted().forEach(line -> sb.append('\n').append(line));
    }

    private static void appendAdrg(StringBuilder sb, List<AdrgRuleReq> rules) {
        if (rules == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (AdrgRuleReq r : rules) {
            lines.add("ADRG|" + r.priority() + "|" + r.adrgCode() + "|" + r.mdcCode() + "|"
                    + r.matchType() + "|" + r.diagPrefix() + "|" + r.procPrefix() + "|"
                    + (r.enabled() == null || r.enabled()));
        }
        lines.stream().sorted().forEach(line -> sb.append('\n').append(line));
    }

    private static void appendDrg(StringBuilder sb, List<DrgDefReq> defs) {
        if (defs == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (DrgDefReq d : defs) {
            lines.add("DRG|" + d.drgCode() + "|" + d.adrgCode() + "|" + d.severity() + "|"
                    + d.weight().stripTrailingZeros().toPlainString());
        }
        lines.stream().sorted().forEach(line -> sb.append('\n').append(line));
    }

    private static void appendCc(StringBuilder sb, List<CcRuleReq> rules) {
        if (rules == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (CcRuleReq r : rules) {
            lines.add("CC|" + r.severity() + "|" + r.diagPrefix());
        }
        lines.stream().sorted().forEach(line -> sb.append('\n').append(line));
    }

    private static void appendCcExclusion(StringBuilder sb, List<CcExclusionReq> rules) {
        if (rules == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (CcExclusionReq r : rules) {
            lines.add("CCX|" + r.primaryDiagPrefix() + "|" + r.secondaryDiagPrefix());
        }
        lines.stream().sorted().forEach(line -> sb.append('\n').append(line));
    }

    private static void appendDip(StringBuilder sb, List<DipCatalogReq> items) {
        if (items == null) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (DipCatalogReq i : items) {
            lines.add("DIP|" + i.dipCode() + "|" + i.diagCode() + "|" + i.procCode() + "|"
                    + i.type() + "|" + i.score().stripTrailingZeros().toPlainString());
        }
        lines.stream().sorted().forEach(line -> sb.append('\n').append(line));
    }

    private String inputHash(GrouperRuleSet ruleSet, GrouperInput input) {
        return sha256(ruleSet.code() + "|" + ruleSet.version() + "|" + ruleSet.checksum()
                + "|" + input.principalDiagnosis() + "|"
                + String.join(",", input.secondaryDiagnoses()) + "|"
                + String.join(",", input.procedures()));
    }

    private static String inputHashOfficial(OfficialReq req, String principal,
                                            List<String> diagnoses, List<String> procedures) {
        return sha256("OFFICIAL|" + req.visitId() + "|" + principal + "|"
                + String.join(",", diagnoses) + "|" + String.join(",", procedures));
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static List<String> normalizeCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        Set<String> normalized = new TreeSet<>();
        for (String code : codes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            normalized.add(code.trim().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private static void compare(List<String> differences, String field, Object pre, Object official) {
        if (pre == null && official == null) {
            return;
        }
        boolean same;
        if (pre instanceof BigDecimal a && official instanceof BigDecimal b) {
            same = a.compareTo(b) == 0;
        } else {
            same = pre != null && pre.equals(official);
        }
        if (!same) {
            differences.add(field + ": " + pre + " → " + official);
        }
    }

    private static ResultRef ref(GrouperResult result) {
        return new ResultRef(result.engine(), result.ruleSetCode(), result.ruleSetVersion(),
                result.mdcCode(), result.adrgCode(), result.drgCode(), result.dipCode(),
                result.severity(), result.weight(), result.score(), result.paymentStandard(),
                result.reason(), result.createdAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("分组轨迹序列化失败", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "GROUPER", targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "GROUPER", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
