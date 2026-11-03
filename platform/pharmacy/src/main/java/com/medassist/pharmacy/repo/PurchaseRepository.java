package com.medassist.pharmacy.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.GoodsReceipt;
import com.medassist.pharmacy.domain.PharmacyModel.GoodsReceiptItem;
import com.medassist.pharmacy.domain.PharmacyModel.PurchaseOrder;
import com.medassist.pharmacy.domain.PharmacyModel.PurchaseOrderItem;
import com.medassist.pharmacy.domain.PharmacyModel.Supplier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 采购仓储（供应商 / 采购单 / 到货验收）。
 *
 * <p>与 {@link PharmacyRepository} 同口径：JdbcClient 显式 SQL、状态变更用
 * "期望状态 + 版本号" CAS、需要在事务内二次校验的行用 {@code FOR UPDATE}。
 *
 * <p>验收最关键的一条 SQL 是 {@link #addReceivedQuantity}：把"未到数量"判断下推到
 * {@code WHERE received_quantity + :qty <= quantity}，即使应用层并发判断同时通过，
 * 也只有一方能更新成功，不会出现超量验收。
 */
@Repository
public class PurchaseRepository {

    private static final RowMapper<Supplier> SUPPLIER_MAPPER = (rs, i) -> new Supplier(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
            rs.getString("contact"), rs.getString("phone"), rs.getString("license_no"),
            rs.getDate("license_expire_date").toLocalDate(), rs.getString("status"),
            rs.getString("remark"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<PurchaseOrder> ORDER_MAPPER = (rs, i) -> new PurchaseOrder(
            rs.getObject("id", UUID.class), rs.getString("order_no"),
            rs.getObject("supplier_id", UUID.class), rs.getString("status"),
            rs.getDate("expected_date") == null ? null : rs.getDate("expected_date").toLocalDate(),
            rs.getBigDecimal("total_amount"), rs.getString("remark"), rs.getString("created_by"),
            rs.getString("submitted_by"), instant(rs.getTimestamp("submitted_at")),
            rs.getString("cancel_reason"), rs.getString("cancelled_by"),
            instant(rs.getTimestamp("cancelled_at")), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<PurchaseOrderItem> ORDER_ITEM_MAPPER = (rs, i) -> new PurchaseOrderItem(
            rs.getObject("id", UUID.class), rs.getObject("purchase_order_id", UUID.class),
            rs.getObject("drug_id", UUID.class), rs.getString("drug_code"),
            rs.getString("drug_name"), rs.getString("spec"), rs.getString("unit"),
            rs.getInt("quantity"), rs.getInt("received_quantity"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"));

    private static final RowMapper<GoodsReceipt> RECEIPT_MAPPER = (rs, i) -> new GoodsReceipt(
            rs.getObject("id", UUID.class), rs.getString("receipt_no"),
            rs.getObject("purchase_order_id", UUID.class), rs.getObject("supplier_id", UUID.class),
            rs.getString("request_id"), rs.getString("delivery_no"), rs.getString("courier"),
            instant(rs.getTimestamp("delivered_at")), rs.getString("received_by"),
            rs.getString("remark"), rs.getBigDecimal("total_amount"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<GoodsReceiptItem> RECEIPT_ITEM_MAPPER = (rs, i) -> new GoodsReceiptItem(
            rs.getObject("id", UUID.class), rs.getObject("receipt_id", UUID.class),
            rs.getObject("po_item_id", UUID.class), rs.getObject("drug_id", UUID.class),
            rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
            rs.getDate("expire_date").toLocalDate(), rs.getInt("quantity"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"));

    private static Instant instant(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcClient jdbc;

    public PurchaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 单号

    public String nextOrderNo() {
        return nextNo("pharmacy_svc.purchase_order_no_seq", "PO");
    }

    public String nextReceiptNo() {
        return nextNo("pharmacy_svc.goods_receipt_no_seq", "GR");
    }

    private String nextNo(String sequence, String prefix) {
        Long seq = jdbc.sql("SELECT nextval(CAST(:seq AS regclass))")
                .param("seq", sequence).query(Long.class).single();
        return prefix + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq);
    }

    // ---------------------------------------------------------------- 供应商

    public void insertSupplier(Supplier s) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.md_supplier
                            (id, code, name, contact, phone, license_no, license_expire_date,
                             status, remark, version)
                        VALUES (:id, :code, :name, CAST(:contact AS text), CAST(:phone AS text),
                                :licenseNo, :licenseExpire, :status, CAST(:remark AS text), 0)
                        """)
                .param("id", s.id()).param("code", s.code()).param("name", s.name())
                .param("contact", s.contact()).param("phone", s.phone())
                .param("licenseNo", s.licenseNo()).param("licenseExpire", s.licenseExpireDate())
                .param("status", s.status()).param("remark", s.remark()).update();
    }

    public Optional<Supplier> findSupplier(UUID id) {
        return jdbc.sql("""
                        SELECT id, code, name, contact, phone, license_no, license_expire_date,
                               status, remark, version, created_at
                          FROM pharmacy_svc.md_supplier WHERE id = ?
                        """).param(id).query(SUPPLIER_MAPPER).optional();
    }

    public Optional<Supplier> findSupplierByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, name, contact, phone, license_no, license_expire_date,
                               status, remark, version, created_at
                          FROM pharmacy_svc.md_supplier WHERE code = ?
                        """).param(code).query(SUPPLIER_MAPPER).optional();
    }

    /** 供应商检索：keyword 匹配名称/编码/许可证号；status 为空返回全部。 */
    public List<Supplier> listSuppliers(String keyword, String status, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT id, code, name, contact, phone, license_no, license_expire_date,
                               status, remark, version, created_at
                          FROM pharmacy_svc.md_supplier
                         WHERE (CAST(:pattern AS text) IS NULL OR name ILIKE CAST(:pattern AS text)
                                OR code ILIKE CAST(:pattern AS text)
                                OR license_no ILIKE CAST(:pattern AS text))
                           AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                         ORDER BY name
                         LIMIT :limit
                        """)
                .param("pattern", pattern).param("status", blankToNull(status))
                .param("limit", limit).query(SUPPLIER_MAPPER).list();
    }

    /** 批量取供应商名称（采购单列表展示用，避免逐行查询）。 */
    public List<Supplier> findSuppliers(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT id, code, name, contact, phone, license_no, license_expire_date,
                               status, remark, version, created_at
                          FROM pharmacy_svc.md_supplier WHERE id IN (:ids)
                        """).param("ids", ids).query(SUPPLIER_MAPPER).list();
    }

    /** 供应商启停（版本号 CAS：并发下第二个请求会失败并提示刷新）。 */
    public boolean updateSupplierStatus(UUID id, String status, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.md_supplier
                           SET status = :status, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """)
                .param("status", status).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    // ---------------------------------------------------------------- 采购单

    public void insertOrder(PurchaseOrder o) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.purchase_order
                            (id, order_no, supplier_id, status, expected_date, total_amount, remark,
                             created_by, version)
                        VALUES (:id, :no, :supplierId, :status, CAST(:expectedDate AS date),
                                :total, CAST(:remark AS text), :createdBy, 0)
                        """)
                .param("id", o.id()).param("no", o.orderNo()).param("supplierId", o.supplierId())
                .param("status", o.status()).param("expectedDate", o.expectedDate())
                .param("total", o.totalAmount()).param("remark", o.remark())
                .param("createdBy", o.createdBy()).update();
    }

    public void insertOrderItem(PurchaseOrderItem item) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.purchase_order_item
                            (id, purchase_order_id, drug_id, drug_code, drug_name, spec, unit,
                             quantity, received_quantity, unit_price, amount)
                        VALUES (:id, :orderId, :drugId, :drugCode, :drugName, :spec, :unit,
                                :quantity, 0, :unitPrice, :amount)
                        """)
                .param("id", item.id()).param("orderId", item.purchaseOrderId())
                .param("drugId", item.drugId()).param("drugCode", item.drugCode())
                .param("drugName", item.drugName()).param("spec", item.spec())
                .param("unit", item.unit()).param("quantity", item.quantity())
                .param("unitPrice", item.unitPrice()).param("amount", item.amount()).update();
    }

    public Optional<PurchaseOrder> findOrder(UUID id) {
        return jdbc.sql("""
                        SELECT id, order_no, supplier_id, status, expected_date, total_amount, remark,
                               created_by, submitted_by, submitted_at, cancel_reason, cancelled_by,
                               cancelled_at, version, created_at
                          FROM pharmacy_svc.purchase_order WHERE id = ?
                        """).param(id).query(ORDER_MAPPER).optional();
    }

    /** 加行锁读取采购单；必须在事务内调用（提交/取消/验收前二次校验状态）。 */
    public Optional<PurchaseOrder> lockOrder(UUID id) {
        return jdbc.sql("""
                        SELECT id, order_no, supplier_id, status, expected_date, total_amount, remark,
                               created_by, submitted_by, submitted_at, cancel_reason, cancelled_by,
                               cancelled_at, version, created_at
                          FROM pharmacy_svc.purchase_order WHERE id = ? FOR UPDATE
                        """).param(id).query(ORDER_MAPPER).optional();
    }

    public List<PurchaseOrder> listOrders(String status, UUID supplierId, String keyword, int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT po.id, po.order_no, po.supplier_id, po.status, po.expected_date,
                               po.total_amount, po.remark, po.created_by, po.submitted_by,
                               po.submitted_at, po.cancel_reason, po.cancelled_by, po.cancelled_at,
                               po.version, po.created_at
                          FROM pharmacy_svc.purchase_order po
                         WHERE (CAST(:status AS text) IS NULL OR po.status = CAST(:status AS text))
                           AND (CAST(:supplierId AS uuid) IS NULL
                                OR po.supplier_id = CAST(:supplierId AS uuid))
                           AND (CAST(:pattern AS text) IS NULL
                                OR po.order_no ILIKE CAST(:pattern AS text)
                                OR EXISTS (SELECT 1 FROM pharmacy_svc.md_supplier s
                                            WHERE s.id = po.supplier_id
                                              AND s.name ILIKE CAST(:pattern AS text)))
                         ORDER BY po.created_at DESC
                         LIMIT :limit
                        """)
                .param("status", blankToNull(status)).param("supplierId", supplierId)
                .param("pattern", pattern).param("limit", limit)
                .query(ORDER_MAPPER).list();
    }

    public List<PurchaseOrderItem> listOrderItems(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, purchase_order_id, drug_id, drug_code, drug_name, spec, unit,
                               quantity, received_quantity, unit_price, amount
                          FROM pharmacy_svc.purchase_order_item
                         WHERE purchase_order_id = ?
                         ORDER BY drug_code
                        """).param(orderId).query(ORDER_ITEM_MAPPER).list();
    }

    public Optional<PurchaseOrderItem> findOrderItem(UUID id) {
        return jdbc.sql("""
                        SELECT id, purchase_order_id, drug_id, drug_code, drug_name, spec, unit,
                               quantity, received_quantity, unit_price, amount
                          FROM pharmacy_svc.purchase_order_item
                         WHERE id = ?
                        """).param(id).query(ORDER_ITEM_MAPPER).optional();
    }

    /** 已到货数量累加（数据库级守卫：累计超过采购量时更新 0 行）。 */
    public boolean addReceivedQuantity(UUID poItemId, int quantity) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.purchase_order_item
                           SET received_quantity = received_quantity + :qty
                         WHERE id = :id AND received_quantity + :qty <= quantity
                        """).param("qty", quantity).param("id", poItemId).update() > 0;
    }

    /** 草稿 → 已提交（CAS：只有 DRAFT 且版本一致才生效）。 */
    public boolean submitOrder(UUID id, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.purchase_order
                           SET status = 'SUBMITTED', submitted_by = :actor, submitted_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'DRAFT' AND version = :version
                        """).param("actor", actor).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    /** 取消（只允许 DRAFT / SUBMITTED；PARTIAL / RECEIVED 的库存已入账）。 */
    public boolean cancelOrder(UUID id, String reason, String actor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.purchase_order
                           SET status = 'CANCELLED', cancel_reason = :reason, cancelled_by = :actor,
                               cancelled_at = now(), version = version + 1, updated_at = now()
                         WHERE id = :id AND status IN ('DRAFT', 'SUBMITTED') AND version = :version
                        """)
                .param("reason", reason).param("actor", actor).param("id", id)
                .param("version", expectedVersion).update() > 0;
    }

    /**
     * 验收后的进度回写：调用方已持有该采购单的行锁，因此这里只校验来源状态，
     * 不再要求版本号（同事务内不会有第三方修改）。
     */
    public boolean updateReceiptStatus(UUID id, String nextStatus) {
        return jdbc.sql("""
                        UPDATE pharmacy_svc.purchase_order
                           SET status = :next, version = version + 1, updated_at = now()
                         WHERE id = :id AND status IN ('SUBMITTED', 'PARTIAL')
                        """).param("next", nextStatus).param("id", id).update() > 0;
    }

    // ---------------------------------------------------------------- 验收单

    public void insertReceipt(GoodsReceipt r) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.goods_receipt
                            (id, receipt_no, purchase_order_id, supplier_id, request_id, delivery_no,
                             courier, delivered_at, received_by, remark, total_amount)
                        VALUES (:id, :no, :orderId, :supplierId, CAST(:requestId AS text),
                                CAST(:deliveryNo AS text), CAST(:courier AS text),
                                CAST(:deliveredAt AS timestamptz), :receivedBy,
                                CAST(:remark AS text), :total)
                        """)
                .param("id", r.id()).param("no", r.receiptNo())
                .param("orderId", r.purchaseOrderId()).param("supplierId", r.supplierId())
                .param("requestId", r.requestId()).param("deliveryNo", r.deliveryNo())
                .param("courier", r.courier())
                .param("deliveredAt", r.deliveredAt() == null ? null
                        : java.sql.Timestamp.from(r.deliveredAt()))
                .param("receivedBy", r.receivedBy()).param("remark", r.remark())
                .param("total", r.totalAmount()).update();
    }

    public void insertReceiptItem(GoodsReceiptItem item) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.goods_receipt_item
                            (id, receipt_id, po_item_id, drug_id, batch_id, batch_no, expire_date,
                             quantity, unit_price, amount)
                        VALUES (:id, :receiptId, :poItemId, :drugId, :batchId, :batchNo,
                                :expireDate, :quantity, :unitPrice, :amount)
                        """)
                .param("id", item.id()).param("receiptId", item.receiptId())
                .param("poItemId", item.poItemId()).param("drugId", item.drugId())
                .param("batchId", item.batchId()).param("batchNo", item.batchNo())
                .param("expireDate", item.expireDate()).param("quantity", item.quantity())
                .param("unitPrice", item.unitPrice()).param("amount", item.amount()).update();
    }

    public Optional<GoodsReceipt> findReceipt(UUID id) {
        return jdbc.sql("""
                        SELECT id, receipt_no, purchase_order_id, supplier_id, request_id,
                               delivery_no, courier, delivered_at, received_by, remark,
                               total_amount, created_at
                          FROM pharmacy_svc.goods_receipt WHERE id = ?
                        """).param(id).query(RECEIPT_MAPPER).optional();
    }

    /** 幂等查询：同一采购单 + 同一 request_id 已存在的验收单。 */
    public Optional<GoodsReceipt> findReceiptByRequestId(UUID orderId, String requestId) {
        return jdbc.sql("""
                        SELECT id, receipt_no, purchase_order_id, supplier_id, request_id,
                               delivery_no, courier, delivered_at, received_by, remark,
                               total_amount, created_at
                          FROM pharmacy_svc.goods_receipt
                         WHERE purchase_order_id = :orderId AND request_id = :requestId
                        """).param("orderId", orderId).param("requestId", requestId)
                .query(RECEIPT_MAPPER).optional();
    }

    public List<GoodsReceipt> listReceipts(UUID orderId) {
        return jdbc.sql("""
                        SELECT id, receipt_no, purchase_order_id, supplier_id, request_id,
                               delivery_no, courier, delivered_at, received_by, remark,
                               total_amount, created_at
                          FROM pharmacy_svc.goods_receipt
                         WHERE purchase_order_id = ?
                         ORDER BY created_at DESC
                        """).param(orderId).query(RECEIPT_MAPPER).list();
    }

    public List<GoodsReceiptItem> listReceiptItems(UUID receiptId) {
        return jdbc.sql("""
                        SELECT id, receipt_id, po_item_id, drug_id, batch_id, batch_no, expire_date,
                               quantity, unit_price, amount
                          FROM pharmacy_svc.goods_receipt_item
                         WHERE receipt_id = ?
                         ORDER BY batch_no
                        """).param(receiptId).query(RECEIPT_ITEM_MAPPER).list();
    }

    /** 各状态采购单数量（KPI 用，不受列表条数上限影响）。 */
    public List<StatusCount> countByStatus() {
        return jdbc.sql("""
                        SELECT status, count(*) AS cnt
                          FROM pharmacy_svc.purchase_order
                         GROUP BY status
                        """)
                .query((rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("cnt")))
                .list();
    }

    /** 待验收金额（已提交/部分到货），用于"在途库存"口径。 */
    public java.math.BigDecimal outstandingAmount() {
        java.math.BigDecimal total = jdbc.sql("""
                        SELECT COALESCE(sum(i.unit_price * (i.quantity - i.received_quantity)), 0)
                          FROM pharmacy_svc.purchase_order_item i
                          JOIN pharmacy_svc.purchase_order po ON po.id = i.purchase_order_id
                         WHERE po.status IN ('SUBMITTED', 'PARTIAL')
                        """).query(java.math.BigDecimal.class).single();
        return total == null ? java.math.BigDecimal.ZERO : total;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 追加式审计（与药房其余写操作共用同一张变更日志表）。 */
    public void logChange(String actor, String action, String targetType, String targetId,
                          String detailJson) {
        jdbc.sql("""
                        INSERT INTO pharmacy_svc.pharmacy_change_log
                            (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :type, :id, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action).param("type", targetType)
                .param("id", targetId).param("detail", detailJson).update();
    }

    /** 状态计数行。 */
    public record StatusCount(String status, long count) {
    }
}
