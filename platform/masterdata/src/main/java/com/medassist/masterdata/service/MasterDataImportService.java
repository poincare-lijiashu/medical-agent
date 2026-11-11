package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.config.MasterDataProperties;
import com.medassist.masterdata.domain.MasterDataModel.Domain;
import com.medassist.masterdata.domain.MasterDataModel.ExternalMap;
import com.medassist.masterdata.domain.MasterDataModel.ExternalSystem;
import com.medassist.masterdata.domain.MasterDataModel.ImportBatch;
import com.medassist.masterdata.domain.MasterDataModel.ImportRow;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.support.CanonicalJson;
import com.medassist.masterdata.support.Csv;
import com.medassist.masterdata.support.Snapshots;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.medassist.masterdata.support.FieldChecks.attributes;
import static com.medassist.masterdata.support.FieldChecks.checkDateOrder;
import static com.medassist.masterdata.support.FieldChecks.optionalDate;
import static com.medassist.masterdata.support.FieldChecks.optionalEnum;
import static com.medassist.masterdata.support.FieldChecks.optionalText;
import static com.medassist.masterdata.support.FieldChecks.requireCode;
import static com.medassist.masterdata.support.FieldChecks.requireText;
import static com.medassist.masterdata.support.FieldChecks.within;

/**
 * 批量导入（JSON 主数据 / CSV 映射）。
 *
 * <p>商业系统与演示系统的差别就在这里：导入不是"一把梭写库"，而是
 * <ol>
 *   <li><b>预检</b>：逐行校验并计算 CREATE/UPDATE/UNCHANGED/CONFLICT/ERROR，给出可读原因；</li>
 *   <li><b>校验和</b>：预检结果带内容校验和；应用时必须与预检一致，防止"预检干净、应用脏"；</li>
 *   <li><b>全有或全无</b>：只要预检存在 ERROR/CONFLICT，应用直接拒绝，不做半截导入；</li>
 *   <li><b>幂等</b>：同一域 + 同一校验和已成功应用过，重复应用返回原批次，不重复写版本；</li>
 *   <li><b>不越权</b>：源表同步条目（IN_HOUSE）的内容不允许导入覆盖，只能通过源表同步；
 *       批量导入可为其新增/修订映射（映射由主数据域负责）。</li>
 * </ol>
 *
 * <p>JSON 与 CSV 共用同一套批次、校验和与审计，只是字段解释不同。
 */
@Service
public class MasterDataImportService {

    private static final Set<String> ITEM_STATUSES = Set.of(Item.ACTIVE, Item.INACTIVE);
    private static final Set<String> MAP_TYPES = Set.of(
            ExternalMap.EXACT, ExternalMap.EQUIVALENT, ExternalMap.GROUP);
    private static final String CSV_HEADER_DOMAIN = "域编码";
    private static final String CSV_HEADER_LOCAL = "本地编码";
    private static final String CSV_HEADER_SYSTEM = "系统编码";
    private static final String CSV_HEADER_EXTERNAL = "外部编码";

    private final MasterDataRepository repo;
    private final MasterDataWriter writer;
    private final MasterDataProperties properties;

    public MasterDataImportService(MasterDataRepository repo, MasterDataWriter writer,
                                   MasterDataProperties properties) {
        this.repo = repo;
        this.writer = writer;
        this.properties = properties;
    }

    /** 导入的一条主数据（映射可选）。 */
    public record ItemSpec(
            String localCode,
            String name,
            String alias,
            String category,
            String spec,
            String unit,
            String status,
            String effectiveFrom,
            String effectiveTo,
            Map<String, Object> attributes,
            List<MappingSpec> mappings) {
    }

    /** 导入的一条映射。 */
    public record MappingSpec(
            String systemCode,
            String externalCode,
            String externalName,
            String mapType,
            Boolean primary,
            String effectiveFrom,
            String effectiveTo,
            String remark) {
    }

    // ------------------------------------------------------------------ JSON

