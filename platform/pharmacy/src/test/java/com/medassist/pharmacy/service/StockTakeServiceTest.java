package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.StockTake;
import com.medassist.pharmacy.domain.PharmacyModel.StockTakeItem;
import com.medassist.pharmacy.domain.PharmacyModel.StockTakeSource;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.StockTakeDtos.CancelStockTakeReq;
import com.medassist.pharmacy.web.StockTakeDtos.CountItemReq;
import com.medassist.pharmacy.web.StockTakeDtos.CreateStockTakeReq;
import com.medassist.pharmacy.web.StockTakeDtos.StockTakeDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 药品盘点：单活约束、差异原因、提交完整性、四眼审批、库存漂移与盘盈盘亏校准。
 */
class StockTakeServiceTest {

    private PharmacyRepository repo;
    private StockTakeService service;

    private final UUID takeId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID drugId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        service = new StockTakeService(repo, JsonMapper.builder().build());
        when(repo.findStockTake(any())).thenReturn(Optional.of(take(StockTake.COUNTING, 0L, "pharm01")));
        when(repo.listStockTakeItems(any())).thenReturn(List.of());
        when(repo.listStockAdjustments(any())).thenReturn(List.of());
    }

    private StockTake take(String status, long version, String createdBy) {
        return new StockTake(takeId, "ST20261103000001", status, "全库批次", null, createdBy,
                null, null, null, null, null, null, null, version, Instant.now());
    }

    private StockTakeItem item(Integer actual, long version) {
        return new StockTakeItem(itemId, takeId, drugId, "D001", "阿莫西林胶囊", "0.25g*24粒",
                "盒", batchId, "B2026A", LocalDate.of(2027, 6, 30), 10, actual,
                actual == null ? null : "盘亏（破损）", actual == null ? null : "pharm01",
                actual == null ? null : Instant.now(), version);
    }

    private StockTakeSource source(UUID batch, int quantity) {
        return new StockTakeSource(drugId, "D001", "阿莫西林胶囊", "0.25g*24粒", "盒",
                batch, "B2026A", LocalDate.of(2027, 6, 30), quantity);
    }

    private DrugBatch batch(int quantity) {
        return new DrugBatch(batchId, drugId, "D001", "阿莫西林胶囊", "B2026A",
                LocalDate.of(2027, 6, 30), quantity, "国药", 5L);
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    // ---------------------------------------------------------------- 建单

    @Test
    @DisplayName("建单：已有进行中的盘点单 → 409，不插入新单")
    void createRejectsWhenActiveExists() {
        when(repo.findActiveStockTake()).thenReturn(Optional.of(take(StockTake.COUNTING, 0L, "pharm01")));

        assertCode(() -> service.create(new CreateStockTakeReq(null, null), "pharm02"),
                ErrorCode.STOCK_TAKE_ACTIVE_EXISTS);
        verify(repo, never()).insertStockTake(any());
    }

    @Test
    @DisplayName("建单：没有可盘点批次 → 422")
    void createRejectsWhenNoStock() {
        when(repo.findActiveStockTake()).thenReturn(Optional.empty());
        when(repo.listStockTakeSources(null)).thenReturn(List.of());

        assertCode(() -> service.create(new CreateStockTakeReq(null, null), "pharm01"),
                ErrorCode.STOCK_TAKE_EMPTY);
        verify(repo, never()).insertStockTake(any());
    }

    @Test
    @DisplayName("建单：按批次快照账面数量并写审计")
    void createSnapshotsBatches() {
        UUID batch2 = UUID.randomUUID();
        when(repo.findActiveStockTake()).thenReturn(Optional.empty());
        when(repo.listStockTakeSources("阿莫西林"))
                .thenReturn(List.of(source(batchId, 10), source(batch2, 6)));
        when(repo.nextStockTakeNo()).thenReturn("ST20261103000001");

        StockTakeDetail detail = service.create(new CreateStockTakeReq("阿莫西林", "月度盘点"), "pharm01");

        assertThat(detail.take().status()).isEqualTo(StockTake.COUNTING);
        verify(repo).insertStockTake(any(StockTake.class));
        // 建单 ID 由服务端生成：按批次与账面数量断言快照内容
        verify(repo).insertStockTakeItem(any(), any(), eq(drugId), eq(batchId), eq(10));
        verify(repo).insertStockTakeItem(any(), any(), eq(drugId), eq(batch2), eq(6));
        verify(repo).logChange(eq("pharm01"), eq("STOCK_TAKE_CREATE"), eq("STOCK_TAKE"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("建单：并发撞唯一索引 → 409，不做二次查库")
    void createHandlesConcurrentInsert() {
        when(repo.findActiveStockTake()).thenReturn(Optional.empty());
        when(repo.listStockTakeSources(null)).thenReturn(List.of(source(batchId, 10)));
        doThrow(new DuplicateKeyException("dup")).when(repo).insertStockTake(any());

        assertCode(() -> service.create(new CreateStockTakeReq(null, null), "pharm01"),
                ErrorCode.STOCK_TAKE_ACTIVE_EXISTS);
        verify(repo, never()).insertStockTakeItem(any(), any(), any(), any(), anyInt());
    }

    // ---------------------------------------------------------------- 实盘

    @Test
    @DisplayName("实盘：非 COUNTING 状态 → 409")
    void countRequiresCountingStatus() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.SUBMITTED, 3L, "pharm01")));

        assertCode(() -> service.count(takeId, itemId, new CountItemReq(9, "盘亏", 0L), "pharm01"),
                ErrorCode.STOCK_TAKE_STATUS_INVALID);
    }

    @Test
    @DisplayName("实盘：数量与账面不一致但未填原因 → 422")
    void countRequiresReasonForVariance() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 0L, "pharm01")));
        when(repo.listStockTakeItems(takeId)).thenReturn(List.of(item(null, 0L)));

        assertCode(() -> service.count(takeId, itemId, new CountItemReq(9, null, 0L), "pharm01"),
                ErrorCode.STOCK_TAKE_VARIANCE_REASON_REQUIRED);
        verify(repo, never()).countStockTakeItem(any(), any(), anyInt(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("实盘：无差异可不填原因；CAS 用明细版本号")
    void countWithoutVarianceNeedsNoReason() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 0L, "pharm01")));
        when(repo.listStockTakeItems(takeId)).thenReturn(List.of(item(null, 4L)));
        when(repo.countStockTakeItem(itemId, takeId, 10, null, "pharm01", 4L)).thenReturn(true);

        service.count(takeId, itemId, new CountItemReq(10, null, 4L), "pharm01");

        verify(repo).countStockTakeItem(itemId, takeId, 10, null, "pharm01", 4L);
        verify(repo).logChange(eq("pharm01"), eq("STOCK_TAKE_COUNT"), eq("STOCK_TAKE"),
                eq(takeId.toString()), anyString());
    }

    @Test
    @DisplayName("实盘：版本冲突 → 409")
    void countCasConflict() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 0L, "pharm01")));
        when(repo.listStockTakeItems(takeId)).thenReturn(List.of(item(null, 4L)));
        when(repo.countStockTakeItem(any(), any(), anyInt(), any(), anyString(), anyLong()))
                .thenReturn(false);

        assertCode(() -> service.count(takeId, itemId, new CountItemReq(9, "盘亏", 4L), "pharm01"),
                ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("实盘：明细不属于该盘点单 → 404")
    void countRejectsUnknownItem() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 0L, "pharm01")));
        when(repo.listStockTakeItems(takeId)).thenReturn(List.of());

        assertCode(() -> service.count(takeId, itemId, new CountItemReq(9, "盘亏", 0L), "pharm01"),
                ErrorCode.STOCK_TAKE_ITEM_NOT_FOUND);
    }

    // ---------------------------------------------------------------- 提交

    @Test
    @DisplayName("提交：仍有未录入批次 → 422")
    void submitRequiresAllCounted() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 2L, "pharm01")));
        when(repo.countUncountedItems(takeId)).thenReturn(3);

        assertCode(() -> service.submit(takeId, "pharm01"), ErrorCode.STOCK_TAKE_INCOMPLETE);
        verify(repo, never()).submitStockTake(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("提交：全部录入后置 SUBMITTED 并写审计")
    void submitSucceeds() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 2L, "pharm01")));
        when(repo.countUncountedItems(takeId)).thenReturn(0);
        when(repo.submitStockTake(takeId, 2L, "pharm01")).thenReturn(true);

        service.submit(takeId, "pharm01");

        verify(repo).submitStockTake(takeId, 2L, "pharm01");
        verify(repo).logChange(eq("pharm01"), eq("STOCK_TAKE_SUBMIT"), eq("STOCK_TAKE"),
                eq(takeId.toString()), anyString());
    }

    // ---------------------------------------------------------------- 审批

    @Test
    @DisplayName("审批：非 SUBMITTED 状态 → 409")
    void approveRequiresSubmitted() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 5L, "pharm01")));

        assertCode(() -> service.approve(takeId, "pharm02"), ErrorCode.STOCK_TAKE_STATUS_INVALID);
    }

    @Test
    @DisplayName("审批：建单人不能审批自己的盘点单 → 403（四眼原则）")
    void approveRejectsSelf() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.SUBMITTED, 5L, "pharm01")));

        assertCode(() -> service.approve(takeId, "pharm01"), ErrorCode.STOCK_TAKE_SELF_APPROVAL);
        verify(repo, never()).calibrateBatchQuantity(any(), anyInt(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("审批：盘点期间批次数量已变化 → 409，整单不校准")
    void approveRejectsStockDrift() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.SUBMITTED, 5L, "pharm01")));
        when(repo.listStockTakeItems(takeId)).thenReturn(List.of(item(8, 1L)));
        when(repo.lockBatch(batchId)).thenReturn(Optional.of(batch(9)));

        assertCode(() -> service.approve(takeId, "pharm02"), ErrorCode.STOCK_TAKE_STOCK_DRIFT);
        verify(repo, never()).calibrateBatchQuantity(any(), anyInt(), anyInt(), anyLong());
        verify(repo, never()).insertStockAdjustment(any());
        verify(repo, never()).approveStockTake(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("审批：盘盈盘亏分别校准并生成调整流水，差异为 0 的批次跳过")
    void approveCalibratesVariances() {
        UUID item2 = UUID.randomUUID();
        UUID batch2 = UUID.randomUUID();
        StockTakeItem loss = item(8, 1L);
        StockTakeItem gain = new StockTakeItem(item2, takeId, drugId, "D002", "布洛芬", "0.2g*20片",
                "盒", batch2, "B2026B", LocalDate.of(2027, 3, 1), 5, 7, "盘盈（拆零归集）",
                "pharm01", Instant.now(), 1L);
        StockTakeItem same = new StockTakeItem(UUID.randomUUID(), takeId, drugId, "D003", "维生素C",
                "100片", "瓶", UUID.randomUUID(), "B2026C", LocalDate.of(2027, 1, 1), 3, 3,
                null, "pharm01", Instant.now(), 1L);
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.SUBMITTED, 5L, "pharm01")));
        when(repo.listStockTakeItems(takeId)).thenReturn(List.of(loss, gain, same));
        when(repo.lockBatch(batchId)).thenReturn(Optional.of(batch(10)));
        when(repo.lockBatch(batch2)).thenReturn(Optional.of(new DrugBatch(batch2, drugId, "D002",
                "布洛芬", "B2026B", LocalDate.of(2027, 3, 1), 5, "国药", 2L)));
        when(repo.calibrateBatchQuantity(any(), anyInt(), anyInt(), anyLong())).thenReturn(true);
        when(repo.approveStockTake(takeId, 5L, "pharm02")).thenReturn(true);

        service.approve(takeId, "pharm02");

        verify(repo).calibrateBatchQuantity(batchId, 10, 8, 5L);
        verify(repo).calibrateBatchQuantity(batch2, 5, 7, 2L);
        verify(repo, org.mockito.Mockito.times(2)).insertStockAdjustment(any());
        verify(repo).approveStockTake(takeId, 5L, "pharm02");
        verify(repo).logChange(eq("pharm02"), eq("STOCK_TAKE_APPROVE"), eq("STOCK_TAKE"),
                eq(takeId.toString()), anyString());
    }

    @Test
    @DisplayName("审批：校准 CAS 失败 → 409，不标记审批通过")
    void approveRejectsCalibrationConflict() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.SUBMITTED, 5L, "pharm01")));
        when(repo.listStockTakeItems(takeId)).thenReturn(List.of(item(8, 1L)));
        when(repo.lockBatch(batchId)).thenReturn(Optional.of(batch(10)));
        when(repo.calibrateBatchQuantity(any(), anyInt(), anyInt(), anyLong())).thenReturn(false);

        assertCode(() -> service.approve(takeId, "pharm02"), ErrorCode.STOCK_TAKE_STOCK_DRIFT);
        verify(repo, never()).insertStockAdjustment(any());
        verify(repo, never()).approveStockTake(any(), anyLong(), anyString());
    }

    // ---------------------------------------------------------------- 取消/列表

    @Test
    @DisplayName("取消：必须写原因；已审批不可取消")
    void cancelRules() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 2L, "pharm01")));
        assertCode(() -> service.cancel(takeId, new CancelStockTakeReq(" "), "pharm01"),
                ErrorCode.VALIDATION_FAILED);

        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.APPROVED, 9L, "pharm01")));
        assertCode(() -> service.cancel(takeId, new CancelStockTakeReq("误建"), "pharm01"),
                ErrorCode.STOCK_TAKE_STATUS_INVALID);
    }

    @Test
    @DisplayName("取消：COUNTING 可取消并写审计")
    void cancelSucceeds() {
        when(repo.lockStockTake(takeId)).thenReturn(Optional.of(take(StockTake.COUNTING, 2L, "pharm01")));
        when(repo.cancelStockTake(takeId, 2L, "pharm01", "误建")).thenReturn(true);

        service.cancel(takeId, new CancelStockTakeReq("误建"), "pharm01");

        verify(repo).cancelStockTake(takeId, 2L, "pharm01", "误建");
        verify(repo).logChange(eq("pharm01"), eq("STOCK_TAKE_CANCEL"), eq("STOCK_TAKE"),
                eq(takeId.toString()), anyString());
    }

    @Test
    @DisplayName("列表：非法状态拒绝，limit 上限 200")
    void listNormalizes() {
        assertCode(() -> service.list("UNKNOWN", 10), ErrorCode.VALIDATION_FAILED);
        service.list("approved", 9999);
        verify(repo).listStockTakes("APPROVED", 200);
    }
}
