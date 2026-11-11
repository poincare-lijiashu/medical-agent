package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.config.MasterDataProperties;
import com.medassist.masterdata.domain.MasterDataModel.Domain;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.domain.MasterDataModel.SyncChange;
import com.medassist.masterdata.domain.MasterDataModel.SyncResult;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.repo.SourceCatalogRepository;
import com.medassist.masterdata.repo.SourceCatalogRepository.SourceRow;
import com.medassist.masterdata.support.Snapshots;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 源字典同步。
 *
 * <p>同步不是"用源表覆盖主数据"，而是<b>对账</b>：
 * <ul>
 *   <li>源表新增 → 建条目（source_system=IN_HOUSE）并记 CREATE 版本；</li>
 *   <li>内容变化 → CAS 更新并记 UPDATE/ENABLE/DISABLE 版本（含字段级 diff）；</li>
 *   <li>源表消失 → 置 MISSING_IN_SOURCE，<b>不删除</b>，映射与历史保留；</li>
 *   <li>手工/导入条目与源表编码冲突 → 记入 conflicts，不覆盖（谁建的要谁负责）；</li>
 *   <li>dryRun=true 只算差异不落库，用于上线前预演。</li>
 * </ul>
 *
 * <p>失败时在独立事务里记录 FAILED 状态：否则"同步失败"这条事实会随外层事务一起回滚，
 * 运维看到的永远是上次成功的旧时间戳。
 */
@Service
public class MasterDataSyncService {

    private static final Logger log = LoggerFactory.getLogger(MasterDataSyncService.class);

    private final MasterDataRepository repo;
    private final SourceCatalogRepository source;
    private final MasterDataWriter writer;
    private final MasterDataProperties properties;
    private final TransactionTemplate failureTx;

    public MasterDataSyncService(MasterDataRepository repo, SourceCatalogRepository source,
                                 MasterDataWriter writer, MasterDataProperties properties,
                                 PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.source = source;
        this.writer = writer;
        this.properties = properties;
        this.failureTx = new TransactionTemplate(transactionManager);
        this.failureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public SyncResult sync(String domainCode, boolean dryRun, String actor,
                           String clientIp, String traceId) {
        Domain domain = repo.findDomain(domainCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.MDM_DOMAIN_NOT_FOUND));
        if (!domain.enabled()) {
            throw new BusinessException(ErrorCode.MDM_DOMAIN_NOT_FOUND, "该主数据域已停用，不能同步");
        }
        if (!domain.syncable() || domain.syncAdapter() == null || domain.syncAdapter().isBlank()) {
            throw new BusinessException(ErrorCode.MDM_DOMAIN_NOT_SYNCABLE,
                    "主数据域 " + domainCode + " 未配置源表适配器，不能从源表同步");
        }

        List<SourceRow> rows;
        try {
            rows = source.load(domain.syncAdapter());
        } catch (DataAccessException e) {
            log.error("mdm.sync.source_failed domain={} adapter={}", domainCode, domain.syncAdapter(), e);
            // 独立事务记录失败，避免随外层回滚
            failureTx.executeWithoutResult(status -> repo.updateSyncResult(domainCode, "FAILED",
                    Map.of("error", rootMessage(e))));
            throw new BusinessException(ErrorCode.MDM_SYNC_FAILED,
                    "源字典读取失败：" + rootMessage(e) + "；请检查源服务与数据库只读权限");
        }

        List<Item> all = repo.listAllItems(domainCode);
        Map<String, Item> byCode = new HashMap<>();
        all.forEach(item -> byCode.put(item.localCode(), item));

        Set<String> seen = new HashSet<>();
        List<String> conflicts = new ArrayList<>();
        List<SyncChange> sample = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int restored = 0;
        int missing = 0;
        int unchanged = 0;

        for (SourceRow row : rows) {
            if (row.code() == null || row.code().isBlank()) {
                conflicts.add("源表存在空编码，已跳过");
                continue;
            }
            if (!seen.add(row.code())) {
                conflicts.add(row.code() + "（源表编码重复，已跳过）");
                continue;
            }
            String status = row.enabled() ? Item.ACTIVE : Item.INACTIVE;
            Map<String, Object> attrs = row.attributes() == null ? Map.of() : row.attributes();
            String fingerprint = Snapshots.fingerprint(row.name(), row.alias(), row.category(),
                    row.spec(), row.unit(), status, null, null, attrs);
            Item existing = byCode.get(row.code());

            if (existing == null) {
                created++;
                addSample(sample, row.code(), "CREATE", row.name());
                if (!dryRun) {
                    writer.create(domainCode, row.code(), row.name(), row.alias(), row.category(),
                            row.spec(), row.unit(), status, Item.SOURCE_IN_HOUSE, row.sourceRef(),
                            null, null, attrs, "CREATE", MasterDataWriter.Ref.SYNC, null,
                            "源表首次同步", actor);
                }
                continue;
            }

            if (!Item.SOURCE_IN_HOUSE.equals(existing.sourceSystem())) {
                conflicts.add(row.code() + "（已存在"
                        + ("MANUAL".equals(existing.sourceSystem()) ? "手工" : "导入")
                        + "条目，未覆盖）");
                continue;
            }

            if (fingerprint.equals(existing.fingerprint())) {
                unchanged++;
                continue;
            }

            String changeType = MasterDataWriter.updateChangeType(existing, status);
            if ("RESTORE".equals(changeType)) {
                restored++;
            } else {
                updated++;
            }
            addSample(sample, row.code(), changeType, row.name());
            if (!dryRun) {
                writer.update(existing, row.name(), row.alias(), row.category(), row.spec(),
                        row.unit(), status, row.sourceRef(), existing.effectiveFrom(),
                        existing.effectiveTo(), attrs, changeType, MasterDataWriter.Ref.SYNC, null,
                        "源同步更新", actor);
            }
        }

        for (Item item : all) {
            if (!Item.SOURCE_IN_HOUSE.equals(item.sourceSystem()) || seen.contains(item.localCode())
                    || Item.MISSING_IN_SOURCE.equals(item.status())) {
                continue;
            }
            missing++;
            addSample(sample, item.localCode(), "MISSING", item.name());
            if (!dryRun) {
                writer.changeStatus(item, Item.MISSING_IN_SOURCE, "MISSING",
                        "源字典中已不存在该编码", MasterDataWriter.Ref.SYNC, null, actor);
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sourceCount", rows.size());
        summary.put("created", created);
        summary.put("updated", updated);
        summary.put("restored", restored);
        summary.put("missing", missing);
        summary.put("unchanged", unchanged);
        summary.put("conflictCount", conflicts.size());
        summary.put("conflicts", conflicts);

        if (!dryRun) {
            repo.updateSyncResult(domainCode, "OK", summary);
            repo.insertAudit(actor, "SYNC", domainCode, null, null, summary, clientIp, traceId);
        }

        return new SyncResult(domainCode, dryRun, rows.size(), created, updated, restored, missing,
                unchanged, List.copyOf(conflicts), List.copyOf(sample), Instant.now());
    }

    private void addSample(List<SyncChange> sample, String code, String action, String name) {
        if (sample.size() < properties.getPreviewSampleRows()) {
            sample.add(new SyncChange(code, action, name));
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