    @Transactional
    public ImportBatch previewJson(String domainCode, String fileName, List<ItemSpec> items,
                                   String actor, String clientIp, String traceId) {
        requireActor(actor);
        Prepared prepared = prepareJson(domainCode, items);
        Plan plan = planJson(prepared);
        ImportBatch batch = save(ImportBatch.PREVIEW, "JSON", domainCode.trim(), fileName,
                prepared.checksum(), plan, ImportBatch.PREVIEWED, actor);
        audit(actor, "IMPORT_PREVIEW", domainCode, null, batch.batchNo(),
                summary(plan, prepared.rows().size()), clientIp, traceId);
        return batch;
    }

    @Transactional
    public ImportBatch applyJson(String domainCode, String fileName, List<ItemSpec> items,
                                 String previewBatchNo, String actor, String clientIp, String traceId) {
        requireActor(actor);
        Prepared prepared = prepareJson(domainCode, items);
        ImportBatch preview = requirePreview(previewBatchNo, "JSON", prepared.checksum(),
                domainCode == null ? null : domainCode.trim());
        return apply(prepared, preview, () -> planJson(prepared), "JSON",
                domainCode == null ? null : domainCode.trim(), fileName, actor, clientIp, traceId);
    }

    // ------------------------------------------------------------------ CSV

    @Transactional
    public ImportBatch previewCsv(String csv, String fileName, String actor,
                                  String clientIp, String traceId) {
        requireActor(actor);
        Prepared prepared = prepareCsv(csv);
        Plan plan = planCsv(prepared);
        ImportBatch batch = save(ImportBatch.PREVIEW, "CSV", null, fileName,
                prepared.checksum(), plan, ImportBatch.PREVIEWED, actor);
        audit(actor, "IMPORT_PREVIEW", null, null, batch.batchNo(),
                summary(plan, prepared.rows().size()), clientIp, traceId);
        return batch;
    }

    @Transactional
    public ImportBatch applyCsv(String csv, String fileName, String previewBatchNo, String actor,
                                String clientIp, String traceId) {
        requireActor(actor);
        Prepared prepared = prepareCsv(csv);
        ImportBatch preview = requirePreview(previewBatchNo, "CSV", prepared.checksum(), null);
        return apply(prepared, preview, () -> planCsv(prepared), "CSV", null, fileName,
                actor, clientIp, traceId);
    }

    // ------------------------------------------------------------------ 批次查询

    public List<ImportBatch> listBatches(String domainCode, String format, Integer limit) {
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return repo.listImportBatches(domainCode, format, size);
    }

