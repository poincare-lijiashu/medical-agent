package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.ExpiredBatchRow;
import com.medassist.pharmacy.domain.PharmacyModel.StockLoss;
import com.medassist.pharmacy.domain.PharmacyModel.StockLossItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.StockLossDtos.AutoDraftResult;
import com.medassist.pharmacy.web.StockLossDtos.CreateStockLossReq;
import com.medassist.pharmacy.web.StockLossDtos.LossLine;
import com.medassist.pharmacy.web.StockLossDtos.ReviewStockLossReq;
import com.medassist.pharmacy.web.StockLossDtos.StockLossView;
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
 * 药品报损：重复批次、超量、四眼审批、驳回原因、审批扣减与调整流水。
 */
class StockLossServiceTest {

    private PharmacyRepository repo;
    private StockLossService service;

    private final UUID lossId = UUID.randomUUID();
    private final UUID drugId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID();
    private final UUID batch2 = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        service = new StockLossService(repo, JsonMapper.builder().build());
        when(repo.findStockLoss(any())).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 0L, "pharm01")));
        when(repo.listStockLossItems(any())).thenReturn(List.of());
        when(repo.listStockLossAdjustments(any())).thenReturn(List.of());
    }

    private StockLoss loss(String status, long version, String createdBy) {
        return new StockLoss(lossId, "LS20261103000001", status, StockLoss.BROKEN, "货架跌落",
                createdBy, null, null, null, version, Instant.now());
    }

    private StockLossItem item(UUID batch, int quantity) {
        return new StockLossItem(UUID.randomUUID(), lossId, drugId, "D001", "阿莫西林胶囊",
                "0.25g*24粒", "盒", batch, batch.equals(batchId) ? "B2026A" : "B2026B",
                LocalDate.of(2027, 6, 30), quantity, "破损");
    }

    private DrugBatch batch(UUID id, int quantity) {
        return new DrugBatch(id, drugId, "D001", "阿莫西林胶囊",
                id.equals(batchId) ? "B2026A" : "B2026B", LocalDate.of(2027, 6, 30),
                quantity, "国药", 3L);
    }

    private CreateStockLossReq createReq(List<LossLine> lines) {
        return new CreateStockLossReq(StockLoss.BROKEN, "货架跌落", lines);
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    // ---------------------------------------------------------------- 提交

    @Test
    @DisplayName("提交：同一批次重复出现 → 422，不落库")
    void createRejectsDuplicateBatch() {
        assertCode(() -> service.create(createReq(List.of(
                new LossLine(batchId, 2, null), new LossLine(batchId, 1, null))), "pharm01"),
                ErrorCode.STOCK_LOSS_ITEM_INVALID);
        verify(repo, never()).insertStockLoss(any());
    }

    @Test
    @DisplayName("提交：批次不存在 → 404")
    void createRejectsUnknownBatch() {
        when(repo.findBatch(batchId)).thenReturn(Optional.empty());

        assertCode(() -> service.create(createReq(List.of(new LossLine(batchId, 1, null))), "pharm01"),
                ErrorCode.STOCK_LOSS_BATCH_NOT_FOUND);
        verify(repo, never()).insertStockLoss(any());
    }

    @Test
    @DisplayName("提交：报损数量超过当前余量 → 422")
    void createRejectsExceedingQuantity() {
        when(repo.findBatch(batchId)).thenReturn(Optional.of(batch(batchId, 3)));

        assertCode(() -> service.create(createReq(List.of(new LossLine(batchId, 4, null))), "pharm01"),
                ErrorCode.STOCK_LOSS_EXCEEDS);
        verify(repo, never()).insertStockLoss(any());
    }

    @Test
    @DisplayName("提交：成功建单并按批次回填药品，写审计")
    void createSucceeds() {
        when(repo.findBatch(batchId)).thenReturn(Optional.of(batch(batchId, 10)));
        when(repo.findBatch(batch2)).thenReturn(Optional.of(batch(batch2, 8)));
        when(repo.nextStockLossNo()).thenReturn("LS20261103000002");

        StockLossView view = service.create(createReq(List.of(
                new LossLine(batchId, 2, "外包装破损"),
                new LossLine(batch2, 1, null))), "pharm01");

        assertThat(view.loss().status()).isEqualTo(StockLoss.SUBMITTED);
        verify(repo).insertStockLoss(any(StockLoss.class));
        verify(repo).insertStockLossItem(any(), any(), eq(drugId), eq(batchId), eq(2), eq("外包装破损"));
        verify(repo).insertStockLossItem(any(), any(), eq(drugId), eq(batch2), eq(1), eq(null));
        verify(repo).logChange(eq("pharm01"), eq("STOCK_LOSS_CREATE"), eq("STOCK_LOSS"),
                anyString(), anyString());
    }

    // ---------------------------------------------------------------- 审批

    @Test
    @DisplayName("审批：已审批的单不能重复处理 → 409")
    void reviewRejectsClosedLoss() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.APPROVED, 4L, "pharm01")));

        assertCode(() -> service.review(lossId, new ReviewStockLossReq(true, null), "pharm02"),
                ErrorCode.STOCK_LOSS_STATUS_INVALID);
    }

    @Test
    @DisplayName("审批：申请人不能自审 → 403")
    void reviewRejectsSelf() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 0L, "pharm01")));

        assertCode(() -> service.review(lossId, new ReviewStockLossReq(true, null), "pharm01"),
                ErrorCode.STOCK_LOSS_SELF_REVIEW);
        verify(repo, never()).deductBatch(any(), anyInt());
    }

    @Test
    @DisplayName("驳回：必须写原因；成功后不改库存")
    void rejectRequiresReason() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 0L, "pharm01")));

        assertCode(() -> service.review(lossId, new ReviewStockLossReq(false, " "), "pharm02"),
                ErrorCode.STOCK_LOSS_REJECT_REASON_REQUIRED);
        verify(repo, never()).reviewStockLoss(any(), anyLong(), eq(false), anyString(), any());
    }

    @Test
    @DisplayName("驳回：写原因并落 REJECTED")
    void rejectSucceeds() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 0L, "pharm01")));
        when(repo.reviewStockLoss(lossId, 0L, false, "pharm02", "数量与实物不符")).thenReturn(true);
        when(repo.findStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.REJECTED, 1L, "pharm01")));

        StockLossView view = service.review(lossId,
                new ReviewStockLossReq(false, "数量与实物不符"), "pharm02");

        assertThat(view.loss().status()).isEqualTo(StockLoss.REJECTED);
        verify(repo, never()).deductBatch(any(), anyInt());
        verify(repo).logChange(eq("pharm02"), eq("STOCK_LOSS_REJECT"), eq("STOCK_LOSS"),
                eq(lossId.toString()), anyString());
    }

    @Test
    @DisplayName("审批通过：逐批校验余量、原子扣减并写调整流水")
    void approveDeductsStock() {
        StockLossItem lossItem = item(batchId, 2);
        StockLossItem lossItem2 = item(batch2, 1);
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 0L, "pharm01")));
        when(repo.listStockLossItems(lossId)).thenReturn(List.of(lossItem, lossItem2));
        when(repo.lockBatch(batchId)).thenReturn(Optional.of(batch(batchId, 10)));
        when(repo.lockBatch(batch2)).thenReturn(Optional.of(batch(batch2, 8)));
        when(repo.deductBatch(any(), anyInt())).thenReturn(true);
        when(repo.reviewStockLoss(lossId, 0L, true, "pharm02", null)).thenReturn(true);
        when(repo.findStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.APPROVED, 1L, "pharm01")));

        StockLossView view = service.review(lossId, new ReviewStockLossReq(true, null), "pharm02");

        assertThat(view.loss().status()).isEqualTo(StockLoss.APPROVED);
        verify(repo).deductBatch(batchId, 2);
        verify(repo).deductBatch(batch2, 1);
        verify(repo).insertStockLossAdjustment(lossId, drugId, batchId, 10, 8, -2, "破损", "pharm02");
        verify(repo).insertStockLossAdjustment(lossId, drugId, batch2, 8, 7, -1, "破损", "pharm02");
        verify(repo).logChange(eq("pharm02"), eq("STOCK_LOSS_APPROVE"), eq("STOCK_LOSS"),
                eq(lossId.toString()), anyString());
    }

    @Test
    @DisplayName("审批通过：审批时余量不足 → 409，整单不扣减")
    void approveRejectsInsufficientStock() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 0L, "pharm01")));
        when(repo.listStockLossItems(lossId)).thenReturn(List.of(item(batchId, 5)));
        when(repo.lockBatch(batchId)).thenReturn(Optional.of(batch(batchId, 3)));

        assertCode(() -> service.review(lossId, new ReviewStockLossReq(true, null), "pharm02"),
                ErrorCode.STOCK_LOSS_STOCK_INSUFFICIENT);
        verify(repo, never()).deductBatch(any(), anyInt());
        verify(repo, never()).reviewStockLoss(any(), anyLong(), eq(true), anyString(), any());
    }

    @Test
    @DisplayName("审批通过：原子扣减失败（并发）→ 409，不写流水")
    void approveRejectsConcurrentDeduction() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 0L, "pharm01")));
        when(repo.listStockLossItems(lossId)).thenReturn(List.of(item(batchId, 2)));
        when(repo.lockBatch(batchId)).thenReturn(Optional.of(batch(batchId, 10)));
        when(repo.deductBatch(batchId, 2)).thenReturn(false);

        assertCode(() -> service.review(lossId, new ReviewStockLossReq(true, null), "pharm02"),
                ErrorCode.STOCK_LOSS_STOCK_INSUFFICIENT);
        verify(repo, never()).insertStockLossAdjustment(any(), any(), any(), anyInt(), anyInt(),
                anyInt(), any(), anyString());
    }

    @Test
    @DisplayName("审批通过：状态 CAS 失败 → 409")
    void approveCasConflict() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 0L, "pharm01")));
        when(repo.listStockLossItems(lossId)).thenReturn(List.of());
        when(repo.reviewStockLoss(any(), anyLong(), eq(true), anyString(), any())).thenReturn(false);

        assertCode(() -> service.review(lossId, new ReviewStockLossReq(true, null), "pharm02"),
                ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 效期自动草稿（G28）

    private ExpiredBatchRow expired(UUID batch, UUID drug, String batchNo, int quantity) {
        return new ExpiredBatchRow(batch, drug, drug.equals(drugId) ? "D001" : "D002",
                drug.equals(drugId) ? "阿莫西林胶囊" : "布洛芬缓释胶囊", batchNo,
                LocalDate.now().minusDays(3), quantity);
    }

    @Test
    @DisplayName("效期草稿：无过期批次 → 0 单 0 项，不建单")
    void autoDraftNoExpiredBatches() {
        when(repo.findExpiredBatchesForLoss(any(LocalDate.class), anyInt())).thenReturn(List.of());

        AutoDraftResult result = service.autoDraftExpired("scheduler");

        assertThat(result.losses()).isZero();
        assertThat(result.items()).isZero();
        verify(repo, never()).insertAutoDraftLoss(any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("效期草稿：按药品聚合成多张 DRAFT，明细为到期批次数量，不扣库存")
    void autoDraftGroupsByDrug() {
        UUID drug2 = UUID.randomUUID();
        UUID batch3 = UUID.randomUUID();
        when(repo.findExpiredBatchesForLoss(any(LocalDate.class), anyInt())).thenReturn(List.of(
                expired(batchId, drugId, "B2026A", 5),
                expired(batch2, drugId, "B2026B", 2),
                expired(batch3, drug2, "B2026C", 4)));
        when(repo.nextStockLossNo()).thenReturn("LS20261104000001", "LS20261104000002");

        AutoDraftResult result = service.autoDraftExpired("scheduler");

        assertThat(result.losses()).isEqualTo(2);
        assertThat(result.items()).isEqualTo(3);
        verify(repo, org.mockito.Mockito.times(2))
                .insertAutoDraftLoss(any(), anyString(), anyString(), eq("scheduler"));
        verify(repo).insertStockLossItem(any(), any(), eq(drugId), eq(batchId), eq(5), anyString());
        verify(repo).insertStockLossItem(any(), any(), eq(drugId), eq(batch2), eq(2), anyString());
        verify(repo).insertStockLossItem(any(), any(), eq(drug2), eq(batch3), eq(4), anyString());
        // 只生成草稿，绝不在此阶段扣库存
        verify(repo, never()).deductBatch(any(), anyInt());
        verify(repo).logChange(eq("scheduler"), eq("STOCK_LOSS_AUTO_DRAFT"), eq("STOCK_LOSS"),
                eq("batch"), anyString());
    }

    // ---------------------------------------------------------------- 草稿提交（G28）

    private StockLoss draft() {
        return new StockLoss(lossId, "LS20261104000001", StockLoss.DRAFT, StockLoss.EXPIRED,
                "系统自动生成", "scheduler", null, null, null, 0L, Instant.now());
    }

    @Test
    @DisplayName("提交草稿：DRAFT → SUBMITTED，写审计")
    void submitDraftSucceeds() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(draft()));
        when(repo.listStockLossItems(lossId)).thenReturn(List.of(item(batchId, 2)));
        when(repo.findBatch(batchId)).thenReturn(Optional.of(batch(batchId, 10)));
        when(repo.submitStockLoss(lossId, "pharm01", 0L)).thenReturn(true);

        StockLossView view = service.submit(lossId, "pharm01");

        assertThat(view.loss().status()).isEqualTo(StockLoss.SUBMITTED);
        verify(repo).submitStockLoss(lossId, "pharm01", 0L);
        verify(repo).logChange(eq("pharm01"), eq("STOCK_LOSS_SUBMIT"), eq("STOCK_LOSS"),
                eq(lossId.toString()), anyString());
        verify(repo, never()).deductBatch(any(), anyInt());
    }

    @Test
    @DisplayName("提交草稿：已提交的幂等返回，不重复 CAS")
    void submitIsIdempotent() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.SUBMITTED, 1L, "pharm01")));

        StockLossView view = service.submit(lossId, "pharm01");

        assertThat(view.loss().status()).isEqualTo(StockLoss.SUBMITTED);
        verify(repo, never()).submitStockLoss(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("提交草稿：已通过/驳回 → 409")
    void submitRejectsClosedLoss() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(loss(StockLoss.APPROVED, 3L, "pharm01")));

        assertCode(() -> service.submit(lossId, "pharm01"), ErrorCode.STOCK_LOSS_STATUS_INVALID);
        verify(repo, never()).submitStockLoss(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("提交草稿：扫描后药已发出、余量不足 → 409，不落状态")
    void submitRejectsInsufficientStock() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(draft()));
        when(repo.listStockLossItems(lossId)).thenReturn(List.of(item(batchId, 5)));
        when(repo.findBatch(batchId)).thenReturn(Optional.of(batch(batchId, 3)));

        assertCode(() -> service.submit(lossId, "pharm01"), ErrorCode.STOCK_LOSS_EXCEEDS);
        verify(repo, never()).submitStockLoss(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("提交草稿：CAS 冲突（并发提交）→ 409")
    void submitCasConflict() {
        when(repo.lockStockLoss(lossId)).thenReturn(Optional.of(draft()));
        when(repo.listStockLossItems(lossId)).thenReturn(List.of());
        when(repo.submitStockLoss(lossId, "pharm01", 0L)).thenReturn(false);

        assertCode(() -> service.submit(lossId, "pharm01"), ErrorCode.CONFLICT);
    }

    // ---------------------------------------------------------------- 列表

    @Test
    @DisplayName("列表：非法状态拒绝，limit 上限 200")
    void listNormalizes() {
        assertCode(() -> service.list("UNKNOWN", 10), ErrorCode.VALIDATION_FAILED);
        service.list("approved", 9999);
        verify(repo).listStockLosses("APPROVED", 200);
    }
}
