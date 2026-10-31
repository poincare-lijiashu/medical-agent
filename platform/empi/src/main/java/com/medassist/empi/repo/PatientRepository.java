package com.medassist.empi.repo;

import com.medassist.empi.domain.Patient;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 患者主索引仓储（JdbcClient 显式 SQL）。
 */
@Repository
public class PatientRepository {

    private static final String COLS = """
            id, patient_no, name, gender, birth_date, id_type, id_no_enc, id_no_hash,
            phone_enc, phone_hash, address_enc, blood_type, allergy, status, merged_into,
            version, created_at, updated_at
            """;

    private static final RowMapper<Patient> MAPPER = (ResultSet rs, int i) -> new Patient(
            rs.getObject("id", UUID.class),
            rs.getString("patient_no"),
            rs.getString("name"),
            rs.getString("gender"),
            rs.getDate("birth_date") == null ? null : rs.getDate("birth_date").toLocalDate(),
            rs.getString("id_type"),
            rs.getString("id_no_enc"),
            rs.getString("id_no_hash"),
            rs.getString("phone_enc"),
            rs.getString("phone_hash"),
            rs.getString("address_enc"),
            rs.getString("blood_type"),
            rs.getString("allergy"),
            rs.getString("status"),
            rs.getObject("merged_into", UUID.class),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
    );

    private final JdbcClient jdbc;

