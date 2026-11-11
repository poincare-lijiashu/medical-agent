package com.medassist.fhir.repo;

import com.medassist.fhir.repo.FhirQueries.DateRange;
import com.medassist.fhir.repo.FhirQueries.EncounterQuery;
import com.medassist.fhir.repo.FhirQueries.MedicationRequestQuery;
import com.medassist.fhir.repo.FhirQueries.ObservationQuery;
import com.medassist.fhir.repo.FhirQueries.PatientQuery;
import com.medassist.fhir.repo.FhirQueries.PatientRef;
import com.medassist.fhir.repo.FhirRows.CrosswalkRow;
import com.medassist.fhir.repo.FhirRows.EncounterRow;
import com.medassist.fhir.repo.FhirRows.MedicationRequestRow;
import com.medassist.fhir.repo.FhirRows.ObservationRow;
import com.medassist.fhir.repo.FhirRows.PatientRow;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FHIR 只读投影仓储。
 *
 * <p><b>纪律</b>：本类只允许 SELECT（唯一的写走 {@code FhirAuditRepository} 的审计表）。
 * 不在这里做业务规则判断——授权、脱敏、分页边界都在上层完成，仓储只负责
 * "按条件把行取出来"，避免同一个规则在 SQL 与 Java 里各写一遍后漂移。
 *
 * <p><b>跨 schema 读取的取舍</b>：FHIR 是只读投影层，按资源需要读取各域 schema。
 * 生产建议把本服务的数据源指向只读副本，并使用仅授予 SELECT 的账号
 * （见 platform/README.md「FHIR 开放层」）；业务写入仍由各域服务负责。
 */
@Repository
public class FhirRepository {

    /** 院内患者号标识系统。 */
    public static final String PATIENT_NO_SYSTEM = "urn:medassist:patient-no";

    /**
     * 就诊的 FHIR 状态表达式（唯一真源）。
     *
     * <p>同一段 SQL 同时用于 SELECT 与 WHERE：若分开手写，过滤条件与返回值
     * 一旦不一致，就会出现"筛 in-progress 却返回 finished"这种最难排查的问题。
     */
    private static final String ENCOUNTER_STATUS_EXPR = """
            CASE
                WHEN a.status = 'DISCHARGED' THEN 'finished'
                WHEN a.status = 'CANCELLED' THEN 'cancelled'
                WHEN a.status IN ('ADMITTED', 'IN_HOSPITAL') THEN 'in-progress'
                WHEN v.status = 'REGISTERED' THEN 'arrived'
                WHEN v.status = 'IN_TREATMENT' THEN 'in-progress'
                WHEN v.status = 'FINISHED' THEN 'finished'
                WHEN v.status = 'CANCELLED' THEN 'cancelled'
                ELSE 'unknown'
            END
            """;

    /** 危急值对应报告的 FHIR Observation 状态：只有已签发/已修订才是 final。 */
    private static final String OBSERVATION_STATUS_EXPR = """
            CASE WHEN r.status IN ('FINAL', 'AMENDED') THEN 'final' ELSE 'preliminary' END
            """;

    /** 处方状态到 MedicationRequest.status 的映射（处方状态机见 pharmacy V1/V5）。 */
    private static final String MEDICATION_STATUS_EXPR = """
            CASE p.status
                WHEN 'PENDING_REVIEW' THEN 'active'
                WHEN 'APPROVED' THEN 'active'
                WHEN 'DISPENSED' THEN 'completed'
                WHEN 'REJECTED' THEN 'cancelled'
                WHEN 'CANCELLED' THEN 'cancelled'
                ELSE 'unknown'
            END
            """;

    private static final String PATIENT_SELECT = """
            SELECT p.id, p.patient_no, p.name, p.gender, p.birth_date, p.blood_type, p.allergy,
                   p.status, p.merged_into, p.version, p.created_at, p.updated_at
              FROM empi_svc.patient p
            """;

