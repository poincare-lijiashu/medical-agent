package com.medassist.clinical.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 整区护理执行单批量查询（只读）。
 *
 * <p>一次 SQL 取“病区在院患者 + 当日有效长期医嘱 + 当日执行记录”，
 * 由服务层按患者分组。执行状态用 {@code coalesce(更正后状态, 原状态)}，
 * 与单患者接口同一口径。
 */
@Repository
public class NursingSheetRepository {

    private final JdbcClient jdbc;

    public NursingSheetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 病区在院患者数（用于“本区 N 人、其中 M 人有长嘱”的对照）。 */
    public int countActiveAdmissions(UUID wardId) {
        return jdbc.sql("""
                        SELECT count(*) FROM clinical_svc.admission
                         WHERE ward_id = :wardId AND status IN ('ADMITTED', 'IN_HOSPITAL')
                        """)
                .param("wardId", wardId)
                .query(Integer.class)
                .single();
    }

    /** 扁平结果：一行 = 一位患者的一条医嘱（含当日执行状态）。 */
    public List<SheetRow> listRows(UUID wardId, LocalDate date) {
        return jdbc.sql("""
                        SELECT a.id AS admission_id, a.admission_no, a.patient_no, a.visit_id,
                               b.bed_no, a.attending_doctor,
                               o.id AS order_id, o.order_no, o.item_name, o.dose, o.frequency,
                               coalesce(nc.to_status, ne.status) AS exec_status,
                               ne.executor, ne.note
                          FROM clinical_svc.admission a
                          JOIN clinical_svc.ward w ON w.id = a.ward_id
                          LEFT JOIN clinical_svc.bed b ON b.id = a.bed_id
                          JOIN clinical_svc.visit_order o
                            ON o.visit_id = a.visit_id
                           AND o.order_mode = 'LONG'
                           AND o.status = 'PLACED'
                           AND (o.start_date IS NULL OR o.start_date <= :date)
                           AND (o.stop_date IS NULL OR o.stop_date >= :date)
                          LEFT JOIN clinical_svc.nursing_execution ne
                            ON ne.admission_id = a.id AND ne.order_id = o.id AND ne.exec_date = :date
                          LEFT JOIN clinical_svc.nursing_execution_correction nc
                            ON nc.execution_id = ne.id
                         WHERE a.ward_id = :wardId AND a.status IN ('ADMITTED', 'IN_HOSPITAL')
                         ORDER BY b.bed_no NULLS LAST, a.admission_no, o.order_no
                        """)
                .param("wardId", wardId).param("date", date)
                .query((rs, i) -> new SheetRow(
                        rs.getObject("admission_id", UUID.class),
                        rs.getString("admission_no"),
                        rs.getString("patient_no"),
                        rs.getObject("visit_id", UUID.class),
                        rs.getString("bed_no"),
                        rs.getString("attending_doctor"),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("order_no"),
                        rs.getString("item_name"),
                        rs.getString("dose"),
                        rs.getString("frequency"),
                        rs.getString("exec_status"),
                        rs.getString("executor"),
                        rs.getString("note")))
                .list();
    }

    public record SheetRow(UUID admissionId, String admissionNo, String patientNo, UUID visitId,
                           String bedNo, String attendingDoctor,
                           UUID orderId, String orderNo, String itemName, String dose,
                           String frequency, String execStatus, String executor, String note) {
    }
}
