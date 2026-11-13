package com.medassist.ops.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实施交付与培训考核模型（§298，对应 ops_svc.impl_* 表）。
 *
 * <p>模板是方法论，项目是事实：项目创建时复制模板任务，模板后续修改不影响在建项目。
 */
public final class ImplementationModel {

    public static final String PROJECT_PLANNED = "PLANNED";
    public static final String PROJECT_IN_PROGRESS = "IN_PROGRESS";
    public static final String PROJECT_GO_LIVE = "GO_LIVE";
    public static final String PROJECT_ACCEPTED = "ACCEPTED";
    public static final String PROJECT_CANCELLED = "CANCELLED";

    public static final String TASK_PENDING = "PENDING";
    public static final String TASK_IN_PROGRESS = "IN_PROGRESS";
    public static final String TASK_BLOCKED = "BLOCKED";
    public static final String TASK_DONE = "DONE";
    public static final String TASK_WAIVED = "WAIVED";

    private ImplementationModel() {
    }

    /** 实施模板（含阶段与任务）。 */
    public record Template(
            UUID id,
            String code,
            String name,
            String description,
            String status,
            String createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<Phase> phases) {

        public Template withPhases(List<Phase> phases) {
            return new Template(id, code, name, description, status, createdBy, version,
                    createdAt, updatedAt, phases);
        }
    }

    /** 模板阶段。 */
    public record Phase(
            UUID id,
            UUID templateId,
            int phaseNo,
            String name,
            int weight,
            String description,
            List<TemplateTask> tasks) {

        public Phase withTasks(List<TemplateTask> tasks) {
            return new Phase(id, templateId, phaseNo, name, weight, description, tasks);
        }
    }

    /** 模板任务。 */
    public record TemplateTask(
            UUID id,
            UUID templateId,
            UUID phaseId,
            int taskNo,
            String title,
            String description,
            boolean required,
            String ownerRole,
            String evidenceHint) {
    }

    /** 实施项目。 */
    public record Project(
            UUID id,
            String projectNo,
            UUID templateId,
            String templateCode,
            String templateName,
            String tenantCode,
            String hospitalName,
            String status,
            String owner,
            LocalDate startDate,
            LocalDate targetDate,
            Instant goLiveAt,
            String approvedBy,
            String acceptanceEvidence,
            Instant acceptedAt,
            String cancelReason,
            List<String> requiredCourseCodes,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<ProjectTask> tasks,
            Readiness readiness) {

        public Project withDetail(List<ProjectTask> tasks, Readiness readiness) {
            return new Project(id, projectNo, templateId, templateCode, templateName, tenantCode,
                    hospitalName, status, owner, startDate, targetDate, goLiveAt, approvedBy,
                    acceptanceEvidence, acceptedAt, cancelReason, requiredCourseCodes, version,
                    createdAt, updatedAt, tasks, readiness);
        }
    }

    /** 项目任务实例。 */
    public record ProjectTask(
            UUID id,
            UUID projectId,
            int phaseNo,
            String phaseName,
            int phaseWeight,
            int taskNo,
            String title,
            boolean required,
            String ownerRole,
            String evidenceHint,
            String status,
            String assignee,
            LocalDate dueDate,
            String evidence,
            String blockedReason,
            String waiverReason,
            String waiverBy,
            Instant waiverAt,
            String completedBy,
            Instant completedAt,
            long version) {
    }

    /** 项目事件（追加式）。 */
    public record ProjectEvent(
            long id,
            UUID projectId,
            Instant ts,
            String actor,
            String action,
            String note,
            Map<String, Object> detail) {
    }

    /** 上线就绪报告（只计算，不落库：任务与成绩变化后必须即时反映）。 */
    public record Readiness(
            String projectNo,
            boolean ready,
            int score,
            int minScore,
            int trainingThreshold,
            double trainingPassRate,
            List<String> missingRequiredTasks,
            List<String> blockedTasks,
            List<PhaseProgress> phases,
            List<CoursePass> training,
            int waivers,
            List<String> warnings,
            Instant generatedAt) {
    }

    /** 阶段进度与加权得分。 */
    public record PhaseProgress(
            int phaseNo,
            String name,
            int weight,
            int total,
            int done,
            double ratio,
            double weightedScore) {
    }

    /** 必修课程通过率。 */
    public record CoursePass(
            String courseCode,
            String title,
            int attempts,
            int passed,
            double passRate,
            boolean met,
            boolean examCreated) {
    }

    /** 培训课程（含题库）。 */
    public record Course(
            UUID id,
            String code,
            String title,
            String category,
            boolean required,
            int durationMinutes,
            String description,
            String status,
            String createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<Question> questions) {

        public Course withQuestions(List<Question> questions) {
            return new Course(id, code, title, category, required, durationMinutes, description,
                    status, createdBy, version, createdAt, updatedAt, questions);
        }
    }

    /** 题目。 */
    public record Question(
            UUID id,
            UUID courseId,
            int questionNo,
            String stem,
            Map<String, String> options,
            String answerKey,
            int score,
            String status) {
    }

    /** 项目考试。 */
    public record Exam(
            UUID id,
            String examNo,
            UUID projectId,
            UUID courseId,
            String courseCode,
            String title,
            int passScore,
            String status,
            String createdBy,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<Attempt> attempts,
            ExamStats stats) {

        public Exam withAttempts(List<Attempt> attempts, ExamStats stats) {
            return new Exam(id, examNo, projectId, courseId, courseCode, title, passScore, status,
                    createdBy, version, createdAt, updatedAt, attempts, stats);
        }
    }

    /** 考试统计（唯一学员口径）。 */
    public record ExamStats(int attempts, int passed, double passRate) {
    }

    /** 考试成绩。 */
    public record Attempt(
            UUID id,
            UUID examId,
            String trainee,
            Map<String, String> answers,
            int score,
            boolean passed,
            int attemptCount,
            String submittedBy,
            Instant submittedAt,
            Instant updatedAt) {
    }
}