    public PatientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 生成院内患者号：P + 日期 + 6 位序列（sequence 全局单调，便于人工核对）。 */
    public String nextPatientNo() {
        Long seq = jdbc.sql("SELECT nextval('empi_svc.patient_no_seq')").query(Long.class).single();
        return "P" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    public Optional<Patient> findById(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM empi_svc.patient WHERE id = ?")
                .param(id).query(MAPPER).optional();
    }

    /**
     * 加行锁读取（{@code SELECT ... FOR UPDATE}），用于合并这类需要"读-判-写"原子性的场景。
     *
     * <p>调用方必须按 id 稳定顺序加锁（见 {@link com.medassist.empi.service.PatientService#merge}），
     * 否则 A→B 与 B→A 并发仍可能死锁/成环。锁必须在事务内持有。
     */
    public Optional<Patient> findByIdForUpdate(UUID id) {
        return jdbc.sql("SELECT " + COLS + " FROM empi_svc.patient WHERE id = ? FOR UPDATE")
                .param(id).query(MAPPER).optional();
    }

    /** 证件号查重（仅有效档案；已合并档案的证件号可被复用）。 */
    public Optional<Patient> findActiveByIdNoHash(String idType, String idNoHash) {
        return jdbc.sql("SELECT " + COLS + """
                         FROM empi_svc.patient
                        WHERE id_type = ? AND id_no_hash = ? AND status <> 'MERGED'
                        """)
                .param(idType).param(idNoHash).query(MAPPER).optional();
    }

    public Optional<Patient> findByPhoneHash(String phoneHash) {
        return jdbc.sql("SELECT " + COLS + """
                         FROM empi_svc.patient
                        WHERE phone_hash = ? AND status <> 'MERGED'
                        ORDER BY created_at DESC LIMIT 1
                        """)
                .param(phoneHash).query(MAPPER).optional();
    }

    public Optional<Patient> findByPatientNo(String patientNo) {
        return jdbc.sql("SELECT " + COLS + " FROM empi_svc.patient WHERE patient_no = ?")
                .param(patientNo).query(MAPPER).optional();
    }

    /** 关键词检索：患者号精确 + 姓名模糊（其余经哈希/标识走专门入口）。 */
    public List<Patient> search(String patientNo, String nameKeyword, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM empi_svc.patient WHERE 1=1");
        if (patientNo != null && !patientNo.isBlank()) {
            sql.append(" AND patient_no = :patientNo");
        }
        if (nameKeyword != null && !nameKeyword.isBlank()) {
            sql.append(" AND name LIKE :name");
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (patientNo != null && !patientNo.isBlank()) {
            spec = spec.param("patientNo", patientNo);
        }
        if (nameKeyword != null && !nameKeyword.isBlank()) {
            spec = spec.param("name", "%" + nameKeyword + "%");
        }
        return spec.param("limit", limit).param("offset", offset).query(MAPPER).list();
    }

    public long count(String patientNo, String nameKeyword) {
        StringBuilder sql = new StringBuilder("SELECT count(*) FROM empi_svc.patient WHERE 1=1");
        if (patientNo != null && !patientNo.isBlank()) {
            sql.append(" AND patient_no = :patientNo");
        }
        if (nameKeyword != null && !nameKeyword.isBlank()) {
            sql.append(" AND name LIKE :name");
        }
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (patientNo != null && !patientNo.isBlank()) {
            spec = spec.param("patientNo", patientNo);
        }
        if (nameKeyword != null && !nameKeyword.isBlank()) {
            spec = spec.param("name", "%" + nameKeyword + "%");
        }
        return spec.query(Long.class).single();
    }

    public void insert(Patient p) {
        jdbc.sql("""
                        INSERT INTO empi_svc.patient
                            (id, patient_no, name, gender, birth_date, id_type, id_no_enc, id_no_hash,
                             phone_enc, phone_hash, address_enc, blood_type, allergy, status, version)
                        VALUES (:id, :patientNo, :name, :gender, :birthDate, :idType, :idNoEnc, :idNoHash,
                                :phoneEnc, :phoneHash, :addressEnc, :bloodType, :allergy, :status, 0)
                        """)
                .param("id", p.id())
                .param("patientNo", p.patientNo())
                .param("name", p.name())
                .param("gender", p.gender())
                .param("birthDate", p.birthDate())
                .param("idType", p.idType())
                .param("idNoEnc", p.idNoEnc())
                .param("idNoHash", p.idNoHash())
                .param("phoneEnc", p.phoneEnc())
                .param("phoneHash", p.phoneHash())
                .param("addressEnc", p.addressEnc())
                .param("bloodType", p.bloodType())
                .param("allergy", p.allergy())
                .param("status", p.status())
                .update();
    }

    /** 资料更新（CAS）。证件号变更时同时更新哈希与密文。 */
    public boolean update(UUID id, String name, String gender, LocalDate birthDate,
                          String idType, String idNoEnc, String idNoHash,
                          String phoneEnc, String phoneHash, String addressEnc,
                          String bloodType, String allergy, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE empi_svc.patient
                           SET name = :name, gender = :gender, birth_date = :birthDate,
                               id_type = :idType, id_no_enc = :idNoEnc, id_no_hash = :idNoHash,
                               phone_enc = :phoneEnc, phone_hash = :phoneHash,
                               address_enc = :addressEnc, blood_type = :bloodType,
                               allergy = :allergy, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("name", name)
                .param("gender", gender)
                .param("birthDate", birthDate)
                .param("idType", idType)
                .param("idNoEnc", idNoEnc)
                .param("idNoHash", idNoHash)
                .param("phoneEnc", phoneEnc)
                .param("phoneHash", phoneHash)
                .param("addressEnc", addressEnc)
                .param("bloodType", bloodType)
                .param("allergy", allergy)
                .param("id", id)
                .param("version", expectedVersion)
                .update() > 0;
    }

    /** 标记为已合并（CAS）。 */
    public boolean markMerged(UUID fromId, UUID toId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE empi_svc.patient
                           SET status = 'MERGED', merged_into = :toId,
                               version = version + 1, updated_at = now()
                         WHERE id = :fromId AND version = :version AND status = 'ACTIVE'
                        """)
                .param("toId", toId)
                .param("fromId", fromId)
                .param("version", expectedVersion)
                .update() > 0;
    }

    /** 迁移跨系统标识：先删除目标侧已存在的重复 (system, external)，再整体改挂。 */
    public int moveCrosswalk(UUID fromId, UUID toId) {
        jdbc.sql("""
                        DELETE FROM empi_svc.patient_crosswalk f
                         WHERE f.patient_id = :fromId
                           AND EXISTS (SELECT 1 FROM empi_svc.patient_crosswalk t
                                        WHERE t.patient_id = :toId
                                          AND t.system_code = f.system_code
                                          AND t.external_id = f.external_id)
                        """)
                .param("fromId", fromId).param("toId", toId).update();
        return jdbc.sql("UPDATE empi_svc.patient_crosswalk SET patient_id = :toId WHERE patient_id = :fromId")
                .param("fromId", fromId).param("toId", toId).update();
    }

    public void addCrosswalk(UUID patientId, String systemCode, String externalId) {
        jdbc.sql("""
                        INSERT INTO empi_svc.patient_crosswalk (patient_id, system_code, external_id)
                        VALUES (:patientId, :systemCode, :externalId)
                        ON CONFLICT (system_code, external_id) DO NOTHING
                        """)
                .param("patientId", patientId)
                .param("systemCode", systemCode)
                .param("externalId", externalId)
                .update();
    }

    public List<String[]> listCrosswalk(UUID patientId) {
        return jdbc.sql("""
                        SELECT system_code, external_id FROM empi_svc.patient_crosswalk
                         WHERE patient_id = ? ORDER BY system_code
                        """)
                .param(patientId)
                .query((rs, i) -> new String[]{rs.getString("system_code"), rs.getString("external_id")})
                .list();
    }

    public void logMerge(UUID fromId, UUID toId, String operator, String reason, int moved) {
        jdbc.sql("""
                        INSERT INTO empi_svc.patient_merge_log
                            (from_id, to_id, operator, reason, moved_crosswalk)
                        VALUES (:fromId, :toId, :operator, :reason, :moved)
                        """)
                .param("fromId", fromId).param("toId", toId)
                .param("operator", operator).param("reason", reason)
                .param("moved", moved).update();
    }

    public void logChange(String actor, String action, String targetId, String detailJson) {
        jdbc.sql("""
                        INSERT INTO empi_svc.change_log (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, 'PATIENT', :targetId, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action)
                .param("targetId", targetId)
                .param("detail", detailJson == null ? "{}" : detailJson)
                .update();
    }
}