    /** 批次详情：返回指定分页的逐行结果（默认前 100 行）。 */
    public ImportBatch importBatch(UUID id, Integer limit, Integer offset) {
        ImportBatch batch = repo.findImportBatch(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_IMPORT_NOT_FOUND));
        int size = limit == null ? 100 : Math.min(Math.max(limit, 1), 500);
        int start = offset == null ? 0 : Math.max(offset, 0);
        List<ImportRow> rows = repo.listImportRows(id, size, start);
        return new ImportBatch(batch.id(), batch.batchNo(), batch.mode(), batch.format(),
                batch.domainCode(), batch.fileName(), batch.checksum(), batch.rowCount(),
                batch.createCount(), batch.updateCount(), batch.unchangedCount(),
                batch.conflictCount(), batch.errorCount(), batch.status(), batch.summary(),
                batch.createdBy(), batch.createdAt(), rows);
    }

    // ------------------------------------------------------------------ 预检准备（纯语法，校验和稳定）

    private record PreparedRow(
            int rowNo,
            String domainCode,
            String localCode,
            NormalizedItem item,
            List<NormalizedMapping> mappings,
            String error) {
    }

    private record Prepared(String checksum, List<PreparedRow> rows) {
    }

    private record NormalizedItem(
            String localCode,
            String name,
            String alias,
            String category,
            String spec,
            String unit,
            String status,
            String effectiveFrom,
            String effectiveTo,
            Map<String, Object> attributes) {
    }

    private record NormalizedMapping(
            String systemCode,
            String externalCode,
            String externalName,
            String mapType,
            boolean primary,
            String effectiveFrom,
            String effectiveTo,
            String remark) {
    }

    private Prepared prepareJson(String domainCode, List<ItemSpec> items) {
        if (domainCode == null || domainCode.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少主数据域编码");
        }
        String domain = domainCode.trim();
        requireDomain(domain);
        if (items == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "导入条目不能为空");
        }
        within(items.size(), properties.getMaxImportRows(), "导入条目");

        List<PreparedRow> rows = new ArrayList<>();
        List<NormalizedItem> valid = new ArrayList<>();
        Set<String> codes = new HashSet<>();

        for (int i = 0; i < items.size(); i++) {
            int rowNo = i + 1;
            ItemSpec spec = items.get(i);
            try {
                if (spec == null) {
                    throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID, "导入行内容为空");
                }
                String code = requireCode(spec.localCode());
                if (!codes.add(code)) {
                    throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                            "第 " + rowNo + " 行本地编码 " + code + " 在本次导入中重复");
                }
                NormalizedItem item = normalizeItem(spec, code);
                List<NormalizedMapping> mappings = normalizeMappings(spec.mappings(), rowNo);
                rows.add(new PreparedRow(rowNo, domain, code, item, mappings, null));
                valid.add(item);
            } catch (BusinessException e) {
                rows.add(new PreparedRow(rowNo, domain,
                        spec == null ? null : trim(spec.localCode()), null, List.of(), e.getMessage()));
            }
        }

        Map<String, Object> checksumPayload = new LinkedHashMap<>();
        checksumPayload.put("format", "JSON");
        checksumPayload.put("domain", domain);
        checksumPayload.put("items", valid.stream().sorted((a, b) ->
                a.localCode().compareTo(b.localCode())).map(this::checksumItem).toList());
        return new Prepared(CanonicalJson.checksum("", checksumPayload), rows);
    }

    private NormalizedItem normalizeItem(ItemSpec spec, String code) {
        if (spec == null) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID, "导入行内容为空");
        }
        String name = requireText(spec.name(), "名称", 200);
        String status = optionalEnum(spec.status(), ITEM_STATUSES, Item.ACTIVE);
        String from = optionalDate(spec.effectiveFrom());
        String to = optionalDate(spec.effectiveTo());
        checkDateOrder(from, to);
        Map<String, Object> attrs = attributes(spec.attributes());
        return new NormalizedItem(code, name, optionalText(spec.alias(), 64),
                optionalText(spec.category(), 64), optionalText(spec.spec(), 200),
                optionalText(spec.unit(), 32), status, from, to, attrs);
    }

    private List<NormalizedMapping> normalizeMappings(List<MappingSpec> specs, int rowNo) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        List<NormalizedMapping> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (MappingSpec spec : specs) {
            if (spec == null) {
                throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                        "第 " + rowNo + " 行存在空映射");
            }
            String system = requireText(spec.systemCode(), "外部系统编码", 64);
            String external = requireText(spec.externalCode(), "外部编码", 128);
            if (!keys.add(system + "\u0000" + external)) {
                throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                        "第 " + rowNo + " 行映射 " + system + ":" + external + " 重复");
            }
            String mapType = optionalEnum(spec.mapType(), MAP_TYPES, ExternalMap.EXACT);
            String from = optionalDate(spec.effectiveFrom());
            String to = optionalDate(spec.effectiveTo());
            checkDateOrder(from, to);
            result.add(new NormalizedMapping(system, external,
                    optionalText(spec.externalName(), 200), mapType,
                    Boolean.TRUE.equals(spec.primary()), from, to,
                    optionalText(spec.remark(), 500)));
        }
        return result;
    }

    private Map<String, Object> checksumItem(NormalizedItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("localCode", item.localCode());
        map.put("name", item.name());
        map.put("alias", item.alias());
        map.put("category", item.category());
        map.put("spec", item.spec());
        map.put("unit", item.unit());
        map.put("status", item.status());
        map.put("effectiveFrom", item.effectiveFrom());
        map.put("effectiveTo", item.effectiveTo());
        map.put("attributes", item.attributes());
        return map;
    }

    // ------------------------------------------------------------------ 计划（结合数据库状态）

    private record PlannedRow(
            int rowNo,
            String domainCode,
            String localCode,
            String action,
            String message,
            NormalizedItem item,
            List<NormalizedMapping> mappings) {
    }

    private record Plan(int create, int update, int unchanged, int conflict, int error,
                        List<PlannedRow> rows) {
    }

    private Plan planJson(Prepared prepared) {
        List<PlannedRow> rows = new ArrayList<>();
        int create = 0;
        int update = 0;
        int unchanged = 0;
        int conflict = 0;
        int error = 0;

        for (PreparedRow row : prepared.rows()) {
            if (row.error() != null) {
                error++;
                rows.add(new PlannedRow(row.rowNo(), row.domainCode(), row.localCode(),
                        ImportRow.ERROR, row.error(), null, List.of()));
                continue;
            }
            Item existing = repo.findItem(row.domainCode(), row.localCode()).orElse(null);
            NormalizedItem target = row.item();
            String fingerprint = fingerprint(target);

            if (existing == null) {
                Map<String, Object> mapping = planMappings(row.domainCode(), row.localCode(),
                        row.mappings(), null);
                if (mapping.get("error") instanceof String mappingError) {
                    error++;
                    rows.add(new PlannedRow(row.rowNo(), row.domainCode(), row.localCode(),
                            ImportRow.ERROR, mappingError, target, row.mappings()));
                    continue;
                }
                create++;
                rows.add(new PlannedRow(row.rowNo(), row.domainCode(), row.localCode(),
                        ImportRow.CREATE, null, target, row.mappings()));
                continue;
            }

            boolean itemChanged = !fingerprint.equals(existing.fingerprint());
            if (Item.SOURCE_IN_HOUSE.equals(existing.sourceSystem()) && itemChanged) {
                conflict++;
                rows.add(new PlannedRow(row.rowNo(), row.domainCode(), row.localCode(),
                        ImportRow.CONFLICT,
                        "该编码由院内源字典同步维护，导入不能覆盖条目内容（映射可单独导入）",
                        target, row.mappings()));
                continue;
            }
            Map<String, Object> mappingPlan = planMappings(row.domainCode(), row.localCode(),
                    row.mappings(), existing);
            if (mappingPlan.get("error") instanceof String mappingError) {
                error++;
                rows.add(new PlannedRow(row.rowNo(), row.domainCode(), row.localCode(),
                        ImportRow.ERROR, mappingError, target, row.mappings()));
                continue;
            }
            boolean mappingChanged = Boolean.TRUE.equals(mappingPlan.get("changed"));
            if (!itemChanged && !mappingChanged) {
                unchanged++;
                rows.add(new PlannedRow(row.rowNo(), row.domainCode(), row.localCode(),
                        ImportRow.UNCHANGED, null, target, row.mappings()));
            } else {
                update++;
                rows.add(new PlannedRow(row.rowNo(), row.domainCode(), row.localCode(),
                        ImportRow.UPDATE, null, target, row.mappings()));
            }
        }
        return new Plan(create, update, unchanged, conflict, error, rows);
    }

    /**
     * 映射计划：校验外部系统存在且启用、检查是否全部无需变化。
     *
     * @return 空 Map 表示无错误；包含 {@code changed=true} 表示至少一条映射需要新增/修订
     */
    private Map<String, Object> planMappings(String domain, String localCode,
                                             List<NormalizedMapping> mappings, Item existing) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (mappings.isEmpty()) {
            return result;
        }
        boolean changed = false;
        for (NormalizedMapping mapping : mappings) {
            ExternalSystem system = repo.findExternalSystem(mapping.systemCode()).orElse(null);
            if (system == null || !ExternalSystem.ACTIVE.equals(system.status())) {
                result.put("error", "外部系统 " + mapping.systemCode() + " 不存在或已停用");
                return result;
            }
            ExternalMap current = repo.findMappingByKey(domain, localCode, mapping.systemCode(),
                    mapping.externalCode()).orElse(null);
            if (current == null) {
                changed = true;
                continue;
            }
            if (!ExternalMap.ACTIVE.equals(current.status()) || !sameMapping(current, mapping)) {
                changed = true;
            }
        }
        if (changed) {
            result.put("changed", true);
        }
        return result;
    }

    private boolean sameMapping(ExternalMap current, NormalizedMapping mapping) {
        return Objects.equals(current.externalName(), mapping.externalName())
                && Objects.equals(current.mapType(), mapping.mapType())
                && current.primary() == mapping.primary()
                && Objects.equals(iso(current.effectiveFrom()), mapping.effectiveFrom())
                && Objects.equals(iso(current.effectiveTo()), mapping.effectiveTo())
                && Objects.equals(current.remark(), mapping.remark());
    }

    private String fingerprint(NormalizedItem item) {
        return Snapshots.fingerprint(item.name(), item.alias(), item.category(), item.spec(),
                item.unit(), item.status(), parse(item.effectiveFrom()), parse(item.effectiveTo()),
                item.attributes());
    }

    // ------------------------------------------------------------------ 应用

    private ImportBatch apply(Prepared prepared, ImportBatch preview,
                              java.util.function.Supplier<Plan> planner,
                              String format, String domainCode, String fileName,
                              String actor, String clientIp, String traceId) {
        // 幂等：同内容已成功应用过 → 直接返回原批次
        ImportBatch appliedBefore = repo.findAppliedBatch(domainCode, prepared.checksum()).orElse(null);
        if (appliedBefore != null) {
            return withRows(appliedBefore);
        }

        if (preview.errorCount() > 0 || preview.conflictCount() > 0) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                    "预检存在 " + (preview.errorCount() + preview.conflictCount())
                    + " 行错误或冲突，请修正后重新预检");
        }

        Plan plan = planner.get();
        if (plan.error() > 0 || plan.conflict() > 0) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_CHECKSUM_MISMATCH,
                    "预检后主数据或外部系统配置已变化，请重新预检");
        }
        if (!prepared.checksum().equals(preview.checksum())) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_CHECKSUM_MISMATCH);
        }
        verifyActions(preview.id(), plan);

        for (PlannedRow row : plan.rows()) {
            if (ImportRow.CREATE.equals(row.action()) || ImportRow.UPDATE.equals(row.action())) {
                applyRow(row, preview.batchNo(), actor);
            }
        }

        ImportBatch batch = save(ImportBatch.APPLY, format, domainCode, fileName,
                prepared.checksum(), plan, ImportBatch.APPLIED, actor);
        audit(actor, "IMPORT_APPLY", domainCode, null, batch.batchNo(),
                summary(plan, prepared.rows().size()), clientIp, traceId);
        return batch;
    }

    private void verifyActions(UUID previewId, Plan plan) {
        List<ImportRow> previewRows = repo.listImportRows(previewId,
                properties.getMaxImportRows(), 0);
        if (previewRows.size() != plan.rows().size()) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_CHECKSUM_MISMATCH,
                    "预检行数与本次提交不一致，请重新预检");
        }
        Map<Integer, ImportRow> byRow = new HashMap<>();
        previewRows.forEach(row -> byRow.put(row.rowNo(), row));
        for (PlannedRow row : plan.rows()) {
            ImportRow before = byRow.get(row.rowNo());
            if (before == null || !row.action().equals(before.action())) {
                throw new BusinessException(ErrorCode.MDM_IMPORT_CHECKSUM_MISMATCH,
                        "第 " + row.rowNo() + " 行的预检结论已变化，请重新预检");
            }
        }
    }

    private void applyRow(PlannedRow row, String batchNo, String actor) {
        if (row.item() == null) {
            // CSV 映射行
            NormalizedMapping mapping = row.mappings().get(0);
            applyMapping(row.domainCode(), row.localCode(), mapping, batchNo, actor);
            return;
        }
        NormalizedItem target = row.item();
        Item existing = repo.findItem(row.domainCode(), row.localCode()).orElse(null);
        if (existing == null) {
            writer.create(row.domainCode(), row.localCode(), target.name(), target.alias(),
                    target.category(), target.spec(), target.unit(), target.status(),
                    Item.SOURCE_IMPORT, null, parse(target.effectiveFrom()), parse(target.effectiveTo()),
                    target.attributes(), "IMPORT", MasterDataWriter.Ref.IMPORT, batchNo,
                    "批量导入（批次 " + batchNo + "）", actor);
        } else if (!Item.SOURCE_IN_HOUSE.equals(existing.sourceSystem())
                && !fingerprint(target).equals(existing.fingerprint())) {
            String changeType = MasterDataWriter.updateChangeType(existing, target.status());
            writer.update(existing, target.name(), target.alias(), target.category(), target.spec(),
                    target.unit(), target.status(), existing.sourceRef(),
                    parse(target.effectiveFrom()), parse(target.effectiveTo()), target.attributes(),
                    changeType, MasterDataWriter.Ref.IMPORT, batchNo,
                    "批量导入（批次 " + batchNo + "）", actor);
        }
        for (NormalizedMapping mapping : row.mappings()) {
            applyMapping(row.domainCode(), row.localCode(), mapping, batchNo, actor);
        }
    }

    private void applyMapping(String domain, String localCode, NormalizedMapping mapping,
                              String batchNo, String actor) {
        ExternalMap existing = repo.findMappingByKey(domain, localCode, mapping.systemCode(),
                mapping.externalCode()).orElse(null);
        if (existing != null && ExternalMap.ACTIVE.equals(existing.status())
                && sameMapping(existing, mapping)) {
            return;
        }
        writer.upsertMapping(domain, localCode, mapping.systemCode(), mapping.externalCode(),
                mapping.externalName(), mapping.mapType(), mapping.primary(), ExternalMap.ACTIVE,
                parse(mapping.effectiveFrom()), parse(mapping.effectiveTo()), mapping.remark(),
                MasterDataWriter.Ref.IMPORT, batchNo, "批量导入映射（批次 " + batchNo + "）", actor);
    }

    // ------------------------------------------------------------------ CSV

    private Prepared prepareCsv(String csv) {
        List<List<String>> table = Csv.parse(csv);
        if (table.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "CSV 内容为空");
        }
        List<String> header = table.get(0);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i).trim(), i);
        }
        List<String> required = List.of(CSV_HEADER_DOMAIN, CSV_HEADER_LOCAL,
                CSV_HEADER_SYSTEM, CSV_HEADER_EXTERNAL);
        for (String column : required) {
            if (!index.containsKey(column)) {
                throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                        "CSV 表头缺少必需列：" + column + "（至少包含 域编码,本地编码,系统编码,外部编码）");
            }
        }
        if (table.size() - 1 > properties.getMaxImportRows()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "CSV 行数超过上限 " + properties.getMaxImportRows());
        }

        List<PreparedRow> rows = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> line = table.get(i);
            int rowNo = i;
            try {
                String domain = requireText(value(line, index, CSV_HEADER_DOMAIN), "域编码", 64);
                String localCode = requireCode(value(line, index, CSV_HEADER_LOCAL));
                String system = requireText(value(line, index, CSV_HEADER_SYSTEM), "系统编码", 64);
                String external = requireText(value(line, index, CSV_HEADER_EXTERNAL), "外部编码", 128);
                if (!keys.add(domain + "\u0000" + localCode + "\u0000" + system + "\u0000" + external)) {
                    throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                            "第 " + rowNo + " 行映射 " + localCode + "/" + system + ":" + external
                            + " 在本次导入中重复");
                }
                String mapType = optionalEnum(optValue(line, index, "映射类型"),
                        MAP_TYPES, ExternalMap.EXACT);
                boolean primary = truthy(optValue(line, index, "主映射"));
                String status = optionalEnum(optValue(line, index, "状态"),
                        Set.of(ExternalMap.ACTIVE, ExternalMap.INACTIVE), ExternalMap.ACTIVE);
                if (ExternalMap.INACTIVE.equals(status)) {
                    throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                            "第 " + rowNo + " 行状态为 INACTIVE：停用映射请走变更申请（MAPPING_DELETE）");
                }
                String from = optionalDate(optValue(line, index, "生效起"));
                String to = optionalDate(optValue(line, index, "生效止"));
                checkDateOrder(from, to);
                NormalizedMapping mapping = new NormalizedMapping(system, external,
                        optionalText(optValue(line, index, "外部名称"), 200), mapType, primary,
                        from, to, optionalText(optValue(line, index, "备注"), 500));
                rows.add(new PreparedRow(rowNo, domain, localCode, null, List.of(mapping), null));
            } catch (BusinessException e) {
                rows.add(new PreparedRow(rowNo, null, null, null, List.of(), e.getMessage()));
            }
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        for (PreparedRow row : rows) {
            if (row.error() != null) {
                continue;
            }
            NormalizedMapping mapping = row.mappings().get(0);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("domainCode", row.domainCode());
            map.put("localCode", row.localCode());
            map.put("systemCode", mapping.systemCode());
            map.put("externalCode", mapping.externalCode());
            map.put("externalName", mapping.externalName());
            map.put("mapType", mapping.mapType());
            map.put("primary", mapping.primary());
            map.put("effectiveFrom", mapping.effectiveFrom());
            map.put("effectiveTo", mapping.effectiveTo());
            map.put("remark", mapping.remark());
            entries.add(map);
        }
        entries.sort((a, b) -> {
            int byDomain = a.get("domainCode").toString().compareTo(b.get("domainCode").toString());
            if (byDomain != 0) {
                return byDomain;
            }
            int byLocal = a.get("localCode").toString().compareTo(b.get("localCode").toString());
            if (byLocal != 0) {
                return byLocal;
            }
            int bySystem = a.get("systemCode").toString().compareTo(b.get("systemCode").toString());
            return bySystem != 0 ? bySystem
                    : a.get("externalCode").toString().compareTo(b.get("externalCode").toString());
        });

        Map<String, Object> checksumPayload = new LinkedHashMap<>();
        checksumPayload.put("format", "CSV");
        checksumPayload.put("mappings", entries);
        return new Prepared(CanonicalJson.checksum("", checksumPayload), rows);
    }

    private Plan planCsv(Prepared prepared) {
        List<PlannedRow> rows = new ArrayList<>();
        int create = 0;
        int update = 0;
        int unchanged = 0;
        int conflict = 0;
        int error = 0;
        for (PreparedRow row : prepared.rows()) {
            if (row.error() != null) {
                error++;
                rows.add(new PlannedRow(row.rowNo(), null, null, ImportRow.ERROR,
                        row.error(), null, List.of()));
                continue;
            }
            String domain = row.domainCode();
            String localCode = row.localCode();
            NormalizedMapping mapping = row.mappings().get(0);
            try {
                requireDomain(domain);
                Item item = repo.findItem(domain, localCode).orElse(null);
                if (item == null) {
                    throw new BusinessException(ErrorCode.MDM_ITEM_NOT_FOUND,
                            "本地编码 " + localCode + " 在域 " + domain + " 中不存在");
                }
                ExternalSystem system = repo.findExternalSystem(mapping.systemCode()).orElse(null);
                if (system == null || !ExternalSystem.ACTIVE.equals(system.status())) {
                    throw new BusinessException(ErrorCode.MDM_EXTERNAL_SYSTEM_NOT_FOUND,
                            "外部系统 " + mapping.systemCode() + " 不存在或已停用");
                }
                ExternalMap existing = repo.findMappingByKey(domain, localCode,
                        mapping.systemCode(), mapping.externalCode()).orElse(null);
                String action;
                if (existing == null) {
                    action = ImportRow.CREATE;
                    create++;
                } else if (ExternalMap.ACTIVE.equals(existing.status())
                        && sameMapping(existing, mapping)) {
                    action = ImportRow.UNCHANGED;
                    unchanged++;
                } else {
                    action = ImportRow.UPDATE;
                    update++;
                }
                rows.add(new PlannedRow(row.rowNo(), domain, localCode, action, null, null,
                        List.of(mapping)));
            } catch (BusinessException e) {
                error++;
                rows.add(new PlannedRow(row.rowNo(), domain, localCode, ImportRow.ERROR,
                        e.getMessage(), null, List.of(mapping)));
            }
        }
        return new Plan(create, update, unchanged, conflict, error, rows);
    }

    // ------------------------------------------------------------------ 批次与审计

    private ImportBatch save(String mode, String format, String domainCode, String fileName,
                             String checksum, Plan plan, String status, String actor) {
        ImportBatch batch = new ImportBatch(UUID.randomUUID(), batchNo(), mode, format, domainCode,
                fileName, checksum, plan.rows().size(), plan.create(), plan.update(),
                plan.unchanged(), plan.conflict(), plan.error(), status,
                summary(plan, plan.rows().size()), actor, Instant.now(), List.of());
        repo.insertImportBatch(batch);
        List<ImportRow> rows = plan.rows().stream().map(row -> new ImportRow(0, row.rowNo(),
                row.localCode(), row.action(), row.message(), detail(row))).toList();
        repo.insertImportRows(batch.id(), rows);
        int sample = Math.min(rows.size(), properties.getPreviewSampleRows());
        return new ImportBatch(batch.id(), batch.batchNo(), mode, format, domainCode, fileName,
                checksum, plan.rows().size(), plan.create(), plan.update(), plan.unchanged(),
                plan.conflict(), plan.error(), status, batch.summary(), actor, batch.createdAt(),
                rows.subList(0, sample));
    }

    private Map<String, Object> detail(PlannedRow row) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (row.item() != null) {
            detail.put("name", row.item().name());
            detail.put("status", row.item().status());
        }
        if (!row.mappings().isEmpty()) {
            detail.put("mappings", row.mappings().size());
            detail.put("systems", row.mappings().stream().map(NormalizedMapping::systemCode)
                    .distinct().toList());
        }
        return detail;
    }

    private ImportBatch withRows(ImportBatch batch) {
        List<ImportRow> rows = repo.listImportRows(batch.id(), properties.getPreviewSampleRows(), 0);
        return new ImportBatch(batch.id(), batch.batchNo(), batch.mode(), batch.format(),
                batch.domainCode(), batch.fileName(), batch.checksum(), batch.rowCount(),
                batch.createCount(), batch.updateCount(), batch.unchangedCount(),
                batch.conflictCount(), batch.errorCount(), batch.status(), batch.summary(),
                batch.createdBy(), batch.createdAt(), rows);
    }

    private ImportBatch requirePreview(String previewBatchNo, String format, String checksum,
                                       String domainCode) {
        if (previewBatchNo == null || previewBatchNo.isBlank()) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                    "应用导入必须提供预检批次号（先预检、再应用）");
        }
        ImportBatch preview = repo.findImportBatchByNo(previewBatchNo.trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_IMPORT_NOT_FOUND));
        if (!ImportBatch.PREVIEW.equals(preview.mode()) || !format.equals(preview.format())) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_INVALID,
                    "该批次不是 " + format + " 格式的预检批次，无法用于应用");
        }
        if (!checksum.equals(preview.checksum())) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_CHECKSUM_MISMATCH,
                    "提交内容与预检批次不一致，请重新预检");
        }
        if (!Objects.equals(domainCode, preview.domainCode())) {
            throw new BusinessException(ErrorCode.MDM_IMPORT_CHECKSUM_MISMATCH,
                    "提交的主数据域与预检批次不一致，请重新预检");
        }
        return preview;
    }

    private Map<String, Object> summary(Plan plan, int rowCount) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rowCount", rowCount);
        summary.put("create", plan.create());
        summary.put("update", plan.update());
        summary.put("unchanged", plan.unchanged());
        summary.put("conflict", plan.conflict());
        summary.put("error", plan.error());
        return summary;
    }

    private void audit(String actor, String action, String domainCode, String localCode,
                       String refId, Map<String, Object> detail, String clientIp, String traceId) {
        repo.insertAudit(actor, action, domainCode, localCode, refId, detail, clientIp, traceId);
    }

    // ------------------------------------------------------------------ 工具

    private Domain requireDomain(String domainCode) {
        return repo.findDomain(domainCode)
                .filter(Domain::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_DOMAIN_NOT_FOUND,
                        "未找到主数据域 " + domainCode + "，或该域已停用"));
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank() || "system".equals(actor)) {
            throw new BusinessException(ErrorCode.MDM_ACTOR_REQUIRED);
        }
    }

    private static String value(List<String> line, Map<String, Integer> index, String column) {
        Integer position = index.get(column);
        if (position == null || position >= line.size()) {
            return null;
        }
        return line.get(position);
    }

    private static String optValue(List<String> line, Map<String, Integer> index, String column) {
        return value(line, index, column);
    }

    private static boolean truthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String text = value.trim().toLowerCase();
        return "1".equals(text) || "true".equals(text) || "yes".equals(text) || "是".equals(text);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static LocalDate parse(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static String batchNo() {
        return "MDM-IMP-" + LocalDate.now().toString().replace("-", "") + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
