package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.GoodsReceipt;
import com.medassist.pharmacy.domain.PharmacyModel.GoodsReceiptItem;
import com.medassist.pharmacy.domain.PharmacyModel.PurchaseOrder;
import com.medassist.pharmacy.domain.PharmacyModel.PurchaseOrderItem;
import com.medassist.pharmacy.domain.PharmacyModel.Supplier;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.repo.PurchaseRepository;
import com.medassist.pharmacy.web.PurchaseDtos.CancelOrderReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreatePurchaseOrderReq;
import com.medassist.pharmacy.web.PurchaseDtos.CreateReceiptReq;
import com.medassist.pharmacy.web.PurchaseDtos.GoodsReceiptView;
import com.medassist.pharmacy.web.PurchaseDtos.ProcurementSummary;
import com.medassist.pharmacy.web.PurchaseDtos.PurchaseOrderLine;
import com.medassist.pharmacy.web.PurchaseDtos.PurchaseOrderSummary;
import com.medassist.pharmacy.web.PurchaseDtos.PurchaseOrderView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 药品采购与到货验收（G2）。
 *
 * <p>完整链路：建单（价格快照）→ 提交 → 供应商到货 → 验收入库（写 drug_batch）→ 采购单结清。
 *
 * <p>三条不能妥协的规则：
 * <ol>
 *   <li>采购单的价格来自药品字典快照，客户端不传单价，防止前端篡改采购价；</li>
 *   <li>验收数量受"未到数量"约束，应用层判断 + 数据库 CAS 双保险，绝不超量入库；</li>
 *   <li>验收单是幂等的：同一采购单同一 {@code requestId} 只入库一次，客户端重试不会重复加库存。</li>
 * </ol>
 */
@Service
public class ProcurementService {

    private final PurchaseRepository purchaseRepo;
    private final PharmacyRepository pharmacyRepo;
    private final SupplierService supplierService;
    private final ObjectMapper objectMapper;

    public ProcurementService(PurchaseRepository purchaseRepo, PharmacyRepository pharmacyRepo,
                              SupplierService supplierService, ObjectMapper objectMapper) {
        this.purchaseRepo = purchaseRepo;
        this.pharmacyRepo = pharmacyRepo;
        this.supplierService = supplierService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 采购单

    @Transactional
    public PurchaseOrderView createOrder(CreatePurchaseOrderReq req, String actor) {
        LocalDate today = BusinessTime.today();
        Supplier supplier = supplierService.requireActiveLicensed(req.supplierId(), today);

        Set<UUID> seen = new HashSet<>();
        List<PurchaseOrderItem> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        UUID orderId = UUID.randomUUID();
        for (PurchaseOrderLine line : req.items()) {
            if (!seen.add(line.drugId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "采购明细存在重复药品，请合并为一行后再提交");
            }
            Drug drug = pharmacyRepo.findDrug(line.drugId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.DRUG_NOT_FOUND));
            if (!drug.enabled()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "药品已停用，不能采购：" + drug.name());
            }
            BigDecimal amount = drug.price()
                    .multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            total = total.add(amount);
            items.add(new PurchaseOrderItem(UUID.randomUUID(), orderId, drug.id(), drug.drugCode(),
                    drug.name(), drug.spec(), drug.unit(), line.quantity(), 0,
                    drug.price().setScale(2, RoundingMode.HALF_UP), amount));
        }

        PurchaseOrder order = new PurchaseOrder(orderId, purchaseRepo.nextOrderNo(),
                supplier.id(), PurchaseOrder.DRAFT, req.expectedDate(), total,
                blankToNull(req.remark()), actor, null, null, null, null, null, 0L,
                Instant.now());
        purchaseRepo.insertOrder(order);
        items.forEach(purchaseRepo::insertOrderItem);

