package com.medassist.fhir.xds;

import com.medassist.fhir.xds.XdsSources.DiagnosisRow;
import com.medassist.fhir.xds.XdsSources.DischargeSource;
import com.medassist.fhir.xds.XdsSources.LabSource;
import com.medassist.fhir.xds.XdsSources.MedicationRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * XDS 注册表仓储。
 *
 * <p><b>读写边界</b>：本服务对业务域（clinical/tech/empi）只读；唯一写入是
 * {@code fhir_svc} 下的 XDS 注册表、文档内容与追加式审计。这样"文档共享"
 * 不需要反向修改业务数据，也不会绕过业务域的状态机。
 */
@Repository
public class XdsRepository {

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;

    public XdsRepository(NamedParameterJdbcTemplate named, JdbcTemplate jdbc) {
        this.named = named;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ 写注册表

    public void insertSubmissionSet(UUID id, String submissionSetUid, String sourceId,
                                    UUID patientId, String patientNo, Instant submissionTime,
                                    String authorPerson, String authorInstitution,
                                    String title, String comments, String actor) {
        named.update("""
                        INSERT INTO fhir_svc.xds_submission_set
                            (id, submission_set_uid, source_id, patient_id, patient_no,
                             submission_time, author_person, author_institution, title,
                             comments, created_by)
                        VALUES (:id, :uid, :sourceId, :patientId, :patientNo,
                                :submissionTime, :authorPerson, :authorInstitution, :title,
                                :comments, :actor)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("uid", submissionSetUid)
                        .addValue("sourceId", sourceId)
                        .addValue("patientId", patientId)
                        .addValue("patientNo", patientNo)
                        .addValue("submissionTime", Timestamp.from(submissionTime))
                        .addValue("authorPerson", authorPerson)
                        .addValue("authorInstitution", authorInstitution)
                        .addValue("title", title)
                        .addValue("comments", comments)
                        .addValue("actor", actor));
    }

    public boolean submissionSetExists(String submissionSetUid) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM fhir_svc.xds_submission_set WHERE submission_set_uid = ?",
                Long.class, submissionSetUid);
        return count != null && count > 0;
    }

    public void insertDocument(UUID id, String uniqueId, String logicalId, int documentVersion,
                               UUID submissionSetId, UUID patientId, String patientNo,
                               UUID campusId, String classCode, String classDisplay,
                               String typeCode, String typeDisplay, String formatCode,
                               String formatDisplay, List<String> eventCodes,
                               Instant creationTime, Instant serviceStart, Instant serviceStop,
                               String facilityTypeCode, String practiceSettingCode,
                               String authorPerson, String authorInstitution, String authorRole,
                               String title, String comments, String confidentialityCode,
                               String languageCode, String mimeType, int sizeBytes,
                               String hashSha256, String actor) {
        named.update("""
                        INSERT INTO fhir_svc.xds_document
                            (id, unique_id, logical_id, document_version, submission_set_id,
                             patient_id, patient_no, campus_id, class_code, class_display,
                             type_code, type_display, format_code, format_display, event_codes,
                             creation_time, service_start_time, service_stop_time,
                             facility_type_code, practice_setting_code, author_person,
                             author_institution, author_role, title, comments,
                             confidentiality_code, language_code, mime_type, size_bytes,
                             hash_sha256, created_by)
                        VALUES (:id, :uniqueId, :logicalId, :documentVersion, :submissionSetId,
                                :patientId, :patientNo, :campusId, :classCode, :classDisplay,
                                :typeCode, :typeDisplay, :formatCode, :formatDisplay,
                                CAST(:eventCodes AS jsonb), :creationTime, :serviceStart,
                                :serviceStop, :facilityTypeCode, :practiceSettingCode,
                                :authorPerson, :authorInstitution, :authorRole, :title,
                                :comments, :confidentialityCode, :languageCode, :mimeType,
                                :sizeBytes, :hashSha256, :actor)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("uniqueId", uniqueId)
                        .addValue("logicalId", logicalId)
                        .addValue("documentVersion", documentVersion)
                        .addValue("submissionSetId", submissionSetId)
                        .addValue("patientId", patientId)
                        .addValue("patientNo", patientNo)
                        .addValue("campusId", campusId)
                        .addValue("classCode", classCode)
                        .addValue("classDisplay", classDisplay)
                        .addValue("typeCode", typeCode)
                        .addValue("typeDisplay", typeDisplay)
                        .addValue("formatCode", formatCode)
                        .addValue("formatDisplay", formatDisplay)
                        .addValue("eventCodes", XdsValidation.toJsonArray(eventCodes))
                        .addValue("creationTime", Timestamp.from(creationTime))
                        .addValue("serviceStart", sqlTime(serviceStart))
                        .addValue("serviceStop", sqlTime(serviceStop))
                        .addValue("facilityTypeCode", facilityTypeCode)
                        .addValue("practiceSettingCode", practiceSettingCode)
                        .addValue("authorPerson", authorPerson)
                        .addValue("authorInstitution", authorInstitution)
                        .addValue("authorRole", authorRole)
                        .addValue("title", title)
                        .addValue("comments", comments)
                        .addValue("confidentialityCode", confidentialityCode)
                        .addValue("languageCode", languageCode)
                        .addValue("mimeType", mimeType)
                        .addValue("sizeBytes", sizeBytes)
                        .addValue("hashSha256", hashSha256)
                        .addValue("actor", actor));
    }

    public void insertContent(UUID documentId, byte[] content, String hashSha256, int sizeBytes) {
        named.update("""
                        INSERT INTO fhir_svc.xds_document_content
                            (document_id, content, hash_sha256, size_bytes)
                        VALUES (:id, :content, :hash, :size)
                        """,
                new MapSqlParameterSource()
                        .addValue("id", documentId)
                        .addValue("content", content)
                        .addValue("hash", hashSha256)
                        .addValue("size", sizeBytes));
    }

    public boolean documentExists(String uniqueId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM fhir_svc.xds_document WHERE unique_id = ?",
                Long.class, uniqueId);
        return count != null && count > 0;
    }

