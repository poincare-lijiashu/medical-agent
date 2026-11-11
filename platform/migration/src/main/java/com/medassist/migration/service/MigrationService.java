package com.medassist.migration.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.FieldCipher;
import com.medassist.common.time.BusinessTime;
import com.medassist.migration.catalog.TargetCatalog;
import com.medassist.migration.config.MigrationProperties;
import com.medassist.migration.domain.MigrationModel;
import com.medassist.migration.domain.MigrationModel.Batch;
import com.medassist.migration.domain.MigrationModel.FieldDef;
import com.medassist.migration.domain.MigrationModel.MappingProfile;
import com.medassist.migration.domain.MigrationModel.MappingRule;
import com.medassist.migration.domain.MigrationModel.Row;
import com.medassist.migration.domain.MigrationModel.Target;
import com.medassist.migration.domain.MigrationModel.TargetDetail;
import com.medassist.migration.domain.MigrationRequests;
import com.medassist.migration.repo.MigrationRepository;
import com.medassist.migration.support.CanonicalJson;
import com.medassist.migration.support.Csv;
import com.medassist.migration.support.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 迁移编排：模板 → 映射档案 → 预检 → 应用 → 回滚 → 对账。
 *
 * <p>三个"不做"：
 * <ul>
 *   <li>不在预检阶段写业务表——预检只读，避免"看了一眼就把脏数据写进去"；</li>
 *   <li>不半截应用——任何一行失败整批回滚，批次标记 FAILED，不留下"导入了一半"的状态；</li>
 *   <li>不静默覆盖——应用前逐行复核目标现状，回滚前比对应用后镜像，冲突一律拒绝并列出行号。</li>
 * </ul>
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> TRANSFORMS = Set.of(
            "TRIM", "UPPER", "LOWER", "CONSTANT", "DEFAULT", "ENUM_MAP",
            "DATE", "DATETIME", "DECIMAL", "INT", "BOOL");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MigrationRepository repo;
    private final RowNormalizer normalizer;
    private final TargetEngine engine;
    private final FieldCipher cipher;
    private final MigrationProperties properties;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public MigrationService(MigrationRepository repo, RowNormalizer normalizer,
                            TargetEngine engine, FieldCipher cipher,
                            MigrationProperties properties, ObjectMapper json,
                            PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.normalizer = normalizer;
        this.engine = engine;
        this.cipher = cipher;
        this.properties = properties;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 目标与模板

    public List<TargetDetail> listTargets() {
        List<TargetDetail> result = new ArrayList<>();
        for (Target target : repo.listTargets(true)) {
            if (TargetCatalog.find(target.code()).isEmpty()) {
                log.warn("migration.target_without_spec code={}（代码未实现，模板不可用）", target.code());
                continue;
            }
            result.add(detailOf(target));
        }
        return result;
    }

    public TargetDetail target(String code) {
        Target target = repo.findTarget(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.MIG_TARGET_NOT_FOUND));
        requireSpec(code);
        return detailOf(target);
    }

    private TargetDetail detailOf(Target target) {
        return new TargetDetail(target, repo.listFields(target.code()),
                repo.listDependsOn(target.code()), repo.listRequiredBy(target.code()));
    }

    /** 模板 CSV：表头 = 字段键，带 BOM，Excel 直接打开不乱码。 */
    public String template(String targetCode) {
        requireSpec(targetCode);
        List<FieldDef> fields = repo.listFields(targetCode);
        return Csv.write(fields.stream().map(FieldDef::fieldKey).toList(), List.of());
    }

    // ------------------------------------------------------------------ 映射档案

    public List<MappingProfile> listProfiles(String targetCode, String sourceSystem, Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), properties.getMaxBatchPageSize());
        return repo.listProfiles(targetCode, sourceSystem, size);
    }

    public MappingProfile createProfile(MigrationRequests.ProfileReq req, String actor,
                                        String clientIp, String traceId) {
        requireActor(actor);
        TargetCatalog.Spec spec = requireSpec(req.targetCode());
        if (req.profileCode() == null || !req.profileCode().matches("[A-Za-z0-9_.-]{3,64}")) {
            throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID,
                    "档案编码只能包含字母/数字/下划线/点/短横线，长度 3-64");
        }
        if (req.mappings() == null || req.mappings().isEmpty()) {
            throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID, "映射规则不能为空");
        }
        Set<String> fields = new LinkedHashSet<>();
        for (MappingRule rule : req.mappings()) {
            if (rule.field() == null || spec.findField(rule.field()).isEmpty()) {
                throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID,
                        "映射目标字段不存在：" + rule.field());
            }
            if (!fields.add(rule.field())) {
                throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID,
                        "同一目标字段被重复映射：" + rule.field());
            }
            String transform = rule.transform() == null ? "TRIM" : rule.transform().toUpperCase();
            if (!TRANSFORMS.contains(transform)) {
                throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID,
                        "不支持的转换规则：" + rule.transform());
            }
            if (!"CONSTANT".equals(transform) && (rule.source() == null || rule.source().isBlank())) {
                throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID,
                        "非 CONSTANT 规则必须提供源列名：" + rule.field());
            }
            if (("CONSTANT".equals(transform) || "DEFAULT".equals(transform))
                    && (rule.value() == null || rule.value().isBlank())) {
                throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID,
                        transform + " 规则必须提供 value：" + rule.field());
            }
        }
        for (FieldDef field : spec.fields()) {
            if (field.required() && !fields.contains(field.fieldKey())) {
                throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID,
                        "必填字段未配置映射：" + field.label() + "（" + field.fieldKey() + "）");
            }
        }

        MappingProfile profile = new MappingProfile(UUID.randomUUID(), req.profileCode().trim(),
                req.name(), req.sourceSystem().trim(), spec.code(), req.mappings(), "ACTIVE",
                0, actor, null, null);
        if (!repo.insertProfile(profile)) {
            throw new BusinessException(ErrorCode.MIG_PROFILE_DUPLICATE);
        }
        repo.audit(actor, "PROFILE_CREATE", spec.code(), null,
                Map.of("profileCode", profile.profileCode(), "sourceSystem", profile.sourceSystem()),
                clientIp, traceId);
        return repo.findProfile(profile.profileCode()).orElse(profile);
    }

    public MappingProfile retireProfile(String profileCode, String actor, String clientIp, String traceId) {
        requireActor(actor);
        MappingProfile profile = requireProfile(profileCode, null);
        if (!"ACTIVE".equals(profile.status())) {
            throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID, "该档案已停用");
        }
        repo.retireProfile(profileCode, actor);
        repo.audit(actor, "PROFILE_RETIRE", profile.targetCode(), null,
                Map.of("profileCode", profileCode), clientIp, traceId);
        return requireProfile(profileCode, null);
    }

    // ------------------------------------------------------------------ 预检

    public Batch preview(MigrationRequests.PreviewReq req, String actor,
                         String clientIp, String traceId) {
        requireActor(actor);
        TargetCatalog.Spec spec = requireSpec(req.targetCode());
        MappingProfile profile = req.profileCode() == null || req.profileCode().isBlank()
                ? null : requireProfile(req.profileCode(), spec.code());

        List<Map<String, String>> rawRows = readRows(req);
        if (rawRows.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "没有可预检的数据行");
        }
        if (rawRows.size() > properties.getMaxRows()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单批最多 " + properties.getMaxRows() + " 行，请拆分文件后重试");
        }
        validateHeaders(spec, profile, req, rawRows);

        String sourceSystem = req.sourceSystem() == null || req.sourceSystem().isBlank()
                ? "LEGACY_HIS" : req.sourceSystem().trim();
        String checksum = CanonicalJson.checksum(null, Map.of(
                "target", spec.code(),
                "sourceSystem", sourceSystem,
                "profileCode", profile == null ? "" : profile.profileCode(),
                "rows", rawRows));

        UUID batchId = UUID.randomUUID();
        String batchNo = nextBatchNo();
        List<Row> rows = new ArrayList<>();
        Map<Integer, String> payloads = new LinkedHashMap<>();
        int creates = 0;
        int updates = 0;
        int unchanged = 0;
        int conflicts = 0;
        int errors = 0;
        Set<String> naturalKeys = new LinkedHashSet<>();

        for (int i = 0; i < rawRows.size(); i++) {
            int rowNo = i + 1;
            Map<String, String> raw = rawRows.get(i);
            try {
                NormalizedRow normalized = normalizer.normalize(spec, profile, raw);
                TargetEngine.Evaluation evaluation = engine.evaluate(spec, normalized);
                // 同一自然键在文件内重复：后一条会让前一条的差异判定失真，必须在预检拦下
                if (!MigrationModel.CONFLICT.equals(evaluation.action())
                        && evaluation.naturalKey() != null && !evaluation.naturalKey().isBlank()
                        && !naturalKeys.add(evaluation.naturalKey())) {
                    errors++;
                    rows.add(errorRow(batchId, rowNo, raw,
                            "自然键在文件内重复：" + evaluation.naturalKey()));
                    continue;
                }
                payloads.put(rowNo, cipher.encrypt(CanonicalJson.write(normalized.values())));
                switch (evaluation.action()) {
                    case MigrationModel.CREATE -> creates++;
                    case MigrationModel.UPDATE -> updates++;
                    case MigrationModel.UNCHANGED -> unchanged++;
                    default -> conflicts++;
                }
                rows.add(new Row(0, batchId, rowNo, normalized.rawProjection(),
                        normalized.normalizedProjection(), evaluation.action(), evaluation.targetPk(),
                        evaluation.naturalKey(),
                        TargetEngine.canonicalImage(evaluation.beforeImage()),
                        Map.of(), evaluation.message(), false, false));
            } catch (RowProblem e) {
                errors++;
                rows.add(errorRow(batchId, rowNo, raw, e.getMessage()));
            } catch (DataAccessException e) {
                errors++;
                log.warn("migration.preview.db_error batch={} row={}", batchNo, rowNo, e);
                rows.add(errorRow(batchId, rowNo, raw, "数据库校验失败，请联系运维检查目标表状态"));
            }
        }

        Batch batch = new Batch(batchId, batchNo, spec.code(), sourceSystem,
                req.fileName(), profile == null ? null : profile.profileCode(), checksum,
                rawRows.size(), creates, updates, unchanged, conflicts, errors,
                MigrationModel.PREVIEWED,
                Map.of("headers", headerOf(req, rawRows),
                        "summary", "预览：" + creates + " 新增 / " + updates + " 更新 / "
                                + unchanged + " 无变化 / " + conflicts + " 冲突 / " + errors + " 错误"),
                actor, null, null, null, null, List.of());

        final int createCount = creates;
        final int updateCount = updates;
        final int unchangedCount = unchanged;
        final int conflictCount = conflicts;
        final int errorCount = errors;
        tx.executeWithoutResult(status -> {
            repo.insertBatch(batch, rows, payloads);
            repo.audit(actor, "BATCH_PREVIEW", spec.code(), batchNo,
                    Map.of("rows", rawRows.size(), "create", createCount, "update", updateCount,
                            "unchanged", unchangedCount, "conflict", conflictCount,
                            "error", errorCount, "checksum", checksum),
                    clientIp, traceId);
        });
        return detail(batchId, null, null);
    }

    // ------------------------------------------------------------------ 应用

    public Batch apply(UUID batchId, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Batch batch = requireBatch(batchId);
        if (MigrationModel.APPLIED.equals(batch.status())) {
            return detail(batchId, null, null);
        }
        if (!MigrationModel.PREVIEWED.equals(batch.status())) {
            throw new BusinessException(ErrorCode.MIG_BATCH_STATUS_INVALID);
        }
        if (batch.blocking()) {
            throw new BusinessException(ErrorCode.MIG_BATCH_HAS_BLOCKING_ROWS,
                    "批次存在 " + batch.conflictCount() + " 条冲突、" + batch.errorCount()
                            + " 条错误，整批拒绝应用");
        }

        TargetCatalog.Spec spec = requireSpec(batch.targetCode());
        long tableRowsBefore = repo.countTarget(spec);
        BigDecimal amountBefore = repo.sumTargetAmount(spec);
        long started = System.currentTimeMillis();

        try {
            Map<String, Object> summary = tx.execute(status -> applyRows(spec, batch));
            long duration = System.currentTimeMillis() - started;
            Map<String, Object> full = new LinkedHashMap<>(summary == null ? Map.of() : summary);
            full.put("tableRowsBefore", tableRowsBefore);
            full.put("tableRowsAfter", repo.countTarget(spec));
            full.put("amountBefore", amountBefore.stripTrailingZeros().toPlainString());
            full.put("amountAfter", repo.sumTargetAmount(spec).stripTrailingZeros().toPlainString());
            full.put("durationMs", duration);
            repo.markBatchApplied(batchId, full);
            auditApply(actor, batch, full, clientIp, traceId);
            return detail(batchId, null, null);
        } catch (TargetEngine.ApplyConflict | RowProblem e) {
            repo.markBatchFailed(batchId, e.getMessage());
            repo.audit(actor, "BATCH_FAIL", batch.targetCode(), batch.batchNo(),
                    Map.of("reason", e.getMessage()), clientIp, traceId);
            throw new BusinessException(ErrorCode.CONFLICT, e.getMessage());
        } catch (BusinessException e) {
            repo.markBatchFailed(batchId, e.getMessage());
            repo.audit(actor, "BATCH_FAIL", batch.targetCode(), batch.batchNo(),
                    Map.of("reason", e.getMessage()), clientIp, traceId);
            throw e;
        } catch (DataAccessException e) {
            repo.markBatchFailed(batchId, "数据库写入失败");
            repo.audit(actor, "BATCH_FAIL", batch.targetCode(), batch.batchNo(),
                    Map.of("reason", "数据库写入失败"), clientIp, traceId);
            log.error("migration.apply.failed batch={}", batch.batchNo(), e);
            throw new BusinessException(ErrorCode.MIG_ROW_INVALID,
                    "数据库写入失败，整批已回滚；请检查约束冲突后重新预检");
        }
    }

    private Map<String, Object> applyRows(TargetCatalog.Spec spec, Batch batch) {
        List<Row> rows = repo.listAllRows(batch.id());
        int applied = 0;
        int unchanged = 0;
        for (Row row : rows) {
            if (MigrationModel.UNCHANGED.equals(row.action())) {
                unchanged++;
                continue;
            }
            if (!MigrationModel.CREATE.equals(row.action())
                    && !MigrationModel.UPDATE.equals(row.action())) {
                throw new RowProblem("第 " + row.rowNo() + " 行为 " + row.action()
                        + "，不允许进入应用阶段");
            }
            Map<String, String> values = decryptPayload(batch.id(), row.rowNo());
            NormalizedRow normalized = normalizer.restore(spec, values);
            TargetEngine.Evaluation now = engine.evaluate(spec, normalized);

            if (!now.action().equals(row.action())) {
                throw new TargetEngine.ApplyConflict("第 " + row.rowNo() + " 行预检后目标数据已变化（"
                        + row.action() + " → " + now.action() + "），请重新预检");
            }
            if (MigrationModel.UPDATE.equals(row.action())
                    && previewVersion(row) != now.beforeVersion()) {
                throw new TargetEngine.ApplyConflict("第 " + row.rowNo()
                        + " 行目标数据在预检后被修改（乐观锁版本不一致），请重新预检");
            }

            UUID pk = engine.apply(spec, normalized, now);
            TargetEngine.Evaluation appliedEval = new TargetEngine.Evaluation(
                    now.action(), pk, now.beforeVersion(), now.naturalKey(),
                    now.beforeImage(), now.resolved(), null);
            Map<String, Object> after = engine.snapshotAfterApply(spec, normalized, appliedEval);
            if (after.isEmpty()) {
                throw new TargetEngine.ApplyConflict("第 " + row.rowNo()
                        + " 行写入后无法读取目标行，整批中止");
            }
            repo.updateRowApplied(row.id(), pk, after);
            applied++;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("appliedRows", applied);
        summary.put("unchangedRows", unchanged);
        summary.put("create", batch.createCount());
        summary.put("update", batch.updateCount());
        summary.put("reconciled", applied == batch.createCount() + batch.updateCount());
        return summary;
    }

    // ------------------------------------------------------------------ 回滚

    public List<Batch> rollback(List<String> batchNos, String reason, String actor,
                                String clientIp, String traceId) {
        requireActor(actor);
        if (reason == null || reason.trim().length() < 2) {
            throw new BusinessException(ErrorCode.MIG_REASON_REQUIRED);
        }
        if (batchNos == null || batchNos.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请指定要回滚的批次号");
        }
        if (new LinkedHashSet<>(batchNos).size() != batchNos.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "批次号重复");
        }

        List<Batch> batches = new ArrayList<>();
        for (String batchNo : batchNos) {
            Batch batch = repo.findBatchByNo(batchNo)
                    .orElseThrow(() -> new BusinessException(ErrorCode.MIG_BATCH_NOT_FOUND,
                            "未找到批次 " + batchNo));
            if (!MigrationModel.APPLIED.equals(batch.status())) {
                throw new BusinessException(ErrorCode.MIG_BATCH_STATUS_INVALID,
                        "批次 " + batchNo + " 不是已应用状态（" + batch.status() + "）");
            }
            batches.add(batch);
        }
        // 回滚必须逆依赖顺序：收费明细 → 收费/医嘱 → 就诊 → 患者 → 字典
        Map<String, Integer> sortNos = new LinkedHashMap<>();
        repo.listTargets(false).forEach(t -> sortNos.put(t.code(), t.sortNo()));
        batches.sort(Comparator.comparingInt(
                (Batch b) -> sortNos.getOrDefault(b.targetCode(), 0)).reversed());

        try {
            tx.executeWithoutResult(status -> {
                for (Batch batch : batches) {
                    TargetCatalog.Spec spec = requireSpec(batch.targetCode());
                    List<Row> rows = repo.listAllRows(batch.id());
                    int rolledBack = 0;
                    for (int i = rows.size() - 1; i >= 0; i--) {
                        Row row = rows.get(i);
                        if (MigrationModel.UNCHANGED.equals(row.action())) {
                            continue;
                        }
                        Map<String, String> values = decryptPayload(batch.id(), row.rowNo());
                        NormalizedRow normalized = normalizer.restore(spec, values);
                        TargetEngine.Evaluation evaluation = new TargetEngine.Evaluation(
                                row.action(), row.targetPk(), 0, row.naturalKey(),
                                row.beforeImage(), Map.of(), row.message());
                        engine.rollback(spec, normalized, evaluation, row.afterImage());
                        rolledBack++;
                    }
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("rolledBackRows", rolledBack);
                    summary.put("reason", reason.trim());
                    summary.put("rollbackOrder", batches.stream().map(Batch::batchNo).toList());
                    repo.markBatchRolledBack(batch.id(), actor, summary);
                    repo.audit(actor, "BATCH_ROLLBACK", batch.targetCode(), batch.batchNo(),
                            summary, clientIp, traceId);
                }
            });
        } catch (TargetEngine.RollbackConflict e) {
            throw new BusinessException(ErrorCode.MIG_ROLLBACK_CONFLICT, e.getMessage());
        } catch (RowProblem e) {
            throw new BusinessException(ErrorCode.MIG_ROW_INVALID, e.getMessage());
        }

        List<Batch> result = new ArrayList<>();
        for (Batch batch : batches) {
            result.add(detail(batch.id(), null, null));
        }
        return result;
    }

    // ------------------------------------------------------------------ 查询/对账/审计

    public List<Batch> listBatches(String targetCode, String status, Integer limit) {
        int size = limit == null ? properties.getAuditListDefault()
                : Math.min(Math.max(limit, 1), properties.getMaxBatchPageSize());
        return repo.listBatches(targetCode, status, size);
    }

    public Batch detail(UUID batchId, Integer limit, Integer offset) {
        Batch batch = requireBatch(batchId);
        int size = limit == null ? properties.getPreviewSampleRows()
                : Math.min(Math.max(limit, 1), properties.getMaxRows());
        int start = offset == null ? 0 : Math.max(offset, 0);
        return new Batch(batch.id(), batch.batchNo(), batch.targetCode(), batch.sourceSystem(),
                batch.fileName(), batch.profileCode(), batch.checksum(), batch.rowCount(),
                batch.createCount(), batch.updateCount(), batch.unchangedCount(),
                batch.conflictCount(), batch.errorCount(), batch.status(), batch.summary(),
                batch.createdBy(), batch.createdAt(), batch.appliedAt(), batch.rolledBackAt(),
                batch.rolledBackBy(), repo.listRows(batchId, size, start));
    }

    public Map<String, Object> reconciliation(UUID batchId) {
        Batch batch = requireBatch(batchId);
        TargetCatalog.Spec spec = requireSpec(batch.targetCode());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("batchNo", batch.batchNo());
        report.put("target", spec.code());
        report.put("status", batch.status());
        report.put("rowCount", batch.rowCount());
        report.put("createCount", batch.createCount());
        report.put("updateCount", batch.updateCount());
        report.put("unchangedCount", batch.unchangedCount());
        report.put("conflictCount", batch.conflictCount());
        report.put("errorCount", batch.errorCount());
        report.put("tableRows", repo.countTarget(spec));
        report.put("tableAmount", repo.sumTargetAmount(spec).stripTrailingZeros().toPlainString());
        report.putAll(batch.summary());
        report.put("reconciled", batch.summary().getOrDefault("reconciled", false));
        return report;
    }

    public List<MigrationModel.AuditLog> audit(Integer limit) {
        int size = limit == null ? properties.getAuditListDefault()
                : Math.min(Math.max(limit, 1), properties.getAuditListMax());
        return repo.listAudit(size);
    }

    // ------------------------------------------------------------------ 内部

    private TargetCatalog.Spec requireSpec(String targetCode) {
        return TargetCatalog.find(targetCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.MIG_TARGET_NOT_FOUND));
    }

    private MappingProfile requireProfile(String profileCode, String targetCode) {
        MappingProfile profile = repo.findProfile(profileCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.MIG_PROFILE_NOT_FOUND));
        if (!"ACTIVE".equals(profile.status())) {
            throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID, "映射档案已停用");
        }
        if (targetCode != null && !profile.targetCode().equals(targetCode)) {
            throw new BusinessException(ErrorCode.MIG_PROFILE_INVALID,
                    "该映射档案属于目标 " + profile.targetCode() + "，与当前目标不一致");
        }
        return profile;
    }

    private Batch requireBatch(UUID batchId) {
        return repo.findBatch(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MIG_BATCH_NOT_FOUND));
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.MIG_ACTOR_REQUIRED);
        }
    }

    private List<Map<String, String>> readRows(MigrationRequests.PreviewReq req) {
        if (req.csv() != null && !req.csv().isBlank()) {
            return Csv.parse(req.csv());
        }
        return req.rows() == null ? List.of() : req.rows();
    }

    private void validateHeaders(TargetCatalog.Spec spec, MappingProfile profile,
                                 MigrationRequests.PreviewReq req, List<Map<String, String>> rows) {
        Set<String> expected = new LinkedHashSet<>();
        if (profile != null) {
            profile.mappings().stream()
                    .filter(rule -> !"CONSTANT".equalsIgnoreCase(rule.transform()))
                    .forEach(rule -> expected.add(rule.source()));
        } else {
            expected.addAll(spec.fieldKeys());
        }
        List<String> headers = req.csv() != null && !req.csv().isBlank()
                ? Csv.header(req.csv())
                : rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
        // 模板模式（无映射档案）必须严格匹配表头，防止列错位；
        // 映射档案模式允许源文件带无关列（旧系统导出往往几十列），只校验映射要求的列是否齐全。
        if (profile == null) {
            for (String header : headers) {
                if (!expected.contains(header)) {
                    throw new BusinessException(ErrorCode.MIG_TEMPLATE_INVALID,
                            "模板存在未知列：" + header + "（请下载最新模板或检查映射档案）");
                }
            }
        }
        if (profile != null) {
            for (String source : expected) {
                if (!headers.contains(source)) {
                    throw new BusinessException(ErrorCode.MIG_TEMPLATE_INVALID,
                            "缺少映射档案要求的源列：" + source);
                }
            }
        }
        if (profile == null) {
            for (FieldDef field : spec.fields()) {
                if (field.required() && !headers.contains(field.fieldKey())) {
                    throw new BusinessException(ErrorCode.MIG_TEMPLATE_INVALID,
                            "缺少必填列：" + field.label() + "（" + field.fieldKey() + "）");
                }
            }
        }
    }

    private List<String> headerOf(MigrationRequests.PreviewReq req, List<Map<String, String>> rows) {
        if (req.csv() != null && !req.csv().isBlank()) {
            return Csv.header(req.csv());
        }
        return rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
    }

    private Row errorRow(UUID batchId, int rowNo, Map<String, String> raw, String message) {
        Map<String, Object> projection = new LinkedHashMap<>();
        raw.forEach((key, value) ->
                projection.put(key, Values.sensitive(key) ? Values.mask(value) : value));
        return new Row(0, batchId, rowNo, projection, Map.of(), MigrationModel.ERROR,
                null, null, Map.of(), Map.of(), message, false, false);
    }

    private Map<String, String> decryptPayload(UUID batchId, int rowNo) {
        String encrypted = repo.findRowPayload(batchId, rowNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.MIG_PAYLOAD_UNREADABLE));
        String payload = cipher.decrypt(encrypted);
        if (payload == null) {
            throw new BusinessException(ErrorCode.MIG_PAYLOAD_UNREADABLE);
        }
        try {
            Map<String, Object> raw = json.readValue(payload, MAP_TYPE);
            Map<String, String> values = new LinkedHashMap<>();
            raw.forEach((field, value) ->
                    values.put(field, value == null ? null : String.valueOf(value)));
            return values;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.MIG_PAYLOAD_UNREADABLE);
        }
    }

    private long previewVersion(Row row) {
        Object version = row.beforeImage().get("version");
        return version == null ? 0L : Long.parseLong(String.valueOf(version));
    }

    private String nextBatchNo() {
        byte[] random = new byte[3];
        RANDOM.nextBytes(random);
        return "MIG" + BATCH_TIME.format(LocalDateTime.now(BusinessTime.zone()))
                + "-" + HexFormat.of().formatHex(random).toUpperCase();
    }

    private void auditApply(String actor, Batch batch, Map<String, Object> summary,
                            String clientIp, String traceId) {
        repo.audit(actor, "BATCH_APPLY", batch.targetCode(), batch.batchNo(),
                Map.of("applied", Objects.toString(summary.get("appliedRows"), "0"),
                        "tableRowsAfter", Objects.toString(summary.get("tableRowsAfter"), "0")),
                clientIp, traceId);
    }
}
