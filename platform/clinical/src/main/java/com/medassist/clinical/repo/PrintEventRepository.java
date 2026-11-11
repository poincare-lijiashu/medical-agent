package com.medassist.clinical.repo;

import com.medassist.clinical.domain.PrintModel.PrintEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 打印留痕读写（只插入 + 有界分页查询）。
 *
 * <p>查询一律带 `LIMIT`/`OFFSET`：打印流水随业务量线性增长，
 * 未加限制的“按单据查全部”会把长住院患者的整段补打历史拉进内存。
 *
 * <p>时间窗口径：`[fromTs, toTs)` —— 调用方把“截止日”换算成次日零点，
 * 避免 `<= 23:59:59` 漏掉当天最后一秒内的记录。
 */
@Repository
public class PrintEventRepository {

    private final JdbcClient jdbc;

    public PrintEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PrintEvent e) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.print_event
                            (id, doc_type, doc_no, target_type, target_id, patient_no,
                             actor, campus_id, printed_at)
                        VALUES
                            (:id, :docType, :docNo, :targetType, :targetId, :patientNo,
                             :actor, :campusId, :printedAt)
                        """)
                .param("id", e.id())
                .param("docType", e.docType())
                .param("docNo", e.docNo())
                .param("targetType", e.targetType())
                .param("targetId", e.targetId())
                .param("patientNo", e.patientNo())
                .param("actor", e.actor())
                .param("campusId", e.campusId())
                .param("printedAt", e.printedAt())
                .update();
    }

    /** 满足条件的记录总数（分页条用）。 */
    public long count(String docType, String docNo, String targetType, UUID targetId,
                      String campusCsv, OffsetDateTime fromTs, OffsetDateTime toTs) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM clinical_svc.print_event
                         WHERE (CAST(:docType AS text) = '' OR doc_type = CAST(:docType AS text))
                           AND (CAST(:docNo AS text) = '' OR doc_no = CAST(:docNo AS text))
                           AND (CAST(:targetType AS text) = '' OR target_type = CAST(:targetType AS text))
                           AND (CAST(:targetId AS uuid) IS NULL OR target_id = CAST(:targetId AS uuid))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                           AND (CAST(:fromTs AS timestamptz) IS NULL
                                OR printed_at >= CAST(:fromTs AS timestamptz))
                           AND (CAST(:toTs AS timestamptz) IS NULL
                                OR printed_at < CAST(:toTs AS timestamptz))
                        """)
                .param("docType", nz(docType))
                .param("docNo", nz(docNo))
                .param("targetType", nz(targetType))
                .param("targetId", targetId)
                .param("campusCsv", nz(campusCsv))
                .param("fromTs", fromTs)
                .param("toTs", toTs)
                .query(Long.class)
                .single();
    }

    /**
     * 分页查询；条件全为空时返回最近的全局流水。
     *
     * <p>{@code campusCsv} 为空表示不限院区（全院角色）；否则只返回这些院区的记录，
     * 且不返回 {@code campus_id IS NULL} 的跨院区流水——那部分只对全院角色可见。
     */
    public List<PrintEvent> list(String docType, String docNo, String targetType, UUID targetId,
                                 String campusCsv, OffsetDateTime fromTs, OffsetDateTime toTs,
                                 int offset, int limit) {
        return jdbc.sql("""
                        SELECT id, doc_type, doc_no, target_type, target_id, patient_no,
                               actor, campus_id, printed_at
                          FROM clinical_svc.print_event
                         WHERE (CAST(:docType AS text) = '' OR doc_type = CAST(:docType AS text))
                           AND (CAST(:docNo AS text) = '' OR doc_no = CAST(:docNo AS text))
                           AND (CAST(:targetType AS text) = '' OR target_type = CAST(:targetType AS text))
                           AND (CAST(:targetId AS uuid) IS NULL OR target_id = CAST(:targetId AS uuid))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                           AND (CAST(:fromTs AS timestamptz) IS NULL
                                OR printed_at >= CAST(:fromTs AS timestamptz))
                           AND (CAST(:toTs AS timestamptz) IS NULL
                                OR printed_at < CAST(:toTs AS timestamptz))
                         ORDER BY printed_at DESC, id DESC
                         LIMIT :limit OFFSET :offset
                        """)
                .param("docType", nz(docType))
                .param("docNo", nz(docNo))
                .param("targetType", nz(targetType))
                .param("targetId", targetId)
                .param("campusCsv", nz(campusCsv))
                .param("fromTs", fromTs)
                .param("toTs", toTs)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, i) -> new PrintEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("doc_type"),
                        rs.getString("doc_no"),
                        rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class),
                        rs.getString("patient_no"),
                        rs.getString("actor"),
                        rs.getObject("campus_id", UUID.class),
                        rs.getObject("printed_at", OffsetDateTime.class)))
                .list();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
