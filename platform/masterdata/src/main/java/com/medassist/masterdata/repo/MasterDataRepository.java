package com.medassist.masterdata.repo;

import com.medassist.masterdata.domain.MasterDataModel.AuditLog;
import com.medassist.masterdata.domain.MasterDataModel.ChangeRequest;
import com.medassist.masterdata.domain.MasterDataModel.Domain;
import com.medassist.masterdata.domain.MasterDataModel.DomainQuality;
import com.medassist.masterdata.domain.MasterDataModel.ExternalMap;
import com.medassist.masterdata.domain.MasterDataModel.ExternalSystem;
import com.medassist.masterdata.domain.MasterDataModel.ImportBatch;
import com.medassist.masterdata.domain.MasterDataModel.ImportRow;
import com.medassist.masterdata.domain.MasterDataModel.Item;
import com.medassist.masterdata.domain.MasterDataModel.ItemVersion;
import com.medassist.masterdata.domain.MasterDataModel.MappingVersion;
import com.medassist.masterdata.domain.MasterDataModel.MetricDefinition;
import com.medassist.masterdata.domain.MasterDataModel.MetricDomainCount;
import com.medassist.masterdata.domain.MasterDataModel.MetricLineage;
import com.medassist.masterdata.domain.MasterDataModel.MetricSummary;
import com.medassist.masterdata.domain.MasterDataModel.MetricVersion;
import com.medassist.masterdata.domain.MasterDataModel.QualityIssue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 主数据仓储（mdm_svc 读写）。
 *
 * <p>纪律：
 * <ul>
 *   <li>所有写操作都带显式参数绑定，JSONB 用 {@code CAST(:x AS jsonb)}；</li>
 *   <li>条目/映射更新用 {@code WHERE version = :expected} 做 CAS，返回 false 由服务层转 409；</li>
 *   <li>版本、导入行、审计表只 INSERT（库级触发器拒绝改删）；</li>
 *   <li>质量统计在 SQL 内完成，避免把整院字典拉到内存里算。</li>
 * </ul>
 */
