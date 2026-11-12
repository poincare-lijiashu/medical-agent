package com.medassist.license.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.license.config.LicenseProperties;
import com.medassist.license.domain.LicenseModel;
import com.medassist.license.domain.LicenseModel.ChangeLog;
import com.medassist.license.domain.LicenseModel.Edition;
import com.medassist.license.domain.LicenseModel.License;
import com.medassist.license.domain.LicenseModel.ModuleEntitlement;
import com.medassist.license.domain.LicenseModel.Phase;
import com.medassist.license.domain.LicenseModel.Quota;
import com.medassist.license.domain.LicenseModel.State;
import com.medassist.license.domain.LicenseModel.Usage;
import com.medassist.license.repo.LicenseRepository;
import com.medassist.license.support.LicenseCrypto;
import com.medassist.license.support.LicensePayloads;
import com.medassist.license.web.LicenseDtos.Issued;
import com.medassist.license.web.LicenseDtos.IssueReq;
import com.medassist.license.web.LicenseDtos.StatusView;
import com.medassist.license.web.LicenseDtos.UsageReq;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 授权事实的唯一入口：签发、导入、判定、吊销、配额对照。
 *
 * <p>判定口径（重要）：
 * <ul>
 *   <li>库里的 {@code status} 只有 ACTIVE / SUPERSEDED / REVOKED，是**人工动作**；</li>
 *   <li>PENDING / GRACE / EXPIRED 是**时间事实**，每次查询按当前时钟现算，不落库——
 *       落库就需要定时任务扫全表，且机器停机后状态会停留在旧值；</li>
 *   <li>超配额只产生告警文本，不抛异常：临床连续性优先，超限由运营人工处理。</li>
 * </ul>
 */
@Service
public class LicenseService {

    /** 本地模块目录：code 与前端导航/网关模块门禁使用同一套编码。 */
    private static final Map<String, String> MODULE_CATALOG = moduleCatalog();

    private static final Pattern LICENSE_NO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{3,63}$");
    private static final Pattern MODULE_CODE = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");