    private static final String ENCOUNTER_SELECT = """
            SELECT v.id, v.visit_no, v.visit_type, %s AS fhir_status,
                   v.visit_date, v.registered_at, v.started_at, v.finished_at, v.campus_id,
                   v.patient_id, d.dept_code, d.name AS dept_name, v.doctor_id,
                   a.id AS admission_id, a.admission_no, a.admitted_at, a.discharged_at,
                   w.code AS ward_code, w.name AS ward_name, b.bed_no
              FROM clinical_svc.visit v
              JOIN clinical_svc.md_dept d ON d.id = v.dept_id
              LEFT JOIN clinical_svc.admission a ON a.visit_id = v.id
              LEFT JOIN clinical_svc.ward w ON w.id = a.ward_id
              LEFT JOIN clinical_svc.bed b ON b.id = a.bed_id
            """.formatted(ENCOUNTER_STATUS_EXPR);

    private static final String ENCOUNTER_FROM = """
              FROM clinical_svc.visit v
              JOIN clinical_svc.md_dept d ON d.id = v.dept_id
              LEFT JOIN clinical_svc.admission a ON a.visit_id = v.id
              LEFT JOIN clinical_svc.ward w ON w.id = a.ward_id
              LEFT JOIN clinical_svc.bed b ON b.id = a.bed_id
            """;

    private static final String OBSERVATION_SELECT = """
            SELECT cv.id, a.patient_id, a.visit_id, cv.item_name, cv.result_value, cv.reference_range,
                   cv.severity, %s AS fhir_status, r.report_no, r.reporter, cv.note,
                   cv.created_at, cv.acknowledged_at
              FROM tech_svc.critical_value cv
              JOIN tech_svc.exam_report r ON r.id = cv.report_id
              JOIN tech_svc.exam_application a ON a.id = cv.application_id
            """.formatted(OBSERVATION_STATUS_EXPR);

    private static final String OBSERVATION_FROM = """
              FROM tech_svc.critical_value cv
              JOIN tech_svc.exam_report r ON r.id = cv.report_id
              JOIN tech_svc.exam_application a ON a.id = cv.application_id
            """;

    private static final String MEDICATION_SELECT = """
            SELECT i.id, p.id AS prescription_id, p.prescription_no, p.patient_id, p.visit_id,
                   %s AS fhir_status, p.prescriber, p.reviewer, p.reviewed_at, p.reject_reason,
                   p.created_at, i.drug_code, i.drug_name, i.spec, i.quantity, i.unit,
                   i.dosage, i.frequency, i.days
              FROM pharmacy_svc.prescription_item i
              JOIN pharmacy_svc.prescription p ON p.id = i.prescription_id
            """.formatted(MEDICATION_STATUS_EXPR);

