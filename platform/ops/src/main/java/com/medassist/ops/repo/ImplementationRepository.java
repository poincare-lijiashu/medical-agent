package com.medassist.ops.repo;

import com.medassist.ops.domain.ImplementationModel.Attempt;
import com.medassist.ops.domain.ImplementationModel.Course;
import com.medassist.ops.domain.ImplementationModel.Exam;
import com.medassist.ops.domain.ImplementationModel.Phase;
import com.medassist.ops.domain.ImplementationModel.Project;
import com.medassist.ops.domain.ImplementationModel.ProjectEvent;
import com.medassist.ops.domain.ImplementationModel.ProjectTask;
import com.medassist.ops.domain.ImplementationModel.Question;
import com.medassist.ops.domain.ImplementationModel.Template;
import com.medassist.ops.domain.ImplementationModel.TemplateTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 实施交付仓储（ops_svc.impl_*）。
 *
 * <p>纪律：任务/项目状态推进带 version 做 CAS；事件只 INSERT；
 * 模板与项目都返回"显式快照"，不靠跨表推断历史。
 */
@Repository
public class ImplementationRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE =
            new TypeReference<>() {
            };
    private static final ObjectMapper STATIC_JSON = new ObjectMapper();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final ObjectMapper objectMapper;

    public ImplementationRepository(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain,
                                    ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ 模板

    private static final RowMapper<Template> TEMPLATE_MAPPER = (rs, n) -> new Template(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            List.of());

    private static final RowMapper<Phase> PHASE_MAPPER = (rs, n) -> new Phase(
            rs.getObject("id", UUID.class),
            rs.getObject("template_id", UUID.class),
            rs.getInt("phase_no"),
            rs.getString("name"),
            rs.getInt("weight"),
            rs.getString("description"),
            List.of());

    private static final RowMapper<TemplateTask> TEMPLATE_TASK_MAPPER = (rs, n) -> new TemplateTask(
            rs.getObject("id", UUID.class),
            rs.getObject("template_id", UUID.class),
            rs.getObject("phase_id", UUID.class),
            rs.getInt("task_no"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getBoolean("required"),
            rs.getString("owner_role"),
            rs.getString("evidence_hint"));

    public List<Template> listTemplates(boolean onlyActive) {
        List<Template> headers = plain.query("""
                SELECT id, code, name, description, status, created_by, version, created_at, updated_at
                  FROM ops_svc.impl_template
                """ + (onlyActive ? " WHERE status = 'ACTIVE'" : "")
                + " ORDER BY code", TEMPLATE_MAPPER);
        List<Template> result = new ArrayList<>();
        for (Template header : headers) {
            result.add(header.withPhases(loadPhases(header.id())));
        }
        return result;
    }

    public Optional<Template> findTemplate(UUID id) {
        return plain.query("""
                SELECT id, code, name, description, status, created_by, version, created_at, updated_at
                  FROM ops_svc.impl_template WHERE id = ?
                """, TEMPLATE_MAPPER, id).stream().findFirst()
                .map(t -> t.withPhases(loadPhases(t.id())));
    }

    public Optional<Template> findTemplateByCode(String code) {
        return plain.query("""
                SELECT id, code, name, description, status, created_by, version, created_at, updated_at
                  FROM ops_svc.impl_template WHERE code = ?
                """, TEMPLATE_MAPPER, code).stream().findFirst()
                .map(t -> t.withPhases(loadPhases(t.id())));
    }

    private List<Phase> loadPhases(UUID templateId) {
        List<Phase> phases = plain.query("""
                SELECT id, template_id, phase_no, name, weight, description
                  FROM ops_svc.impl_template_phase WHERE template_id = ? ORDER BY phase_no
                """, PHASE_MAPPER, templateId);
        List<Phase> result = new ArrayList<>();
        for (Phase phase : phases) {
            List<TemplateTask> tasks = plain.query("""
                    SELECT id, template_id, phase_id, task_no, title, description, required,
                           owner_role, evidence_hint
                      FROM ops_svc.impl_template_task WHERE phase_id = ? ORDER BY task_no
                    """, TEMPLATE_TASK_MAPPER, phase.id());
            result.add(phase.withTasks(tasks));
        }
        return result;
    }

    public void insertTemplateHeader(Template template) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_template (id, code, name, description, status, created_by)
                VALUES (:id, :code, :name, :description, :status, :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", template.id())
                .addValue("code", template.code())
                .addValue("name", template.name())
                .addValue("description", template.description())
                .addValue("status", template.status())
                .addValue("createdBy", template.createdBy()));
    }

    public void insertPhase(Phase phase) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_template_phase
                    (id, template_id, phase_no, name, weight, description)
                VALUES (:id, :template, :no, :name, :weight, :description)
                """, new MapSqlParameterSource()
                .addValue("id", phase.id())
                .addValue("template", phase.templateId())
                .addValue("no", phase.phaseNo())
                .addValue("name", phase.name())
                .addValue("weight", phase.weight())
                .addValue("description", phase.description()));
    }

    public void insertTemplateTask(TemplateTask task) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_template_task
                    (id, template_id, phase_id, task_no, title, description, required,
                     owner_role, evidence_hint)
                VALUES (:id, :template, :phase, :no, :title, :description, :required,
                        :ownerRole, :evidenceHint)
                """, new MapSqlParameterSource()
                .addValue("id", task.id())
                .addValue("template", task.templateId())
                .addValue("phase", task.phaseId())
                .addValue("no", task.taskNo())
                .addValue("title", task.title())
                .addValue("description", task.description())
                .addValue("required", task.required())
                .addValue("ownerRole", task.ownerRole())
                .addValue("evidenceHint", task.evidenceHint()));
    }

    public boolean updateTemplateStatus(UUID id, String status) {
        return plain.update("""
                UPDATE ops_svc.impl_template
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ?
                """, status, id) > 0;
    }

    public long countProjectsByTemplate(UUID templateId) {
        Long n = plain.queryForObject(
                "SELECT count(*) FROM ops_svc.impl_project WHERE template_id = ?", Long.class,
                templateId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 项目

    private static final RowMapper<Project> PROJECT_MAPPER = (rs, n) -> new Project(
            rs.getObject("id", UUID.class),
            rs.getString("project_no"),
            rs.getObject("template_id", UUID.class),
            rs.getString("template_code"),
            rs.getString("template_name"),
            rs.getString("tenant_code"),
            rs.getString("hospital_name"),
            rs.getString("status"),
            rs.getString("owner"),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("target_date", LocalDate.class),
            instant(rs, "go_live_at"),
            rs.getString("approved_by"),
            rs.getString("acceptance_evidence"),
            instant(rs, "accepted_at"),
            rs.getString("cancel_reason"),
            stringList(rs, "required_course_codes"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            List.of(),
            null);

    private static final String PROJECT_COLUMNS = """
            id, project_no, template_id, template_code, template_name, tenant_code,
            hospital_name, status, owner, start_date, target_date, go_live_at, approved_by,
            acceptance_evidence, accepted_at, cancel_reason, required_course_codes, version,
            created_at, updated_at
            """;

    public String nextProjectNo() {
        Long seq = plain.queryForObject("SELECT nextval('ops_svc.impl_project_no_seq')", Long.class);
        return "IMP" + LocalDate.now().format(DAY) + String.format("%05d", seq == null ? 1 : seq);
    }

    public void insertProject(Project project) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_project
                    (id, project_no, template_id, template_code, template_name, tenant_code,
                     hospital_name, status, owner, start_date, target_date, required_course_codes)
                VALUES
                    (:id, :no, :template, :templateCode, :templateName, :tenant,
                     :hospital, :status, :owner, :startDate, :targetDate,
                     CAST(:courses AS text[]))
                """, new MapSqlParameterSource()
                .addValue("id", project.id())
                .addValue("no", project.projectNo())
                .addValue("template", project.templateId())
                .addValue("templateCode", project.templateCode())
                .addValue("templateName", project.templateName())
                .addValue("tenant", project.tenantCode())
                .addValue("hospital", project.hospitalName())
                .addValue("status", project.status())
                .addValue("owner", project.owner())
                .addValue("startDate", project.startDate())
                .addValue("targetDate", project.targetDate())
                .addValue("courses", arrayLiteral(project.requiredCourseCodes())));
    }

    public void insertProjectTasks(List<ProjectTask> tasks) {
        for (ProjectTask task : tasks) {
            jdbc.update("""
                    INSERT INTO ops_svc.impl_project_task
                        (id, project_id, phase_no, phase_name, phase_weight, task_no, title,
                         required, owner_role, evidence_hint)
                    VALUES
                        (:id, :project, :phaseNo, :phaseName, :phaseWeight, :taskNo, :title,
                         :required, :ownerRole, :evidenceHint)
                    """, new MapSqlParameterSource()
                    .addValue("id", task.id())
                    .addValue("project", task.projectId())
                    .addValue("phaseNo", task.phaseNo())
                    .addValue("phaseName", task.phaseName())
                    .addValue("phaseWeight", task.phaseWeight())
                    .addValue("taskNo", task.taskNo())
                    .addValue("title", task.title())
                    .addValue("required", task.required())
                    .addValue("ownerRole", task.ownerRole())
                    .addValue("evidenceHint", task.evidenceHint()));
        }
    }

    public Optional<Project> findProject(UUID id) {
        return plain.query("SELECT " + PROJECT_COLUMNS
                + " FROM ops_svc.impl_project WHERE id = ?", PROJECT_MAPPER, id).stream().findFirst();
    }

    public List<Project> listProjects(String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + PROJECT_COLUMNS
                + " FROM ops_svc.impl_project WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, PROJECT_MAPPER);
    }

    public long countProjects(String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT count(*) FROM ops_svc.impl_project WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long n = jdbc.queryForObject(sql.toString(), params, Long.class);
        return n == null ? 0 : n;
    }

    private static final java.util.Set<String> MUTABLE_PROJECT_COLUMNS = java.util.Set.of(
            "status", "owner", "start_date", "target_date", "go_live_at", "approved_by",
            "acceptance_evidence", "accepted_at", "cancel_reason");

    public boolean updateProjectFields(UUID id, long expectedVersion, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String column = entry.getKey();
            if (!MUTABLE_PROJECT_COLUMNS.contains(column)) {
                throw new IllegalArgumentException("不允许修改的实施项目字段：" + column);
            }
            sets.add(column + " = :" + column);
            params.addValue(column, jdbcValue(entry.getValue()));
        }
        params.addValue("id", id).addValue("expected", expectedVersion);
        return jdbc.update("UPDATE ops_svc.impl_project SET " + String.join(", ", sets)
                + ", version = version + 1, updated_at = now()"
                + " WHERE id = :id AND version = :expected", params) > 0;
    }

    // ------------------------------------------------------------------ 项目任务

    private static final RowMapper<ProjectTask> TASK_MAPPER = (rs, n) -> new ProjectTask(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getInt("phase_no"),
            rs.getString("phase_name"),
            rs.getInt("phase_weight"),
            rs.getInt("task_no"),
            rs.getString("title"),
            rs.getBoolean("required"),
            rs.getString("owner_role"),
            rs.getString("evidence_hint"),
            rs.getString("status"),
            rs.getString("assignee"),
            rs.getObject("due_date", LocalDate.class),
            rs.getString("evidence"),
            rs.getString("blocked_reason"),
            rs.getString("waiver_reason"),
            rs.getString("waiver_by"),
            instant(rs, "waiver_at"),
            rs.getString("completed_by"),
            instant(rs, "completed_at"),
            rs.getLong("version"));

    public List<ProjectTask> listTasks(UUID projectId) {
        return plain.query("""
                SELECT id, project_id, phase_no, phase_name, phase_weight, task_no, title,
                       required, owner_role, evidence_hint, status, assignee, due_date, evidence,
                       blocked_reason, waiver_reason, waiver_by, waiver_at, completed_by,
                       completed_at, version
                  FROM ops_svc.impl_project_task WHERE project_id = ? ORDER BY task_no
                """, TASK_MAPPER, projectId);
    }

    public Optional<ProjectTask> findTask(UUID projectId, UUID taskId) {
        return plain.query("""
                SELECT id, project_id, phase_no, phase_name, phase_weight, task_no, title,
                       required, owner_role, evidence_hint, status, assignee, due_date, evidence,
                       blocked_reason, waiver_reason, waiver_by, waiver_at, completed_by,
                       completed_at, version
                  FROM ops_svc.impl_project_task WHERE project_id = ? AND id = ?
                """, TASK_MAPPER, projectId, taskId).stream().findFirst();
    }

    private static final java.util.Set<String> MUTABLE_TASK_COLUMNS = java.util.Set.of(
            "status", "assignee", "due_date", "evidence", "blocked_reason", "completed_by",
            "completed_at", "waiver_reason", "waiver_by", "waiver_at");

    public boolean updateTaskFields(UUID taskId, long expectedVersion, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return false;
        }
        List<String> sets = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String column = entry.getKey();
            if (!MUTABLE_TASK_COLUMNS.contains(column)) {
                throw new IllegalArgumentException("不允许修改的实施任务字段：" + column);
            }
            sets.add(column + " = :" + column);
            params.addValue(column, jdbcValue(entry.getValue()));
        }
        params.addValue("id", taskId).addValue("expected", expectedVersion);
        return jdbc.update("UPDATE ops_svc.impl_project_task SET " + String.join(", ", sets)
                + ", version = version + 1, updated_at = now()"
                + " WHERE id = :id AND version = :expected", params) > 0;
    }

    public long countBlockedTasks(UUID projectId) {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.impl_project_task
                 WHERE project_id = ? AND status = 'BLOCKED'
                """, Long.class, projectId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 项目事件

    public void insertEvent(ProjectEvent event) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_project_event
                    (project_id, actor, action, note, detail)
                VALUES (:project, :actor, :action, :note, CAST(:detail AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("project", event.projectId())
                .addValue("actor", event.actor())
                .addValue("action", event.action())
                .addValue("note", event.note())
                .addValue("detail", write(event.detail())));
    }

    public List<ProjectEvent> listEvents(UUID projectId, int limit) {
        return plain.query("""
                SELECT id, project_id, ts, actor, action, note, detail
                  FROM ops_svc.impl_project_event
                 WHERE project_id = ? ORDER BY ts, id LIMIT ?
                """, (rs, n) -> new ProjectEvent(
                rs.getLong("id"),
                rs.getObject("project_id", UUID.class),
                instant(rs, "ts"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("note"),
                readMap(rs.getString("detail"))), projectId, limit);
    }

    // ------------------------------------------------------------------ 培训课程

    private static final RowMapper<Course> COURSE_MAPPER = (rs, n) -> new Course(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("title"),
            rs.getString("category"),
            rs.getBoolean("required"),
            rs.getInt("duration_minutes"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            List.of());

    private static final RowMapper<Question> QUESTION_MAPPER = (rs, n) -> new Question(
            rs.getObject("id", UUID.class),
            rs.getObject("course_id", UUID.class),
            rs.getInt("question_no"),
            rs.getString("stem"),
            readStringMap(rs.getString("options")),
            rs.getString("answer_key"),
            rs.getInt("score"),
            rs.getString("status"));

    public List<Course> listCourses(boolean onlyActive) {
        return plain.query("""
                SELECT id, code, title, category, required, duration_minutes, description,
                       status, created_by, version, created_at, updated_at
                  FROM ops_svc.impl_course
                """ + (onlyActive ? " WHERE status = 'ACTIVE'" : "")
                + " ORDER BY code", COURSE_MAPPER);
    }

    public Optional<Course> findCourseByCode(String code) {
        return plain.query("""
                SELECT id, code, title, category, required, duration_minutes, description,
                       status, created_by, version, created_at, updated_at
                  FROM ops_svc.impl_course WHERE code = ?
                """, COURSE_MAPPER, code).stream().findFirst()
                .map(c -> c.withQuestions(listQuestions(c.id())));
    }

    public Optional<Course> findCourse(UUID id) {
        return plain.query("""
                SELECT id, code, title, category, required, duration_minutes, description,
                       status, created_by, version, created_at, updated_at
                  FROM ops_svc.impl_course WHERE id = ?
                """, COURSE_MAPPER, id).stream().findFirst()
                .map(c -> c.withQuestions(listQuestions(c.id())));
    }

    public List<Question> listQuestions(UUID courseId) {
        return plain.query("""
                SELECT id, course_id, question_no, stem, options, answer_key, score, status
                  FROM ops_svc.impl_question WHERE course_id = ? ORDER BY question_no
                """, QUESTION_MAPPER, courseId);
    }

    public void insertCourse(Course course) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_course
                    (id, code, title, category, required, duration_minutes, description, status,
                     created_by)
                VALUES (:id, :code, :title, :category, :required, :minutes, :description, :status,
                        :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", course.id())
                .addValue("code", course.code())
                .addValue("title", course.title())
                .addValue("category", course.category())
                .addValue("required", course.required())
                .addValue("minutes", course.durationMinutes())
                .addValue("description", course.description())
                .addValue("status", course.status())
                .addValue("createdBy", course.createdBy()));
    }

    public void insertQuestion(Question question) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_question
                    (id, course_id, question_no, stem, options, answer_key, score, status)
                VALUES (:id, :course, :no, :stem, CAST(:options AS jsonb), :answer, :score, :status)
                """, new MapSqlParameterSource()
                .addValue("id", question.id())
                .addValue("course", question.courseId())
                .addValue("no", question.questionNo())
                .addValue("stem", question.stem())
                .addValue("options", write(question.options()))
                .addValue("answer", question.answerKey())
                .addValue("score", question.score())
                .addValue("status", question.status()));
    }

    public boolean updateCourseStatus(UUID id, String status) {
        return plain.update("""
                UPDATE ops_svc.impl_course
                   SET status = ?, version = version + 1, updated_at = now()
                 WHERE id = ?
                """, status, id) > 0;
    }

    // ------------------------------------------------------------------ 考试与成绩

    private static final RowMapper<Exam> EXAM_MAPPER = (rs, n) -> new Exam(
            rs.getObject("id", UUID.class),
            rs.getString("exam_no"),
            rs.getObject("project_id", UUID.class),
            rs.getObject("course_id", UUID.class),
            rs.getString("course_code"),
            rs.getString("title"),
            rs.getInt("pass_score"),
            rs.getString("status"),
            rs.getString("created_by"),
            rs.getLong("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            List.of(),
            new com.medassist.ops.domain.ImplementationModel.ExamStats(0, 0, 0));

    public String nextExamNo() {
        Long seq = plain.queryForObject("SELECT nextval('ops_svc.impl_exam_no_seq')", Long.class);
        return "EX" + LocalDate.now().format(DAY) + String.format("%04d", seq == null ? 1 : seq);
    }

    public Optional<Exam> findExam(UUID id) {
        return plain.query("""
                SELECT id, exam_no, project_id, course_id, course_code, title, pass_score, status,
                       created_by, version, created_at, updated_at
                  FROM ops_svc.impl_exam WHERE id = ?
                """, EXAM_MAPPER, id).stream().findFirst();
    }

    public Optional<Exam> findExamByProjectCourse(UUID projectId, String courseCode) {
        return plain.query("""
                SELECT id, exam_no, project_id, course_id, course_code, title, pass_score, status,
                       created_by, version, created_at, updated_at
                  FROM ops_svc.impl_exam WHERE project_id = ? AND course_code = ?
                """, EXAM_MAPPER, projectId, courseCode).stream().findFirst();
    }

    public List<Exam> listExams(UUID projectId) {
        return plain.query("""
                SELECT id, exam_no, project_id, course_id, course_code, title, pass_score, status,
                       created_by, version, created_at, updated_at
                  FROM ops_svc.impl_exam WHERE project_id = ? ORDER BY course_code
                """, EXAM_MAPPER, projectId);
    }

    public void insertExam(Exam exam) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_exam
                    (id, exam_no, project_id, course_id, course_code, title, pass_score, status,
                     created_by)
                VALUES (:id, :no, :project, :course, :courseCode, :title, :passScore, :status,
                        :createdBy)
                """, new MapSqlParameterSource()
                .addValue("id", exam.id())
                .addValue("no", exam.examNo())
                .addValue("project", exam.projectId())
                .addValue("course", exam.courseId())
                .addValue("courseCode", exam.courseCode())
                .addValue("title", exam.title())
                .addValue("passScore", exam.passScore())
                .addValue("status", exam.status())
                .addValue("createdBy", exam.createdBy()));
    }

    public boolean closeExam(UUID id, long expectedVersion) {
        return plain.update("""
                UPDATE ops_svc.impl_exam
                   SET status = 'CLOSED', version = version + 1, updated_at = now()
                 WHERE id = ? AND version = ? AND status = 'OPEN'
                """, id, expectedVersion) > 0;
    }

    private static final RowMapper<Attempt> ATTEMPT_MAPPER = (rs, n) -> new Attempt(
            rs.getObject("id", UUID.class),
            rs.getObject("exam_id", UUID.class),
            rs.getString("trainee"),
            readStringMap(rs.getString("answers")),
            rs.getInt("score"),
            rs.getBoolean("passed"),
            rs.getInt("attempt_count"),
            rs.getString("submitted_by"),
            instant(rs, "submitted_at"),
            instant(rs, "updated_at"));

    public Optional<Attempt> findAttempt(UUID examId, String trainee) {
        return plain.query("""
                SELECT id, exam_id, trainee, answers, score, passed, attempt_count, submitted_by,
                       submitted_at, updated_at
                  FROM ops_svc.impl_attempt WHERE exam_id = ? AND trainee = ?
                """, ATTEMPT_MAPPER, examId, trainee).stream().findFirst();
    }

    public List<Attempt> listAttempts(UUID examId) {
        return plain.query("""
                SELECT id, exam_id, trainee, answers, score, passed, attempt_count, submitted_by,
                       submitted_at, updated_at
                  FROM ops_svc.impl_attempt WHERE exam_id = ? ORDER BY trainee
                """, ATTEMPT_MAPPER, examId);
    }

    /** 录入成绩：同一学员同一考试只保留最新一次，重考累加次数。 */
    public void upsertAttempt(Attempt attempt) {
        jdbc.update("""
                INSERT INTO ops_svc.impl_attempt
                    (id, exam_id, trainee, answers, score, passed, attempt_count, submitted_by)
                VALUES (:id, :exam, :trainee, CAST(:answers AS jsonb), :score, :passed, 1,
                        :submittedBy)
                ON CONFLICT (exam_id, trainee) DO UPDATE SET
                    answers = EXCLUDED.answers, score = EXCLUDED.score, passed = EXCLUDED.passed,
                    attempt_count = ops_svc.impl_attempt.attempt_count + 1,
                    submitted_by = EXCLUDED.submitted_by, submitted_at = now(), updated_at = now()
                """, new MapSqlParameterSource()
                .addValue("id", attempt.id())
                .addValue("exam", attempt.examId())
                .addValue("trainee", attempt.trainee())
                .addValue("answers", write(attempt.answers()))
                .addValue("score", attempt.score())
                .addValue("passed", attempt.passed())
                .addValue("submittedBy", attempt.submittedBy()));
    }

    /** 项目内各课程的通过率（唯一学员口径）。 */
    public List<Map<String, Object>> courseStats(UUID projectId) {
        return plain.queryForList("""
                SELECT e.course_code AS course_code, c.title AS title,
                       count(a.id) AS attempts,
                       count(*) FILTER (WHERE a.passed) AS passed
                  FROM ops_svc.impl_exam e
                  JOIN ops_svc.impl_course c ON c.id = e.course_id
                  LEFT JOIN ops_svc.impl_attempt a ON a.exam_id = e.id
                 WHERE e.project_id = ?
                 GROUP BY e.course_code, c.title
                 ORDER BY e.course_code
                """, projectId);
    }

    public Map<String, Object> examStats(UUID examId) {
        Map<String, Object> row = plain.queryForMap("""
                SELECT count(*) AS attempts, count(*) FILTER (WHERE passed) AS passed
                  FROM ops_svc.impl_attempt WHERE exam_id = ?
                """, examId);
        return row == null ? Map.of() : new LinkedHashMap<>(row);
    }

    // ------------------------------------------------------------------ 看板

    public List<Map<String, Object>> projectCountsByStatus() {
        return plain.queryForList("""
                SELECT status, count(*) AS n FROM ops_svc.impl_project GROUP BY status
                """);
    }

    public long countBlockedTasksAll() {
        Long n = plain.queryForObject("""
                SELECT count(*) FROM ops_svc.impl_project_task WHERE status = 'BLOCKED'
                """, Long.class);
        return n == null ? 0 : n;
    }

    public long countTemplates(boolean onlyActive) {
        Long n = plain.queryForObject("SELECT count(*) FROM ops_svc.impl_template"
                + (onlyActive ? " WHERE status = 'ACTIVE'" : ""), Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 工具

    private Object jdbcValue(Object value) {
        if (value instanceof Instant instant) {
            return timestamp(instant);
        }
        return value;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return STATIC_JSON.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    private static Map<String, String> readStringMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return STATIC_JSON.readValue(json, STRING_MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败", e);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static List<String> stringList(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return List.of(values);
        }
        if (raw instanceof Object[] values) {
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                result.add(String.valueOf(value));
            }
            return result;
        }
        return List.of();
    }

    private static String arrayLiteral(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        return "{" + String.join(",", values) + "}";
    }
}