    private final LicenseRepository repo;
    private final LicenseProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    public LicenseService(LicenseRepository repo, LicenseProperties props, ObjectMapper json, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------ 查询 */

    public StatusView status() {
        Instant now = clock.instant();
        License active = currentOf();
        Optional<Usage> usage = repo.latestUsage(props.instanceCode());
        List<Edition> editions = repo.listEditions();
        State state = active != null ? stateOf(active, now) : new State(Phase.NONE, null, null);
        List<ModuleEntitlement> entitlements = entitlementsOf(active, state);
        List<Quota> quotas = quotasOf(active, usage.orElse(null));
        List<String> warnings = warningsOf(active, state, usage.orElse(null), quotas);

        String verifyKeyId = null;
        try {
            verifyKeyId = LicenseCrypto.keyId(LicenseCrypto.publicKey(props));
        } catch (RuntimeException e) {
            warnings.add("验签公钥不可用，导入授权书会失败：" + e.getMessage());
        }
        return new StatusView(props.instanceCode(), state.phase().name(), state, active,
                usage.orElse(null), entitlements, quotas, warnings, editions,
                props.issuanceEnabled(), props.enforce(), verifyKeyId);
    }

    public List<ModuleEntitlement> entitlements() {
        License active = currentOf();
        State state = active != null ? stateOf(active, clock.instant()) : new State(Phase.NONE, null, null);
        return entitlementsOf(active, state);
    }

    /**
     * 当前应展示的授权：优先 ACTIVE；若只剩"已吊销"，仍返回它，
     * 让页面显示"已吊销 + 原因"而不是退回"未授权"（后者会掩盖运维事实）。
     */
    private License currentOf() {
        Optional<License> active = repo.findActive(props.instanceCode());
        if (active.isPresent()) {
            return active.get();
        }
        return repo.findLatest(props.instanceCode())
                .filter(l -> "REVOKED".equals(l.status()))
                .orElse(null);
    }

    public List<License> licenses(int limit) {
        return repo.list(limit);
    }

    /**
     * 某实例的授权摘要（§284）：开通编排只需要"有没有、能不能用"。
     *
     * <p>不存在返回 {@code present=false}，而不是 404：对开通流程来说
     * "没有授权"是一个正常分支（是否需要它由开通参数决定），不是接口错误。
     */
    public com.medassist.license.web.LicenseDtos.Summary summaryOf(String instanceCode) {
        String instance = instanceCode == null ? "" : instanceCode.trim();
        if (instance.isEmpty()) {
            return emptySummary();
        }
        Optional<License> found = repo.findLatest(instance);
        if (found.isEmpty()) {
            return emptySummary();
        }
        License license = found.get();
        State state = stateOf(license, clock.instant());
        return new com.medassist.license.web.LicenseDtos.Summary(true, license.licenseNo(),
                license.status(), license.editionCode(), license.expiresAt(),
                license.modules(), state.phase().name(),
                license.maxBeds(), license.maxUsers(), license.maxAiCallsPerDay(),
                license.graceDays(), state.daysLeft());
    }

    private static com.medassist.license.web.LicenseDtos.Summary emptySummary() {
        return new com.medassist.license.web.LicenseDtos.Summary(false, null, null, null, null,
                List.of(), "NONE", null, null, null, 0, null);
    }

    public List<Edition> editions() {
        return repo.listEditions();
    }

    public List<ChangeLog> audit(int limit) {
        return repo.listAudit(limit);
    }

    /* ------------------------------------------------------------------ 签发（厂商侧） */

    public Issued issue(IssueReq req, String actor, String traceId) {
        if (!props.issuanceEnabled()) {
            throw new BusinessException(ErrorCode.LICENSE_ISSUANCE_DISABLED);
        }
        requireActor(actor);
        List<Edition> editions = repo.listEditions();
        Edition edition = editions.stream()
                .filter(e -> e.code().equalsIgnoreCase(text(req.editionCode())))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                        "未找到版本：" + req.editionCode()));

        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        List<String> modules = LicensePayloads.normalizeModules(
                req.modules() == null || req.modules().isEmpty() ? edition.modules() : req.modules());
        Instant notBefore = parseInstant(req.notBefore(), issuedAt, "notBefore");
        Instant expiresAt = parseInstant(req.expiresAt(), issuedAt.plus(365, ChronoUnit.DAYS), "expiresAt");
        int graceDays = req.graceDays() == null ? props.graceDays() : req.graceDays();

        License draft = new License(UUID.randomUUID(), text(req.licenseNo()),
                text(req.instanceCode()).isEmpty() ? props.instanceCode() : text(req.instanceCode()),
                text(req.hospitalName()), edition.code(), modules,
                req.maxBeds() == null ? edition.maxBeds() : req.maxBeds(),
                req.maxUsers() == null ? edition.maxUsers() : req.maxUsers(),
                req.maxAiCallsPerDay() == null ? edition.maxAiCallsPerDay() : req.maxAiCallsPerDay(),
                notBefore, expiresAt, graceDays, "ACTIVE",
                null, null, null, null, actor, issuedAt, issuedAt, null, null, null);
        validate(draft, edition);

        String canonical = canonicalOf(draft);
        String signature = LicenseCrypto.sign(LicensePayloads.bytes(canonical), LicenseCrypto.privateKey(props));
        String keyId = LicenseCrypto.keyId(LicenseCrypto.publicKey(props));
        String document = documentOf(draft, canonical, signature, keyId);

