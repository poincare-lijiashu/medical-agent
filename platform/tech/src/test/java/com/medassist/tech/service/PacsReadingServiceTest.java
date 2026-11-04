package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.domain.TechModel.ReadingQueueItem;
import com.medassist.tech.domain.TechModel.ReadingTask;
import com.medassist.tech.domain.TechModel.Report;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.PacsDtos.ReadingNoteReq;
import com.medassist.tech.web.PacsDtos.ReadingTaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 影像阅片：领取幂等/抢占冲突、越权释放、完成前的报告门槛、队列过滤与上限。
 */
class PacsReadingServiceTest {

    private static final String UID = "1.2.840.113619.2.55.3.20261103.1";
    private static final String ACCESSION = "AP20261103000001";

    private TechRepository repo;
    private PacsReadingService service;

    private final UUID studyId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        service = new PacsReadingService(repo, JsonMapper.builder().build());
        when(repo.lockPacsStudyByUid(UID)).thenReturn(Optional.of(study(PacsStudy.COMPLETED)));
        when(repo.findPacsStudyByUid(UID)).thenReturn(Optional.of(study(PacsStudy.COMPLETED)));
    }

    private PacsStudy study(String status) {
        return new PacsStudy(studyId, applicationId, UID, ACCESSION, "CT", "胸部", status,
                3, 120, null, Instant.now().minusSeconds(600), Instant.now(), 1L, Instant.now());
    }

    private ReadingTask task(String assignee, String status, long version) {
        return new ReadingTask(taskId, studyId, assignee, status, Instant.now(), null, null, null, version);
    }

    private Report report(String status) {
        return new Report(UUID.randomUUID(), "RP20261103000001", applicationId, 1, true,
                "未见异常", "正常胸片", "doc01", "chief01", Instant.now(), status, false,
                Instant.now(), null, null);
    }

    private ReadingQueueItem queueItem() {
        return new ReadingQueueItem(studyId, UID, ACCESSION, "CT", "胸部", 3, 120,
                Instant.now(), null, applicationId, ACCESSION, "P20261103000001", "胸部CT平扫",
                "URGENT", null, "FINAL", null, null, null, null, null);
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    // ---------------------------------------------------------------- 队列

    @Test
    @DisplayName("队列：状态与设备类型规范化，limit 上限 200")
    void queueNormalizesAndClamps() {
        when(repo.listReadingQueue(eq("CT"), eq("MINE"), eq("doc01"), isNull(), isNull(), eq(200)))
                .thenReturn(List.of(queueItem()));

        List<ReadingQueueItem> result = service.queue("ct", "mine", null, 1000, "doc01");

        assertThat(result).hasSize(1);
        verify(repo).listReadingQueue("CT", "MINE", "doc01", null, null, 200);
    }

    @Test
    @DisplayName("队列：按完成日过滤使用自然日窗口")
    void queueUsesDayWindow() {
        LocalDate day = LocalDate.of(2026, 11, 3);
        ZoneId zone = BusinessTime.zone();
        OffsetDateTime fromTs = day.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime toTs = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        when(repo.listReadingQueue(eq("CT"), isNull(), isNull(), eq(fromTs), eq(toTs), eq(20)))
                .thenReturn(List.of());

        service.queue("ct", null, day, 20, null);

        verify(repo).listReadingQueue("CT", null, null, fromTs, toTs, 20);
    }

    @Test
    @DisplayName("队列：非法状态、MINE 缺操作人、非法 Modality 都拒绝")
    void queueRejectsInvalidFilters() {
        assertCode(() -> service.queue(null, "UNKNOWN", null, 10, "doc01"), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.queue(null, "MINE", null, 10, " "), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.queue("C", null, null, 10, "doc01"), ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 领取

    @Test
    @DisplayName("领取：检查未完成 → 46423，不插入任务")
    void claimRequiresCompletedStudy() {
        when(repo.lockPacsStudyByUid(UID)).thenReturn(Optional.of(study(PacsStudy.IN_PROGRESS)));

        assertCode(() -> service.claim(UID, "doc01"), ErrorCode.TECH_READING_STUDY_NOT_READY);
        verify(repo, never()).insertReadingTask(any());
    }

    @Test
    @DisplayName("领取：检查不存在 → 46406")
    void claimRequiresStudy() {
        when(repo.lockPacsStudyByUid(UID)).thenReturn(Optional.empty());

        assertCode(() -> service.claim(UID, "doc01"), ErrorCode.TECH_PACS_STUDY_NOT_FOUND);
    }

    @Test
    @DisplayName("领取：同一医师重复领取幂等返回，不新增任务")
    void claimIsIdempotentForSameAssignee() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.of(task("doc01", ReadingTask.CLAIMED, 0)));

        ReadingTaskView view = service.claim(UID, "doc01");

        assertThat(view.assignee()).isEqualTo("doc01");
        verify(repo, never()).insertReadingTask(any());
    }

    @Test
    @DisplayName("领取：他人已领取 → 409")
    void claimRejectsTakenStudy() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.of(task("doc02", ReadingTask.CLAIMED, 0)));

        assertCode(() -> service.claim(UID, "doc01"), ErrorCode.CONFLICT);
        verify(repo, never()).insertReadingTask(any());
    }

    @Test
    @DisplayName("领取：成功插入任务并写审计")
    void claimInsertsTaskAndAudits() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.empty());
        when(repo.findReadingTask(any())).thenReturn(Optional.empty());

        ReadingTaskView view = service.claim(UID, "doc01");

        assertThat(view.studyInstanceUid()).isEqualTo(UID);
        assertThat(view.accessionNumber()).isEqualTo(ACCESSION);
        assertThat(view.status()).isEqualTo(ReadingTask.CLAIMED);
        verify(repo).insertReadingTask(any(ReadingTask.class));
        verify(repo).logChange(eq("doc01"), eq("PACS_READING_CLAIM"), eq("PACS_READING"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("领取：并发撞唯一索引 → 409，且不做二次查库")
    void claimHandlesConcurrentInsert() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.empty());
        doThrow(new DuplicateKeyException("dup")).when(repo).insertReadingTask(any());

        assertCode(() -> service.claim(UID, "doc01"), ErrorCode.CONFLICT);
        verify(repo, never()).findReadingTask(any());
    }

    @Test
    @DisplayName("领取：缺少操作人 → 422")
    void claimRequiresActor() {
        assertCode(() -> service.claim(UID, null), ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 释放

    @Test
    @DisplayName("释放：他人持有的任务不能代释放 → 403")
    void releaseRejectsNonOwner() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.of(task("doc02", ReadingTask.CLAIMED, 0)));

        assertCode(() -> service.release(UID, new ReadingNoteReq("临时去急诊"), "doc01"),
                ErrorCode.TECH_READING_NOT_ASSIGNEE);
        verify(repo, never()).closeReadingTask(any(), anyString(), anyString(), anyString(),
                any(), anyLong());
    }

    @Test
    @DisplayName("释放：无进行中任务 → 46420")
    void releaseRequiresActiveTask() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.empty());

        assertCode(() -> service.release(UID, null, "doc01"), ErrorCode.TECH_READING_TASK_NOT_FOUND);
    }

    @Test
    @DisplayName("释放：CAS 成功并写审计；CAS 失败 → 409")
    void releaseUsesCas() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.of(task("doc01", ReadingTask.CLAIMED, 7)));
        when(repo.findReadingTask(taskId)).thenReturn(Optional.of(task("doc01", ReadingTask.RELEASED, 8)));
        when(repo.closeReadingTask(taskId, ReadingTask.CLAIMED, ReadingTask.RELEASED,
                "doc01", "临时去急诊", 7L)).thenReturn(true);

        ReadingTaskView view = service.release(UID, new ReadingNoteReq("临时去急诊"), "doc01");

        assertThat(view.status()).isEqualTo(ReadingTask.RELEASED);
        verify(repo).logChange(eq("doc01"), eq("PACS_READING_RELEASE"), eq("PACS_READING"),
                eq(taskId.toString()), anyString());
    }

    @Test
    @DisplayName("释放：版本已变化 → 409")
    void releaseCasConflict() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.of(task("doc01", ReadingTask.CLAIMED, 7)));
        when(repo.closeReadingTask(any(), anyString(), anyString(), anyString(), any(), anyLong()))
                .thenReturn(false);

        assertCode(() -> service.release(UID, null, "doc01"), ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 完成

    @Test
    @DisplayName("完成：报告缺失或未签发 → 46424")
    void completeRequiresFinalizedReport() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.of(task("doc01", ReadingTask.CLAIMED, 0)));
        when(repo.findCurrentReport(applicationId)).thenReturn(Optional.empty());
        assertCode(() -> service.complete(UID, null, "doc01"), ErrorCode.TECH_READING_REPORT_NOT_FINAL);

        when(repo.findCurrentReport(applicationId)).thenReturn(Optional.of(report(Report.DRAFT)));
        assertCode(() -> service.complete(UID, null, "doc01"), ErrorCode.TECH_READING_REPORT_NOT_FINAL);
        verify(repo, never()).closeReadingTask(any(), anyString(), anyString(), anyString(),
                any(), anyLong());
    }

    @Test
    @DisplayName("完成：已签发报告 → COMPLETED 并写审计")
    void completeSucceedsWithFinalReport() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.of(task("doc01", ReadingTask.CLAIMED, 3)));
        when(repo.findCurrentReport(applicationId)).thenReturn(Optional.of(report(Report.FINAL)));
        when(repo.closeReadingTask(taskId, ReadingTask.CLAIMED, ReadingTask.COMPLETED,
                "doc01", null, 3L)).thenReturn(true);
        when(repo.findReadingTask(taskId)).thenReturn(Optional.of(task("doc01", ReadingTask.COMPLETED, 4)));

        ReadingTaskView view = service.complete(UID, null, "doc01");

        assertThat(view.status()).isEqualTo(ReadingTask.COMPLETED);
        verify(repo).logChange(eq("doc01"), eq("PACS_READING_COMPLETE"), eq("PACS_READING"),
                eq(taskId.toString()), anyString());
    }

    @Test
    @DisplayName("完成：他人持有 → 403，且不检查报告")
    void completeRejectsNonOwner() {
        when(repo.findActiveReadingTask(studyId)).thenReturn(Optional.of(task("doc02", ReadingTask.CLAIMED, 0)));

        assertCode(() -> service.complete(UID, null, "doc01"), ErrorCode.TECH_READING_NOT_ASSIGNEE);
        verify(repo, never()).findCurrentReport(any());
    }

    // ---------------------------------------------------------------- 审计

    @Test
    @DisplayName("调阅审计：limit 上限 200，检查不存在 → 404")
    void accessLogClampsAndRequiresStudy() {
        when(repo.listPacsAccess(studyId, 200)).thenReturn(List.of());
        assertThat(service.accessLog(UID, 9999)).isEmpty();
        verify(repo).listPacsAccess(studyId, 200);

        when(repo.findPacsStudyByUid("1.2.3")).thenReturn(Optional.empty());
        assertCode(() -> service.accessLog("1.2.3", 10), ErrorCode.TECH_PACS_STUDY_NOT_FOUND);
    }
}
