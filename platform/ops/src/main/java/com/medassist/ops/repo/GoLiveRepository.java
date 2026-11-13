package com.medassist.ops.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 上线指挥台只读聚合（跨 schema）。
 *
 * <p>只查询各域已存在的事实表，不复制、不写回；任一查询失败由服务层降级为 WARN/FAIL，
 * 不因单个域不可用而让整张报告不可读。
 */
@Repository
public class GoLiveRepository {

    private final JdbcTemplate plain;

    public GoLiveRepository(JdbcTemplate plain) {
        this.plain = plain;
    }

    /** 未关闭的 SEV1/SEV2 事件。 */
    public List<Map<String, Object>> openMajorIncidents() {
        return plain.queryForList("""
                SELECT incident_no, title, severity, status, detected_at
                  FROM ops_svc.ops_incident
                 WHERE status NOT IN ('RESOLVED', 'CLOSED')
                   AND severity IN ('SEV1', 'SEV2')
                 ORDER BY detected_at DESC
                 LIMIT 20
                """);
    }

    /** 未闭环的数据质量 BLOCKER/HIGH 问题。 */
    public List<Map<String, Object>> openQualityIssues() {
        return plain.queryForList("""
                SELECT issue_no, rule_code, rule_name, severity, status, occurrence_count
                  FROM mdm_svc.md_dq_issue
                 WHERE status IN ('OPEN', 'ACKNOWLEDGED')
                   AND severity IN ('BLOCKER', 'HIGH')
                 ORDER BY severity, updated_at DESC
                 LIMIT 20
                """);
    }

    /** 当前生效的变更冻结窗口（scope 为空=全院）。 */
    public List<Map<String, Object>> activeFreezes(Instant now, String componentCode) {
        return plain.queryForList("""
                SELECT name, reason, scope
                  FROM release_svc.rel_change_window
                 WHERE status = 'ACTIVE' AND kind = 'FREEZE'
                   AND start_at <= ? AND end_at >= ?
                   AND (cardinality(scope) = 0 OR ? = ANY(scope))
                 ORDER BY start_at
                """, Timestamp.from(now), Timestamp.from(now), componentCode);
    }

    /** 未关闭的 P1 工单。 */
    public List<Map<String, Object>> openP1Tickets() {
        return plain.queryForList("""
                SELECT ticket_no, title, status, service_code, created_at
                  FROM ops_svc.ops_ticket
                 WHERE priority = 'P1'
                   AND status NOT IN ('RESOLVED', 'CLOSED', 'CANCELLED')
                 ORDER BY created_at
                 LIMIT 20
                """);
    }
}
