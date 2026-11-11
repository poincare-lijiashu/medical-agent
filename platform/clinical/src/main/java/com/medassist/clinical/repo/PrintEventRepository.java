package com.medassist.clinical.repo;

import com.medassist.clinical.domain.PrintModel;
import com.medassist.clinical.domain.PrintModel.PrintAnomaly;
import com.medassist.clinical.domain.PrintModel.PrintAnomalySummary;
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
                             actor, campus_id, printed_at, reprint_seq, reason)
                        VALUES
                            (:id, :docType, :docNo, :targetType, :targetId, :patientNo,
                             :actor, :campusId, :printedAt, :reprintSeq, :reason)
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
                .param("reprintSeq", e.reprintSeq())
                .param("reason", e.reason())
                .update();
    }

    /**
     * 该单号已有多少条留痕（第 238 批：判定本次是第几次打印）。
     *
     * <p>不按日期/院区过滤：补打次数是单据维度的累计事实，
     * 换个日期查就变成“首次”会让原因登记形同虚设。
     */
    public long countByDoc(String docType, String docNo) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM clinical_svc.print_event
                         WHERE doc_type = :docType
                           AND doc_no = :docNo
                        """)
                .param("docType", docType)
                .param("docNo", docNo)
                .query(Long.class)
                .single();
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
                               actor, campus_id, printed_at, reprint_seq, reason
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
                        rs.getObject("printed_at", OffsetDateTime.class),
                        rs.getInt("reprint_seq"),
                        rs.getString("reason")))
                .list();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /**
     * 补打异常聚合：同一单号在期间内打印次数 ≥ threshold 的记录。
     *
     * <p>`patient_no` 取 max（同单号应一致）；`actors` 为去重后的人数，
     * 用来区分“同一人反复打”与“多人各打一次”——两者都算重复出单，但处置不同。
     *
     * <p>第 239 批：左连最新一次核查处置，`pending` 由服务层按
     * “未处置 或 处置早于最后一次出纸”判定，SQL 只负责取数。
     */
    public List<PrintAnomaly> listAnomalies(String campusCsv, OffsetDateTime fromTs,
                                            OffsetDateTime toTs, int threshold, int limit) {
        return jdbc.sql(AGGREGATE_CTE + """
                        SELECT a.doc_type, a.doc_no, a.patient_no, a.prints, a.actors, a.unexplained,
                               a.first_at, a.last_at,
                               l.verdict, l.note AS review_note, l.actor AS review_actor,
                               l.reviewed_at
                          FROM agg a
                          LEFT JOIN latest l ON l.doc_type = a.doc_type AND l.doc_no = a.doc_no
                         ORDER BY a.prints DESC, a.last_at DESC
                         LIMIT :limit
                        """)
                .param("campusCsv", nz(campusCsv))
                .param("fromTs", fromTs)
                .param("toTs", toTs)
                .param("threshold", threshold)
                .param("limit", limit)
                .query((rs, i) -> {
                    OffsetDateTime reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
                    OffsetDateTime lastAt = rs.getObject("last_at", OffsetDateTime.class);
                    PrintModel.Review review = reviewedAt == null ? null : new PrintModel.Review(
                            rs.getString("verdict"),
                            rs.getString("review_note"),
                            rs.getString("review_actor"),
                            reviewedAt);
                    return new PrintAnomaly(
                            rs.getString("doc_type"),
                            rs.getString("doc_no"),
                            rs.getString("patient_no"),
                            rs.getLong("prints"),
                            rs.getLong("actors"),
                            rs.getLong("unexplained"),
                            rs.getObject("first_at", OffsetDateTime.class),
                            lastAt,
                            review,
                            reviewedAt == null || reviewedAt.isBefore(lastAt));
                })
                .list();
    }

    /** 待核查统计（第 239 批）：全局待办只消费 pending 一个数。 */
    public PrintAnomalySummary anomalySummary(String campusCsv, OffsetDateTime fromTs,
                                              OffsetDateTime toTs, int threshold) {
        return jdbc.sql(AGGREGATE_CTE + """
                        SELECT count(*) AS total,
                               count(*) FILTER (
                                   WHERE l.reviewed_at IS NULL OR l.reviewed_at < a.last_at
                               ) AS pending
                          FROM agg a
                          LEFT JOIN latest l ON l.doc_type = a.doc_type AND l.doc_no = a.doc_no
                        """)
                .param("campusCsv", nz(campusCsv))
                .param("fromTs", fromTs)
                .param("toTs", toTs)
                .param("threshold", threshold)
                .query((rs, i) -> new PrintAnomalySummary(rs.getLong("pending"), rs.getLong("total")))
                .single();
    }

    /** 追加一条核查处置；同一单号可多次处置，最新一条为当前结论。 */
    public void insertReview(UUID id, String docType, String docNo, String verdict,
                             String note, String actor, OffsetDateTime reviewedAt) {
        jdbc.sql("""
                        INSERT INTO clinical_svc.print_anomaly_review
                            (id, doc_type, doc_no, verdict, note, actor, reviewed_at)
                        VALUES
                            (:id, :docType, :docNo, :verdict, :note, :actor, :reviewedAt)
                        """)
                .param("id", id)
                .param("docType", docType)
                .param("docNo", docNo)
                .param("verdict", verdict)
                .param("note", note)
                .param("actor", actor)
                .param("reviewedAt", reviewedAt)
                .update();
    }

    /**
     * 异常聚合 CTE（列表与计数共用）。
     *
     * <p>两处必须同口径，否则会出现“待办说有 3 条、点进去列表只有 2 条”的错位；
     * 放在一处常量里，改一处即两边同时生效。
     */
    private static final String AGGREGATE_CTE = """
            WITH agg AS (
                SELECT doc_type,
                       doc_no,
                       max(patient_no) AS patient_no,
                       count(*) AS prints,
                       count(DISTINCT actor) AS actors,
                       count(*) FILTER (WHERE reprint_seq >= 2
                                          AND (reason IS NULL OR reason = '')) AS unexplained,
                       min(printed_at) AS first_at,
                       max(printed_at) AS last_at
                  FROM clinical_svc.print_event
                 WHERE (CAST(:campusCsv AS text) = ''
                        OR campus_id = ANY(string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                   AND (CAST(:fromTs AS timestamptz) IS NULL
                        OR printed_at >= CAST(:fromTs AS timestamptz))
                   AND (CAST(:toTs AS timestamptz) IS NULL
                        OR printed_at < CAST(:toTs AS timestamptz))
                 GROUP BY doc_type, doc_no
                HAVING count(*) >= :threshold
            ), latest AS (
                SELECT DISTINCT ON (doc_type, doc_no)
                       doc_type, doc_no, verdict, note, actor, reviewed_at
                  FROM clinical_svc.print_anomaly_review
                 ORDER BY doc_type, doc_no, reviewed_at DESC, id DESC
            )
            """;
}
