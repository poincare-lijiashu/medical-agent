package com.medassist.kb.service;

import com.medassist.kb.config.KbProperties;
import com.medassist.kb.domain.KbModel.IngestTask;
import com.medassist.kb.repo.KbRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 索引 worker 编排：抢占 → 处理 → 失败回写退避。
 *
 * <p>此前该链路（含租约重抢、失败退避）完全没有测试，属于"只活在 SQL 里"的盲区。
 */
class IngestTaskWorkerTest {

    private KbRepository repo;
    private IngestService ingestService;
    private IngestTaskWorker worker;

    @BeforeEach
    void setUp() {
        repo = mock(KbRepository.class);
        ingestService = mock(IngestService.class);
        // 默认：batchSize=5, maxAttempts=5, leaseSeconds=600
        worker = new IngestTaskWorker(repo, ingestService,
                new KbProperties(null, null, null, null, null));
    }

    private IngestTask task(UUID documentId, int attempts) {
        return new IngestTask(UUID.randomUUID(), documentId, "INDEX", IngestTask.RUNNING, attempts);
    }

    @Test
    @DisplayName("没有可执行任务：直接返回，不做任何处理")
    void doesNothingWhenNoTaskClaimed() {
        when(repo.claimTasks(anyInt(), anyInt(), anyInt())).thenReturn(List.of());

        worker.tick();

        verify(ingestService, never()).process(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("抢占到任务：按 batchSize / maxAttempts / 租约调用 claim，并逐个处理")
    void processesClaimedTasks() {
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        IngestTask t1 = task(doc1, 1);
        IngestTask t2 = task(doc2, 2);
        when(repo.claimTasks(eq(5), eq(5), eq(600))).thenReturn(List.of(t1, t2));

        worker.tick();

        verify(ingestService).process(t1.id(), doc1);
        verify(ingestService).process(t2.id(), doc2);
        verify(repo, never()).failTask(org.mockito.ArgumentMatchers.any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("处理失败：任务退回 FAILED（带退避次数）且文档置 FAILED，不中断后续任务")
    void failureMarksTaskAndDocument() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        IngestTask t1 = task(bad, 3);
        IngestTask t2 = task(good, 1);
        when(repo.claimTasks(anyInt(), anyInt(), anyInt())).thenReturn(List.of(t1, t2));
        doThrow(new IllegalStateException("文档没有可索引的文本内容"))
                .when(ingestService).process(t1.id(), bad);

        worker.tick();

        verify(repo).failTask(eq(t1.id()), eq("文档没有可索引的文本内容"), eq(3));
        verify(repo).markFailed(eq(bad), anyString());
        // 前一个失败不应阻断后一个
        verify(ingestService).process(t2.id(), good);
        verify(repo, never()).failTask(eq(t2.id()), anyString(), anyInt());
    }
}