    private static final String MEDICATION_FROM = """
              FROM pharmacy_svc.prescription_item i
              JOIN pharmacy_svc.prescription p ON p.id = i.prescription_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public FhirRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 患者

    public Page<PatientRow> searchPatients(PatientQuery q) {
        Filtered filtered = patientFilter(q);
        long total = count("SELECT count(*) FROM empi_svc.patient p" + filtered.where(),
                filtered.params());
        MapSqlParameterSource params = copy(filtered.params())
                .addValue("limit", q.count())
                .addValue("offset", q.offset());
        List<PatientRow> rows = jdbc.query(
                PATIENT_SELECT + filtered.where()
                        + " ORDER BY p.updated_at DESC, p.id LIMIT :limit OFFSET :offset",
                params, PATIENT_MAPPER);
        return new Page<>(rows, total);
    }

    public Optional<PatientRow> findPatient(UUID id) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        return jdbc.query(PATIENT_SELECT + " WHERE p.id = :id", params, PATIENT_MAPPER)
                .stream().findFirst();
    }

    public List<CrosswalkRow> findCrosswalks(Collection<UUID> patientIds) {
        if (patientIds == null || patientIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                        SELECT patient_id, system_code, external_id
                          FROM empi_svc.patient_crosswalk
                         WHERE patient_id IN (:ids)
                         ORDER BY system_code, external_id
                        """,
                new MapSqlParameterSource("ids", patientIds), CROSSWALK_MAPPER);
    }

    private static Filtered patientFilter(PatientQuery q) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (q.id() != null) {
            where.add("p.id = :id");
            params.addValue("id", q.id());
        }
        if (q.identifierValue() != null) {
            params.addValue("identifier", q.identifierValue());
            if (q.identifierSystem() == null) {
                where.add("""
                        (p.patient_no = :identifier
                         OR EXISTS (SELECT 1 FROM empi_svc.patient_crosswalk cw
                                     WHERE cw.patient_id = p.id AND cw.external_id = :identifier))
                        """);
            } else if (PATIENT_NO_SYSTEM.equals(q.identifierSystem())) {
                where.add("p.patient_no = :identifier");
            } else {
                where.add("""
                        EXISTS (SELECT 1 FROM empi_svc.patient_crosswalk cw
                                 WHERE cw.patient_id = p.id
                                   AND cw.system_code = :cwSystem
                                   AND cw.external_id = :identifier)
                        """);
                params.addValue("cwSystem", q.identifierSystem());
            }
        }
        if (q.name() != null) {
            where.add("p.name ILIKE :name ESCAPE '\\'");
            params.addValue("name", "%" + escapeLike(q.name()) + "%");
        }
        if (q.active() != null) {
            where.add(q.active() ? "p.status = 'ACTIVE'" : "p.status <> 'ACTIVE'");
        }
        return new Filtered(whereSql(where), params);
    }

    // ---------------------------------------------------------------- 就诊

    public Page<EncounterRow> searchEncounters(EncounterQuery q, Collection<UUID> campuses) {
        Filtered filtered = encounterFilter(q, campuses);
        long total = count("SELECT count(*)" + ENCOUNTER_FROM + filtered.where(),
                filtered.params());
        MapSqlParameterSource params = copy(filtered.params())
                .addValue("limit", q.count())
                .addValue("offset", q.offset());
        List<EncounterRow> rows = jdbc.query(
                ENCOUNTER_SELECT + filtered.where()
                        + " ORDER BY v.visit_date DESC, v.registered_at DESC, v.id"
                        + " LIMIT :limit OFFSET :offset",
                params, ENCOUNTER_MAPPER);
        return new Page<>(rows, total);
    }

    public Optional<EncounterRow> findEncounter(UUID id, Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.add("v.id = :id");
        params.addValue("id", id);
        addCampusFilter(where, params, campuses, "v.campus_id");
        return jdbc.query(ENCOUNTER_SELECT + whereSql(where), params, ENCOUNTER_MAPPER)
                .stream().findFirst();
    }

    private static Filtered encounterFilter(EncounterQuery q, Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (q.id() != null) {
            where.add("v.id = :id");
            params.addValue("id", q.id());
        }
        addPatientRef(where, params, q.patient(), "v.patient_id", "v.patient_no");
        if (q.status() != null) {
            where.add("(" + ENCOUNTER_STATUS_EXPR + ") = :status");
            params.addValue("status", q.status());
        }
        addDateColumnRange(where, params, q.date(), "v.visit_date");
        addCampusFilter(where, params, campuses, "v.campus_id");
        return new Filtered(whereSql(where), params);
    }

    // ------------------------------------------------------------ 观察(危急值)

    public Page<ObservationRow> searchObservations(ObservationQuery q, Collection<UUID> campuses) {
        Filtered filtered = observationFilter(q, campuses);
        long total = count("SELECT count(*)" + OBSERVATION_FROM + filtered.where(),
                filtered.params());
        MapSqlParameterSource params = copy(filtered.params())
                .addValue("limit", q.count())
                .addValue("offset", q.offset());
        List<ObservationRow> rows = jdbc.query(
                OBSERVATION_SELECT + filtered.where()
                        + " ORDER BY cv.created_at DESC, cv.id LIMIT :limit OFFSET :offset",
                params, OBSERVATION_MAPPER);
        return new Page<>(rows, total);
    }

    public Optional<ObservationRow> findObservation(UUID id, Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.add("cv.id = :id");
        params.addValue("id", id);
        addCampusFilter(where, params, campuses, "a.campus_id");
        return jdbc.query(OBSERVATION_SELECT + whereSql(where), params, OBSERVATION_MAPPER)
                .stream().findFirst();
    }

    private static Filtered observationFilter(ObservationQuery q, Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (q.id() != null) {
            where.add("cv.id = :id");
            params.addValue("id", q.id());
        }
        addPatientRef(where, params, q.patient(), "a.patient_id", "a.patient_no");
        if (q.status() != null) {
            where.add("(" + OBSERVATION_STATUS_EXPR + ") = :status");
            params.addValue("status", q.status());
        }
        addTimestampDateRange(where, params, q.date(), "cv.created_at");
        addCampusFilter(where, params, campuses, "a.campus_id");
        return new Filtered(whereSql(where), params);
    }

    // -------------------------------------------------------------- 处方明细

    public Page<MedicationRequestRow> searchMedicationRequests(MedicationRequestQuery q,
                                                               Collection<UUID> campuses) {
        Filtered filtered = medicationFilter(q, campuses);
        long total = count("SELECT count(*)" + MEDICATION_FROM + filtered.where(),
                filtered.params());
        MapSqlParameterSource params = copy(filtered.params())
                .addValue("limit", q.count())
                .addValue("offset", q.offset());
        List<MedicationRequestRow> rows = jdbc.query(
                MEDICATION_SELECT + filtered.where()
                        + " ORDER BY p.created_at DESC, i.id LIMIT :limit OFFSET :offset",
                params, MEDICATION_MAPPER);
        return new Page<>(rows, total);
    }

    public Optional<MedicationRequestRow> findMedicationRequest(UUID id, Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        where.add("i.id = :id");
        params.addValue("id", id);
        addCampusFilter(where, params, campuses, "p.campus_id");
        return jdbc.query(MEDICATION_SELECT + whereSql(where), params, MEDICATION_MAPPER)
                .stream().findFirst();
    }

    private static Filtered medicationFilter(MedicationRequestQuery q, Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();

        if (q.id() != null) {
            where.add("i.id = :id");
            params.addValue("id", q.id());
        }
        addPatientRef(where, params, q.patient(), "p.patient_id", "p.patient_no");
        if (q.status() != null) {
            where.add("(" + MEDICATION_STATUS_EXPR + ") = :status");
            params.addValue("status", q.status());
        }
        addTimestampDateRange(where, params, q.date(), "p.created_at");
        addCampusFilter(where, params, campuses, "p.campus_id");
        return new Filtered(whereSql(where), params);
    }

    // ---------------------------------------------------------------- 公共

    /** 院区收敛：空集合表示"不限制院区"（仅网关下发的显式全院区账号）。 */
    public static void addCampusFilter(List<String> where, MapSqlParameterSource params,
                                       Collection<UUID> campuses, String column) {
        if (campuses == null || campuses.isEmpty()) {
            return;
        }
        where.add(column + " IN (:campuses)");
        params.addValue("campuses", campuses);
    }

    private static void addPatientRef(List<String> where, MapSqlParameterSource params,
                                      PatientRef patient, String idColumn, String noColumn) {
        if (patient == null) {
            return;
        }
        if (patient.patientId() != null) {
            where.add(idColumn + " = :patientId");
            params.addValue("patientId", patient.patientId());
        } else {
            where.add(noColumn + " = :patientNo");
            params.addValue("patientNo", patient.patientNo());
        }
    }

    /** date 类型列的闭区间（就诊日期）。 */
    private static void addDateColumnRange(List<String> where, MapSqlParameterSource params,
                                           DateRange range, String column) {
        if (range == null) {
            return;
        }
        if (range.from() != null) {
            where.add(column + " >= CAST(:dateFrom AS date)");
            params.addValue("dateFrom", range.from());
        }
        if (range.to() != null) {
            where.add(column + " <= CAST(:dateTo AS date)");
            params.addValue("dateTo", range.to());
        }
    }

    /** timestamptz 列的闭区间：上界用"次日零点"保证整天都被包含。 */
    private static void addTimestampDateRange(List<String> where, MapSqlParameterSource params,
                                              DateRange range, String column) {
        if (range == null) {
            return;
        }
        if (range.from() != null) {
            where.add(column + " >= CAST(:dateFrom AS date)");
            params.addValue("dateFrom", range.from());
        }
        if (range.to() != null) {
            where.add(column + " < CAST(:dateTo AS date) + INTERVAL '1 day'");
            params.addValue("dateTo", range.to());
        }
    }

    private static String whereSql(List<String> conditions) {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static MapSqlParameterSource copy(MapSqlParameterSource source) {
        return new MapSqlParameterSource(source.getValues());
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record Filtered(String where, MapSqlParameterSource params) {
    }

    /** 一页结果 + 满足条件的总数（用于 Bundle.total 与 next 链接）。 */
    public record Page<T>(List<T> rows, long total) {
        public Page {
            rows = List.copyOf(rows);
        }
    }

    // ------------------------------------------------------------ 行映射

    private static final RowMapper<PatientRow> PATIENT_MAPPER = (rs, i) -> new PatientRow(
            rs.getObject("id", UUID.class),
            rs.getString("patient_no"),
            rs.getString("name"),
            rs.getString("gender"),
            rs.getObject("birth_date", java.time.LocalDate.class),
            rs.getString("blood_type"),
            rs.getString("allergy"),
            rs.getString("status"),
            rs.getObject("merged_into", UUID.class),
            rs.getLong("version"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class));

    private static final RowMapper<CrosswalkRow> CROSSWALK_MAPPER = (rs, i) -> new CrosswalkRow(
            rs.getObject("patient_id", UUID.class),
            rs.getString("system_code"),
            rs.getString("external_id"));

    private static final RowMapper<EncounterRow> ENCOUNTER_MAPPER = (rs, i) -> new EncounterRow(
            rs.getObject("id", UUID.class),
            rs.getString("visit_no"),
            rs.getString("visit_type"),
            rs.getString("fhir_status"),
            rs.getObject("visit_date", java.time.LocalDate.class),
            rs.getObject("registered_at", java.time.OffsetDateTime.class),
            rs.getObject("started_at", java.time.OffsetDateTime.class),
            rs.getObject("finished_at", java.time.OffsetDateTime.class),
            rs.getObject("campus_id", UUID.class),
            rs.getObject("patient_id", UUID.class),
            rs.getString("dept_code"),
            rs.getString("dept_name"),
            rs.getString("doctor_id"),
            rs.getObject("admission_id", UUID.class),
            rs.getString("admission_no"),
            rs.getObject("admitted_at", java.time.OffsetDateTime.class),
            rs.getObject("discharged_at", java.time.OffsetDateTime.class),
            rs.getString("ward_code"),
            rs.getString("ward_name"),
            rs.getString("bed_no"));

    private static final RowMapper<ObservationRow> OBSERVATION_MAPPER = (rs, i) -> new ObservationRow(
            rs.getObject("id", UUID.class),
            rs.getObject("patient_id", UUID.class),
            rs.getObject("visit_id", UUID.class),
            rs.getString("item_name"),
            rs.getString("result_value"),
            rs.getString("reference_range"),
            rs.getString("severity"),
            rs.getString("fhir_status"),
            rs.getString("report_no"),
            rs.getString("reporter"),
            rs.getString("note"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("acknowledged_at", java.time.OffsetDateTime.class));

    private static final RowMapper<MedicationRequestRow> MEDICATION_MAPPER =
            (rs, i) -> new MedicationRequestRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("prescription_id", UUID.class),
                    rs.getString("prescription_no"),
                    rs.getObject("patient_id", UUID.class),
                    rs.getObject("visit_id", UUID.class),
                    rs.getString("fhir_status"),
                    rs.getString("prescriber"),
                    rs.getString("reviewer"),
                    rs.getObject("reviewed_at", java.time.OffsetDateTime.class),
                    rs.getString("reject_reason"),
                    rs.getObject("created_at", java.time.OffsetDateTime.class),
                    rs.getString("drug_code"),
                    rs.getString("drug_name"),
                    rs.getString("spec"),
                    rs.getInt("quantity"),
                    rs.getString("unit"),
                    rs.getString("dosage"),
                    rs.getString("frequency"),
                    (Integer) rs.getObject("days"));
}
