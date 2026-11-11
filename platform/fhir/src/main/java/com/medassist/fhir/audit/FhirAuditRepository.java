package com.medassist.fhir.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * FHIR 读取审计（追加式）。
 *
 * <p><b>失败方向</b>：审计写失败时异常向上抛，请求以 500 结束——数据不返回。
 * 这是刻意选择：PHI 读取"发生了但没留下痕迹"比"这次读取失败"严重得多
 * （等保三级与 ISO 27799 都要求访问可追溯）。
 *
 * <p><b>不落明文</b>：{@code querySummary} 只记参数名与是否给出，
 * 姓名/证件号等检索值一律不入库；定位对象用资源 id。
 */
@Repository
public class FhirAuditRepository {

    private static final String INSERT = """
            INSERT INTO fhir_svc.access_log
                (actor, action, resource_type, resource_id, query_summary, result_count,
                 outcome, client_ip, trace_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public FhirAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String resourceType, String resourceId,
                       String querySummary, Integer resultCount, String outcome,
                       String clientIp, String traceId) {
        jdbc.update(INSERT, actor, action, resourceType, resourceId, querySummary, resultCount,
                outcome, clientIp, traceId);
    }
}