    public int deprecate(String uniqueId, String actor, String reason, Long rowVersion) {
        StringBuilder sql = new StringBuilder("""
                UPDATE fhir_svc.xds_document
                   SET availability_status = 'DEPRECATED',
                       deprecated_at = now(),
                       deprecated_by = ?,
                       deprecate_reason = ?,
                       updated_at = now(),
                       row_version = row_version + 1
                 WHERE unique_id = ?
                   AND availability_status = 'APPROVED'
                """);
        List<Object> args = new ArrayList<>(List.of(actor, reason, uniqueId));
        if (rowVersion != null) {
            sql.append("   AND row_version = ?");
            args.add(rowVersion);
        }
        return jdbc.update(sql.toString(), args.toArray());
    }

    // ------------------------------------------------------------ 读注册表

    public record QueryParams(String patient, String uniqueId, String classCode, String status,
                              Instant from, Instant to, String keyword, int limit, int offset) {
    }

    public record Page<T>(List<T> rows, long total) {
    }

    public Optional<DocumentRow> findDocument(String uniqueId, Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource("uniqueId", uniqueId);
        where.add("d.unique_id = :uniqueId");
        addCampusFilter(where, params, campuses);
        return named.query(DOCUMENT_SELECT + whereSql(where), params, DOCUMENT_MAPPER)
                .stream().findFirst();
    }

    public Page<DocumentRow> query(QueryParams q, Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        applyQueryFilter(where, params, q, campuses);

        long total = count("SELECT count(*) FROM fhir_svc.xds_document d" + whereSql(where),
                params);
        params.addValue("limit", q.limit()).addValue("offset", q.offset());
        List<DocumentRow> rows = named.query(
                DOCUMENT_SELECT + whereSql(where)
                        + " ORDER BY d.creation_time DESC, d.id LIMIT :limit OFFSET :offset",
                params, DOCUMENT_MAPPER);
        return new Page<>(rows, total);
    }

    public Optional<ContentRow> findContent(UUID documentId) {
        List<ContentRow> rows = jdbc.query("""
                        SELECT content, hash_sha256, size_bytes
                          FROM fhir_svc.xds_document_content
                         WHERE document_id = ?
                        """,
                (rs, i) -> new ContentRow(rs.getBytes("content"),
                        rs.getString("hash_sha256"), rs.getInt("size_bytes")),
                documentId);
        return rows.stream().findFirst();
    }

    public record ContentRow(byte[] content, String hashSha256, int sizeBytes) {
    }

    public record SummaryCounts(long total, long approved, long deprecated,
                                long dischargeSummaries, long labReports,
                                long todayRegistered, long todayRetrieved) {
    }

