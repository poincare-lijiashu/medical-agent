package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.domain.MasterDataModel.ExternalMap;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.domain.MasterDataModel.ItemVersion;
import com.medassist.masterdata.domain.MasterDataModel.MappingVersion;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.support.CanonicalJson;
import com.medassist.masterdata.support.Snapshots;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * 主数据写入器：所有条目/映射的落库与版本记账的唯一入口。
 *
 * <p>集中在这里的原因：版本号、校验和链、CAS、快照口径必须完全一致。
 * 如果同步、审批、导入各写一套，迟早出现"同一个变更在三条路径上产生三种版本记录"。
 *
 * <p>本类不开启事务：由调用方（服务层）用 {@code @Transactional} 圈住
 * "版本 + 业务表 + 审计"的原子性。
 */
@Component
public class MasterDataWriter {

    private final MasterDataRepository repo;

    public MasterDataWriter(MasterDataRepository repo) {
        this.repo = repo;
    }

    /** 新建条目并写首版版本记录。 */
    public Item create(String domainCode, String localCode, String name, String alias,
                       String category, String spec, String unit, String status,
                       String sourceSystem, String sourceRef, LocalDate effectiveFrom,
                       LocalDate effectiveTo, Map<String, Object> attributes,
                       String changeType, String refType, String refId, String reason, String actor) {
        Map<String, Object> attrs = attributes == null ? Map.of() : attributes;
        String fingerprint = Snapshots.fingerprint(name, alias, category, spec, unit, status,
                effectiveFrom, effectiveTo, attrs);
        Item item = new Item(UUID.randomUUID(), domainCode, localCode, name, alias, category, spec,
                unit, status, sourceSystem, sourceRef, effectiveFrom, effectiveTo, attrs,
                fingerprint, 0, Instant.now(), Instant.now());
        try {
            repo.insertItem(item);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.MDM_ITEM_DUPLICATE,
                    "该域下已存在本地编码 " + localCode + " 的主数据条目");
        }
        recordItemVersion(item, 1, changeType, null, reason, refType, refId, actor);
        return item;
    }

    /**
     * 更新条目（CAS）。
     *
     * @param changeType CREATE/UPDATE/ENABLE/DISABLE/RESTORE 由调用方判定
     * @return 更新后的条目（version 已 +1）
     */
    public Item update(Item existing, String name, String alias, String category, String spec,
                       String unit, String status, String sourceRef, LocalDate effectiveFrom,
                       LocalDate effectiveTo, Map<String, Object> attributes,
                       String changeType, String refType, String refId, String reason, String actor) {
        Map<String, Object> attrs = attributes == null ? Map.of() : attributes;
        String fingerprint = Snapshots.fingerprint(name, alias, category, spec, unit, status,
                effectiveFrom, effectiveTo, attrs);
        Item next = new Item(existing.id(), existing.domainCode(), existing.localCode(), name, alias,
                category, spec, unit, status, existing.sourceSystem(), sourceRef, effectiveFrom,
                effectiveTo, attrs, fingerprint, existing.version(), existing.createdAt(), Instant.now());
        if (!repo.updateItem(next, existing.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该主数据条目已被他人修改，请刷新后重新提交");
        }
        Item updated = new Item(next.id(), next.domainCode(), next.localCode(), next.name(),
                next.alias(), next.category(), next.spec(), next.unit(), next.status(),
                next.sourceSystem(), next.sourceRef(), next.effectiveFrom(), next.effectiveTo(),
                next.attributes(), next.fingerprint(), existing.version() + 1,
                next.createdAt(), next.updatedAt());
        Map<String, Object> diff = Snapshots.diff(Snapshots.of(existing), Snapshots.of(updated));
        recordItemVersion(updated, existing.version() + 1, changeType, diff, reason, refType, refId, actor);
        return updated;
    }

    /** 仅变更状态（启用/停用/源缺失/恢复）。 */
    public Item changeStatus(Item existing, String status, String changeType, String reason,
                             String refType, String refId, String actor) {
        if (!repo.updateItemStatus(existing.id(), status, existing.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该主数据条目已被他人修改，请刷新后重新提交");
        }
        Item updated = new Item(existing.id(), existing.domainCode(), existing.localCode(),
                existing.name(), existing.alias(), existing.category(), existing.spec(),
                existing.unit(), status, existing.sourceSystem(), existing.sourceRef(),
                existing.effectiveFrom(), existing.effectiveTo(), existing.attributes(),
                existing.fingerprint(), existing.version() + 1, existing.createdAt(), Instant.now());
        Map<String, Object> diff = Snapshots.diff(
                Map.of("status", existing.status()), Map.of("status", status));
        recordItemVersion(updated, existing.version() + 1, changeType, diff, reason, refType, refId, actor);
        return updated;
    }

    /** 追加一条条目版本（哈希链自动串联）。 */
    public void recordItemVersion(Item item, long versionNo, String changeType,
                                  Map<String, Object> diff, String reason, String refType,
                                  String refId, String actor) {
        String prev = repo.lastItemChecksum(item.id()).orElse(null);
        Map<String, Object> snapshot = Snapshots.of(item);
        String checksum = CanonicalJson.checksum(prev, snapshot);
        repo.insertItemVersion(new ItemVersion(0, item.id(), item.domainCode(), item.localCode(),
                versionNo, changeType, snapshot, diff, reason, refType, refId, actor,
                prev, checksum, Instant.now()));
    }

    /**
     * 新增或更新映射。
     *
     * <p>主映射切换时先把同域同系统的其他主映射降级，再写当前映射，
     * 避免部分唯一索引在中间状态冲突；被降级的映射同样记一条版本，便于追溯"为什么不是主映射"。
     */
    public ExternalMap upsertMapping(String domainCode, String localCode, String systemCode,
                                     String externalCode, String externalName, String mapType,
                                     boolean primary, String status, LocalDate effectiveFrom,
                                     LocalDate effectiveTo, String remark,
                                     String refType, String refId, String reason, String actor) {
        ExternalMap existing = repo.findMappingByKey(domainCode, localCode, systemCode, externalCode)
                .orElse(null);
        UUID mappingId = existing == null ? UUID.randomUUID() : existing.id();

        if (primary && ExternalMap.ACTIVE.equals(status)) {
            for (ExternalMap other : repo.findOtherPrimary(domainCode, localCode, systemCode)) {
                if (other.id().equals(mappingId)) {
                    continue;
                }
                if (repo.demotePrimary(other.id(), other.version())) {
                    ExternalMap demoted = replica(other, other.version() + 1, false, other.status(),
                            other.externalName(), other.mapType(), other.effectiveFrom(),
                            other.effectiveTo(), other.remark());
                    recordMappingVersion(demoted, other.version() + 1, "UPDATE",
                            "主映射切换到 " + externalCode, refType, refId, actor);
                }
            }
        }

        if (existing == null) {
            ExternalMap created = new ExternalMap(mappingId, domainCode, localCode, null, systemCode,
                    null, externalCode, externalName, mapType, primary, status, effectiveFrom,
                    effectiveTo, remark, 0, Instant.now(), Instant.now());
            try {
                repo.insertMapping(created);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ErrorCode.MDM_MAPPING_DUPLICATE,
                        "该本地编码在 " + systemCode + " 下已存在外部编码 " + externalCode + " 的映射");
            }
            recordMappingVersion(created, 1, "CREATE", reason, refType, refId, actor);
            return created;
        }

        ExternalMap next = new ExternalMap(existing.id(), domainCode, localCode, existing.localName(),
                systemCode, existing.systemName(), externalCode, externalName, mapType, primary,
                status, effectiveFrom, effectiveTo, remark, existing.version(), existing.createdAt(),
                Instant.now());
        if (!repo.updateMapping(next, existing.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该映射已被他人修改，请刷新后重新提交");
        }
        ExternalMap updated = replica(next, existing.version() + 1, primary, status, externalName,
                mapType, effectiveFrom, effectiveTo, remark);
        recordMappingVersion(updated, existing.version() + 1, "UPDATE", reason, refType, refId, actor);
        return updated;
    }

    /** 停用映射（不物理删除）。 */
    public ExternalMap retireMapping(ExternalMap existing, String reason, String refType,
                                     String refId, String actor) {
        ExternalMap next = replica(existing, existing.version(), false, ExternalMap.INACTIVE,
                existing.externalName(), existing.mapType(), existing.effectiveFrom(),
                existing.effectiveTo(), existing.remark());
        if (!repo.updateMapping(next, existing.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该映射已被他人修改，请刷新后重新提交");
        }
        ExternalMap retired = replica(next, existing.version() + 1, false, ExternalMap.INACTIVE,
                next.externalName(), next.mapType(), next.effectiveFrom(), next.effectiveTo(),
                next.remark());
        recordMappingVersion(retired, existing.version() + 1, "RETIRE", reason, refType, refId, actor);
        return retired;
    }

    /** 追加一条映射版本。 */
    public void recordMappingVersion(ExternalMap map, long versionNo, String changeType,
                                     String reason, String refType, String refId, String actor) {
        repo.insertMappingVersion(new MappingVersion(0, map.id(), map.domainCode(), map.localCode(),
                map.systemCode(), map.externalCode(), versionNo, changeType, Snapshots.of(map),
                reason, refType, refId, actor, Instant.now()));
    }

    private static ExternalMap replica(ExternalMap map, long version, boolean primary, String status,
                                       String externalName, String mapType, LocalDate from,
                                       LocalDate to, String remark) {
        return new ExternalMap(map.id(), map.domainCode(), map.localCode(), map.localName(),
                map.systemCode(), map.systemName(), map.externalCode(), externalName, mapType,
                primary, status, from, to, remark, version, map.createdAt(), Instant.now());
    }

    /** 变更类型判定：源条目从"缺失"恢复时记 RESTORE，而不是普通 UPDATE。 */
    public static String updateChangeType(Item existing, String nextStatus) {
        if (Item.MISSING_IN_SOURCE.equals(existing.status()) && Item.ACTIVE.equals(nextStatus)) {
            return "RESTORE";
        }
        if (!existing.status().equals(nextStatus)) {
            return Item.ACTIVE.equals(nextStatus) ? "ENABLE" : "DISABLE";
        }
        return "UPDATE";
    }

    /** 引用类型常量（与数据库 CHECK 对齐）。 */
    public static final class Ref {
        public static final String CHANGE_REQUEST = "CHANGE_REQUEST";
        public static final String IMPORT = "IMPORT";
        public static final String SYNC = "SYNC";
        public static final String MANUAL = "MANUAL";

        private Ref() {
        }
    }
}