        repo.audit(actor, "LICENSE_ISSUE", draft.licenseNo(),
                detail(Map.of("instanceCode", draft.instanceCode(), "edition", draft.editionCode(),
                        "modules", draft.modules(), "expiresAt", draft.expiresAt().toString())), traceId);
        return new Issued(document, LicenseCrypto.sha256Hex(LicensePayloads.bytes(canonical)), keyId, draft.licenseNo());
    }

    /* ------------------------------------------------------------------ 导入 / 吊销 */

    /**
     * 导入授权书。
     *
     * <p>顺序很重要：库里对"同一实例只能有一条 ACTIVE"有部分唯一索引
     * （{@code ux_lic_active_instance}）。因此必须**先把旧 ACTIVE 置为 SUPERSEDED，
     * 再插入新授权**；反过来会直接撞唯一索引，把"换签"误报成"授权编号重复"。
     *
     * <p>整个过程在事务里：插入失败会抛业务异常并回滚，旧授权恢复 ACTIVE，
     * 不会出现"旧的失效、新的没进来"的空窗。
     */
    @org.springframework.transaction.annotation.Transactional
    public License importLicense(String document, String actor, String traceId) {
        requireActor(actor);
        if (document == null || document.isBlank()) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID, "授权文件为空");
        }
        JsonNode doc = readJson(document);
        String schema = doc.path("schema").asText("");
        if (!LicensePayloads.SCHEMA.equals(schema)) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                    "不支持的授权文件格式：" + (schema.isEmpty() ? "缺少 schema" : schema));
        }
        String canonical = doc.path("payloadCanonical").asText("");
        String signature = doc.path("signature").asText("");
        String keyId = doc.path("keyId").asText("");
        JsonNode payload = doc.path("payload");
        if (canonical.isBlank() || signature.isBlank() || !payload.isObject()) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                    "授权文件缺少 signature / payload / payloadCanonical");
        }

        PublicKey pub;
        try {
            pub = LicenseCrypto.publicKey(props);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.LICENSE_KEY_UNAVAILABLE);
        }
        if (!LicenseCrypto.verify(LicensePayloads.bytes(canonical), signature, pub)) {
            throw new BusinessException(ErrorCode.LICENSE_SIGNATURE_INVALID);
        }

        License candidate = fromPayload(payload);
        // 可读视图与签名原文必须一致：否则"看到的内容"和"签过的内容"是两份东西
        if (!canonicalOf(candidate).equals(canonical)) {
            throw new BusinessException(ErrorCode.LICENSE_SIGNATURE_INVALID,
                    "授权文件的可读内容与签名原文不一致，请使用原始签发文件");
        }
        if (!props.instanceCode().equals(candidate.instanceCode())) {
            throw new BusinessException(ErrorCode.LICENSE_INSTANCE_MISMATCH,
                    "授权实例为 " + candidate.instanceCode() + "，当前部署实例为 " + props.instanceCode());
        }
        Edition edition = repo.listEditions().stream()
                .filter(e -> e.code().equals(candidate.editionCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                        "授权引用的版本不存在：" + candidate.editionCode()));
        validate(candidate, edition);

        if (repo.findByNo(candidate.licenseNo()).isPresent()) {
            throw new BusinessException(ErrorCode.LICENSE_DUPLICATE);
        }
        UUID id = UUID.randomUUID();
        License stored = new License(id, candidate.licenseNo(), candidate.instanceCode(),
                candidate.hospitalName(), candidate.editionCode(), candidate.modules(),
                candidate.maxBeds(), candidate.maxUsers(), candidate.maxAiCallsPerDay(),
                candidate.notBefore(), candidate.expiresAt(), candidate.graceDays(), "ACTIVE",
                canonical, LicenseCrypto.sha256Hex(LicensePayloads.bytes(canonical)),
                signature, keyId.isEmpty() ? LicenseCrypto.keyId(pub) : keyId, actor, clock.instant(),
                candidate.issuedAt(), null, null, null);
        // 先让位再插入：部分唯一索引只允许一条 ACTIVE，顺序反了必然撞索引
        int superseded = repo.supersedeActive(stored.instanceCode(), id);
        if (!repo.insert(stored)) {
            // 抛出去让事务回滚：旧授权的 SUPERSEDED 一并撤销，避免"旧的失效、新的没进来"
            throw new BusinessException(ErrorCode.LICENSE_DUPLICATE);
        }
        repo.audit(actor, "LICENSE_IMPORT", stored.licenseNo(),
                detail(Map.of("instanceCode", stored.instanceCode(), "edition", stored.editionCode(),
                        "expiresAt", stored.expiresAt().toString(), "superseded", superseded,
                        "payloadHash", stored.payloadHash())), traceId);
        return repo.findById(id).orElse(stored);
    }

    public License revoke(UUID id, String reason, String actor, String traceId) {
        requireActor(actor);
        String text = text(reason);
        if (text.length() < 2) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "吊销授权必须填写原因（至少 2 个字符）");
        }
        License existing = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.LICENSE_NOT_FOUND));
        if ("REVOKED".equals(existing.status())) {
            throw new BusinessException(ErrorCode.LICENSE_STATUS_INVALID, "该授权已吊销");
        }
        if ("SUPERSEDED".equals(existing.status())) {
            throw new BusinessException(ErrorCode.LICENSE_STATUS_INVALID,
                    "该授权已被新授权替代，请吊销当前生效授权");
        }
        repo.revoke(id, actor, text);
        repo.audit(actor, "LICENSE_REVOKE", existing.licenseNo(),
                detail(Map.of("reason", text)), traceId);
        return repo.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.LICENSE_NOT_FOUND));
    }

    public Usage captureUsage(UsageReq req, String actor, String traceId) {
        requireActor(actor);
        int beds = nonNegative(req.activeBeds(), "activeBeds");
        int users = nonNegative(req.activeUsers(), "activeUsers");
        int calls = nonNegative(req.aiCalls(), "aiCalls");
        LocalDate date;
        try {
            date = req.date() == null || req.date().isBlank()
                    ? LocalDate.now(clock.withZone(ZoneOffset.UTC))
                    : LocalDate.parse(req.date());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.LICENSE_USAGE_INVALID, "usage_date 需为 yyyy-MM-dd");
        }
        Usage usage = new Usage(date, beds, users, calls,
                req.source() == null || req.source().isBlank() ? "MANUAL" : req.source().trim(),
                clock.instant());
        repo.upsertUsage(props.instanceCode(), usage);
        repo.audit(actor, "USAGE_CAPTURE", props.instanceCode(),
                detail(Map.of("date", date.toString(), "beds", beds, "users", users, "aiCalls", calls)), traceId);
        return usage;
    }

    /* ------------------------------------------------------------------ 判定 */

    /** 阶段判定：ACTIVE 覆盖 not_before/expires_at，REVOKED 优先。 */
    public State stateOf(License license, Instant now) {
        if (license == null) {
            return new State(Phase.NONE, null, null);
        }
        if ("REVOKED".equals(license.status())) {
            return new State(Phase.REVOKED, null, 0L);
        }
        Instant graceEndsAt = license.expiresAt().plus(Duration.ofDays(license.graceDays()));
        if (now.isBefore(license.notBefore())) {
            return new State(Phase.PENDING, graceEndsAt,
                    Math.max(0, Duration.between(now, license.notBefore()).toDays()));
        }
        if (!now.isAfter(license.expiresAt())) {
            return new State(Phase.ACTIVE, graceEndsAt,
                    Math.max(0, Duration.between(now, license.expiresAt()).toDays()));
        }
        if (!now.isAfter(graceEndsAt)) {
            return new State(Phase.GRACE, graceEndsAt,
                    Math.max(0, Duration.between(now, graceEndsAt).toDays()));
        }
        return new State(Phase.EXPIRED, graceEndsAt, 0L);
    }

    private List<ModuleEntitlement> entitlementsOf(License license, State state) {
        boolean usable = license != null && state.usable();
        List<ModuleEntitlement> out = new ArrayList<>();
        MODULE_CATALOG.forEach((code, name) -> {
            boolean entitled = usable && license.modules().contains(code);
            String note = !usable
                    ? (license == null ? "未导入授权" : "授权不在有效期内")
                    : (entitled ? "已授权" : "授权未包含该模块");
            out.add(new ModuleEntitlement(code, name, entitled, note));
        });
        return out;
    }

    private List<Quota> quotasOf(License license, Usage usage) {
        List<Quota> out = new ArrayList<>();
        out.add(quota("beds", "开放床位", license == null ? null : license.maxBeds(),
                usage == null ? null : usage.activeBeds()));
        out.add(quota("users", "活跃账号", license == null ? null : license.maxUsers(),
                usage == null ? null : usage.activeUsers()));
        out.add(quota("aiCalls", "AI 日调用", license == null ? null : license.maxAiCallsPerDay(),
                usage == null ? null : usage.aiCalls()));
        return out;
    }

    private Quota quota(String code, String name, Integer limit, Integer used) {
        if (limit == null) {
            return new Quota(code, name, null, used, null, false);
        }
        int value = used == null ? 0 : used;
        double utilization = limit == 0 ? (value > 0 ? 1.0 : 0.0) : (double) value / limit;
        return new Quota(code, name, limit, used, Math.round(utilization * 1000) / 1000.0, value > limit);
    }

    private List<String> warningsOf(License license, State state, Usage usage, List<Quota> quotas) {
        List<String> out = new ArrayList<>();
        switch (state.phase()) {
            case NONE -> out.add("尚未导入授权书：模块门禁当前处于观察模式，请导入厂商授权后复核");
            case PENDING -> out.add("授权尚未生效，还有 " + state.daysLeft() + " 天到生效日");
            case GRACE -> out.add("授权已到期，处于宽限期，剩余 " + state.daysLeft() + " 天，请尽快完成续期");
            case EXPIRED -> out.add("授权已过期且超出宽限期，模块门禁将按策略拦截业务访问");
            case REVOKED -> out.add("授权已被吊销，禁止继续使用");
            case ACTIVE -> {
                if (state.daysLeft() != null && state.daysLeft() <= 30) {
                    out.add("授权将在 " + state.daysLeft() + " 天后到期，请安排续期");
                }
            }
        }
        if (usage == null && license != null) {
            out.add("尚无用量快照：配额使用率按 0 展示，建议配置每日用量上报");
        }
        quotas.stream().filter(Quota::exceeded)
                .forEach(q -> out.add("配额超限：" + q.name() + " 已用 " + q.used() + " / 上限 " + q.limit()));
        if (license != null && !props.enforce()) {
            out.add("模块门禁当前为观察模式（LICENSE_ENFORCE=false）：超限/未授权只告警，不拦截请求");
        }
        return out;
    }

    /* ------------------------------------------------------------------ 内部 */

    private void validate(License license, Edition edition) {
        if (!LICENSE_NO.matcher(license.licenseNo()).matches()) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                    "授权编号需为 4-64 位字母/数字/点/下划线/短横线");
        }
        String hospital = license.hospitalName();
        if (hospital == null || hospital.length() < 2 || hospital.length() > 120) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID, "机构名称需 2-120 字");
        }
        if (license.modules() == null || license.modules().isEmpty()) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID, "授权模块不能为空");
        }
        for (String module : license.modules()) {
            if (!MODULE_CODE.matcher(module).matches()) {
                throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID, "模块编码不合法：" + module);
            }
            if (!edition.modules().contains(module)) {
                throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                        "版本 " + edition.name() + " 不包含模块 " + module);
            }
        }
        checkCap(license.maxBeds(), edition.maxBeds(), "床位上限");
        checkCap(license.maxUsers(), edition.maxUsers(), "账号上限");
        checkCap(license.maxAiCallsPerDay(), edition.maxAiCallsPerDay(), "AI 日调用上限");
        if (!license.notBefore().isBefore(license.expiresAt())) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID, "生效时间必须早于到期时间");
        }
        if (license.graceDays() < 0 || license.graceDays() > 365) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID, "宽限期需在 0-365 天之间");
        }
    }

    private void checkCap(Integer value, Integer editionCap, String label) {
        if (value == null) {
            return;
        }
        if (value < 0) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID, label + "不能为负");
        }
        if (editionCap != null && value > editionCap) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                    label + "超过版本上限 " + editionCap + "：" + value);
        }
    }

    private String canonicalOf(License l) {
        return LicensePayloads.canonical(l.licenseNo(), l.instanceCode(), l.hospitalName(), l.editionCode(),
                l.modules(), l.maxBeds(), l.maxUsers(), l.maxAiCallsPerDay(),
                l.notBefore(), l.expiresAt(), l.graceDays(), l.issuedAt());
    }

    private License fromPayload(JsonNode p) {
        try {
            return new License(null,
                    p.path("licenseNo").asText(""),
                    p.path("instanceCode").asText(""),
                    p.path("hospitalName").asText(""),
                    p.path("editionCode").asText(""),
                    LicensePayloads.normalizeModules(strings(p.path("modules"))),
                    integer(p, "maxBeds"),
                    integer(p, "maxUsers"),
                    integer(p, "maxAiCallsPerDay"),
                    Instant.parse(p.path("notBefore").asText("")),
                    Instant.parse(p.path("expiresAt").asText("")),
                    p.path("graceDays").asInt(0),
                    "ACTIVE",
                    null, null, null, null,
                    null, null,
                    Instant.parse(p.path("issuedAt").asText("")),
                    null, null, null);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                    "授权内容解析失败（时间需为 ISO-8601 UTC，如 2027-11-11T00:00:00Z）");
        }
    }

    private String documentOf(License l, String canonical, String signature, String keyId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("editionCode", l.editionCode());
        payload.put("expiresAt", l.expiresAt().toString());
        payload.put("graceDays", l.graceDays());
        payload.put("hospitalName", l.hospitalName());
        payload.put("instanceCode", l.instanceCode());
        payload.put("issuedAt", l.issuedAt().toString());
        payload.put("licenseNo", l.licenseNo());
        payload.put("maxAiCallsPerDay", l.maxAiCallsPerDay());
        payload.put("maxBeds", l.maxBeds());
        payload.put("maxUsers", l.maxUsers());
        payload.put("modules", l.modules());
        payload.put("notBefore", l.notBefore().toString());
        payload.put("product", LicensePayloads.PRODUCT);
        payload.put("schema", LicensePayloads.SCHEMA);

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", LicensePayloads.SCHEMA);
        doc.put("keyId", keyId);
        doc.put("algorithm", "Ed25519");
        doc.put("signature", signature);
        doc.put("payloadCanonical", canonical);
        doc.put("payload", payload);
        try {
            return json.writeValueAsString(doc);
        } catch (JacksonException e) {
            throw new IllegalStateException("授权文件序列化失败", e);
        }
    }

    private JsonNode readJson(String raw) {
        try {
            return json.readTree(raw);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID, "授权文件不是合法 JSON");
        }
    }

    private String detail(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException e) {
            return "{}";
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.LICENSE_ACTOR_REQUIRED);
        }
    }

    private static int nonNegative(Integer value, String field) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw new BusinessException(ErrorCode.LICENSE_USAGE_INVALID, field + "不能为负");
        }
        return value;
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        node.forEach(item -> out.add(item.asText("")));
        return out;
    }

    private static Instant parseInstant(String raw, Instant fallback, String field) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.LICENSE_PAYLOAD_INVALID,
                    field + " 需为 ISO-8601 UTC 时间（如 2027-11-11T00:00:00Z）");
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> moduleCatalog() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("clinical", "临床核心");
        out.put("pharmacy", "药事管理");
        out.put("emr", "病历与病案");
        out.put("tech", "医技（检查检验）");
        out.put("insurance", "医保结算");
        out.put("material", "物资耗材");
        out.put("kb", "知识库");
        out.put("mdm", "主数据");
        out.put("ai", "AI 中台");
        out.put("fhir", "FHIR 开放层");
        out.put("ops", "运营支撑");
        out.put("release", "发布治理");
        out.put("integration", "集成平台");
        out.put("migration", "历史数据迁移");
        return java.util.Collections.unmodifiableMap(out);
    }

    /** 供页面做"未授权模块"提示时复用。 */
    public static Map<String, String> moduleCatalogView() {
        return MODULE_CATALOG;
    }
}
