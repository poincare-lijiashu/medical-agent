package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.domain.MaterialModel.DeptConsumableStock;
import com.medassist.material.domain.MaterialModel.DeptTransfer;
import com.medassist.material.domain.MaterialModel.DeptTransferItem;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialTransferDtos.CreateDeptTransferReq;
import com.medassist.material.web.MaterialTransferDtos.TransferLine;
import com.medassist.material.web.MaterialTransferDtos.TransferReasonReq;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 耗材跨科室调拨：同科室拒绝、重复批次、源库余额预校验、自接收拦截、接收原子搬库。
 */
class DeptTransferServiceTest {

    private MaterialRepository repo;
    private DeptTransferService service;

    private final UUID fromDeptId = UUID.randomUUID();
    private final UUID toDeptId = UUID.randomUUID();
    private final UUID consumableId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final UUID transferId = UUID.randomUUID();

    private final ConsumableBatch batch = new ConsumableBatch(batchId, consumableId, "CONS-SYR-5ML",
            "一次性使用无菌注射器", "B-1", LocalDate.now().plusDays(300), 10, null,
            new BigDecimal("0.80"), 0L);

    private final DeptTransferItem item = new DeptTransferItem(UUID.randomUUID(), transferId,
            consumableId, batch.consumableCode(), batch.consumableName(), null, null, batchId,
            batch.batchNo(), batch.expireDate(), 4);

