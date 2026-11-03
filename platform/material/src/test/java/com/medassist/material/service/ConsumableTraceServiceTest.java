package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.material.domain.MaterialModel.ConsumableIssueItem;
import com.medassist.material.domain.MaterialModel.ConsumableTrace;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialDtos.CreateTraceReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 高值耗材追溯：不得超用、过期批次不得用于患者、召回口径。
 */
class ConsumableTraceServiceTest {

    private MaterialRepository repo;
    private ConsumableTraceService service;

    private final UUID issueItemId = UUID.randomUUID();
    private final UUID issueId = UUID.randomUUID();
    private final UUID consumableId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    private ConsumableIssueItem item(LocalDate expire, int quantity, int traced) {
        return new ConsumableIssueItem(issueItemId, issueId, consumableId, "CONS-STENT-001",
                "冠状动脉支架系统", UUID.randomUUID(), "B-1", expire, quantity, traced,
                new BigDecimal("4800.00"), new BigDecimal("4800.00"));
    }

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        service = new ConsumableTraceService(repo, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("登记：正常追溯写入并带耗材/批次快照")
    void registerStoresSnapshot() {
        when(repo.findIssueItem(issueItemId))
                .thenReturn(Optional.of(item(LocalDate.now().plusDays(100), 5, 0)));
        when(repo.addTracedQuantity(issueItemId, 1)).thenReturn(true);

        var view = service.register(trace(1), "nurse01");

        assertThat(view.trace().consumableCode()).isEqualTo("CONS-STENT-001");
        assertThat(view.trace().batchNo()).isEqualTo("B-1");
        assertThat(view.trace().patientNo()).isEqualTo("P000001");
        verify(repo).insertTrace(any());
    }

    @Test
    @DisplayName("登记：超过未追溯数量 → 422")
    void registerRejectsOverTrace() {
        when(repo.findIssueItem(issueItemId))
                .thenReturn(Optional.of(item(LocalDate.now().plusDays(100), 5, 4)));

        assertThatThrownBy(() -> service.register(trace(2), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_TRACE_EXCEEDS);
        verify(repo, never()).addTracedQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("登记：过期批次禁止用于患者")
    void registerRejectsExpiredBatch() {
        when(repo.findIssueItem(issueItemId))
                .thenReturn(Optional.of(item(LocalDate.now().minusDays(1), 5, 0)));

        assertThatThrownBy(() -> service.register(trace(1), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_BATCH_EXPIRED);
    }

    @Test
    @DisplayName("登记：并发下 CAS 未命中 → 422（不写重复追溯）")
    void registerRejectsLostRace() {
        when(repo.findIssueItem(issueItemId))
                .thenReturn(Optional.of(item(LocalDate.now().plusDays(100), 5, 0)));
        when(repo.addTracedQuantity(issueItemId, 1)).thenReturn(false);

        assertThatThrownBy(() -> service.register(trace(1), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_TRACE_EXCEEDS);
        verify(repo, never()).insertTrace(any());
    }

    @Test
    @DisplayName("登记：出库明细不存在 → 404")
    void registerRejectsMissingIssueItem() {
        when(repo.findIssueItem(issueItemId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(trace(1), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_ISSUE_ITEM_NOT_FOUND);
    }

    @Test
    @DisplayName("召回：按批号返回影响患者/手术数与明细")
    void recallAggregatesByBatch() {
        ConsumableTrace t = new ConsumableTrace(UUID.randomUUID(), issueItemId, consumableId,
                "CONS-STENT-001", "冠状动脉支架系统", "B-1", LocalDate.now().plusDays(100), 1,
                patientId, "P000001", null, UUID.randomUUID(), "nurse01", Instant.now(), null,
                Instant.now());
        when(repo.recallStats("B-1")).thenReturn(
                new MaterialRepository.RecallStats(1, 1, 1, 1));
        when(repo.listTraces(eq("B-1"), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(t));

        var recall = service.recall("B-1");

        assertThat(recall.batchNo()).isEqualTo("B-1");
        assertThat(recall.patientCount()).isEqualTo(1);
        assertThat(recall.surgeryCount()).isEqualTo(1);
        assertThat(recall.traces()).hasSize(1);
    }

    @Test
    @DisplayName("召回：未指定批号 → 422（不允许全库导出）")
    void recallRequiresBatchNo() {
        assertThatThrownBy(() -> service.recall("  "))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private CreateTraceReq trace(int quantity) {
        return new CreateTraceReq(issueItemId, quantity, patientId, "P000001", null, null, null, null);
    }
}
