package com.medassist.ops.repo;

import com.medassist.ops.domain.SloModel.Slo;
import com.medassist.ops.domain.SloModel.Snapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * SLO 仓储（ops_svc.ops_slo*）。
 *
 * <p>快照以幂等键唯一；聚合在 SQL 内完成，不把明细拉进内存再求和。
 */
@Repository
public class SloRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;

    public SloRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain) {
        this.jdbc = jdbc;
        this.plain = plain;
    }

    // ------------------------------------------------------------------ SLO

    private static final RowMapper<Slo> SLO_MAPPER = (rs, n) -> new Slo(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("service_code"),
            rs.getString("indicator"),
            rs.getBigDecimal("target_percent"),
            rs.getInt("window_days"),
            rs.getObject("latency_threshold_ms", Integer.class),
            rs.getString("owner"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getLong("version"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"));

    private static final String SLO_COLUMNS = """
            id, code, name, service_code, indicator, target_percent, window_days,
            latency_threshold_ms, owner, description, status, version, created_by,
            created_at, updated_at
            """;

    public List<Slo> listSlos(boolean onlyActive) {
        return plain.query("SELECT " + SLO_COLUMNS + " FROM ops_svc.ops_slo"
                + (onlyActive ? " WHERE status = 'ACTIVE'" : "")
                + " ORDER BY code", SLO_MAPPER);
    }

    public Optional<Slo> findSlo(UUID id) {
        return plain.query("SELECT " + SLO_COLUMNS
                + " FROM ops_svc.ops_slo WHERE id = ?", SLO_MAPPER, id).stream().findFirst();
    }

    public Optional<Slo> findSloByCode(String code) {
        return plain.query("SELECT " + SLO_COLUMNS
                + " FROM ops_svc.ops_slo WHERE code = ?", SLO_MAPPER, code).stream().findFirst();
    }

    public void upsertSlo(Slo slo) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_slo
                    (id, code, name, service_code, indicator, target_percent, window_days,
                     latency_threshold_ms, owner, description, status, created_by)
                VALUES
                    (:id, :code, :name, :service, :indicator, :target, :window,
                     :latency, :owner, :description, :status, :createdBy)
                ON CONFLICT (code) DO UPDATE SET
                    name = EXCLUDED.name, service_code = EXCLUDED.service_code,
                    indicator = EXCLUDED.indicator, target_percent = EXCLUDED.target_percent,
                    window_days = EXCLUDED.window_days,
                    latency_threshold_ms = EXCLUDED.latency_threshold_ms,
                    owner = EXCLUDED.owner, description = EXCLUDED.description,
                    status = EXCLUDED.status, version = ops_svc.ops_slo.version + 1,
                    updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", slo.id())
                .addValue("code", slo.code())
                .addValue("name", slo.name())
                .addValue("service", slo.serviceCode())
                .addValue("indicator", slo.indicator())
                .addValue("target", slo.targetPercent())
                .addValue("window", slo.windowDays())
                .addValue("latency", slo.latencyThresholdMs())
                .addValue("owner", slo.owner())
                .addValue("description", slo.description())
                .addValue("status", slo.status())
                .addValue("createdBy", slo.createdBy()));
    }

    public boolean updateStatus(UUID id, String status) {
        return plain.update("""
                UPDATE ops_svc.ops_slo
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ?
                """, status, id) > 0;
    }

    // ------------------------------------------------------------------ 快照

    private static final RowMapper<Snapshot> SNAPSHOT_MAPPER = (rs, n) -> new Snapshot(
            rs.getObject("id", UUID.class),
            rs.getObject("slo_id", UUID.class),
            instant(rs, "window_start"),
            instant(rs, "window_end"),
            rs.getLong("total_events"),
            rs.getLong("good_events"),
            rs.getString("source"),
            rs.getString("idempotency_key"),
            rs.getString("note"),
            rs.getString("reported_by"),
            instant(rs, "created_at"));

    public Optional<Snapshot> findSnapshotByIdempotency(String key) {
        return plain.query("""
                SELECT id, slo_id, window_start, window_end, total_events, good_events, source,
                       idempotency_key, note, reported_by, created_at
                  FROM ops_svc.ops_slo_snapshot WHERE idempotency_key = ?
                """, SNAPSHOT_MAPPER, key).stream().findFirst();
    }

    public void insertSnapshot(Snapshot snapshot) {
        jdbc.update("""
                INSERT INTO ops_svc.ops_slo_snapshot
                    (id, slo_id, window_start, window_end, total_events, good_events, source,
                     idempotency_key, note, reported_by)
                VALUES
                    (:id, :slo, :start, :end, :total, :good, :source, :key, :note, :reporter)
                """, new MapSqlParameterSource()
                .addValue("id", snapshot.id())
                .addValue("slo", snapshot.sloId())
                .addValue("start", timestamp(snapshot.windowStart()))
                .addValue("end", timestamp(snapshot.windowEnd()))
                .addValue("total", snapshot.totalEvents())
                .addValue("good", snapshot.goodEvents())
                .addValue("source", snapshot.source())
                .addValue("key", snapshot.idempotencyKey())
                .addValue("note", snapshot.note())
                .addValue("reporter", snapshot.reportedBy()));
    }

    public List<Snapshot> listSnapshots(UUID sloId, int limit) {
        return plain.query("""
                SELECT id, slo_id, window_start, window_end, total_events, good_events, source,
                       idempotency_key, note, reported_by, created_at
                  FROM ops_svc.ops_slo_snapshot
                 WHERE slo_id = ? ORDER BY window_end DESC LIMIT ?
                """, SNAPSHOT_MAPPER, sloId, limit);
    }

    /** 滚动窗口聚合：total/good/样本数与窗口边界。 */
    public Map<String, Object> aggregate(UUID sloId, Instant since) {
        Map<String, Object> row = plain.queryForMap("""
                SELECT coalesce(sum(total_events), 0) AS total,
                       coalesce(sum(good_events), 0) AS good,
                       count(*) AS samples,
                       min(window_start) AS window_start,
                       max(window_end) AS window_end
                  FROM ops_svc.ops_slo_snapshot
                 WHERE slo_id = ? AND window_end >= ?
                """, sloId, timestamp(since));
        return row == null ? new LinkedHashMap<>() : new LinkedHashMap<>(row);
    }

    public List<Map<String, Object>> serviceCatalog() {
        return plain.queryForList("""
                SELECT service_code, name FROM ops_svc.ops_service_catalog
                 WHERE status = 'ACTIVE' ORDER BY sort_no, service_code
                """);
    }

    // ------------------------------------------------------------------ 工具

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
