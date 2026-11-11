package com.medassist.clinical.repo;

import com.medassist.clinical.domain.PrintModel.PrintEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 打印留痕读写（只插入 + 有界查询）。
 *
 * <p>查询一律带 {@code LIMIT}：打印流水会随业务量线性增长，
 * 未加限制的"按单据查全部"会把长住院患者的整段补打历史拉进内存。
 */
@Repository
public class PrintEventRepository {

    private static final int MAX_LIMIT = 200;

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

    /**
     * 按条件查最近打印记录；条件全部为空时返回最近的全局流水。
     *
     * <p>{@code campusCsv} 为空表示不限院区（全院角色）；否则只返回这些院区的记录，
     * 且不返回 {@code campus_id IS NULL} 的跨院区流水——那部分只对全院角色可见。
     */
    public List<PrintEvent> list(String docType, String docNo, String targetType,
                                 UUID targetId, String campusCsv, int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
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
                         ORDER BY printed_at DESC, id DESC
                         LIMIT :size
                        """)
                .param("docType", docType == null ? "" : docType)
                .param("docNo", docNo == null ? "" : docNo)
                .param("targetType", targetType == null ? "" : targetType)
                .param("targetId", targetId)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("size", size)
                .query((rs, i) -> new PrintEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("doc_type"),
                        rs.getString("doc_no"),
                        rs.getString("target_type"),
                        rs.getObject("target_id", UUID.class),
                        rs.getString("patient_no"),
                        rs.getString("actor"),
                        rs.getObject("campus_id", UUID.class),
                        rs.getObject("printed_at", java.time.OffsetDateTime.class)))
                .list();
    }
}
