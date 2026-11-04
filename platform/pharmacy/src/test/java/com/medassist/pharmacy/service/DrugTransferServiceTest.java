package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.DeptDrugStock;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransfer;
import com.medassist.pharmacy.domain.PharmacyModel.DrugTransferItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.DrugTransferDtos.CreateDrugTransferReq;
import com.medassist.pharmacy.web.DrugTransferDtos.DrugTransferView;
import com.medassist.pharmacy.web.DrugTransferDtos.TransferLine;
import com.medassist.pharmacy.web.DrugTransferDtos.TransferReasonReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

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
 * 药品科室调拨：重复批次、超量、发出扣库、接收入二级库、拒收退回、状态机与权限边界。
 */
class DrugTransferServiceTest {

    private PharmacyRepository repo;
    private DrugTransferService service;

    private final UUID transferId = UUID.randomUUID();
    private final UUID deptId = UUID.randomUUID();
    private final UUID drugId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final UUID batch2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        service = new DrugTransferService(repo, JsonMapper.builder().build());
        when(repo.findDrugTransfer(any())).thenReturn(Optional.of(transfer(DrugTransfer.SUBMITTED, 0L)));
        when(repo.listDrugTransferItems(any())).thenReturn(List.of());
    }

    private DrugTransfer transfer(String status, long version) {
        return new DrugTransfer(transferId, "TR20261104000001", status, deptId, "备货",
                "pharm01", null, null, null, null, null, null, null, null, null, null,
                version, Instant.now());
    }

    private DrugTransferItem item(UUID batch, int quantity) {
        return new DrugTransferItem(UUID.randomUUID(), transferId, drugId, "D001", "阿莫西林胶囊",
                "0.25g*24粒", "盒", batch, batch.equals(batchId) ? "B2026A" : "B2026B",
                LocalDate.of(2027, 6, 30), quantity);
    }

    private DrugBatch batch(UUID id, int quantity) {
        return new DrugBatch(id, drugId, "D001", "阿莫西林胶囊",
                id.equals(batchId) ? "B2026A" : "B2026B", LocalDate.of(2027, 6, 30),
                quantity, "国药", 2L);
    }

    private CreateDrugTransferReq createReq(List<TransferLine> lines) {
        return new CreateDrugTransferReq(deptId, "病区备货", lines);
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    // ---------------------------------------------------------------- 建单

    @Test
    @DisplayName("建单：同一批次重复 → 422，不落库")
    void createRejectsDuplicateBatch() {
        assertCode(() -> service.create(createReq(List.of(
                new TransferLine(batchId, 2), new TransferLine(batchId, 1))), "pharm01"),
                ErrorCode.DRUG_TRANSFER_ITEM_INVALID);
        verify(repo, never()).insertDrugTransfer(any());
    }

    @Test
    @DisplayName("建单：批次不存在 → 404")
    void createRejectsUnknownBatch() {
        when(repo.findBatch(batchId)).thenReturn(Optional.empty());

        assertCode(() -> service.create(createReq(List.of(new TransferLine(batchId, 1))), "pharm01"),
                ErrorCode.DRUG_TRANSFER_BATCH_NOT_FOUND);
        verify(repo, never()).insertDrugTransfer(any());
    }

    @Test
    @DisplayName("建单：超过当前余量 → 422")
    void createRejectsExceedingQuantity() {
        when(repo.findBatch(batchId)).thenReturn(Optional.of(batch(batchId, 3)));

        assertCode(() -> service.create(createReq(List.of(new TransferLine(batchId, 4))), "pharm01"),
                ErrorCode.DRUG_TRANSFER_EXCEEDS);
        verify(repo, never()).insertDrugTransfer(any());
    }

    @Test
    @DisplayName("建单：成功并写审计")
    void createSucceeds() {
        when(repo.findBatch(batchId)).thenReturn(Optional.of(batch(batchId, 10)));
        when(repo.findBatch(batch2)).thenReturn(Optional.of(batch(batch2, 8)));
        when(repo.nextDrugTransferNo()).thenReturn("TR20261104000002");

        DrugTransferView view = service.create(createReq(List.of(
                new TransferLine(batchId, 2), new TransferLine(batch2, 1))), "pharm01");

        assertThat(view.transfer().status()).isEqualTo(DrugTransfer.SUBMITTED);
        verify(repo).insertDrugTransfer(any(DrugTransfer.class));
        verify(repo).insertDrugTransferItem(any(), any(), eq(drugId), eq(batchId), eq(2));
        verify(repo).insertDrugTransferItem(any(), any(), eq(drugId), eq(batch2), eq(1));
        verify(repo).logChange(eq("pharm01"), eq("DRUG_TRANSFER_CREATE"), eq("DRUG_TRANSFER"),
                anyString(), anyString());
    }

    // ---------------------------------------------------------------- 发出

    @Test
    @DisplayName("发出：非 SUBMITTED → 409")
    void dispatchRequiresSubmitted() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.ACCEPTED, 4L)));

        assertCode(() -> service.dispatch(transferId, "pharm01"),
                ErrorCode.DRUG_TRANSFER_STATUS_INVALID);
    }

    @Test
    @DisplayName("发出：批次余量不足 → 409，不更新状态")
    void dispatchRejectsInsufficientStock() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.SUBMITTED, 0L)));
        when(repo.listDrugTransferItems(transferId)).thenReturn(List.of(item(batchId, 5)));
        when(repo.deductBatch(batchId, 5)).thenReturn(false);

        assertCode(() -> service.dispatch(transferId, "pharm01"),
                ErrorCode.DRUG_TRANSFER_STOCK_INSUFFICIENT);
        verify(repo, never()).dispatchDrugTransfer(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("发出：逐批原子扣减并 CAS 置 DISPATCHED")
    void dispatchDeductsAndMovesState() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.SUBMITTED, 0L)));
        when(repo.listDrugTransferItems(transferId))
                .thenReturn(List.of(item(batchId, 2), item(batch2, 1)));
        when(repo.deductBatch(any(), anyInt())).thenReturn(true);
        when(repo.dispatchDrugTransfer(transferId, 0L, "pharm01")).thenReturn(true);
        when(repo.findDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.DISPATCHED, 1L)));

        DrugTransferView view = service.dispatch(transferId, "pharm01");

        assertThat(view.transfer().status()).isEqualTo(DrugTransfer.DISPATCHED);
        verify(repo).deductBatch(batchId, 2);
        verify(repo).deductBatch(batch2, 1);
        verify(repo).logChange(eq("pharm01"), eq("DRUG_TRANSFER_DISPATCH"), eq("DRUG_TRANSFER"),
                eq(transferId.toString()), anyString());
    }

    @Test
    @DisplayName("发出：状态 CAS 失败 → 409")
    void dispatchCasConflict() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.SUBMITTED, 0L)));
        when(repo.listDrugTransferItems(transferId)).thenReturn(List.of());
        when(repo.dispatchDrugTransfer(any(), anyLong(), anyString())).thenReturn(false);

        assertCode(() -> service.dispatch(transferId, "pharm01"), ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 接收

    @Test
    @DisplayName("接收：非 DISPATCHED → 409")
    void acceptRequiresDispatched() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.SUBMITTED, 0L)));

        assertCode(() -> service.accept(transferId, "nurse01"),
                ErrorCode.DRUG_TRANSFER_STATUS_INVALID);
        verify(repo, never()).upsertDeptDrugStock(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("接收：逐批入科室二级库并 CAS 置 ACCEPTED")
    void acceptUpsertsDeptStock() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.DISPATCHED, 1L)));
        when(repo.listDrugTransferItems(transferId))
                .thenReturn(List.of(item(batchId, 2), item(batch2, 1)));
        when(repo.acceptDrugTransfer(transferId, 1L, "nurse01")).thenReturn(true);
        when(repo.findDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.ACCEPTED, 2L)));

        DrugTransferView view = service.accept(transferId, "nurse01");

        assertThat(view.transfer().status()).isEqualTo(DrugTransfer.ACCEPTED);
        verify(repo).upsertDeptDrugStock(deptId, drugId, batchId, 2);
        verify(repo).upsertDeptDrugStock(deptId, drugId, batch2, 1);
        verify(repo).logChange(eq("nurse01"), eq("DRUG_TRANSFER_ACCEPT"), eq("DRUG_TRANSFER"),
                eq(transferId.toString()), anyString());
    }

    @Test
    @DisplayName("接收：CAS 失败 → 409")
    void acceptCasConflict() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.DISPATCHED, 1L)));
        when(repo.listDrugTransferItems(transferId)).thenReturn(List.of());
        when(repo.acceptDrugTransfer(any(), anyLong(), anyString())).thenReturn(false);

        assertCode(() -> service.accept(transferId, "nurse01"), ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 拒收/撤回

    @Test
    @DisplayName("拒收：必须写原因")
    void rejectRequiresReason() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.DISPATCHED, 1L)));

        assertCode(() -> service.reject(transferId, new TransferReasonReq(" "), "nurse01"),
                ErrorCode.DRUG_TRANSFER_REJECT_REASON_REQUIRED);
        verify(repo, never()).rejectDrugTransfer(any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("拒收：状态置 REJECTED 并把库存退回中心批次")
    void rejectReturnsStock() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.DISPATCHED, 1L)));
        when(repo.listDrugTransferItems(transferId))
                .thenReturn(List.of(item(batchId, 2), item(batch2, 1)));
        when(repo.rejectDrugTransfer(transferId, 1L, "nurse01", "药品破损")).thenReturn(true);
        when(repo.findDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.REJECTED, 2L)));

        DrugTransferView view = service.reject(transferId, new TransferReasonReq("药品破损"), "nurse01");

        assertThat(view.transfer().status()).isEqualTo(DrugTransfer.REJECTED);
        verify(repo).addBatchQuantity(batchId, 2);
        verify(repo).addBatchQuantity(batch2, 1);
        verify(repo).logChange(eq("nurse01"), eq("DRUG_TRANSFER_REJECT"), eq("DRUG_TRANSFER"),
                eq(transferId.toString()), anyString());
    }

    @Test
    @DisplayName("撤回：必须写原因；仅 SUBMITTED 可撤回")
    void cancelRules() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.SUBMITTED, 0L)));
        assertCode(() -> service.cancel(transferId, new TransferReasonReq(null), "pharm01"),
                ErrorCode.DRUG_TRANSFER_CANCEL_REASON_REQUIRED);

        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.DISPATCHED, 1L)));
        assertCode(() -> service.cancel(transferId, new TransferReasonReq("重复建单"), "pharm01"),
                ErrorCode.DRUG_TRANSFER_STATUS_INVALID);
    }

    @Test
    @DisplayName("撤回：CAS 成功并写审计")
    void cancelSucceeds() {
        when(repo.lockDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.SUBMITTED, 0L)));
        when(repo.cancelDrugTransfer(transferId, 0L, "pharm01", "重复建单")).thenReturn(true);
        when(repo.findDrugTransfer(transferId))
                .thenReturn(Optional.of(transfer(DrugTransfer.CANCELLED, 1L)));

        service.cancel(transferId, new TransferReasonReq("重复建单"), "pharm01");

        verify(repo).cancelDrugTransfer(transferId, 0L, "pharm01", "重复建单");
        verify(repo).logChange(eq("pharm01"), eq("DRUG_TRANSFER_CANCEL"), eq("DRUG_TRANSFER"),
                eq(transferId.toString()), anyString());
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @DisplayName("列表：非法状态拒绝，limit 上限 200")
    void listNormalizes() {
        assertCode(() -> service.list("UNKNOWN", null, 10), ErrorCode.VALIDATION_FAILED);
        service.list("dispatched", deptId, 9999);
        verify(repo).listDrugTransfers("DISPATCHED", deptId, 200);
    }

    @Test
    @DisplayName("科室库存：必须指定科室，limit 上限 200")
    void deptStockRules() {
        assertCode(() -> service.deptStock(null, null, 10), ErrorCode.VALIDATION_FAILED);
        when(repo.listDeptDrugStock(deptId, "阿莫西林", 200))
                .thenReturn(List.of(new DeptDrugStock(UUID.randomUUID(), deptId, drugId, "D001",
                        "阿莫西林胶囊", "0.25g*24粒", "盒", batchId, "B2026A",
                        LocalDate.of(2027, 6, 30), 3, 1L)));
        assertThat(service.deptStock(deptId, "阿莫西林", 9999)).hasSize(1);
        verify(repo).listDeptDrugStock(deptId, "阿莫西林", 200);
    }

    @Test
    @DisplayName("详情：不存在 → 404")
    void detailNotFound() {
        when(repo.findDrugTransfer(transferId)).thenReturn(Optional.empty());

        assertCode(() -> service.detail(transferId), ErrorCode.DRUG_TRANSFER_NOT_FOUND);
    }
}