        audit(actor, "PURCHASE_ORDER_CREATE", order.id().toString(), detail()
                .put("orderNo", order.orderNo())
                .put("supplier", supplier.name())
                .put("items", items.size())
                .put("totalAmount", total.toPlainString())
                .build());
        return new PurchaseOrderView(order, supplier.name(), items);
    }

    @Transactional
    public PurchaseOrderView submit(UUID id, long version, String actor) {
        PurchaseOrder order = purchaseRepo.lockOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        if (!order.submittable()) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_STATUS_INVALID,
                    "只有草稿采购单可以提交（当前 " + order.status() + "）");
        }
        if (!purchaseRepo.submitOrder(id, actor, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "采购单已被他人修改，请刷新后重试");
        }
        audit(actor, "PURCHASE_ORDER_SUBMIT", id.toString(), detail()
                .put("orderNo", order.orderNo())
                .put("fromVersion", version)
                .build());
        return detail(id);
    }

    @Transactional
    public PurchaseOrderView cancel(UUID id, CancelOrderReq req, String actor) {
        PurchaseOrder order = purchaseRepo.lockOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        if (!order.cancellable()) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_STATUS_INVALID,
                    "已到货的采购单不能取消（当前 " + order.status() + "），请走退货或报损");
        }
        if (!purchaseRepo.cancelOrder(id, req.reason().trim(), actor, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "采购单已被他人修改，请刷新后重试");
        }
        audit(actor, "PURCHASE_ORDER_CANCEL", id.toString(), detail()
                .put("orderNo", order.orderNo())
                .put("reason", req.reason().trim())
                .build());
        return detail(id);
    }

    public PurchaseOrderView detail(UUID id) {
        PurchaseOrder order = purchaseRepo.findOrder(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        String supplierName = purchaseRepo.findSupplier(order.supplierId())
                .map(Supplier::name).orElse("");
        return new PurchaseOrderView(order, supplierName, purchaseRepo.listOrderItems(id));
    }

    public List<PurchaseOrderSummary> list(String status, UUID supplierId, String keyword,
                                           Integer limit) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        List<PurchaseOrder> orders = purchaseRepo.listOrders(status, supplierId, keyword, size);
        Map<UUID, String> names = new HashMap<>();
        purchaseRepo.findSuppliers(orders.stream().map(PurchaseOrder::supplierId).toList())
                .forEach(s -> names.put(s.id(), s.name()));
        return orders.stream()
                .map(o -> new PurchaseOrderSummary(o, names.getOrDefault(o.supplierId(), "")))
                .toList();
    }

    public ProcurementSummary summary() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : List.of(PurchaseOrder.DRAFT, PurchaseOrder.SUBMITTED,
                PurchaseOrder.PARTIAL, PurchaseOrder.RECEIVED, PurchaseOrder.CANCELLED)) {
            counts.put(s, 0L);
        }
        purchaseRepo.countByStatus().forEach(c -> counts.put(c.status(), c.count()));
        long pending = counts.getOrDefault(PurchaseOrder.SUBMITTED, 0L)
                + counts.getOrDefault(PurchaseOrder.PARTIAL, 0L);
        return new ProcurementSummary(counts, purchaseRepo.outstandingAmount(), pending);
    }

    // ---------------------------------------------------------------- 到货验收

    /**
     * 到货验收：一次提交即完成入库。
     *
     * <p>幂等口径：先锁采购单，再按 (采购单, requestId) 查已有验收单；
     * 命中直接返回原单，不重复累加库存与到货数量。
     */
    @Transactional
    public GoodsReceiptView receive(UUID orderId, CreateReceiptReq req, String actor) {
        PurchaseOrder order = purchaseRepo.lockOrder(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        Optional<GoodsReceipt> existing =
                purchaseRepo.findReceiptByRequestId(orderId, req.requestId().trim());
        if (existing.isPresent()) {
            return receiptView(existing.get());
        }
        if (!order.receivable()) {
            throw new BusinessException(ErrorCode.PURCHASE_ORDER_STATUS_INVALID,
                    "只有已提交/部分到货的采购单可以验收（当前 " + order.status() + "）");
        }

        LocalDate today = BusinessTime.today();
        Supplier supplier = purchaseRepo.findSupplier(order.supplierId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND));

        // 第一遍：校验收货明细（归属、数量、效期），并累计金额；任何一条不合法都整单拒绝
        Set<UUID> seenItems = new HashSet<>();
        Map<UUID, PurchaseOrderItem> resolved = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var line : req.items()) {
            if (!seenItems.add(line.poItemId())) {
                throw new BusinessException(ErrorCode.PURCHASE_RECEIPT_INVALID,
                        "验收明细存在重复行，请合并后再提交");
            }
            PurchaseOrderItem item = purchaseRepo.findOrderItem(line.poItemId())
                    .filter(i -> orderId.equals(i.purchaseOrderId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ITEM_NOT_FOUND));
            if (line.quantity() > item.outstanding()) {
                throw new BusinessException(ErrorCode.PURCHASE_RECEIPT_EXCEEDS,
                        "药品 " + item.drugName() + " 未到数量仅 " + item.outstanding()
                                + "，本次验收 " + line.quantity());
            }
            if (line.expireDate().isBefore(today)) {
                throw new BusinessException(ErrorCode.DRUG_BATCH_EXPIRED,
                        "不能验收已过期药品（" + item.drugName() + " 效期 " + line.expireDate()
                                + "，业务日 " + today + "）");
            }
            resolved.put(item.id(), item);
            total = total.add(item.unitPrice()
                    .multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(2, RoundingMode.HALF_UP));
        }

        UUID receiptId = UUID.randomUUID();
        GoodsReceipt receipt = new GoodsReceipt(receiptId, purchaseRepo.nextReceiptNo(), orderId,
                order.supplierId(), req.requestId().trim(), blankToNull(req.deliveryNo()),
                blankToNull(req.courier()), req.deliveredAt(), actor, blankToNull(req.remark()),
                total, Instant.now());
        purchaseRepo.insertReceipt(receipt);

        for (var line : req.items()) {
            PurchaseOrderItem item = resolved.get(line.poItemId());
            UUID batchId = upsertBatch(item, line.batchNo().trim(), line.expireDate(),
                    line.quantity(), supplier.name());
            // 数据库级守卫：并发下若未到数量被其他验收单吃掉，这里更新 0 行
            if (!purchaseRepo.addReceivedQuantity(item.id(), line.quantity())) {
                throw new BusinessException(ErrorCode.PURCHASE_RECEIPT_EXCEEDS,
                        "药品 " + item.drugName() + " 的未到数量已被其他验收单占用，请刷新后重试");
            }
            BigDecimal amount = item.unitPrice()
                    .multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            purchaseRepo.insertReceiptItem(new GoodsReceiptItem(UUID.randomUUID(), receiptId,
                    item.id(), item.drugId(), batchId, line.batchNo().trim(), line.expireDate(),
                    line.quantity(), item.unitPrice(), amount));
        }

        // 全部到齐 → RECEIVED；否则 PARTIAL。调用方持有行锁，无需版本号
        boolean allReceived = purchaseRepo.listOrderItems(orderId).stream()
                .allMatch(i -> i.receivedQuantity() >= i.quantity());
        purchaseRepo.updateReceiptStatus(orderId,
                allReceived ? PurchaseOrder.RECEIVED : PurchaseOrder.PARTIAL);

        audit(actor, "GOODS_RECEIPT", receipt.id().toString(), detail()
                .put("receiptNo", receipt.receiptNo())
                .put("orderNo", order.orderNo())
                .put("supplier", supplier.name())
                .put("lines", req.items().size())
                .put("totalAmount", total.toPlainString())
                .put("deliveryNo", receipt.deliveryNo())
                .build());
        return receiptView(receipt);
    }

    /**
     * 批次落库：同药品同批号视为同一批次，累加数量；新批号建新批次。
     *
     * <p>同一批号出现不同效期直接拒绝——这不是"补录"，而是供应商串批或录入错误，
     * 放任会污染 FEFO 取药顺序。
     */
    private UUID upsertBatch(PurchaseOrderItem item, String batchNo, LocalDate expireDate,
                             int quantity, String supplierName) {
        Optional<DrugBatch> existing =
                pharmacyRepo.findBatchByDrugAndBatchNo(item.drugId(), batchNo);
        if (existing.isPresent()) {
            if (!existing.get().expireDate().equals(expireDate)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "批号 " + batchNo + " 已存在且效期为 " + existing.get().expireDate()
                                + "，本次录入 " + expireDate + "，请核对批号与效期");
            }
            pharmacyRepo.addBatchQuantity(existing.get().id(), quantity);
            return existing.get().id();
        }
        UUID batchId = UUID.randomUUID();
        try {
            pharmacyRepo.insertBatch(batchId, item.drugId(), batchNo, expireDate, quantity,
                    supplierName);
            return batchId;
        } catch (DataIntegrityViolationException e) {
            // 并发验收同批号：唯一键兜底，改为补录到先落库的批次
            DrugBatch raced = pharmacyRepo.findBatchByDrugAndBatchNo(item.drugId(), batchNo)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                            "批次写入冲突，请重试"));
            if (!raced.expireDate().equals(expireDate)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "批号 " + batchNo + " 的效期与已有记录不一致，请核对");
            }
            pharmacyRepo.addBatchQuantity(raced.id(), quantity);
            return raced.id();
        }
    }

    public List<GoodsReceiptView> receipts(UUID orderId) {
        purchaseRepo.findOrder(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PURCHASE_ORDER_NOT_FOUND));
        return purchaseRepo.listReceipts(orderId).stream().map(this::receiptView).toList();
    }

    public GoodsReceiptView receipt(UUID receiptId) {
        GoodsReceipt receipt = purchaseRepo.findReceipt(receiptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.GOODS_RECEIPT_NOT_FOUND));
        return receiptView(receipt);
    }

    private GoodsReceiptView receiptView(GoodsReceipt receipt) {
        return new GoodsReceiptView(receipt, purchaseRepo.listReceiptItems(receipt.id()));
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Detail detail() {
        return new Detail();
    }

    /** 审计明细构造器：允许 null 值（Map.of 不允许），最后统一序列化。 */
    private final class Detail {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Detail put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        String build() {
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JacksonException e) {
                return "{\"_detail_serialize_failed\":true}";
            }
        }
    }

    private void audit(String actor, String action, String targetId, String detailJson) {
        purchaseRepo.logChange(actor, action, "PURCHASE", targetId, detailJson);
    }
}
