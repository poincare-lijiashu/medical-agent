package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.domain.MaterialModel.ConsumableReturn;
import com.medassist.material.domain.MaterialModel.ConsumableReturnItem;
import com.medassist.material.domain.MaterialModel.DeptConsumableStock;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialReturnDtos.CreateReturnReq;
import com.medassist.material.web.MaterialReturnDtos.ReturnLine;
import com.medassist.material.web.MaterialReturnDtos.ReturnReasonReq;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 耗材未使用退回：预校验、四眼、审批扣科室库并回冲中心、驳回/撤回不动库存、状态机。
 */
class ConsumableReturnServiceTest {

    private MaterialRepository repo;
    private ConsumableReturnService service;

    private final UUID deptId = UUID.randomUUID();
    private final UUID consumableId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final UUID returnId = UUID.randomUUID();

    private final ConsumableBatch batch = new ConsumableBatch(batchId, consumableId, "CONS-SYR-5ML",
            "一次性使用无菌注射器", "B-1", LocalDate.now().plusDays(300), 10, null,
            new BigDecimal("0.80"), 0L);

    private final ConsumableReturn submitted = new ConsumableReturn(returnId, "MR20261104000001",
            ConsumableReturn.SUBMITTED, deptId, "术后剩余", "nurse01", null, null, null, null,
            null, null, 0L, Instant.now());