    public SummaryCounts summary(Collection<UUID> campuses) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        addCampusFilter(where, params, campuses);
        SummaryCounts counts = named.queryForObject("""
                        SELECT count(*) AS total,
                               count(*) FILTER (WHERE availability_status = 'APPROVED') AS approved,
                               count(*) FILTER (WHERE availability_status = 'DEPRECATED') AS deprecated,
                               count(*) FILTER (WHERE class_code = '18842-5') AS discharge_summaries,
                               count(*) FILTER (WHERE class_code = '11502-2') AS lab_reports,
                               count(*) FILTER (WHERE created_at >= date_trunc('day', now()))
                                   AS today_registered
                          FROM fhir_svc.xds_document d
                        """ + whereSql(where), params, (rs, i) -> new SummaryCounts(
                rs.getLong("total"), rs.getLong("approved"), rs.getLong("deprecated"),
                rs.getLong("discharge_summaries"), rs.getLong("lab_reports"),
                rs.getLong("today_registered"), 0));
        Long retrieved = named.queryForObject("""
                SELECT count(*)
                  FROM fhir_svc.xds_access_log l
                  JOIN fhir_svc.xds_document d ON d.unique_id = l.document_uid
                 WHERE l.action = 'RETRIEVE' AND l.outcome = 'OK'
                   AND l.ts >= date_trunc('day', now())
                """ + campusCondition(campuses), campusParams(campuses), Long.class);
        long todayRetrieved = retrieved == null ? 0 : retrieved;
        if (counts == null) {
            return new SummaryCounts(0, 0, 0, 0, 0, 0, todayRetrieved);
        }
        return new SummaryCounts(counts.total(), counts.approved(), counts.deprecated(),
                counts.dischargeSummaries(), counts.labReports(), counts.todayRegistered(),
                todayRetrieved);
    }

    private void applyQueryFilter(List<String> where, MapSqlParameterSource params,
                                  QueryParams q, Collection<UUID> campuses) {
        if (q.uniqueId() != null) {
            where.add("d.unique_id = :uniqueId");
            params.addValue("uniqueId", q.uniqueId());
        }
        if (q.patient() != null) {
            UUID patientId = tryUuid(q.patient());
            if (patientId != null) {
                where.add("d.patient_id = :patientId");
                params.addValue("patientId", patientId);
            } else {
                where.add("d.patient_no = :patientNo");
                params.addValue("patientNo", q.patient());
            }
        }
        if (q.classCode() != null) {
            where.add("d.class_code = :classCode");
            params.addValue("classCode", q.classCode());
        }
        String status = q.status();
        if (status == null || "APPROVED".equals(status)) {
            where.add("d.availability_status = 'APPROVED'");
        } else if ("DEPRECATED".equals(status)) {
            where.add("d.availability_status = 'DEPRECATED'");
        }
        if (q.from() != null) {
            where.add("d.creation_time >= :from");
            params.addValue("from", Timestamp.from(q.from()));
        }
        if (q.to() != null) {
            where.add("d.creation_time <= :to");
            params.addValue("to", Timestamp.from(q.to()));
        }
        if (q.keyword() != null) {
            where.add("(d.title ILIKE :keyword ESCAPE '\\' OR d.unique_id ILIKE :keyword ESCAPE '\\')");
            params.addValue("keyword", "%" + escapeLike(q.keyword()) + "%");
        }
        addCampusFilter(where, params, campuses);
    }

    private static final String DOCUMENT_SELECT = """
            SELECT d.id, d.unique_id, d.logical_id, d.document_version, d.patient_id,
                   d.patient_no, d.campus_id, d.class_code, d.class_display, d.type_code,
                   d.type_display, d.format_code, d.format_display, d.event_codes, d.title,
                   d.comments, d.author_person, d.author_institution, d.confidentiality_code,
                   d.language_code, d.mime_type, d.size_bytes, d.hash_sha256,
                   d.availability_status, d.creation_time, d.service_start_time,
                   d.service_stop_time, d.created_at, d.row_version
              FROM fhir_svc.xds_document d
            """;

    private static final RowMapper<DocumentRow> DOCUMENT_MAPPER =
            (rs, i) -> new DocumentRow(
                    uuid(rs, "id"),
                    rs.getString("unique_id"),
                    rs.getString("logical_id"),
                    rs.getInt("document_version"),
                    uuid(rs, "patient_id"),
                    rs.getString("patient_no"),
                    uuid(rs, "campus_id"),
                    rs.getString("class_code"),
                    rs.getString("class_display"),
                    rs.getString("type_code"),
                    rs.getString("type_display"),
                    rs.getString("format_code"),
                    rs.getString("format_display"),
                    XdsValidation.parseJsonArray(rs.getString("event_codes")),
                    rs.getString("title"),
                    rs.getString("comments"),
                    rs.getString("author_person"),
                    rs.getString("author_institution"),
                    rs.getString("confidentiality_code"),
                    rs.getString("language_code"),
                    rs.getString("mime_type"),
                    rs.getInt("size_bytes"),
                    rs.getString("hash_sha256"),
                    rs.getString("availability_status"),
                    instant(rs, "creation_time"),
                    instant(rs, "service_start_time"),
                    instant(rs, "service_stop_time"),
                    instant(rs, "created_at"),
                    rs.getLong("row_version"));

    public record DocumentRow(UUID id, String uniqueId, String logicalId, int documentVersion,
                              UUID patientId, String patientNo, UUID campusId, String classCode,
                              String classDisplay, String typeCode, String typeDisplay,
                              String formatCode, String formatDisplay, List<String> eventCodes,
                              String title, String comments, String authorPerson,
                              String authorInstitution, String confidentialityCode,
                              String languageCode, String mimeType, int sizeBytes,
                              String hashSha256, String availabilityStatus,
                              Instant creationTime, Instant serviceStart, Instant serviceStop,
                              Instant registeredAt, long rowVersion) {
    }

    // ------------------------------------------------------ 业务域只读投影

    public record PatientRow(UUID id, String patientNo, String name, String gender,
                             LocalDate birthDate) {
    }

    public Optional<PatientRow> findPatient(String idOrNo) {
        if (idOrNo == null || idOrNo.isBlank()) {
            return Optional.empty();
        }
        UUID id = tryUuid(idOrNo);
        String sql = "SELECT id, patient_no, name, gender, birth_date FROM empi_svc.patient WHERE ";
        List<PatientRow> rows = id != null
                ? jdbc.query(sql + "id = ?", PATIENT_MAPPER, id)
                : jdbc.query(sql + "patient_no = ?", PATIENT_MAPPER, idOrNo.trim());
        return rows.stream().findFirst();
    }

    public Optional<DischargeSource> findDischargeSource(UUID visitId) {
        List<DischargeSource> rows = jdbc.query("""
                        SELECT v.id AS visit_id, v.visit_no, v.visit_date, v.doctor_id,
                               v.campus_id,
                               p.id AS patient_id, p.patient_no, p.name AS patient_name,
                               p.gender, p.birth_date,
                               a.id AS admission_id, a.admission_no, a.attending_doctor,
                               a.admitted_at, a.discharged_at,
                               d.dept_code AS dept_code, d.name AS dept_name,
                               w.name AS ward_name, b.bed_no
                          FROM clinical_svc.visit v
                          JOIN empi_svc.patient p ON p.id = v.patient_id
                          JOIN clinical_svc.md_dept d ON d.id = v.dept_id
                          LEFT JOIN clinical_svc.admission a ON a.visit_id = v.id
                          LEFT JOIN clinical_svc.ward w ON w.id = a.ward_id
                          LEFT JOIN clinical_svc.bed b ON b.id = a.bed_id
                         WHERE v.id = ?
                        """,
                (rs, i) -> new DischargeSource(
                        uuid(rs, "visit_id"), rs.getString("visit_no"),
                        rs.getObject("visit_date", LocalDate.class), rs.getString("doctor_id"),
                        uuid(rs, "campus_id"),
                        new XdsSources.PatientRef(uuid(rs, "patient_id"),
                                rs.getString("patient_no"), rs.getString("patient_name"),
                                rs.getString("gender"),
                                rs.getObject("birth_date", LocalDate.class)),
                        uuid(rs, "admission_id"), rs.getString("admission_no"),
                        rs.getString("attending_doctor"), instant(rs, "admitted_at"),
                        instant(rs, "discharged_at"), rs.getString("dept_code"),
                        rs.getString("dept_name"), rs.getString("ward_name"),
                        rs.getString("bed_no")),
                visitId);
        return rows.stream().findFirst();
    }

    public List<DiagnosisRow> findDiagnoses(UUID visitId) {
        return jdbc.query("""
                        SELECT code, name, primary_flag, diagnosed_by, diagnosed_at
                          FROM clinical_svc.visit_diagnosis
                         WHERE visit_id = ?
                         ORDER BY primary_flag DESC, sort_no, diagnosed_at
                        """,
                (rs, i) -> new DiagnosisRow(rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("primary_flag"), rs.getString("diagnosed_by"),
                        instant(rs, "diagnosed_at")),
                visitId);
    }

    public List<MedicationRow> findDischargeMedications(UUID admissionId) {
        return jdbc.query("""
                        SELECT i.drug_name, i.spec, i.dosage, i.frequency, i.days,
                               i.quantity, i.unit
                          FROM clinical_svc.discharge_medication_item i
                         WHERE i.medication_id = (
                               SELECT m.id
                                 FROM clinical_svc.discharge_medication m
                                WHERE m.admission_id = ?
                                  AND m.status IN ('DRAFT', 'SUBMITTED', 'DISPENSED')
                                ORDER BY m.created_at DESC
                                LIMIT 1)
                         ORDER BY i.seq
                        """,
                (rs, i) -> new MedicationRow(rs.getString("drug_name"), rs.getString("spec"),
                        rs.getString("dosage"), rs.getString("frequency"),
                        (Integer) rs.getObject("days"), rs.getInt("quantity"),
                        rs.getString("unit")),
                admissionId);
    }

    public Optional<LabSource> findLabSource(UUID reportId) {
        List<LabSource> rows = jdbc.query("""
                        SELECT a.id AS application_id, a.application_no, a.patient_id,
                               a.patient_no, p.name AS patient_name, p.gender, p.birth_date,
                               a.visit_id, a.visit_no, a.applied_by, a.item_code, a.item_name,
                               a.specimen_type, a.clinical_diag,
                               v.campus_id,
                               r.id AS report_id, r.report_no, r.revision, r.findings,
                               r.conclusion, r.reporter, r.reviewer, r.reviewed_at,
                               r.status AS report_status, r.created_at
                          FROM tech_svc.exam_report r
                          JOIN tech_svc.exam_application a ON a.id = r.application_id
                          JOIN empi_svc.patient p ON p.id = a.patient_id
                          LEFT JOIN clinical_svc.visit v ON v.id = a.visit_id
                         WHERE r.id = ?
                        """,
                (rs, i) -> new LabSource(
                        uuid(rs, "application_id"), rs.getString("application_no"),
                        uuid(rs, "patient_id"), rs.getString("patient_no"),
                        rs.getString("patient_name"), rs.getString("gender"),
                        rs.getObject("birth_date", LocalDate.class),
                        uuid(rs, "visit_id"), rs.getString("visit_no"),
                        rs.getString("applied_by"), rs.getString("item_code"),
                        rs.getString("item_name"), rs.getString("specimen_type"),
                        rs.getString("clinical_diag"), uuid(rs, "campus_id"),
                        uuid(rs, "report_id"), rs.getString("report_no"),
                        rs.getInt("revision"), rs.getString("findings"),
                        rs.getString("conclusion"), rs.getString("reporter"),
                        rs.getString("reviewer"), instant(rs, "reviewed_at"),
                        rs.getString("report_status"), instant(rs, "created_at")),
                reportId);
        return rows.stream().findFirst();
    }

    private static final RowMapper<PatientRow> PATIENT_MAPPER =
            (rs, i) -> new PatientRow(uuid(rs, "id"), rs.getString("patient_no"),
                    rs.getString("name"), rs.getString("gender"),
                    rs.getObject("birth_date", LocalDate.class));

    // ---------------------------------------------------------------- 审计

    public void audit(String actor, String action, String documentUid, UUID patientId,
                      String querySummary, Integer resultCount, String outcome,
                      String clientIp, String traceId) {
        jdbc.update("""
                        INSERT INTO fhir_svc.xds_access_log
                            (actor, action, document_uid, patient_id, query_summary,
                             result_count, outcome, client_ip, trace_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                actor, action, documentUid, patientId, querySummary, resultCount, outcome,
                clientIp, traceId);
    }

    // ---------------------------------------------------------------- 公共

    private static void addCampusFilter(List<String> where, MapSqlParameterSource params,
                                        Collection<UUID> campuses) {
        if (campuses == null || campuses.isEmpty()) {
            return;
        }
        where.add("d.campus_id IN (:campuses)");
        params.addValue("campuses", campuses);
    }

    private static String campusCondition(Collection<UUID> campuses) {
        return campuses == null || campuses.isEmpty() ? "" : " AND d.campus_id IN (:campuses)";
    }

    private static MapSqlParameterSource campusParams(Collection<UUID> campuses) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (campuses != null && !campuses.isEmpty()) {
            params.addValue("campuses", campuses);
        }
        return params;
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = named.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static String whereSql(List<String> conditions) {
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static UUID tryUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp sqlTime(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
