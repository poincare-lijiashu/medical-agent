package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.domain.MaterialModel.ConsumableIssue;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialDtos.CreateIssueReq;
import com.medassist.material.web.MaterialDtos.IssueLine;
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
 * 耗材出库：FEFO 拆批、库存不足整单回滚、requestId 幂等、重复明细拒绝。
 */
class ConsumableIssueServiceTest {

    private MaterialRepository repo;
    private ConsumableIssueService service;

    private final UUID deptId = UUID.randomUUID();
    private final UUID consumableId = UUID.randomUUID();
    private final Consumable consumable = new Consumable(consumableId, "CONS-SYR-5ML",
            "一次性使用无菌注射器", "5ml 带针", "支", Consumable.GENERAL, new BigDecimal("0.80"),
            "某医疗器械公司", null, true, 0L, Instant.now());

    private final ConsumableBatch nearExpiry = new ConsumableBatch(UUID.randomUUID(), consumableId,
            consumable.code(), consumable.name(), "B-NEAR", LocalDate.now().plusDays(10), 3, null,
            new BigDecimal("0.80"), 0L);
    private final ConsumableBatch later = new ConsumableBatch(UUID.randomUUID(), consumableId,
            consumable.code(), consumable.name(), "B-LATER", LocalDate.now().plusDays(300), 10, null,
            new BigDecimal("0.80"), 0L);

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        service = new ConsumableIssueService(repo, JsonMapper.builder().build());
        when(repo.nextIssueNo()).thenReturn("MI20261103000001");
        when(repo.insertIssue(any(), any())).thenReturn(true);
        when(repo.findConsumable(consumableId)).thenReturn(Optional.of(consumable));
    }

    @Test
    @DisplayName("出库：按近效期优先拆成多批，并回写出库总额")
    void issueSplitsByFefo() {
        when(repo.listAvailableBatches(eq(consumableId), any(LocalDate.class)))
                .thenReturn(List.of(nearExpiry, later));
        when(repo.deductBatch(nearExpiry.id(), 3)).thenReturn(true);
        when(repo.deductBatch(later.id(), 2)).thenReturn(true);

        var view = service.issue(request("req-1", consumableId, 5), "nurse01");

        assertThat(view.items()).hasSize(2);
        assertThat(view.items().get(0).batchNo()).isEqualTo("B-NEAR");
        assertThat(view.items().get(0).quantity()).isEqualTo(3);
        assertThat(view.items().get(1).quantity()).isEqualTo(2);
        assertThat(view.issue().totalAmount()).isEqualByComparingTo("4.00");
        verify(repo).updateIssueTotal(any(), any());
    }

    @Test
    @DisplayName("出库：院区由调用方固化（G50）")
    void issueStoresCampus() {
        UUID campusId = UUID.randomUUID();
        when(repo.listAvailableBatches(eq(consumableId), any(LocalDate.class)))
                .thenReturn(List.of(later));
        when(repo.deductBatch(later.id(), 1)).thenReturn(true);

        service.issue(request("req-campus", consumableId, 1), campusId, "nurse01");

        verify(repo).insertIssue(any(ConsumableIssue.class), eq(campusId));
    }

    @Test
    @DisplayName("出库：成功后按批次累加科室二级库（科室可在退回时扣减）")
    void issueAddsToDeptStock() {
        when(repo.listAvailableBatches(eq(consumableId), any(LocalDate.class)))
                .thenReturn(List.of(nearExpiry, later));
        when(repo.deductBatch(nearExpiry.id(), 3)).thenReturn(true);
        when(repo.deductBatch(later.id(), 2)).thenReturn(true);

        service.issue(request("req-1", consumableId, 5), "nurse01");

        verify(repo).upsertDeptConsumableStock(deptId, consumableId, nearExpiry.id(), 3);
        verify(repo).upsertDeptConsumableStock(deptId, consumableId, later.id(), 2);
    }

    @Test
    @DisplayName("出库：库存不足整单失败（已扣批次随事务回滚）")
    void issueRejectsWhenStockInsufficient() {
        when(repo.listAvailableBatches(eq(consumableId), any(LocalDate.class)))
                .thenReturn(List.of(nearExpiry, later));
        when(repo.deductBatch(any(), anyInt())).thenReturn(true);

        assertThatThrownBy(() -> service.issue(request("req-1", consumableId, 20), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_STOCK_INSUFFICIENT);
        verify(repo, never()).insertIssueItem(any());
    }

    @Test
    @DisplayName("出库：同一 dept + requestId 重放返回原单，不重复扣库存（幂等）")
    void issueIsIdempotent() {
        ConsumableIssue existing = new ConsumableIssue(UUID.randomUUID(), "MI20261103000009", deptId,
                "手术室", ConsumableIssue.SURGERY, "req-1", "nurse01", null,
                new BigDecimal("4.00"), Instant.now());
        when(repo.findIssueByRequestId(deptId, "req-1")).thenReturn(Optional.of(existing));

        var view = service.issue(request("req-1", consumableId, 100), "nurse01");

        assertThat(view.issue().issueNo()).isEqualTo("MI20261103000009");
        verify(repo, never()).deductBatch(any(), anyInt());
        verify(repo, never()).insertIssue(any(), any());
    }

    @Test
    @DisplayName("出库：同一耗材重复出现 → 422")
    void issueRejectsDuplicateConsumable() {
        CreateIssueReq req = new CreateIssueReq(deptId, "手术室", ConsumableIssue.SURGERY,
                "req-1", null, List.of(new IssueLine(consumableId, 1),
                new IssueLine(consumableId, 2)));

        assertThatThrownBy(() -> service.issue(req, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_ISSUE_INVALID);
    }

    @Test
    @DisplayName("出库：停用耗材拒绝")
    void issueRejectsDisabledConsumable() {
        Consumable disabled = new Consumable(consumableId, consumable.code(), consumable.name(),
                consumable.spec(), consumable.unit(), consumable.category(), consumable.price(),
                null, null, false, 1L, Instant.now());
        when(repo.findConsumable(consumableId)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.issue(request("req-1", consumableId, 1), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("出库：并发扣减失败时提示库存不足（CAS 未命中）")
    void issueRejectsWhenDeductLosesRace() {
        when(repo.listAvailableBatches(eq(consumableId), any(LocalDate.class)))
                .thenReturn(List.of(nearExpiry));
        when(repo.deductBatch(nearExpiry.id(), 3)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(request("req-1", consumableId, 3), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_STOCK_INSUFFICIENT);
    }

    private CreateIssueReq request(String requestId, UUID consumableId, int quantity) {
        return new CreateIssueReq(deptId, "手术室", ConsumableIssue.SURGERY, requestId, null,
                List.of(new IssueLine(consumableId, quantity)));
    }
}