@Repository
public class MasterDataRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    /** 只用于读 JSONB 的静态 mapper：RowMapper 是静态字段，不能依赖注入实例。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final ObjectMapper objectMapper;

    public MasterDataRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.objectMapper = objectMapper;
    }

    /** 条目检索条件（domain 为空表示跨域）。 */
    public record ItemFilter(String domainCode, String keyword, String status, Boolean hasMapping) {
    }

    // ------------------------------------------------------------------ 域

    private static final RowMapper<Domain> DOMAIN_MAPPER = (rs, n) -> new Domain(
            rs.getString("domain_code"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("owner_service"),
            rs.getString("source_schema"),
            rs.getString("source_table"),
            rs.getString("code_column"),
            rs.getString("name_column"),
            rs.getString("sync_adapter"),
            rs.getBoolean("syncable"),
            readStringList(rs.getString("required_systems")),
            rs.getString("description"),
            rs.getInt("sort_no"),
            rs.getBoolean("enabled"),
            instant(rs, "last_synced_at"),
            rs.getString("last_sync_status"),
            Map.of());

    public List<Domain> listDomains() {
        return plain.query("""
                SELECT domain_code, name, category, owner_service, source_schema, source_table,
                       code_column, name_column, sync_adapter, syncable, required_systems,
                       description, sort_no, enabled, last_synced_at, last_sync_status
                  FROM mdm_svc.md_domain
                 ORDER BY sort_no, domain_code
                """, DOMAIN_MAPPER);
    }

    public Optional<Domain> findDomain(String domainCode) {
        return plain.query("""
                SELECT domain_code, name, category, owner_service, source_schema, source_table,
                       code_column, name_column, sync_adapter, syncable, required_systems,
                       description, sort_no, enabled, last_synced_at, last_sync_status
                  FROM mdm_svc.md_domain
                 WHERE domain_code = ?
                """, DOMAIN_MAPPER, domainCode).stream().findFirst();
    }

    public void updateSyncResult(String domainCode, String status, Map<String, Object> summary) {
        jdbc.update("""
                UPDATE mdm_svc.md_domain
                   SET last_synced_at = now(), last_sync_status = :status,
                       last_sync_summary = CAST(:summary AS jsonb), updated_at = now()
                 WHERE domain_code = :code
                """, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("summary", json(summary))
                .addValue("code", domainCode));
    }

    // ------------------------------------------------------------------ 条目

    private static final String ITEM_COLUMNS = """
            i.id, i.domain_code, i.local_code, i.name, i.alias, i.category, i.spec, i.unit,
            i.status, i.source_system, i.source_ref, i.effective_from, i.effective_to,
            i.attributes, i.fingerprint, i.version, i.created_at, i.updated_at
            """;

    private static final RowMapper<Item> ITEM_MAPPER = (rs, n) -> new Item(
            rs.getObject("id", UUID.class),
            rs.getString("domain_code"),
            rs.getString("local_code"),
            rs.getString("name"),
            rs.getString("alias"),
            rs.getString("category"),
            rs.getString("spec"),
            rs.getString("unit"),
            rs.getString("status"),
            rs.getString("source_system"),
            rs.getString("source_ref"),
            localDate(rs, "effective_from"),
            localDate(rs, "effective_to"),
            readMap(rs.getString("attributes")),
            rs.getString("fingerprint"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    public List<Item> listItems(ItemFilter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT ").append(ITEM_COLUMNS)
                .append(" FROM mdm_svc.md_item i");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendItemWhere(sql, params, filter);
        sql.append(" ORDER BY i.domain_code, i.local_code LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, ITEM_MAPPER);
    }

    public long countItems(ItemFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM mdm_svc.md_item i");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendItemWhere(sql, params, filter);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0L : count;
    }

    private void appendItemWhere(StringBuilder sql, MapSqlParameterSource params, ItemFilter filter) {
        sql.append(" WHERE 1 = 1");
        if (filter.domainCode() != null && !filter.domainCode().isBlank()) {
            sql.append(" AND i.domain_code = :domain");
            params.addValue("domain", filter.domainCode());
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND (i.local_code ILIKE :kw OR i.name ILIKE :kw")
                    .append(" OR coalesce(i.alias, '') ILIKE :kw)");
            params.addValue("kw", "%" + filter.keyword().trim() + "%");
        }
        if (filter.status() != null && !filter.status().isBlank()) {
            sql.append(" AND i.status = :status");
            params.addValue("status", filter.status());
        }
        if (filter.hasMapping() != null) {
            String exists = """
                     EXISTS (SELECT 1 FROM mdm_svc.md_external_map m
                              WHERE m.domain_code = i.domain_code
                                AND m.local_code = i.local_code
                                AND m.status = 'ACTIVE')
                    """;
            sql.append(filter.hasMapping() ? " AND " : " AND NOT ").append(exists);
        }
    }

    public Optional<Item> findItem(String domainCode, String localCode) {
        return plain.query("SELECT " + ITEM_COLUMNS + """
                  FROM mdm_svc.md_item i
                 WHERE i.domain_code = ? AND i.local_code = ?
                """, ITEM_MAPPER, domainCode, localCode).stream().findFirst();
    }

    public List<Item> listInHouseItems(String domainCode) {
        return plain.query("SELECT " + ITEM_COLUMNS + """
                  FROM mdm_svc.md_item i
                 WHERE i.domain_code = ? AND i.source_system = 'IN_HOUSE'
                 ORDER BY i.local_code
                """, ITEM_MAPPER, domainCode);
    }

    /** 某域全部条目（同步冲突检测与导入预检用；字典规模有限，不分页）。 */
    public List<Item> listAllItems(String domainCode) {
        return plain.query("SELECT " + ITEM_COLUMNS + """
                  FROM mdm_svc.md_item i
                 WHERE i.domain_code = ?
                 ORDER BY i.local_code
                """, ITEM_MAPPER, domainCode);
    }

    public void insertItem(Item item) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_item
                    (id, domain_code, local_code, name, alias, category, spec, unit, status,
                     source_system, source_ref, effective_from, effective_to, attributes,
                     fingerprint, version)
                VALUES
                    (:id, :domain, :code, :name, :alias, :category, :spec, :unit, :status,
                     :sourceSystem, :sourceRef, :effectiveFrom, :effectiveTo,
                     CAST(:attributes AS jsonb), :fingerprint, :version)
                """, itemParams(item));
    }

    /** CAS 更新；返回 false 表示版本已变化（并发修改）。 */
    public boolean updateItem(Item item, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mdm_svc.md_item
                   SET name = :name, alias = :alias, category = :category, spec = :spec,
                       unit = :unit, status = :status, source_ref = :sourceRef,
                       effective_from = :effectiveFrom, effective_to = :effectiveTo,
                       attributes = CAST(:attributes AS jsonb), fingerprint = :fingerprint,
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected
                """, itemParams(item).addValue("expected", expectedVersion));
        return rows == 1;
    }

    public boolean updateItemStatus(UUID id, String status, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mdm_svc.md_item
                   SET status = :status, version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected
                """, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("id", id)
                .addValue("expected", expectedVersion));
        return rows == 1;
    }

    private MapSqlParameterSource itemParams(Item item) {
        return new MapSqlParameterSource()
                .addValue("id", item.id())
                .addValue("domain", item.domainCode())
                .addValue("code", item.localCode())
                .addValue("name", item.name())
                .addValue("alias", item.alias())
                .addValue("category", item.category())
                .addValue("spec", item.spec())
                .addValue("unit", item.unit())
                .addValue("status", item.status())
                .addValue("sourceSystem", item.sourceSystem())
                .addValue("sourceRef", item.sourceRef())
                .addValue("effectiveFrom", item.effectiveFrom())
                .addValue("effectiveTo", item.effectiveTo())
                .addValue("attributes", json(item.attributes()))
                .addValue("fingerprint", item.fingerprint())
                .addValue("version", item.version());
    }

    // ------------------------------------------------------------------ 条目版本

    private static final RowMapper<ItemVersion> ITEM_VERSION_MAPPER = (rs, n) -> new ItemVersion(
            rs.getLong("id"),
            rs.getObject("item_id", UUID.class),
            rs.getString("domain_code"),
            rs.getString("local_code"),
            rs.getLong("version_no"),
            rs.getString("change_type"),
            readMap(rs.getString("snapshot")),
            readMap(rs.getString("diff")),
            rs.getString("reason"),
            rs.getString("ref_type"),
            rs.getString("ref_id"),
            rs.getString("actor"),
            rs.getString("prev_checksum"),
            rs.getString("checksum"),
            instant(rs, "created_at"));

    public long nextItemVersionNo(UUID itemId) {
        Long max = plain.queryForObject(
                "SELECT coalesce(max(version_no), 0) FROM mdm_svc.md_item_version WHERE item_id = ?",
                Long.class, itemId);
        return (max == null ? 0L : max) + 1L;
    }

    public Optional<String> lastItemChecksum(UUID itemId) {
        return plain.queryForList("""
                SELECT checksum FROM mdm_svc.md_item_version
                 WHERE item_id = ? ORDER BY version_no DESC LIMIT 1
                """, String.class, itemId).stream().findFirst();
    }

    public void insertItemVersion(ItemVersion version) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_item_version
                    (item_id, domain_code, local_code, version_no, change_type, snapshot, diff,
                     reason, ref_type, ref_id, actor, prev_checksum, checksum)
                VALUES
                    (:itemId, :domain, :code, :versionNo, :changeType,
                     CAST(:snapshot AS jsonb), CAST(:diff AS jsonb),
                     :reason, :refType, :refId, :actor, :prevChecksum, :checksum)
                """, new MapSqlParameterSource()
                .addValue("itemId", version.itemId())
                .addValue("domain", version.domainCode())
                .addValue("code", version.localCode())
                .addValue("versionNo", version.versionNo())
                .addValue("changeType", version.changeType())
                .addValue("snapshot", json(version.snapshot()))
                .addValue("diff", version.diff() == null ? null : json(version.diff()))
                .addValue("reason", version.reason())
                .addValue("refType", version.refType())
                .addValue("refId", version.refId())
                .addValue("actor", version.actor())
                .addValue("prevChecksum", version.prevChecksum())
                .addValue("checksum", version.checksum()));
    }

    public List<ItemVersion> listItemVersions(UUID itemId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM mdm_svc.md_item_version
                 WHERE item_id = :itemId
                 ORDER BY version_no DESC
                 LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("itemId", itemId)
                .addValue("limit", limit)
                .addValue("offset", offset), ITEM_VERSION_MAPPER);
    }

    public long countItemVersions(UUID itemId) {
        Long count = plain.queryForObject(
                "SELECT count(*) FROM mdm_svc.md_item_version WHERE item_id = ?", Long.class, itemId);
        return count == null ? 0L : count;
    }

    /** 版本链校验：按版本号升序取全量。 */
    public List<ItemVersion> allItemVersionsAsc(UUID itemId) {
        return plain.query("""
                SELECT * FROM mdm_svc.md_item_version
                 WHERE item_id = ? ORDER BY version_no ASC
                """, ITEM_VERSION_MAPPER, itemId);
    }

    // ------------------------------------------------------------------ 外部系统

    private static final RowMapper<ExternalSystem> SYSTEM_MAPPER = (rs, n) -> new ExternalSystem(
            rs.getString("system_code"),
            rs.getString("name"),
            rs.getString("system_type"),
            rs.getString("vendor"),
            rs.getString("endpoint"),
            rs.getString("contact"),
            rs.getString("status"),
            rs.getInt("sort_no"));

    public List<ExternalSystem> listExternalSystems() {
        return plain.query("""
                SELECT system_code, name, system_type, vendor, endpoint, contact, status, sort_no
                  FROM mdm_svc.md_external_system
                 ORDER BY sort_no, system_code
                """, SYSTEM_MAPPER);
    }

    public Optional<ExternalSystem> findExternalSystem(String systemCode) {
        return plain.query("""
                SELECT system_code, name, system_type, vendor, endpoint, contact, status, sort_no
                  FROM mdm_svc.md_external_system
                 WHERE system_code = ?
                """, SYSTEM_MAPPER, systemCode).stream().findFirst();
    }

    // ------------------------------------------------------------------ 映射

    private static final String MAP_SELECT = """
            SELECT m.id, m.domain_code, m.local_code, i.name AS local_name, m.system_code,
                   s.name AS system_name, m.external_code, m.external_name, m.map_type,
                   m.primary_flag, m.status, m.effective_from, m.effective_to, m.remark,
                   m.version, m.created_at, m.updated_at
              FROM mdm_svc.md_external_map m
              JOIN mdm_svc.md_item i
                ON i.domain_code = m.domain_code AND i.local_code = m.local_code
              JOIN mdm_svc.md_external_system s ON s.system_code = m.system_code
            """;

    private static final RowMapper<ExternalMap> MAP_MAPPER = (rs, n) -> new ExternalMap(
            rs.getObject("id", UUID.class),
            rs.getString("domain_code"),
            rs.getString("local_code"),
            rs.getString("local_name"),
            rs.getString("system_code"),
            rs.getString("system_name"),
            rs.getString("external_code"),
            rs.getString("external_name"),
            rs.getString("map_type"),
            rs.getBoolean("primary_flag"),
            rs.getString("status"),
            localDate(rs, "effective_from"),
            localDate(rs, "effective_to"),
            rs.getString("remark"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    public List<ExternalMap> listMappings(String domainCode, String localCode) {
        return plain.query(MAP_SELECT + """
                 WHERE m.domain_code = ? AND m.local_code = ?
                 ORDER BY m.system_code, m.primary_flag DESC, m.external_code
                """, MAP_MAPPER, domainCode, localCode);
    }

    public Optional<ExternalMap> findMapping(UUID id) {
        return plain.query(MAP_SELECT + " WHERE m.id = ?", MAP_MAPPER, id).stream().findFirst();
    }

    public Optional<ExternalMap> findMappingByKey(String domainCode, String localCode,
                                                  String systemCode, String externalCode) {
        return plain.query(MAP_SELECT + """
                 WHERE m.domain_code = ? AND m.local_code = ?
                   AND m.system_code = ? AND m.external_code = ?
                """, MAP_MAPPER, domainCode, localCode, systemCode, externalCode)
                .stream().findFirst();
    }

    public List<ExternalMap> findMappingsByExternal(String systemCode, String externalCode) {
        return plain.query(MAP_SELECT + """
                 WHERE m.system_code = ? AND m.external_code = ? AND m.status = 'ACTIVE'
                 ORDER BY m.domain_code, m.local_code
                """, MAP_MAPPER, systemCode, externalCode);
    }

    /** 同域同系统下已存在的其他主映射（用于唯一性预检）。 */
    public List<ExternalMap> findOtherPrimary(String domainCode, String localCode, String systemCode) {
        return plain.query(MAP_SELECT + """
                 WHERE m.domain_code = ? AND m.local_code = ? AND m.system_code = ?
                   AND m.primary_flag AND m.status = 'ACTIVE'
                """, MAP_MAPPER, domainCode, localCode, systemCode);
    }

    public void insertMapping(ExternalMap map) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_external_map
                    (id, domain_code, local_code, system_code, external_code, external_name,
                     map_type, primary_flag, status, effective_from, effective_to, remark, version)
                VALUES
                    (:id, :domain, :code, :system, :externalCode, :externalName,
                     :mapType, :primary, :status, :effectiveFrom, :effectiveTo, :remark, :version)
                """, mappingParams(map));
    }

    public boolean updateMapping(ExternalMap map, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mdm_svc.md_external_map
                   SET external_name = :externalName, map_type = :mapType,
                       primary_flag = :primary, status = :status,
                       effective_from = :effectiveFrom, effective_to = :effectiveTo,
                       remark = :remark, version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected
                """, mappingParams(map).addValue("expected", expectedVersion));
        return rows == 1;
    }

    /** 主映射切换：把其他主映射降级（不删行，保留历史）。 */
    public boolean demotePrimary(UUID id, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mdm_svc.md_external_map
                   SET primary_flag = false, version = version + 1, updated_at = now()
                 WHERE id = :id AND version = :expected
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("expected", expectedVersion));
        return rows == 1;
    }

    private MapSqlParameterSource mappingParams(ExternalMap map) {
        return new MapSqlParameterSource()
                .addValue("id", map.id())
                .addValue("domain", map.domainCode())
                .addValue("code", map.localCode())
                .addValue("system", map.systemCode())
                .addValue("externalCode", map.externalCode())
                .addValue("externalName", map.externalName())
                .addValue("mapType", map.mapType())
                .addValue("primary", map.primary())
                .addValue("status", map.status())
                .addValue("effectiveFrom", map.effectiveFrom())
                .addValue("effectiveTo", map.effectiveTo())
                .addValue("remark", map.remark())
                .addValue("version", map.version());
    }

    public long nextMappingVersionNo(UUID mappingId) {
        Long max = plain.queryForObject(
                "SELECT coalesce(max(version_no), 0) FROM mdm_svc.md_mapping_version WHERE mapping_id = ?",
                Long.class, mappingId);
        return (max == null ? 0L : max) + 1L;
    }

    public void insertMappingVersion(MappingVersion version) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_mapping_version
                    (mapping_id, domain_code, local_code, system_code, external_code, version_no,
                     change_type, snapshot, reason, ref_type, ref_id, actor)
                VALUES
                    (:mappingId, :domain, :code, :system, :externalCode, :versionNo,
                     :changeType, CAST(:snapshot AS jsonb), :reason, :refType, :refId, :actor)
                """, new MapSqlParameterSource()
                .addValue("mappingId", version.mappingId())
                .addValue("domain", version.domainCode())
                .addValue("code", version.localCode())
                .addValue("system", version.systemCode())
                .addValue("externalCode", version.externalCode())
                .addValue("versionNo", version.versionNo())
                .addValue("changeType", version.changeType())
                .addValue("snapshot", json(version.snapshot()))
                .addValue("reason", version.reason())
                .addValue("refType", version.refType())
                .addValue("refId", version.refId())
                .addValue("actor", version.actor()));
    }

    /** 映射导出（有上限：导出不是全库倾倒）。 */
    public List<ExternalMap> exportMappings(String domainCode, String systemCode, int limit) {
        StringBuilder sql = new StringBuilder(MAP_SELECT).append(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (domainCode != null && !domainCode.isBlank()) {
            sql.append(" AND m.domain_code = :domain");
            params.addValue("domain", domainCode);
        }
        if (systemCode != null && !systemCode.isBlank()) {
            sql.append(" AND m.system_code = :system");
            params.addValue("system", systemCode);
        }
        sql.append(" ORDER BY m.domain_code, m.local_code, m.system_code, m.external_code")
                .append(" LIMIT :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, MAP_MAPPER);
    }

    // ------------------------------------------------------------------ 变更申请

    private static final RowMapper<ChangeRequest> REQUEST_MAPPER = (rs, n) -> new ChangeRequest(
            rs.getObject("id", UUID.class),
            rs.getString("request_no"),
            rs.getString("change_type"),
            rs.getString("domain_code"),
            rs.getString("local_code"),
            readMap(rs.getString("payload")),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("submitted_by"),
            instant(rs, "submitted_at"),
            rs.getString("reviewed_by"),
            instant(rs, "reviewed_at"),
            rs.getString("review_comment"),
            rs.getObject("applied_version") == null ? null : rs.getLong("applied_version"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    public void insertChangeRequest(ChangeRequest request) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_change_request
                    (id, request_no, change_type, domain_code, local_code, payload, reason,
                     status, submitted_by, submitted_at, version)
                VALUES
                    (:id, :requestNo, :changeType, :domain, :code, CAST(:payload AS jsonb), :reason,
                     :status, :submittedBy, now(), 0)
                """, new MapSqlParameterSource()
                .addValue("id", request.id())
                .addValue("requestNo", request.requestNo())
                .addValue("changeType", request.changeType())
                .addValue("domain", request.domainCode())
                .addValue("code", request.localCode())
                .addValue("payload", json(request.payload()))
                .addValue("reason", request.reason())
                .addValue("status", ChangeRequest.SUBMITTED)
                .addValue("submittedBy", request.submittedBy()));
    }

    public Optional<ChangeRequest> findChangeRequest(UUID id) {
        return plain.query("SELECT * FROM mdm_svc.md_change_request WHERE id = ?",
                REQUEST_MAPPER, id).stream().findFirst();
    }

    public Optional<ChangeRequest> findChangeRequestByNo(String requestNo) {
        return plain.query("SELECT * FROM mdm_svc.md_change_request WHERE request_no = ?",
                REQUEST_MAPPER, requestNo).stream().findFirst();
    }

    public Optional<ChangeRequest> findOpenItemRequest(String domainCode, String localCode) {
        return plain.query("""
                SELECT * FROM mdm_svc.md_change_request
                 WHERE domain_code = ? AND local_code = ? AND status = 'SUBMITTED'
                   AND change_type IN ('ITEM_CREATE', 'ITEM_UPDATE', 'ITEM_STATUS')
                """, REQUEST_MAPPER, domainCode, localCode).stream().findFirst();
    }

    public List<ChangeRequest> listChangeRequests(String status, String domainCode, String changeType,
                                                  int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM mdm_svc.md_change_request WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        if (domainCode != null && !domainCode.isBlank()) {
            sql.append(" AND domain_code = :domain");
            params.addValue("domain", domainCode);
        }
        if (changeType != null && !changeType.isBlank()) {
            sql.append(" AND change_type = :type");
            params.addValue("type", changeType);
        }
        sql.append(" ORDER BY submitted_at DESC LIMIT :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, REQUEST_MAPPER);
    }

    public boolean markApplied(UUID id, String actor, String comment, long appliedVersion,
                               long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mdm_svc.md_change_request
                   SET status = 'APPLIED', reviewed_by = :actor, reviewed_at = now(),
                       review_comment = :comment, applied_version = :appliedVersion,
                       version = version + 1, updated_at = now()
                 WHERE id = :id AND status = 'SUBMITTED' AND version = :expected
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("comment", comment)
                .addValue("appliedVersion", appliedVersion)
                .addValue("id", id)
                .addValue("expected", expectedVersion));
        return rows == 1;
    }

    public boolean markRejected(UUID id, String actor, String comment, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mdm_svc.md_change_request
                   SET status = 'REJECTED', reviewed_by = :actor, reviewed_at = now(),
                       review_comment = :comment, version = version + 1, updated_at = now()
                 WHERE id = :id AND status = 'SUBMITTED' AND version = :expected
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("comment", comment)
                .addValue("id", id)
                .addValue("expected", expectedVersion));
        return rows == 1;
    }

    public boolean markCancelled(UUID id, String actor, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE mdm_svc.md_change_request
                   SET status = 'CANCELLED', reviewed_by = :actor, reviewed_at = now(),
                       review_comment = '提交人撤回', version = version + 1, updated_at = now()
                 WHERE id = :id AND status = 'SUBMITTED' AND version = :expected
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("id", id)
                .addValue("expected", expectedVersion));
        return rows == 1;
    }

    // ------------------------------------------------------------------ 导入批次

    private static final RowMapper<ImportBatch> BATCH_MAPPER = (rs, n) -> new ImportBatch(
            rs.getObject("id", UUID.class),
            rs.getString("batch_no"),
            rs.getString("mode"),
            rs.getString("format"),
            rs.getString("domain_code"),
            rs.getString("file_name"),
            rs.getString("checksum"),
            rs.getInt("row_count"),
            rs.getInt("create_count"),
            rs.getInt("update_count"),
            rs.getInt("unchanged_count"),
            rs.getInt("conflict_count"),
            rs.getInt("error_count"),
            rs.getString("status"),
            readMap(rs.getString("summary")),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            List.of());

    public void insertImportBatch(ImportBatch batch) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_import_batch
                    (id, batch_no, mode, format, domain_code, file_name, checksum, row_count,
                     create_count, update_count, unchanged_count, conflict_count, error_count,
                     status, summary, created_by)
                VALUES
                    (:id, :batchNo, :mode, :format, :domain, :fileName, :checksum, :rowCount,
                     :createCount, :updateCount, :unchangedCount, :conflictCount, :errorCount,
                     :status, CAST(:summary AS jsonb), :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", batch.id())
                .addValue("batchNo", batch.batchNo())
                .addValue("mode", batch.mode())
                .addValue("format", batch.format())
                .addValue("domain", batch.domainCode())
                .addValue("fileName", batch.fileName())
                .addValue("checksum", batch.checksum())
                .addValue("rowCount", batch.rowCount())
                .addValue("createCount", batch.createCount())
                .addValue("updateCount", batch.updateCount())
                .addValue("unchangedCount", batch.unchangedCount())
                .addValue("conflictCount", batch.conflictCount())
                .addValue("errorCount", batch.errorCount())
                .addValue("status", batch.status())
                .addValue("summary", json(batch.summary()))
                .addValue("createdBy", batch.createdBy()));
    }

    public void insertImportRows(UUID batchId, List<ImportRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = rows.stream().map(row -> new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("rowNo", row.rowNo())
                .addValue("localCode", row.localCode())
                .addValue("action", row.action())
                .addValue("message", row.message())
                .addValue("detail", row.detail() == null ? null : json(row.detail())))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO mdm_svc.md_import_row (batch_id, row_no, local_code, action, message, detail)
                VALUES (:batchId, :rowNo, :localCode, :action, :message, CAST(:detail AS jsonb))
                """, batch);
    }

    public Optional<ImportBatch> findImportBatch(UUID id) {
        return plain.query("SELECT * FROM mdm_svc.md_import_batch WHERE id = ?",
                BATCH_MAPPER, id).stream().findFirst();
    }

    public Optional<ImportBatch> findImportBatchByNo(String batchNo) {
        return plain.query("SELECT * FROM mdm_svc.md_import_batch WHERE batch_no = ?",
                BATCH_MAPPER, batchNo).stream().findFirst();
    }

    /** 幂等应用：同一域 + 同一校验和已有成功应用批次时直接返回该批次。 */
    public Optional<ImportBatch> findAppliedBatch(String domainCode, String checksum) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM mdm_svc.md_import_batch
                 WHERE mode = 'APPLY' AND status = 'APPLIED' AND checksum = :checksum
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("checksum", checksum);
        if (domainCode != null) {
            sql.append(" AND domain_code = :domain");
            params.addValue("domain", domainCode);
        } else {
            sql.append(" AND domain_code IS NULL");
        }
        sql.append(" ORDER BY created_at DESC LIMIT 1");
        return jdbc.query(sql.toString(), params, BATCH_MAPPER).stream().findFirst();
    }

    public List<ImportBatch> listImportBatches(String domainCode, String format, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM mdm_svc.md_import_batch WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (domainCode != null && !domainCode.isBlank()) {
            sql.append(" AND domain_code = :domain");
            params.addValue("domain", domainCode);
        }
        if (format != null && !format.isBlank()) {
            sql.append(" AND format = :format");
            params.addValue("format", format);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, BATCH_MAPPER);
    }

    private static final RowMapper<ImportRow> ROW_MAPPER = (rs, n) -> new ImportRow(
            rs.getLong("id"),
            rs.getInt("row_no"),
            rs.getString("local_code"),
            rs.getString("action"),
            rs.getString("message"),
            readMap(rs.getString("detail")));

    public List<ImportRow> listImportRows(UUID batchId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM mdm_svc.md_import_row
                 WHERE batch_id = :batchId
                 ORDER BY row_no
                 LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource()
                .addValue("batchId", batchId)
                .addValue("limit", limit)
                .addValue("offset", offset), ROW_MAPPER);
    }

    // ------------------------------------------------------------------ 审计

    public void insertAudit(String actor, String action, String domainCode, String localCode,
                            String refId, Map<String, Object> detail, String clientIp, String traceId) {
        jdbc.update("""
                INSERT INTO mdm_svc.md_change_log
                    (actor, action, domain_code, local_code, ref_id, detail, client_ip, trace_id)
                VALUES
                    (:actor, :action, :domain, :code, :refId, CAST(:detail AS jsonb), :ip, :traceId)
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("action", action)
                .addValue("domain", domainCode)
                .addValue("code", localCode)
                .addValue("refId", refId)
                .addValue("detail", detail == null ? null : json(detail))
                .addValue("ip", clientIp)
                .addValue("traceId", traceId));
    }

    private static final RowMapper<AuditLog> AUDIT_MAPPER = (rs, n) -> new AuditLog(
            rs.getLong("id"),
            instant(rs, "ts"),
            rs.getString("actor"),
            rs.getString("action"),
            rs.getString("domain_code"),
            rs.getString("local_code"),
            rs.getString("ref_id"),
            readMap(rs.getString("detail")),
            rs.getString("client_ip"),
            rs.getString("trace_id"));

    public List<AuditLog> listAudit(String domainCode, String actor, String action, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM mdm_svc.md_change_log WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (domainCode != null && !domainCode.isBlank()) {
            sql.append(" AND domain_code = :domain");
            params.addValue("domain", domainCode);
        }
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND actor = :actor");
            params.addValue("actor", actor);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = :action");
            params.addValue("action", action);
        }
        sql.append(" ORDER BY ts DESC LIMIT :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, AUDIT_MAPPER);
    }

    // ------------------------------------------------------------------ 质量统计

    private static final RowMapper<DomainQuality> QUALITY_MAPPER = (rs, n) -> new DomainQuality(
            rs.getString("domain_code"),
            rs.getString("name"),
            rs.getLong("total"),
            rs.getLong("active"),
            rs.getLong("inactive"),
            rs.getLong("missing"),
            rs.getLong("mapped_active"),
            rs.getLong("unmapped_active"),
            rs.getLong("missing_required"),
            rs.getLong("duplicate_names"),
            rs.getLong("ambiguous_external"),
            instant(rs, "last_synced_at"),
            rs.getString("last_sync_status"),
            false,
            readStringList(rs.getString("required_systems")));

    /**
     * 按域汇总质量指标。
     *
     * <p>口径：
     * <ul>
     *   <li>{@code mapped_active}：至少有一条生效映射的启用条目；</li>
     *   <li>{@code missing_required}：未覆盖"域要求的外部系统"的启用条目；</li>
     *   <li>{@code duplicate_names}：同名（忽略大小写）的启用条目组数；</li>
     *   <li>{@code ambiguous_external}：同一外部系统下同一外部编码对应多个本地编码的组数。</li>
     * </ul>
     */
    public List<DomainQuality> listDomainQuality() {
        return plain.query("""
                SELECT d.domain_code, d.name, d.required_systems, d.last_synced_at, d.last_sync_status,
                       count(i.id) AS total,
                       count(*) FILTER (WHERE i.status = 'ACTIVE') AS active,
                       count(*) FILTER (WHERE i.status = 'INACTIVE') AS inactive,
                       count(*) FILTER (WHERE i.status = 'MISSING_IN_SOURCE') AS missing,
                       count(DISTINCT i.id) FILTER (
                           WHERE i.status = 'ACTIVE' AND EXISTS (
                               SELECT 1 FROM mdm_svc.md_external_map m
                                WHERE m.domain_code = i.domain_code
                                  AND m.local_code = i.local_code
                                  AND m.status = 'ACTIVE')) AS mapped_active,
                       count(*) FILTER (WHERE i.status = 'ACTIVE') - count(DISTINCT i.id) FILTER (
                           WHERE i.status = 'ACTIVE' AND EXISTS (
                               SELECT 1 FROM mdm_svc.md_external_map m
                                WHERE m.domain_code = i.domain_code
                                  AND m.local_code = i.local_code
                                  AND m.status = 'ACTIVE')) AS unmapped_active,
                       (SELECT count(*) FROM mdm_svc.md_item x
                         WHERE x.domain_code = d.domain_code AND x.status = 'ACTIVE'
                           AND EXISTS (
                               SELECT 1 FROM jsonb_array_elements_text(d.required_systems) rs(system_code)
                                WHERE NOT EXISTS (
                                    SELECT 1 FROM mdm_svc.md_external_map m
                                     WHERE m.domain_code = x.domain_code
                                       AND m.local_code = x.local_code
                                       AND m.system_code = rs.system_code
                                       AND m.status = 'ACTIVE'))) AS missing_required,
                       (SELECT count(*) FROM (
                            SELECT lower(x.name) FROM mdm_svc.md_item x
                             WHERE x.domain_code = d.domain_code AND x.status = 'ACTIVE'
                             GROUP BY lower(x.name) HAVING count(*) > 1) g) AS duplicate_names,
                       (SELECT count(*) FROM (
                            SELECT 1 FROM mdm_svc.md_external_map m
                             WHERE m.domain_code = d.domain_code AND m.status = 'ACTIVE'
                             GROUP BY m.system_code, m.external_code
                            HAVING count(DISTINCT m.local_code) > 1) a) AS ambiguous_external
                  FROM mdm_svc.md_domain d
                  LEFT JOIN mdm_svc.md_item i ON i.domain_code = d.domain_code
                 GROUP BY d.domain_code, d.name, d.required_systems, d.last_synced_at, d.last_sync_status,
                          d.sort_no
                 ORDER BY d.sort_no, d.domain_code
                """, QUALITY_MAPPER);
    }

    /** 未建立任何映射的启用条目。 */
    public List<QualityIssue> unmappedIssues(int limit) {
        return jdbc.query("""
                SELECT 'UNMAPPED' AS type, i.domain_code, i.local_code, i.name,
                       '未建立任何外部编码映射' AS detail
                  FROM mdm_svc.md_item i
                 WHERE i.status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM mdm_svc.md_external_map m
                                    WHERE m.domain_code = i.domain_code
                                      AND m.local_code = i.local_code
                                      AND m.status = 'ACTIVE')
                 ORDER BY i.domain_code, i.local_code
                 LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), ISSUE_MAPPER);
    }

    /** 未覆盖域要求外部系统的启用条目（按缺失系统聚合）。 */
    public List<QualityIssue> missingRequiredIssues(int limit) {
        return jdbc.query("""
                SELECT 'MISSING_REQUIRED_MAPPING' AS type, i.domain_code, i.local_code, i.name,
                       '缺少必需外部系统映射：' || string_agg(rs.system_code, ',' ORDER BY rs.system_code) AS detail
                  FROM mdm_svc.md_item i
                  JOIN mdm_svc.md_domain d ON d.domain_code = i.domain_code
                  CROSS JOIN LATERAL jsonb_array_elements_text(d.required_systems) AS rs(system_code)
                 WHERE i.status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM mdm_svc.md_external_map m
                                    WHERE m.domain_code = i.domain_code
                                      AND m.local_code = i.local_code
                                      AND m.system_code = rs.system_code
                                      AND m.status = 'ACTIVE')
                 GROUP BY i.domain_code, i.local_code, i.name
                 ORDER BY i.domain_code, i.local_code
                 LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), ISSUE_MAPPER);
    }

    /** 同名启用条目（域内重名意味着"同一物两码"，必须人工确认）。 */
    public List<QualityIssue> duplicateNameIssues(int limit) {
        return jdbc.query("""
                SELECT 'DUPLICATE_NAME' AS type, i.domain_code, min(i.local_code) AS local_code,
                       min(i.name) AS name,
                       '同名条目 ' || count(*) || ' 条：' || string_agg(i.local_code, ',' ORDER BY i.local_code) AS detail
                  FROM mdm_svc.md_item i
                 WHERE i.status = 'ACTIVE'
                 GROUP BY i.domain_code, lower(i.name)
                HAVING count(*) > 1
                 ORDER BY i.domain_code, min(i.local_code)
                 LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), ISSUE_MAPPER);
    }

    /** 一源多码：同一外部编码对应多个本地编码（解析时必须返回候选，不能自动选一个）。 */
    public List<QualityIssue> ambiguousExternalIssues(int limit) {
        return jdbc.query("""
                SELECT 'AMBIGUOUS_EXTERNAL_CODE' AS type, m.domain_code,
                       string_agg(DISTINCT m.local_code, ',') AS local_code, NULL AS name,
                       m.system_code || ':' || m.external_code || ' 对应多个本地编码' AS detail
                  FROM mdm_svc.md_external_map m
                 WHERE m.status = 'ACTIVE'
                 GROUP BY m.domain_code, m.system_code, m.external_code
                HAVING count(DISTINCT m.local_code) > 1
                 ORDER BY m.domain_code, m.system_code, m.external_code
                 LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), ISSUE_MAPPER);
    }

    /** 源表已消失但映射仍保留的条目。 */
    public List<QualityIssue> missingInSourceIssues(int limit) {
        return jdbc.query("""
                SELECT 'MISSING_IN_SOURCE' AS type, i.domain_code, i.local_code, i.name,
                       '源字典中已不存在该编码，请确认是否停用或重新同步' AS detail
                  FROM mdm_svc.md_item i
                 WHERE i.status = 'MISSING_IN_SOURCE'
                 ORDER BY i.domain_code, i.local_code
                 LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), ISSUE_MAPPER);
    }

    private static final RowMapper<QualityIssue> ISSUE_MAPPER = (rs, n) -> new QualityIssue(
            rs.getString("type"),
            rs.getString("domain_code"),
            rs.getString("local_code"),
            rs.getString("name"),
            rs.getString("detail"));

    // ------------------------------------------------------------------ 工具

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        return objectMapper.writeValueAsString(value);
    }

    // ------------------------------------------------------------------ 指标字典（§293）

    /** 指标检索条件（空字符串表示不限）。 */
    public record MetricFilter(String domain, String status, String keyword) {
    }

    private static final RowMapper<MetricDefinition> METRIC_MAPPER = (rs, n) ->
            new MetricDefinition(
                    rs.getString("code"), rs.getString("name"), rs.getString("domain"),
                    rs.getString("definition"), rs.getString("formula"),
                    rs.getString("aggregation"), rs.getString("unit"),
                    rs.getString("time_window"), rs.getString("granularity"),
                    rs.getString("source_schema"), readStringList(rs.getString("source_tables")),
                    rs.getString("owner_dept"), rs.getString("owner_role"),
                    rs.getString("status"), rs.getLong("version"),
                    rs.getObject("published_version") == null ? null : rs.getLong("published_version"),
                    instant(rs, "published_at"),
                    rs.getString("published_by"), rs.getString("created_by"),
                    rs.getString("updated_by"), instant(rs, "created_at"),
                    instant(rs, "updated_at"));

    private static final RowMapper<MetricVersion> METRIC_VERSION_MAPPER = (rs, n) ->
            new MetricVersion(rs.getLong("id"), rs.getString("metric_code"),
                    rs.getLong("version_no"), rs.getString("change_type"),
                    readMap(rs.getString("snapshot")), rs.getString("checksum"),
                    rs.getString("actor"), instant(rs, "created_at"));

    private static final RowMapper<MetricLineage> METRIC_LINEAGE_MAPPER = (rs, n) ->
            new MetricLineage(rs.getObject("id", UUID.class), rs.getString("metric_code"),
                    rs.getString("direction"), rs.getString("ref_type"),
                    rs.getString("ref_code"), rs.getString("description"),
                    rs.getString("created_by"), instant(rs, "created_at"));

    private static final String METRIC_COLUMNS = """
            code, name, domain, definition, formula, aggregation, unit, time_window,
            granularity, source_schema, source_tables::text AS source_tables, owner_dept,
            owner_role, status, version, published_version, published_at, published_by,
            created_by, updated_by, created_at, updated_at
            """;

    public List<MetricDefinition> listMetrics(MetricFilter filter, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("domain", filter == null || filter.domain() == null
                        ? "" : filter.domain())
                .addValue("status", filter == null || filter.status() == null
                        ? "" : filter.status())
                .addValue("keyword", filter == null || filter.keyword() == null
                        ? "" : filter.keyword().trim())
                .addValue("limit", limit);
        return jdbc.query("SELECT " + METRIC_COLUMNS + """
                          FROM mdm_svc.metric_definition
                         WHERE (CAST(:domain AS text) = '' OR domain = CAST(:domain AS text))
                           AND (CAST(:status AS text) = '' OR status = CAST(:status AS text))
                           AND (CAST(:keyword AS text) = ''
                                OR code ILIKE '%' || CAST(:keyword AS text) || '%'
                                OR name ILIKE '%' || CAST(:keyword AS text) || '%')
                         ORDER BY domain, code
                         LIMIT :limit
                        """, params, METRIC_MAPPER);
    }

    public Optional<MetricDefinition> findMetric(String code) {
        return jdbc.query("SELECT " + METRIC_COLUMNS
                        + " FROM mdm_svc.metric_definition WHERE code = :code",
                new MapSqlParameterSource().addValue("code", code), METRIC_MAPPER)
                .stream().findFirst();
    }

    public boolean insertMetric(MetricDefinition m) {
        try {
            jdbc.update("""
                            INSERT INTO mdm_svc.metric_definition
                                (code, name, domain, definition, formula, aggregation, unit,
                                 time_window, granularity, source_schema, source_tables,
                                 owner_dept, owner_role, status, version, created_by, updated_by)
                            VALUES (:code, :name, :domain, :definition, :formula, :aggregation,
                                    :unit, :timeWindow, :granularity, :sourceSchema,
                                    CAST(:sourceTables AS jsonb), :ownerDept, :ownerRole,
                                    'DRAFT', 1, :by, :by)
                            """,
                    new MapSqlParameterSource()
                            .addValue("code", m.code()).addValue("name", m.name())
                            .addValue("domain", m.domain()).addValue("definition", m.definition())
                            .addValue("formula", m.formula()).addValue("aggregation", m.aggregation())
                            .addValue("unit", m.unit()).addValue("timeWindow", m.timeWindow())
                            .addValue("granularity", m.granularity())
                            .addValue("sourceSchema", m.sourceSchema())
                            .addValue("sourceTables", json(m.sourceTables()))
                            .addValue("ownerDept", m.ownerDept())
                            .addValue("ownerRole", m.ownerRole()).addValue("by", m.createdBy()));
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    /** 修改草稿（只有 DRAFT + 版本一致才允许）。 */
    public boolean updateMetric(MetricDefinition m) {
        return jdbc.update("""
                        UPDATE mdm_svc.metric_definition
                           SET name = :name, domain = :domain, definition = :definition,
                               formula = :formula, aggregation = :aggregation, unit = :unit,
                               time_window = :timeWindow, granularity = :granularity,
                               source_schema = :sourceSchema,
                               source_tables = CAST(:sourceTables AS jsonb),
                               owner_dept = :ownerDept, owner_role = :ownerRole,
                               updated_by = :by, updated_at = now(), version = version + 1
                         WHERE code = :code AND status = 'DRAFT' AND version = :version
                        """,
                new MapSqlParameterSource()
                        .addValue("code", m.code()).addValue("name", m.name())
                        .addValue("domain", m.domain()).addValue("definition", m.definition())
                        .addValue("formula", m.formula()).addValue("aggregation", m.aggregation())
                        .addValue("unit", m.unit()).addValue("timeWindow", m.timeWindow())
                        .addValue("granularity", m.granularity())
                        .addValue("sourceSchema", m.sourceSchema())
                        .addValue("sourceTables", json(m.sourceTables()))
                        .addValue("ownerDept", m.ownerDept())
                        .addValue("ownerRole", m.ownerRole()).addValue("by", m.updatedBy())
                        .addValue("version", m.version())) > 0;
    }

    /** 发布：版本不变，记录 published_version/时间/人。 */
    public boolean publishMetric(String code, long version, String actor) {
        return jdbc.update("""
                        UPDATE mdm_svc.metric_definition
                           SET status = 'PUBLISHED', published_version = :version,
                               published_at = now(), published_by = :by,
                               updated_by = :by, updated_at = now()
                         WHERE code = :code AND status = 'DRAFT' AND version = :version
                        """,
                new MapSqlParameterSource().addValue("code", code)
                        .addValue("version", version).addValue("by", actor)) > 0;
    }

    /** 修订已发布指标：head 进入下一版本 DRAFT，published_version 保持不动。 */
    public boolean reviseMetric(String code, long version, String actor) {
        return jdbc.update("""
                        UPDATE mdm_svc.metric_definition
                           SET status = 'DRAFT', version = version + 1,
                               updated_by = :by, updated_at = now()
                         WHERE code = :code AND status = 'PUBLISHED' AND version = :version
                        """,
                new MapSqlParameterSource().addValue("code", code)
                        .addValue("version", version).addValue("by", actor)) > 0;
    }

    /** 停用：草稿或已发布都可停用；停用后不再对外提供口径。 */
    public boolean disableMetric(String code, long version, String actor) {
        return jdbc.update("""
                        UPDATE mdm_svc.metric_definition
                           SET status = 'DISABLED', updated_by = :by, updated_at = now(),
                               version = version + 1
                         WHERE code = :code AND status IN ('DRAFT', 'PUBLISHED')
                           AND version = :version
                        """,
                new MapSqlParameterSource().addValue("code", code)
                        .addValue("version", version).addValue("by", actor)) > 0;
    }

    public void insertMetricVersion(MetricVersion v) {
        jdbc.update("""
                        INSERT INTO mdm_svc.metric_version
                            (metric_code, version_no, change_type, snapshot, checksum, actor)
                        VALUES (:code, :versionNo, :changeType, CAST(:snapshot AS jsonb),
                                :checksum, :actor)
                        """,
                new MapSqlParameterSource().addValue("code", v.metricCode())
                        .addValue("versionNo", v.versionNo())
                        .addValue("changeType", v.changeType())
                        .addValue("snapshot", json(v.snapshot()))
                        .addValue("checksum", v.checksum())
                        .addValue("actor", v.actor()));
    }

    public List<MetricVersion> listMetricVersions(String code, int limit) {
        return jdbc.query("""
                        SELECT id, metric_code, version_no, change_type, snapshot::text AS snapshot,
                               checksum, actor, created_at
                          FROM mdm_svc.metric_version
                         WHERE metric_code = :code
                         ORDER BY version_no DESC, id DESC
                         LIMIT :limit
                        """,
                new MapSqlParameterSource().addValue("code", code).addValue("limit", limit),
                METRIC_VERSION_MAPPER);
    }

    /** 注册血缘（同指标同方向同引用幂等）；返回是否新增。 */
    public boolean insertMetricLineage(MetricLineage l) {
        return jdbc.update("""
                        INSERT INTO mdm_svc.metric_lineage
                            (id, metric_code, direction, ref_type, ref_code, description, created_by)
                        VALUES (:id, :code, :direction, :refType, :refCode, :description, :by)
                        ON CONFLICT (metric_code, direction, ref_type, ref_code) DO NOTHING
                        """,
                new MapSqlParameterSource().addValue("id", l.id())
                        .addValue("code", l.metricCode()).addValue("direction", l.direction())
                        .addValue("refType", l.refType()).addValue("refCode", l.refCode())
                        .addValue("description", l.description())
                        .addValue("by", l.createdBy())) > 0;
    }

    public List<MetricLineage> listMetricLineage(String code) {
        return jdbc.query("""
                        SELECT id, metric_code, direction, ref_type, ref_code, description,
                               created_by, created_at
                          FROM mdm_svc.metric_lineage
                         WHERE metric_code = :code
                         ORDER BY direction, ref_type, ref_code
                        """,
                new MapSqlParameterSource().addValue("code", code), METRIC_LINEAGE_MAPPER);
    }

    public int countMetricLineage(String code, String direction) {
        Integer n = jdbc.queryForObject("""
                        SELECT count(*) FROM mdm_svc.metric_lineage
                         WHERE metric_code = :code AND direction = :direction
                        """,
                new MapSqlParameterSource().addValue("code", code)
                        .addValue("direction", direction), Integer.class);
        return n == null ? 0 : n;
    }

    public int countMetricVersions(String code) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM mdm_svc.metric_version WHERE metric_code = :code",
                new MapSqlParameterSource().addValue("code", code), Integer.class);
        return n == null ? 0 : n;
    }

    /** 字典汇总：状态计数 + 按域统计（SQL 内完成，不把全量字典拉内存）。 */
    public MetricSummary metricSummary() {
        long total = count("SELECT count(*) FROM mdm_svc.metric_definition");
        long draft = count("SELECT count(*) FROM mdm_svc.metric_definition WHERE status = 'DRAFT'");
        long published = count(
                "SELECT count(*) FROM mdm_svc.metric_definition WHERE status = 'PUBLISHED'");
        long disabled = count(
                "SELECT count(*) FROM mdm_svc.metric_definition WHERE status = 'DISABLED'");
        long revision = count("""
                SELECT count(*) FROM mdm_svc.metric_definition
                 WHERE status = 'DRAFT' AND published_version IS NOT NULL
                """);
        List<MetricDomainCount> domains = jdbc.query("""
                        SELECT domain, count(*) AS total,
                               count(*) FILTER (WHERE status = 'PUBLISHED') AS published
                          FROM mdm_svc.metric_definition
                         GROUP BY domain
                         ORDER BY domain
                        """, Map.of(), (rs, n) -> new MetricDomainCount(
                rs.getString("domain"), rs.getLong("total"), rs.getLong("published")));
        return new MetricSummary(total, draft, published, disabled, revision, domains);
    }

    private long count(String sql) {
        Long n = plain.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 指标字典结束

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = JSON.readValue(json, MAP_TYPE);
            return value == null ? Map.of() : value;
        } catch (RuntimeException e) {
            // 库里的 JSONB 由本服务写入；解析失败说明数据被外部改写，不能静默当空
            throw new IllegalStateException("主数据 JSONB 内容损坏，无法解析", e);
        }
    }

    private static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> value = JSON.readValue(json, STRING_LIST_TYPE);
            return value == null ? List.of() : List.copyOf(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException("主数据域 required_systems 内容损坏，无法解析", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }
}