    private final ConsumableReturnItem item = new ConsumableReturnItem(UUID.randomUUID(), returnId,
            consumableId, batch.consumableCode(), batch.consumableName(), null, null, batchId,
            batch.batchNo(), batch.expireDate(), 3);

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        service = new ConsumableReturnService(repo, JsonMapper.builder().build());
        when(repo.nextReturnNo()).thenReturn("MR20261104000001");
        when(repo.findBatch(batchId)).thenReturn(Optional.of(batch));
        when(repo.findReturn(returnId)).thenReturn(Optional.of(submitted));
        when(repo.listReturnItems(returnId)).thenReturn(List.of(item));
    }

    @Test
    @DisplayName("建单：同一批次重复出现 → 422")
    void createRejectsDuplicateBatch() {
        CreateReturnReq req = new CreateReturnReq(deptId, "术后剩余",
                List.of(new ReturnLine(batchId, 1), new ReturnLine(batchId, 2)));

        assertThatThrownBy(() -> service.create(req, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_RETURN_ITEM_INVALID);
        verify(repo, never()).insertReturn(any(), any());
    }

    @Test
    @DisplayName("建单：院区由调用方固化（G50）")
    void createStoresCampus() {
        UUID campusId = UUID.randomUUID();
        when(repo.findDeptConsumableStock(deptId, consumableId, batchId))
                .thenReturn(Optional.of(stock(10)));
        when(repo.findReturn(any())).thenReturn(Optional.of(submitted));
        when(repo.listReturnItems(any())).thenReturn(List.of());

        service.create(request(1), campusId, "nurse01");

        verify(repo).insertReturn(any(ConsumableReturn.class), eq(campusId));
    }

    @Test
    @DisplayName("建单：超过科室二级库余量 → 409")
    void createRejectsWhenDeptStockInsufficient() {
        when(repo.findDeptConsumableStock(deptId, consumableId, batchId))
                .thenReturn(Optional.of(stock(2)));

        assertThatThrownBy(() -> service.create(request(3), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_DEPT_STOCK_INSUFFICIENT);
        verify(repo, never()).insertReturn(any(), any());
    }

    @Test
    @DisplayName("建单：批次不存在 → 404")
    void createRejectsUnknownBatch() {
        when(repo.findBatch(batchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(1), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_RETURN_BATCH_NOT_FOUND);
    }

    @Test
    @DisplayName("建单：余量足够时落单但不扣库存")
    void createPersistsWithoutStockMovement() {
        when(repo.findDeptConsumableStock(deptId, consumableId, batchId))
                .thenReturn(Optional.of(stock(10)));
        when(repo.findReturn(any())).thenReturn(Optional.of(submitted));
        when(repo.listReturnItems(any())).thenReturn(List.of());

        var view = service.create(request(5), "nurse01");

        assertThat(view.consumableReturn().status()).isEqualTo(ConsumableReturn.SUBMITTED);
        verify(repo).insertReturn(any(), any());
        verify(repo).insertReturnItem(any());
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
        verify(repo, never()).addBatchQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("审批通过：扣科室二级库并回冲中心批次")
    void approveDeductsDeptStockAndReplenishesCenter() {
        ConsumableReturn approved = new ConsumableReturn(returnId, submitted.returnNo(),
                ConsumableReturn.APPROVED, deptId, submitted.reason(), "nurse01", "head01",
                Instant.now(), "同意", null, null, null, 1L, submitted.createdAt());
        when(repo.lockReturn(returnId)).thenReturn(Optional.of(submitted));
        when(repo.deductDeptConsumableStock(deptId, consumableId, batchId, 3)).thenReturn(true);
        when(repo.reviewReturn(eq(returnId), eq(0L), eq("head01"), eq(true), any()))
                .thenReturn(true);
        when(repo.findReturn(returnId)).thenReturn(Optional.of(approved));

        var view = service.approve(returnId, null, "head01");

        assertThat(view.consumableReturn().status()).isEqualTo(ConsumableReturn.APPROVED);
        verify(repo).deductDeptConsumableStock(deptId, consumableId, batchId, 3);
        verify(repo).addBatchQuantity(batchId, 3);
    }

    @Test
    @DisplayName("审批：申请人自审 → 403")
    void approveRejectsSelfReview() {
        when(repo.lockReturn(returnId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.approve(returnId, null, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_RETURN_SELF_REVIEW);
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("审批：科室余量在审批时被先用掉 → 409（回滚）")
    void approveRejectsWhenDeptStockLostRace() {
        when(repo.lockReturn(returnId)).thenReturn(Optional.of(submitted));
        when(repo.deductDeptConsumableStock(deptId, consumableId, batchId, 3)).thenReturn(false);

        assertThatThrownBy(() -> service.approve(returnId, null, "head01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_DEPT_STOCK_INSUFFICIENT);
        verify(repo, never()).addBatchQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("审批：已关闭的单不能再次审批 → 409")
    void approveRejectsClosedReturn() {
        ConsumableReturn approved = new ConsumableReturn(returnId, submitted.returnNo(),
                ConsumableReturn.APPROVED, deptId, submitted.reason(), "nurse01", "head01",
                Instant.now(), null, null, null, null, 1L, submitted.createdAt());
        when(repo.lockReturn(returnId)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.approve(returnId, null, "head02"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_RETURN_STATUS_INVALID);
    }

    @Test
    @DisplayName("驳回：必须写原因")
    void rejectRequiresReason() {
        when(repo.lockReturn(returnId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.reject(returnId, new ReturnReasonReq(" "), "head01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_RETURN_REJECT_REASON_REQUIRED);
        verify(repo, never()).reviewReturn(any(), anyLong(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("驳回：不动库存，只记录原因")
    void rejectDoesNotMoveStock() {
        ConsumableReturn rejected = new ConsumableReturn(returnId, submitted.returnNo(),
                ConsumableReturn.REJECTED, deptId, submitted.reason(), "nurse01", "head01",
                Instant.now(), "包装已破损", null, null, null, 1L, submitted.createdAt());
        when(repo.lockReturn(returnId)).thenReturn(Optional.of(submitted));
        when(repo.reviewReturn(returnId, 0L, "head01", false, "包装已破损")).thenReturn(true);
        when(repo.findReturn(returnId)).thenReturn(Optional.of(rejected));

        var view = service.reject(returnId, new ReturnReasonReq("包装已破损"), "head01");

        assertThat(view.consumableReturn().status()).isEqualTo(ConsumableReturn.REJECTED);
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
        verify(repo, never()).addBatchQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("撤回：必须写原因，且不动库存")
    void cancelRequiresReasonAndKeepsStock() {
        when(repo.lockReturn(returnId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.cancel(returnId, new ReturnReasonReq(""), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_RETURN_CANCEL_REASON_REQUIRED);

        when(repo.cancelReturn(returnId, 0L, "nurse01", "重复建单")).thenReturn(true);
        ConsumableReturn cancelled = new ConsumableReturn(returnId, submitted.returnNo(),
                ConsumableReturn.CANCELLED, deptId, submitted.reason(), "nurse01", null, null,
                null, "nurse01", Instant.now(), "重复建单", 1L, submitted.createdAt());
        when(repo.findReturn(returnId)).thenReturn(Optional.of(cancelled));

        var view = service.cancel(returnId, new ReturnReasonReq("重复建单"), "nurse01");

        assertThat(view.consumableReturn().status()).isEqualTo(ConsumableReturn.CANCELLED);
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
        verify(repo, never()).addBatchQuantity(any(), anyInt());
    }

    @Test
    @DisplayName("撤回：已审批通过的单不能撤回 → 409")
    void cancelRejectsApprovedReturn() {
        ConsumableReturn approved = new ConsumableReturn(returnId, submitted.returnNo(),
                ConsumableReturn.APPROVED, deptId, submitted.reason(), "nurse01", "head01",
                Instant.now(), null, null, null, null, 1L, submitted.createdAt());
        when(repo.lockReturn(returnId)).thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> service.cancel(returnId, new ReturnReasonReq("不想退了"), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_RETURN_STATUS_INVALID);
    }

    @Test
    @DisplayName("列表：未知状态 → 422")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list("DONE", null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private CreateReturnReq request(int quantity) {
        return new CreateReturnReq(deptId, "术后剩余", List.of(new ReturnLine(batchId, quantity)));
    }

    private DeptConsumableStock stock(int quantity) {
        return new DeptConsumableStock(UUID.randomUUID(), deptId, consumableId,
                batch.consumableCode(), batch.consumableName(), "5ml 带针", "支", batchId,
                batch.batchNo(), batch.expireDate(), quantity, 0L);
    }
}
