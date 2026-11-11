package com.medassist.release.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.release.domain.ReleaseModel;
import com.medassist.release.domain.ReleaseModel.AuditLog;
import com.medassist.release.domain.ReleaseModel.ChangeWindow;
import com.medassist.release.domain.ReleaseModel.Compatibility;
import com.medassist.release.domain.ReleaseModel.Component;
import com.medassist.release.domain.ReleaseModel.Deployment;
import com.medassist.release.domain.ReleaseModel.Patch;
import com.medassist.release.domain.ReleaseModel.Train;
import com.medassist.release.domain.ReleaseModel.TrainItem;
import com.medassist.release.domain.ReleaseModel.Version;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 发布仓储（release_svc）。
 *
 * <p>纪律：版本/列车/补丁/窗口的状态推进都带 version 做 CAS；
 * 审计与部署记录只 INSERT；兼容矩阵与列车车厢是显式事实，不做隐式推断。
 */
@Repository
public class ReleaseRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper STATIC_JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final ObjectMapper objectMapper;

    public ReleaseRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain,
                             ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 组件

    private static final RowMapper<Component> COMPONENT_MAPPER = (rs, n) -> new Component(
            rs.getString("code"), rs.getString("name"), rs.getString("kind"),
            rs.getString("owner_group"), rs.getString("status"), rs.getInt("sort_no"));

    public List<Component> listComponents(boolean onlyActive) {
        return plain.query("""
                SELECT code, name, kind, owner_group, status, sort_no
                  FROM release_svc.rel_component
                """ + (onlyActive ? " WHERE status = 'ACTIVE'" : "")
                + " ORDER BY sort_no, code", COMPONENT_MAPPER);
    }

    public Optional<Component> findComponent(String code) {
        return plain.query("""
                SELECT code, name, kind, owner_group, status, sort_no
                  FROM release_svc.rel_component WHERE code = ?
                """, COMPONENT_MAPPER, code).stream().findFirst();
    }

    public void upsertComponent(Component component) {
        jdbc.update("""
                INSERT INTO release_svc.rel_component (code, name, kind, owner_group, status, sort_no)
                VALUES (:code, :name, :kind, :group, :status, :sort)
                ON CONFLICT (code) DO UPDATE SET
                    name = EXCLUDED.name, kind = EXCLUDED.kind,
                    owner_group = EXCLUDED.owner_group, status = EXCLUDED.status,
                    sort_no = EXCLUDED.sort_no, updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("code", component.code())
                .addValue("name", component.name())
                .addValue("kind", component.kind())
                .addValue("group", component.ownerGroup())
                .addValue("status", component.status())
                .addValue("sort", component.sortNo()));
    }

    // ------------------------------------------------------------------ 版本

    private static final RowMapper<Version> VERSION_MAPPER = (rs, n) -> new Version(
            rs.getObject("id", UUID.class),
            rs.getString("component_code"),
            rs.getString("version"),
            rs.getString("git_commit"),
            rs.getString("image_digest"),
            rs.getString("schema_version"),
            rs.getString("status"),
            rs.getBoolean("breaking_changes"),
            rs.getString("changelog"),
            instant(rs, "released_at"),
            rs.getString("created_by"),
            rs.getLong("lock_version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String VERSION_COLUMNS = """
            id, component_code, version, git_commit, image_digest, schema_version, status,
            breaking_changes, changelog, released_at, created_by,
            lock_version, created_at, updated_at
            """;

    public List<Version> listVersions(String component, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + VERSION_COLUMNS
                + " FROM release_svc.rel_version WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (component != null && !component.isBlank()) {
            sql.append(" AND component_code = :component");
            params.addValue("component", component);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, VERSION_MAPPER);
    }

    public long countVersions(String component, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM release_svc.rel_version WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (component != null && !component.isBlank()) {
            sql.append(" AND component_code = :component");
            params.addValue("component", component);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    public Optional<Version> findVersion(UUID id) {
        return plain.query("SELECT " + VERSION_COLUMNS
                + " FROM release_svc.rel_version WHERE id = ?", VERSION_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Version> findVersionByKey(String component, String version) {
        return plain.query("SELECT " + VERSION_COLUMNS
                        + " FROM release_svc.rel_version WHERE component_code = ? AND version = ?",
                VERSION_MAPPER, component, version).stream().findFirst();
    }

    public List<Version> listVersionsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT " + VERSION_COLUMNS
                + " FROM release_svc.rel_version WHERE id IN (:ids)", 
                new MapSqlParameterSource("ids", ids), VERSION_MAPPER);
    }

    public void insertVersion(Version version) {
        jdbc.update("""
                INSERT INTO release_svc.rel_version
                    (id, component_code, version, git_commit, image_digest, schema_version,
                     status, breaking_changes, changelog, released_at, created_by)
                VALUES (:id, :component, :version, :commit, :digest, :schemaVersion,
                        :status, :breaking, :changelog, :releasedAt, :actor)
                """, new MapSqlParameterSource()
                .addValue("id", version.id())
                .addValue("component", version.componentCode())
                .addValue("version", version.version())
                .addValue("commit", version.gitCommit())
                .addValue("digest", version.imageDigest())
                .addValue("schemaVersion", version.schemaVersion())
                .addValue("status", version.status())
                .addValue("breaking", version.breakingChanges())
                .addValue("changelog", version.changelog())
                .addValue("releasedAt", timestamp(version.releasedAt()))
                .addValue("actor", version.createdBy()));
    }

    private static final java.util.Set<String> MUTABLE_VERSION_COLUMNS = java.util.Set.of(
            "git_commit", "image_digest", "schema_version", "status", "breaking_changes",
            "changelog", "released_at");

    public boolean updateVersionFields(UUID id, long expected, Map<String, Object> fields) {
        return updateFields("release_svc.rel_version", "lock_version", id, expected, fields,
                MUTABLE_VERSION_COLUMNS, java.util.Set.of());
    }

    // ------------------------------------------------------------------ 列车

    private static final RowMapper<Train> TRAIN_MAPPER = (rs, n) -> new Train(
            rs.getObject("id", UUID.class),
            rs.getString("train_code"),
            rs.getString("name"),
            rs.getObject("planned_date", LocalDate.class),
            instant(rs, "freeze_at"),
            rs.getString("status"),
            rs.getString("notes"),
            instant(rs, "released_at"),
            rs.getString("created_by"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            List.of());

    private static final String TRAIN_COLUMNS = """
            id, train_code, name, planned_date, freeze_at, status, notes, released_at,
            created_by, version, created_at, updated_at
            """;

    public List<Train> listTrains(String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + TRAIN_COLUMNS
                + " FROM release_svc.rel_release_train WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY planned_date DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, TRAIN_MAPPER);
    }

    public long countTrains(String status) {
        if (status == null || status.isBlank()) {
            Long count = plain.queryForObject(
                    "SELECT count(*) FROM release_svc.rel_release_train", Long.class);
            return count == null ? 0 : count;
        }
        Long count = plain.queryForObject(
                "SELECT count(*) FROM release_svc.rel_release_train WHERE status = ?",
                Long.class, status);
        return count == null ? 0 : count;
    }

    public Optional<Train> findTrain(UUID id) {
        return plain.query("SELECT " + TRAIN_COLUMNS
                + " FROM release_svc.rel_release_train WHERE id = ?", TRAIN_MAPPER, id)
                .stream().findFirst();
    }

    public void insertTrain(Train train) {
        jdbc.update("""
                INSERT INTO release_svc.rel_release_train
                    (id, train_code, name, planned_date, freeze_at, status, notes, created_by)
                VALUES (:id, :code, :name, :planned, :freeze, :status, :notes, :actor)
                """, new MapSqlParameterSource()
                .addValue("id", train.id())
                .addValue("code", train.trainCode())
                .addValue("name", train.name())
                .addValue("planned", train.plannedDate())
                .addValue("freeze", timestamp(train.freezeAt()))
                .addValue("status", train.status())
                .addValue("notes", train.notes())
                .addValue("actor", train.createdBy()));
    }

    private static final java.util.Set<String> MUTABLE_TRAIN_COLUMNS = java.util.Set.of(
            "name", "planned_date", "freeze_at", "status", "notes", "released_at");

    public boolean updateTrainFields(UUID id, long expected, Map<String, Object> fields) {
        return updateFields("release_svc.rel_release_train", "version", id, expected, fields,
                MUTABLE_TRAIN_COLUMNS, java.util.Set.of());
    }

    private static final RowMapper<TrainItem> ITEM_MAPPER = (rs, n) -> new TrainItem(
            rs.getObject("id", UUID.class),
            rs.getObject("train_id", UUID.class),
            rs.getString("component_code"),
            rs.getObject("version_id", UUID.class),
            rs.getString("added_by"),
            rs.getString("version_no"));

    public List<TrainItem> listTrainItems(UUID trainId) {
        return plain.query("""
                SELECT i.id, i.train_id, i.component_code, i.version_id, i.added_by,
                       v.version AS version_no
                  FROM release_svc.rel_train_item i
                  JOIN release_svc.rel_version v ON v.id = i.version_id
                 WHERE i.train_id = ?
                 ORDER BY i.component_code
                """, ITEM_MAPPER, trainId);
    }

    public Optional<TrainItem> findTrainItem(UUID trainId, String componentCode) {
        return plain.query("""
                SELECT i.id, i.train_id, i.component_code, i.version_id, i.added_by,
                       v.version AS version_no
                  FROM release_svc.rel_train_item i
                  JOIN release_svc.rel_version v ON v.id = i.version_id
                 WHERE i.train_id = ? AND i.component_code = ?
                """, ITEM_MAPPER, trainId, componentCode).stream().findFirst();
    }

    public void insertTrainItem(UUID trainId, String componentCode, UUID versionId, String actor) {
        jdbc.update("""
                INSERT INTO release_svc.rel_train_item
                    (id, train_id, component_code, version_id, added_by)
                VALUES (:id, :train, :component, :version, :actor)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("train", trainId)
                .addValue("component", componentCode)
                .addValue("version", versionId)
                .addValue("actor", actor));
    }

    public boolean deleteTrainItem(UUID trainId, String componentCode) {
        return plain.update("DELETE FROM release_svc.rel_train_item"
                + " WHERE train_id = ? AND component_code = ?", trainId, componentCode) > 0;
    }

    // ------------------------------------------------------------------ 兼容矩阵

    private static final RowMapper<Compatibility> COMPAT_MAPPER = (rs, n) -> new Compatibility(
            rs.getObject("id", UUID.class),
            rs.getObject("version_id", UUID.class),
            rs.getString("kind"),
            rs.getObject("target_version_id", UUID.class),
            rs.getString("note"),
            rs.getString("created_by"),
            instant(rs, "created_at"));

    public List<Compatibility> listCompatibility(UUID versionId) {
        return plain.query("""
                SELECT id, version_id, kind, target_version_id, note, created_by, created_at
                  FROM release_svc.rel_compatibility WHERE version_id = ?
                 ORDER BY kind, target_version_id
                """, COMPAT_MAPPER, versionId);
    }

    public List<Compatibility> listCompatibilityForVersions(List<UUID> versionIds) {
        if (versionIds == null || versionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, version_id, kind, target_version_id, note, created_by, created_at
                  FROM release_svc.rel_compatibility WHERE version_id IN (:ids)
                """, new MapSqlParameterSource("ids", versionIds), COMPAT_MAPPER);
    }

    public void insertCompatibility(Compatibility compatibility) {
        jdbc.update("""
                INSERT INTO release_svc.rel_compatibility
                    (id, version_id, kind, target_version_id, note, created_by)
                VALUES (:id, :version, :kind, :target, :note, :actor)
                """, new MapSqlParameterSource()
                .addValue("id", compatibility.id())
                .addValue("version", compatibility.versionId())
                .addValue("kind", compatibility.kind())
                .addValue("target", compatibility.targetVersionId())
                .addValue("note", compatibility.note())
                .addValue("actor", compatibility.createdBy()));
    }

    public boolean deleteCompatibility(UUID id) {
        return plain.update("DELETE FROM release_svc.rel_compatibility WHERE id = ?", id) > 0;
    }

    // ------------------------------------------------------------------ 补丁

    public String nextPatchNo() {
        Long seq = plain.queryForObject("SELECT nextval('release_svc.rel_patch_no_seq')", Long.class);
        return "PAT" + DAY.format(BusinessTime.today())
                + String.format("%05d", seq == null ? 1 : seq);
    }

    private static final RowMapper<Patch> PATCH_MAPPER = (rs, n) -> new Patch(
            rs.getObject("id", UUID.class),
            rs.getString("patch_no"),
            rs.getString("title"),
            rs.getString("severity"),
            rs.getString("component_code"),
            stringList(rs, "affected_versions"),
            rs.getObject("fixed_version_id", UUID.class),
            rs.getString("advisory"),
            rs.getString("cve_id"),
            rs.getString("status"),
            rs.getString("requested_by"),
            rs.getString("approved_by"),
            instant(rs, "approved_at"),
            instant(rs, "released_at"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String PATCH_COLUMNS = """
            id, patch_no, title, severity, component_code, affected_versions, fixed_version_id,
            advisory, cve_id, status, requested_by, approved_by, approved_at, released_at,
            version, created_at, updated_at
            """;

    public List<Patch> listPatches(String component, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + PATCH_COLUMNS
                + " FROM release_svc.rel_patch WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (component != null && !component.isBlank()) {
            sql.append(" AND component_code = :component");
            params.addValue("component", component);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, PATCH_MAPPER);
    }

    public long countPatches(String component, String status) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM release_svc.rel_patch WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (component != null && !component.isBlank()) {
            sql.append(" AND component_code = :component");
            params.addValue("component", component);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    public Optional<Patch> findPatch(UUID id) {
        return plain.query("SELECT " + PATCH_COLUMNS + " FROM release_svc.rel_patch WHERE id = ?",
                PATCH_MAPPER, id).stream().findFirst();
    }

    public Optional<Patch> findReleasedPatch(String component, UUID fixedVersionId) {
        return plain.query("SELECT " + PATCH_COLUMNS + """
                  FROM release_svc.rel_patch
                 WHERE component_code = ? AND fixed_version_id = ? AND status = 'RELEASED'
                 ORDER BY released_at DESC LIMIT 1
                """, PATCH_MAPPER, component, fixedVersionId).stream().findFirst();
    }

    public void insertPatch(Patch patch) {
        jdbc.update("""
                INSERT INTO release_svc.rel_patch
                    (id, patch_no, title, severity, component_code, affected_versions,
                     fixed_version_id, advisory, cve_id, status, requested_by)
                VALUES (:id, :no, :title, :severity, :component, CAST(:affected AS text[]),
                        :fixed, :advisory, :cve, :status, :actor)
                """, new MapSqlParameterSource()
                .addValue("id", patch.id())
                .addValue("no", patch.patchNo())
                .addValue("title", patch.title())
                .addValue("severity", patch.severity())
                .addValue("component", patch.componentCode())
                .addValue("affected", arrayLiteral(patch.affectedVersions()))
                .addValue("fixed", patch.fixedVersionId())
                .addValue("advisory", patch.advisory())
                .addValue("cve", patch.cveId())
                .addValue("status", patch.status())
                .addValue("actor", patch.requestedBy()));
    }

    private static final java.util.Set<String> MUTABLE_PATCH_COLUMNS = java.util.Set.of(
            "title", "severity", "component_code", "affected_versions", "fixed_version_id",
            "advisory", "cve_id", "status", "approved_by", "approved_at", "released_at");

    public boolean updatePatchFields(UUID id, long expected, Map<String, Object> fields) {
        return updateFields("release_svc.rel_patch", "version", id, expected, fields,
                MUTABLE_PATCH_COLUMNS, java.util.Set.of("affected_versions"));
    }

    // ------------------------------------------------------------------ 变更窗口

    private static final RowMapper<ChangeWindow> WINDOW_MAPPER = (rs, n) -> new ChangeWindow(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("kind"),
            instant(rs, "start_at"),
            instant(rs, "end_at"),
            stringList(rs, "scope"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getLong("version"));

    public List<ChangeWindow> listWindows(String kind, String status, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, name, kind, start_at, end_at, scope, reason, status, created_by, version
                  FROM release_svc.rel_change_window WHERE 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND kind = :kind");
            params.addValue("kind", kind);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY start_at DESC LIMIT :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, WINDOW_MAPPER);
    }

    public Optional<ChangeWindow> findWindow(UUID id) {
        return plain.query("""
                SELECT id, name, kind, start_at, end_at, scope, reason, status, created_by, version
                  FROM release_svc.rel_change_window WHERE id = ?
                """, WINDOW_MAPPER, id).stream().findFirst();
    }

    public void insertWindow(ChangeWindow window) {
        jdbc.update("""
                INSERT INTO release_svc.rel_change_window
                    (id, name, kind, start_at, end_at, scope, reason, status, created_by)
                VALUES (:id, :name, :kind, :start, :end, CAST(:scope AS text[]), :reason,
                        :status, :actor)
                """, new MapSqlParameterSource()
                .addValue("id", window.id())
                .addValue("name", window.name())
                .addValue("kind", window.kind())
                .addValue("start", timestamp(window.startAt()))
                .addValue("end", timestamp(window.endAt()))
                .addValue("scope", arrayLiteral(window.scope()))
                .addValue("reason", window.reason())
                .addValue("status", window.status())
                .addValue("actor", window.createdBy()));
    }

    private static final java.util.Set<String> MUTABLE_WINDOW_COLUMNS = java.util.Set.of(
            "name", "kind", "start_at", "end_at", "scope", "reason", "status");

    public boolean updateWindowFields(UUID id, long expected, Map<String, Object> fields) {
        return updateFields("release_svc.rel_change_window", "version", id, expected, fields,
                MUTABLE_WINDOW_COLUMNS, java.util.Set.of("scope"));
    }

    /** 当前生效的 FREEZE 窗口（scope 为空=全院，否则按组件匹配）。 */
    public List<ChangeWindow> activeFreezes(Instant at, String componentCode) {
        return jdbc.query("""
                SELECT id, name, kind, start_at, end_at, scope, reason, status, created_by, version
                  FROM release_svc.rel_change_window
                 WHERE status = 'ACTIVE' AND kind = 'FREEZE'
                   AND start_at <= :at AND end_at >= :at
                   AND (cardinality(scope) = 0 OR :component = ANY(scope))
                """, new MapSqlParameterSource("at", timestamp(at)).addValue("component", componentCode),
                WINDOW_MAPPER);
    }

    // ------------------------------------------------------------------ 部署

    private static final RowMapper<Deployment> DEPLOY_MAPPER = (rs, n) -> new Deployment(
            rs.getObject("id", UUID.class),
            rs.getString("component_code"),
            rs.getObject("version_id", UUID.class),
            rs.getString("environment"),
            rs.getString("action"),
            rs.getString("result"),
            readMap(rs.getString("checklist")),
            rs.getObject("rollback_of", UUID.class),
            rs.getString("reason"),
            rs.getString("actor"),
            instant(rs, "started_at"),
            instant(rs, "finished_at"),
            instant(rs, "created_at"));

    private static final String DEPLOY_COLUMNS = """
            id, component_code, version_id, environment, action, result, checklist, rollback_of,
            reason, actor, started_at, finished_at, created_at
            """;

    public void insertDeployment(Deployment deployment) {
        jdbc.update("""
                INSERT INTO release_svc.rel_deployment
                    (id, component_code, version_id, environment, action, result, checklist,
                     rollback_of, reason, actor, started_at, finished_at)
                VALUES (:id, :component, :version, :env, :action, :result,
                        CAST(:checklist AS jsonb), :rollbackOf, :reason, :actor, :started, :finished)
                """, new MapSqlParameterSource()
                .addValue("id", deployment.id())
                .addValue("component", deployment.componentCode())
                .addValue("version", deployment.versionId())
                .addValue("env", deployment.environment())
                .addValue("action", deployment.action())
                .addValue("result", deployment.result())
                .addValue("checklist", write(deployment.checklist()))
                .addValue("rollbackOf", deployment.rollbackOf())
                .addValue("reason", deployment.reason())
                .addValue("actor", deployment.actor())
                .addValue("started", timestamp(deployment.startedAt()))
                .addValue("finished", timestamp(deployment.finishedAt())));
    }

    public Optional<Deployment> findDeployment(UUID id) {
        return plain.query("SELECT " + DEPLOY_COLUMNS
                + " FROM release_svc.rel_deployment WHERE id = ?", DEPLOY_MAPPER, id)
                .stream().findFirst();
    }

    public List<Deployment> listDeployments(String component, String environment, String result,
                                            int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + DEPLOY_COLUMNS
                + " FROM release_svc.rel_deployment WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (component != null && !component.isBlank()) {
            sql.append(" AND component_code = :component");
            params.addValue("component", component);
        }
        if (environment != null && !environment.isBlank()) {
            sql.append(" AND environment = :env");
            params.addValue("env", environment);
        }
        if (result != null && !result.isBlank()) {
            sql.append(" AND result = :result");
            params.addValue("result", result);
        }
        sql.append(" ORDER BY started_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, DEPLOY_MAPPER);
    }

    public long countDeployments(String component, String environment, String result) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM release_svc.rel_deployment WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (component != null && !component.isBlank()) {
            sql.append(" AND component_code = :component");
            params.addValue("component", component);
        }
        if (environment != null && !environment.isBlank()) {
            sql.append(" AND environment = :env");
            params.addValue("env", environment);
        }
        if (result != null && !result.isBlank()) {
            sql.append(" AND result = :result");
            params.addValue("result", result);
        }
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count == null ? 0 : count;
    }

    /** 回滚目标：同一组件/环境最近一次成功部署（排除当前记录）。 */
    public Optional<Deployment> previousSuccessfulDeployment(String component, String environment,
                                                             UUID excludeId) {
        return jdbc.query("SELECT " + DEPLOY_COLUMNS + """
                  FROM release_svc.rel_deployment
                 WHERE component_code = :component AND environment = :env
                   AND result = 'SUCCESS' AND action IN ('DEPLOY', 'HOTFIX')
                   AND id <> :exclude
                 ORDER BY started_at DESC LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("component", component)
                        .addValue("env", environment)
                        .addValue("exclude", excludeId),
                DEPLOY_MAPPER).stream().findFirst();
    }

    // ------------------------------------------------------------------ 看板/审计

    public List<Map<String, Object>> countVersionsByStatus() {
        return plain.queryForList(
                "SELECT status, count(*) AS n FROM release_svc.rel_version GROUP BY status");
    }

    public List<Map<String, Object>> countOpenPatchesBySeverity() {
        return plain.queryForList("""
                SELECT severity, count(*) AS n FROM release_svc.rel_patch
                 WHERE status NOT IN ('RELEASED', 'WITHDRAWN')
                 GROUP BY severity ORDER BY severity
                """);
    }

    public long countDeploymentsSince(Instant since) {
        Long n = plain.queryForObject(
                "SELECT count(*) FROM release_svc.rel_deployment WHERE started_at >= ?",
                Long.class, timestamp(since));
        return n == null ? 0 : n;
    }

    public long countFailedDeploymentsSince(Instant since) {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM release_svc.rel_deployment
                 WHERE started_at >= ? AND result = 'FAILED'
                """, Long.class, timestamp(since));
        return n == null ? 0 : n;
    }

    public long countActiveFreezes(Instant at) {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM release_svc.rel_change_window
                 WHERE status = 'ACTIVE' AND kind = 'FREEZE' AND start_at <= ? AND end_at >= ?
                """, Long.class, timestamp(at), timestamp(at));
        return n == null ? 0 : n;
    }

    public void audit(String actor, String action, String targetRef, Map<String, Object> detail,
                      String clientIp, String traceId) {
        jdbc.update("""
                INSERT INTO release_svc.rel_change_log
                    (actor, action, target_ref, detail, client_ip, trace_id)
                VALUES (:actor, :action, :target, CAST(:detail AS jsonb), :ip, :trace)
                """, new MapSqlParameterSource()
                .addValue("actor", actor)
                .addValue("action", action)
                .addValue("target", targetRef)
                .addValue("detail", write(detail == null ? Map.of() : detail))
                .addValue("ip", clientIp)
                .addValue("trace", traceId));
    }

    public List<AuditLog> listAudit(int limit) {
        return plain.query("""
                SELECT id, ts, actor, action, target_ref, detail, client_ip, trace_id
                  FROM release_svc.rel_change_log ORDER BY ts DESC LIMIT ?
                """, (rs, n) -> new AuditLog(
                rs.getLong("id"),
                instant(rs, "ts"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target_ref"),
                readMap(rs.getString("detail")),
                rs.getString("client_ip"),
                rs.getString("trace_id")), limit);
    }

    // ------------------------------------------------------------------ 泛型更新/JSON

    private boolean updateFields(String table, String versionColumn, UUID id, long expected,
                                 Map<String, Object> fields, java.util.Set<String> allowed,
                                 java.util.Set<String> arrayColumns) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String column = entry.getKey();
            if (!allowed.contains(column)) {
                throw new IllegalArgumentException("不允许修改的字段：" + column);
            }
            if (arrayColumns.contains(column)) {
                @SuppressWarnings("unchecked")
                List<String> values = (List<String>) entry.getValue();
                sets.add(column + " = CAST(:" + column + " AS text[])");
                params.addValue(column, arrayLiteral(values));
            } else {
                sets.add(column + " = :" + column);
                params.addValue(column, entry.getValue() instanceof Instant instant
                        ? timestamp(instant) : entry.getValue());
            }
        }
        params.addValue("id", id).addValue("expected", expected);
        String sql = "UPDATE " + table + " SET " + String.join(", ", sets)
                + ", " + versionColumn + " = " + versionColumn + " + 1, updated_at = now()"
                + " WHERE id = :id AND " + versionColumn + " = :expected";
        return jdbc.update(sql, params) > 0;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return STATIC_JSON.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return Arrays.asList(values);
        }
        if (raw instanceof Object[] values) {
            return Arrays.stream(values).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static String arrayLiteral(List<?> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values.stream().map(String::valueOf).toList()) + "}";
    }
}