    private final DeptTransfer submitted = new DeptTransfer(transferId, "DT20261104000001",
            DeptTransfer.SUBMITTED, fromDeptId, toDeptId, "术间临时调拨", null, "nurse01",
            null, null, null, null, null, null, null, null, 0L, Instant.now());

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        service = new DeptTransferService(repo, JsonMapper.builder().build());
        when(repo.nextDeptTransferNo()).thenReturn("DT20261104000001");
        when(repo.findBatch(batchId)).thenReturn(Optional.of(batch));
        when(repo.findDeptTransfer(transferId)).thenReturn(Optional.of(submitted));
        when(repo.listDeptTransferItems(transferId)).thenReturn(List.of(item));
    }

    @Test
    @DisplayName("建单：调出与调入科室相同 → 422")
    void createRejectsSameDept() {
        CreateDeptTransferReq req = new CreateDeptTransferReq(fromDeptId, fromDeptId,
                "重复科室", null, List.of(new TransferLine(batchId, 1)));

        assertThatThrownBy(() -> service.create(req, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_SAME_DEPT);
        verify(repo, never()).insertDeptTransfer(any(), any());
    }

    @Test
    @DisplayName("建单：院区由调用方固化（G50）")
    void createStoresCampus() {
        UUID campusId = UUID.randomUUID();
        when(repo.findDeptConsumableStock(fromDeptId, consumableId, batchId))
                .thenReturn(Optional.of(stock(fromDeptId, 10)));
        when(repo.findDeptTransfer(any())).thenReturn(Optional.of(submitted));
        when(repo.listDeptTransferItems(any())).thenReturn(List.of());

        service.create(request(1), campusId, "nurse01");

        verify(repo).insertDeptTransfer(any(DeptTransfer.class), eq(campusId));
    }

    @Test
    @DisplayName("建单：同一批次重复出现 → 422")
    void createRejectsDuplicateBatch() {
        CreateDeptTransferReq req = new CreateDeptTransferReq(fromDeptId, toDeptId,
                "重复批次", null,
                List.of(new TransferLine(batchId, 1), new TransferLine(batchId, 2)));

        assertThatThrownBy(() -> service.create(req, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_ITEM_INVALID);
    }

    @Test
    @DisplayName("建单：超过调出科室余量 → 409")
    void createRejectsInsufficientSource() {
        when(repo.findDeptConsumableStock(fromDeptId, consumableId, batchId))
                .thenReturn(Optional.of(stock(fromDeptId, 3)));

        assertThatThrownBy(() -> service.create(request(4), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_STOCK_INSUFFICIENT);
        verify(repo, never()).insertDeptTransfer(any(), any());
    }

    @Test
    @DisplayName("建单：批次不存在 → 404")
    void createRejectsUnknownBatch() {
        when(repo.findBatch(batchId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(request(1), "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_BATCH_NOT_FOUND);
    }

    @Test
    @DisplayName("建单：余量足够时落单但不扣库存")
    void createPersistsWithoutStockMovement() {
        when(repo.findDeptConsumableStock(fromDeptId, consumableId, batchId))
                .thenReturn(Optional.of(stock(fromDeptId, 10)));
        when(repo.findDeptTransfer(any())).thenReturn(Optional.of(submitted));
        when(repo.listDeptTransferItems(any())).thenReturn(List.of());

        var view = service.create(request(4), "nurse01");

        assertThat(view.deptTransfer().status()).isEqualTo(DeptTransfer.SUBMITTED);
        verify(repo).insertDeptTransfer(any(), any());
        verify(repo).insertDeptTransferItem(any());
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
        verify(repo, never()).upsertDeptConsumableStock(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("接收：申请人自己接收 → 403")
    void acceptRejectsSelf() {
        when(repo.lockDeptTransfer(transferId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.accept(transferId, "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_SELF_ACCEPT);
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("接收：扣调出科室库并加调入科室库")
    void acceptMovesStock() {
        when(repo.lockDeptTransfer(transferId)).thenReturn(Optional.of(submitted));
        when(repo.deductDeptConsumableStock(fromDeptId, consumableId, batchId, 4)).thenReturn(true);
        when(repo.acceptDeptTransfer(transferId, 0L, "nurse02")).thenReturn(true);
        DeptTransfer accepted = new DeptTransfer(transferId, submitted.transferNo(),
                DeptTransfer.ACCEPTED, fromDeptId, toDeptId, submitted.reason(), null,
                "nurse01", "nurse02", Instant.now(), null, null, null, null, null, null,
                1L, submitted.createdAt());
        when(repo.findDeptTransfer(transferId)).thenReturn(Optional.of(accepted));

        var view = service.accept(transferId, "nurse02");

        assertThat(view.deptTransfer().status()).isEqualTo(DeptTransfer.ACCEPTED);
        verify(repo).deductDeptConsumableStock(fromDeptId, consumableId, batchId, 4);
        verify(repo).upsertDeptConsumableStock(toDeptId, consumableId, batchId, 4);
        verify(repo).acceptDeptTransfer(transferId, 0L, "nurse02");
    }

    @Test
    @DisplayName("接收：调出科室余量在接收时不足 → 409（回滚）")
    void acceptRejectsWhenSourceLostRace() {
        when(repo.lockDeptTransfer(transferId)).thenReturn(Optional.of(submitted));
        when(repo.deductDeptConsumableStock(fromDeptId, consumableId, batchId, 4))
                .thenReturn(false);

        assertThatThrownBy(() -> service.accept(transferId, "nurse02"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_STOCK_INSUFFICIENT);
        verify(repo, never()).upsertDeptConsumableStock(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("接收：已关闭的单不能再接收 → 409")
    void acceptRejectsClosed() {
        DeptTransfer accepted = new DeptTransfer(transferId, submitted.transferNo(),
                DeptTransfer.ACCEPTED, fromDeptId, toDeptId, submitted.reason(), null,
                "nurse01", "nurse02", Instant.now(), null, null, null, null, null, null,
                1L, submitted.createdAt());
        when(repo.lockDeptTransfer(transferId)).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.accept(transferId, "nurse03"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_STATUS_INVALID);
    }

    @Test
    @DisplayName("拒收：必须写原因")
    void rejectRequiresReason() {
        when(repo.lockDeptTransfer(transferId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.reject(transferId, new TransferReasonReq(" "),
                "nurse02"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_REJECT_REASON_REQUIRED);
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("拒收：不动库存")
    void rejectKeepsStock() {
        when(repo.lockDeptTransfer(transferId)).thenReturn(Optional.of(submitted));
        when(repo.rejectDeptTransfer(transferId, 0L, "nurse02", "包装破损")).thenReturn(true);
        DeptTransfer rejected = new DeptTransfer(transferId, submitted.transferNo(),
                DeptTransfer.REJECTED, fromDeptId, toDeptId, submitted.reason(), null,
                "nurse01", null, null, "nurse02", Instant.now(), "包装破损", null, null,
                null, 1L, submitted.createdAt());
        when(repo.findDeptTransfer(transferId)).thenReturn(Optional.of(rejected));

        var view = service.reject(transferId, new TransferReasonReq("包装破损"), "nurse02");

        assertThat(view.deptTransfer().status()).isEqualTo(DeptTransfer.REJECTED);
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
        verify(repo, never()).upsertDeptConsumableStock(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("撤回：必须写原因，且不动库存")
    void cancelRequiresReasonAndKeepsStock() {
        when(repo.lockDeptTransfer(transferId)).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.cancel(transferId, new TransferReasonReq(""),
                "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DEPT_TRANSFER_CANCEL_REASON_REQUIRED);

        when(repo.cancelDeptTransfer(transferId, 0L, "nurse01", "重复建单")).thenReturn(true);
        DeptTransfer cancelled = new DeptTransfer(transferId, submitted.transferNo(),
                DeptTransfer.CANCELLED, fromDeptId, toDeptId, submitted.reason(), null,
                "nurse01", null, null, null, null, null, "nurse01", Instant.now(),
                "重复建单", 1L, submitted.createdAt());
        when(repo.findDeptTransfer(transferId)).thenReturn(Optional.of(cancelled));

        var view = service.cancel(transferId, new TransferReasonReq("重复建单"), "nurse01");

        assertThat(view.deptTransfer().status()).isEqualTo(DeptTransfer.CANCELLED);
        verify(repo, never()).deductDeptConsumableStock(any(), any(), any(), anyInt());
        verify(repo, never()).upsertDeptConsumableStock(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("列表：未知状态 → 422")
    void listRejectsUnknownStatus() {
        assertThatThrownBy(() -> service.list("DONE", null, 10))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    private CreateDeptTransferReq request(int quantity) {
        return new CreateDeptTransferReq(fromDeptId, toDeptId, "术间临时调拨", null,
                List.of(new TransferLine(batchId, quantity)));
    }

    private DeptConsumableStock stock(UUID deptId, int quantity) {
        return new DeptConsumableStock(UUID.randomUUID(), deptId, consumableId,
                batch.consumableCode(), batch.consumableName(), "5ml 带针", "支", batchId,
                batch.batchNo(), batch.expireDate(), quantity, 0L);
    }
}
