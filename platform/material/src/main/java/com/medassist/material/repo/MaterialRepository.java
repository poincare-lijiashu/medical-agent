package com.medassist.material.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.domain.MaterialModel.ConsumableIssue;
import com.medassist.material.domain.MaterialModel.ConsumableIssueItem;
import com.medassist.material.domain.MaterialModel.ConsumableReturn;
import com.medassist.material.domain.MaterialModel.ConsumableReturnItem;
import com.medassist.material.domain.MaterialModel.ConsumableTrace;
import com.medassist.material.domain.MaterialModel.DailyIssueTotal;
import com.medassist.material.domain.MaterialModel.DeptConsumableStock;
import com.medassist.material.domain.MaterialModel.DeptTransfer;
import com.medassist.material.domain.MaterialModel.DeptTransferItem;
import com.medassist.material.domain.MaterialModel.ReportSnapshot;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 物资耗材仓储（JdbcClient 显式 SQL）。
 *
 * <p>并发语义与药房一致：
 * <ul>
 *   <li>{@link #deductBatch} 用 {@code quantity >= :qty} 作数据库级守卫，库存不会为负；</li>
 *   <li>{@link #addTracedQuantity} 用 {@code traced_quantity + :qty <= quantity} 守卫，
 *       同一批次无法被"超用"到多于出库量；</li>
 *   <li>出库单以 {@code (dept_id, request_id)} 唯一键实现幂等。</li>
 * </ul>
 */
@Repository
public class MaterialRepository {

    private static final RowMapper<Consumable> CONSUMABLE_MAPPER = (rs, i) -> new Consumable(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
            rs.getString("spec"), rs.getString("unit"), rs.getString("category"),
            rs.getBigDecimal("price"), rs.getString("manufacturer"),
            rs.getString("registration_no"), rs.getBoolean("enabled"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ConsumableBatch> BATCH_MAPPER = (rs, i) -> new ConsumableBatch(
            rs.getObject("id", UUID.class), rs.getObject("consumable_id", UUID.class),
            rs.getString("consumable_code"), rs.getString("consumable_name"),
            rs.getString("batch_no"),
            rs.getDate("expire_date") == null ? null : rs.getDate("expire_date").toLocalDate(),
            rs.getInt("quantity"), rs.getString("supplier"), rs.getBigDecimal("unit_price"),
            rs.getLong("version"));

    private static final RowMapper<ConsumableIssue> ISSUE_MAPPER = (rs, i) -> new ConsumableIssue(
            rs.getObject("id", UUID.class), rs.getString("issue_no"),
            rs.getObject("dept_id", UUID.class), rs.getString("dept_name"),
            rs.getString("purpose"), rs.getString("request_id"), rs.getString("operator"),
            rs.getString("remark"), rs.getBigDecimal("total_amount"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ConsumableIssueItem> ISSUE_ITEM_MAPPER = (rs, i) -> new ConsumableIssueItem(
            rs.getObject("id", UUID.class), rs.getObject("issue_id", UUID.class),
            rs.getObject("consumable_id", UUID.class), rs.getString("consumable_code"),
            rs.getString("consumable_name"), rs.getObject("batch_id", UUID.class),
            rs.getString("batch_no"),
            rs.getDate("expire_date") == null ? null : rs.getDate("expire_date").toLocalDate(),
            rs.getInt("quantity"), rs.getInt("traced_quantity"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("amount"));

    private static final RowMapper<ConsumableTrace> TRACE_MAPPER = (rs, i) -> new ConsumableTrace(
            rs.getObject("id", UUID.class), rs.getObject("issue_item_id", UUID.class),
            rs.getObject("consumable_id", UUID.class), rs.getString("consumable_code"),
            rs.getString("consumable_name"), rs.getString("batch_no"),
            rs.getDate("expire_date") == null ? null : rs.getDate("expire_date").toLocalDate(),
            rs.getInt("quantity"), rs.getObject("patient_id", UUID.class),
            rs.getString("patient_no"), rs.getObject("visit_id", UUID.class),
            rs.getObject("surgery_id", UUID.class), rs.getString("operator"),
            rs.getTimestamp("used_at").toInstant(), rs.getString("remark"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<DeptConsumableStock> DEPT_STOCK_MAPPER =
            (rs, i) -> new DeptConsumableStock(
                    rs.getObject("id", UUID.class), rs.getObject("dept_id", UUID.class),
                    rs.getObject("consumable_id", UUID.class), rs.getString("consumable_code"),
                    rs.getString("consumable_name"), rs.getString("spec"), rs.getString("unit"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
                    rs.getDate("expire_date") == null ? null
                            : rs.getDate("expire_date").toLocalDate(),
                    rs.getInt("quantity"), rs.getLong("version"));

    private static final RowMapper<ConsumableReturn> RETURN_MAPPER = (rs, i) -> new ConsumableReturn(
            rs.getObject("id", UUID.class), rs.getString("return_no"), rs.getString("status"),
            rs.getObject("dept_id", UUID.class), rs.getString("reason"),
            rs.getString("created_by"), rs.getString("reviewed_by"),
            rs.getTimestamp("reviewed_at") == null ? null
                    : rs.getTimestamp("reviewed_at").toInstant(),
            rs.getString("review_note"), rs.getString("cancelled_by"),
            rs.getTimestamp("cancelled_at") == null ? null
                    : rs.getTimestamp("cancelled_at").toInstant(),
            rs.getString("cancel_reason"), rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ConsumableReturnItem> RETURN_ITEM_MAPPER =
            (rs, i) -> new ConsumableReturnItem(
                    rs.getObject("id", UUID.class), rs.getObject("return_id", UUID.class),
                    rs.getObject("consumable_id", UUID.class), rs.getString("consumable_code"),
                    rs.getString("consumable_name"), rs.getString("spec"), rs.getString("unit"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
                    rs.getDate("expire_date") == null ? null
                            : rs.getDate("expire_date").toLocalDate(),
                    rs.getInt("quantity"));

    private static final RowMapper<DeptTransfer> DEPT_TRANSFER_MAPPER =
            (rs, i) -> new DeptTransfer(
                    rs.getObject("id", UUID.class), rs.getString("transfer_no"),
                    rs.getString("status"), rs.getObject("from_dept_id", UUID.class),
                    rs.getObject("to_dept_id", UUID.class), rs.getString("reason"),
                    rs.getString("note"), rs.getString("created_by"),
                    rs.getString("accepted_by"), instant(rs, "accepted_at"),
                    rs.getString("rejected_by"), instant(rs, "rejected_at"),
                    rs.getString("reject_reason"), rs.getString("cancelled_by"),
                    instant(rs, "cancelled_at"), rs.getString("cancel_reason"),
                    rs.getLong("version"), instant(rs, "created_at"));

    private static final RowMapper<DeptTransferItem> DEPT_TRANSFER_ITEM_MAPPER =
            (rs, i) -> new DeptTransferItem(
                    rs.getObject("id", UUID.class), rs.getObject("transfer_id", UUID.class),
                    rs.getObject("consumable_id", UUID.class), rs.getString("consumable_code"),
                    rs.getString("consumable_name"), rs.getString("spec"), rs.getString("unit"),
                    rs.getObject("batch_id", UUID.class), rs.getString("batch_no"),
                    rs.getDate("expire_date") == null ? null
                            : rs.getDate("expire_date").toLocalDate(),
                    rs.getInt("quantity"));

    private final JdbcClient jdbc;

    public MaterialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 单号

    public String nextIssueNo() {
        Long seq = jdbc.sql("SELECT nextval('material_svc.consumable_issue_no_seq'::regclass)")
                .query(Long.class).single();
        return "MI" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq == null ? 0 : seq);
    }

    public String nextReturnNo() {
        Long seq = jdbc.sql("SELECT nextval('material_svc.consumable_return_no_seq'::regclass)")
                .query(Long.class).single();
        return "MR" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq == null ? 0 : seq);
    }

    public String nextDeptTransferNo() {
        Long seq = jdbc.sql("SELECT nextval('material_svc.dept_transfer_no_seq'::regclass)")
                .query(Long.class).single();
        return "DT" + BusinessTime.today().format(DateTimeFormatter.BASIC_ISO_DATE)
                + String.format("%06d", seq == null ? 0 : seq);
    }

    // ---------------------------------------------------------------- 耗材目录

    public Optional<Consumable> findConsumable(UUID id) {
        return jdbc.sql("""
                        SELECT id, code, name, spec, unit, category, price, manufacturer,
                               registration_no, enabled, version, created_at
                          FROM material_svc.md_consumable WHERE id = ?
                        """).param(id).query(CONSUMABLE_MAPPER).optional();
    }

    public Optional<Consumable> findConsumableByCode(String code) {
        return jdbc.sql("""
                        SELECT id, code, name, spec, unit, category, price, manufacturer,
                               registration_no, enabled, version, created_at
                          FROM material_svc.md_consumable WHERE code = ?
                        """).param(code).query(CONSUMABLE_MAPPER).optional();
    }

    /** 耗材检索：keyword 匹配名称/编码/厂商/注册证号；category/enabled 为空返回全部。 */
    public List<Consumable> listConsumables(String keyword, String category, Boolean enabled,
                                            int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT id, code, name, spec, unit, category, price, manufacturer,
                               registration_no, enabled, version, created_at
                          FROM material_svc.md_consumable
                         WHERE (CAST(:pattern AS text) IS NULL OR name ILIKE CAST(:pattern AS text)
                                OR code ILIKE CAST(:pattern AS text)
                                OR COALESCE(manufacturer, '') ILIKE CAST(:pattern AS text)
                                OR COALESCE(registration_no, '') ILIKE CAST(:pattern AS text))
                           AND (CAST(:category AS text) IS NULL
                                OR category = CAST(:category AS text))
                           AND (CAST(:enabled AS boolean) IS NULL
                                OR enabled = CAST(:enabled AS boolean))
                         ORDER BY code
                         LIMIT :limit
                        """)
                .param("pattern", pattern).param("category", blankToNull(category))
                .param("enabled", enabled).param("limit", limit)
                .query(CONSUMABLE_MAPPER).list();
    }

    public void insertConsumable(Consumable c) {
        jdbc.sql("""
                        INSERT INTO material_svc.md_consumable
                            (id, code, name, spec, unit, category, price, manufacturer,
                             registration_no, enabled, version)
                        VALUES (:id, :code, :name, :spec, :unit, :category, :price,
                                CAST(:manufacturer AS text), CAST(:registrationNo AS text),
                                :enabled, 0)
                        """)
                .param("id", c.id()).param("code", c.code()).param("name", c.name())
                .param("spec", c.spec()).param("unit", c.unit()).param("category", c.category())
                .param("price", c.price()).param("manufacturer", c.manufacturer())
                .param("registrationNo", c.registrationNo()).param("enabled", c.enabled())
                .update();
    }

    public boolean updatePrice(UUID id, BigDecimal price, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE material_svc.md_consumable
                           SET price = :price, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """).param("price", price).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public boolean setEnabled(UUID id, boolean enabled, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE material_svc.md_consumable
                           SET enabled = :enabled, version = version + 1, updated_at = now()
                         WHERE id = :id AND version = :version
                        """).param("enabled", enabled).param("id", id)
                .param("version", expectedVersion).update() > 0;
    }

    public int totalStock(UUID consumableId) {
        Integer total = jdbc.sql("""
                        SELECT COALESCE(sum(quantity), 0)
                          FROM material_svc.consumable_batch WHERE consumable_id = ?
                        """).param(consumableId).query(Integer.class).single();
        return total == null ? 0 : total;
    }

    /** 全院耗材库存总件数（看板口径）。 */
    public int totalStockAll() {
        Integer total = jdbc.sql("SELECT COALESCE(sum(quantity), 0) FROM material_svc.consumable_batch")
                .query(Integer.class).single();
        return total == null ? 0 : total;
    }

    /** 批量取库存合计（目录列表用，避免逐行查询）。 */
    public java.util.Map<UUID, Integer> totalStocks(java.util.Collection<UUID> consumableIds) {
        if (consumableIds == null || consumableIds.isEmpty()) {
            return java.util.Map.of();
        }
        record StockRow(UUID id, int total) {
        }
        List<StockRow> rows = jdbc.sql("""
                        SELECT consumable_id, COALESCE(sum(quantity), 0) AS total
                          FROM material_svc.consumable_batch
                         WHERE consumable_id IN (:ids)
                         GROUP BY consumable_id
                        """).param("ids", consumableIds)
                .query((rs, i) -> new StockRow(rs.getObject("consumable_id", UUID.class),
                        rs.getInt("total")))
                .list();
        java.util.Map<UUID, Integer> result = new java.util.HashMap<>();
        rows.forEach(r -> result.put(r.id(), r.total()));
        return result;
    }

    // ---------------------------------------------------------------- 批次库存

    public void insertBatch(UUID id, UUID consumableId, String batchNo, LocalDate expireDate,
                            int quantity, String supplier, BigDecimal unitPrice) {
        jdbc.sql("""
                        INSERT INTO material_svc.consumable_batch
                            (id, consumable_id, batch_no, expire_date, quantity, supplier, unit_price,
                             version)
                        VALUES (:id, :consumableId, :batchNo, CAST(:expireDate AS date), :quantity,
                                CAST(:supplier AS text), :unitPrice, 0)
                        """)
                .param("id", id).param("consumableId", consumableId).param("batchNo", batchNo)
                .param("expireDate", expireDate).param("quantity", quantity)
                .param("supplier", supplier).param("unitPrice", unitPrice).update();
    }

    public Optional<ConsumableBatch> findBatch(UUID id) {
        return jdbc.sql("""
                        SELECT b.id, b.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, b.batch_no, b.expire_date, b.quantity,
                               b.supplier, b.unit_price, b.version
                          FROM material_svc.consumable_batch b
                          JOIN material_svc.md_consumable c ON c.id = b.consumable_id
                         WHERE b.id = ?
                        """).param(id).query(BATCH_MAPPER).optional();
    }

    public Optional<ConsumableBatch> findBatchByConsumableAndBatchNo(UUID consumableId,
                                                                     String batchNo) {
        return jdbc.sql("""
                        SELECT b.id, b.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, b.batch_no, b.expire_date, b.quantity,
                               b.supplier, b.unit_price, b.version
                          FROM material_svc.consumable_batch b
                          JOIN material_svc.md_consumable c ON c.id = b.consumable_id
                         WHERE b.consumable_id = :consumableId AND b.batch_no = :batchNo
                        """).param("consumableId", consumableId).param("batchNo", batchNo)
                .query(BATCH_MAPPER).optional();
    }

    public List<ConsumableBatch> listBatchesByConsumable(UUID consumableId) {
        return jdbc.sql("""
                        SELECT b.id, b.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, b.batch_no, b.expire_date, b.quantity,
                               b.supplier, b.unit_price, b.version
                          FROM material_svc.consumable_batch b
                          JOIN material_svc.md_consumable c ON c.id = b.consumable_id
                         WHERE b.consumable_id = ?
                         ORDER BY b.expire_date NULLS LAST, b.batch_no
                        """).param(consumableId).query(BATCH_MAPPER).list();
    }

    /** 可用批次：有余量且未过期（无有效期视为可用），按效期升序 —— FEFO 取货顺序。 */
    public List<ConsumableBatch> listAvailableBatches(UUID consumableId, LocalDate today) {
        return jdbc.sql("""
                        SELECT b.id, b.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, b.batch_no, b.expire_date, b.quantity,
                               b.supplier, b.unit_price, b.version
                          FROM material_svc.consumable_batch b
                          JOIN material_svc.md_consumable c ON c.id = b.consumable_id
                         WHERE b.consumable_id = :consumableId
                           AND b.quantity > 0
                           AND (b.expire_date IS NULL OR b.expire_date >= :today)
                         ORDER BY b.expire_date NULLS LAST, b.batch_no
                        """).param("consumableId", consumableId).param("today", today)
                .query(BATCH_MAPPER).list();
    }

    /** 原子扣减（数据库级守卫：余量不足则更新 0 行）。 */
    public boolean deductBatch(UUID batchId, int quantity) {
        return jdbc.sql("""
                        UPDATE material_svc.consumable_batch
                           SET quantity = quantity - :qty, version = version + 1, updated_at = now()
                         WHERE id = :id AND quantity >= :qty
                        """).param("qty", quantity).param("id", batchId).update() > 0;
    }

    /** 中心批次回冲（退回审批通过 / 拒收退回时使用）。 */
    public boolean addBatchQuantity(UUID batchId, int quantity) {
        return jdbc.sql("""
                        UPDATE material_svc.consumable_batch
                           SET quantity = quantity + :qty, version = version + 1, updated_at = now()
                         WHERE id = :id
                        """).param("qty", quantity).param("id", batchId).update() > 0;
    }

    /** 近效期与已过期批次（有余量、且设了效期）。 */
    public List<ConsumableBatch> listExpiringBefore(LocalDate threshold) {
        return jdbc.sql("""
                        SELECT b.id, b.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, b.batch_no, b.expire_date, b.quantity,
                               b.supplier, b.unit_price, b.version
                          FROM material_svc.consumable_batch b
                          JOIN material_svc.md_consumable c ON c.id = b.consumable_id
                         WHERE b.quantity > 0
                           AND b.expire_date IS NOT NULL
                           AND b.expire_date <= :threshold
                         ORDER BY b.expire_date, b.batch_no
                        """).param("threshold", threshold).query(BATCH_MAPPER).list();
    }

    // ---------------------------------------------------------------- 出库

    /**
     * 插入出库单；返回 false 表示同一 (dept_id, request_id) 已存在（幂等命中）。
     *
     * <p>用 {@code ON CONFLICT DO NOTHING} 而不是"先查后插"：并发下两个请求同时通过预检查时，
     * 唯一键冲突不会把整个 PostgreSQL 事务置为 aborted（捕获 DataIntegrityViolationException
     * 后再执行 SQL 会报 25P02），这里返回 0 行由调用方转幂等返回。
     */
    public boolean insertIssue(ConsumableIssue issue, UUID campusId) {
        return jdbc.sql("""
                        INSERT INTO material_svc.consumable_issue
                            (id, issue_no, dept_id, dept_name, purpose, request_id, operator, remark,
                             total_amount, campus_id)
                        VALUES (:id, :no, :deptId, CAST(:deptName AS text), :purpose, :requestId,
                                :operator, CAST(:remark AS text), :total, :campusId)
                        ON CONFLICT (dept_id, request_id) DO NOTHING
                        """)
                .param("id", issue.id()).param("no", issue.issueNo())
                .param("deptId", issue.deptId()).param("deptName", issue.deptName())
                .param("purpose", issue.purpose()).param("requestId", issue.requestId())
                .param("operator", issue.operator()).param("remark", issue.remark())
                .param("total", issue.totalAmount()).param("campusId", campusId).update() > 0;
    }

    /** 出库单所属院区；用于院区级越权判定（不存在返回空）。 */
    public Optional<UUID> findIssueCampus(UUID issueId) {
        return jdbc.sql("""
                        SELECT campus_id FROM material_svc.consumable_issue WHERE id = ?
                        """).param(issueId).query(UUID.class).optional();
    }

    /** 出库明细所属院区（经出库单回溯）；用于追溯登记前的院区校验。 */
    public Optional<UUID> findIssueItemCampus(UUID issueItemId) {
        return jdbc.sql("""
                        SELECT i.campus_id
                          FROM material_svc.consumable_issue_item it
                          JOIN material_svc.consumable_issue i ON i.id = it.issue_id
                         WHERE it.id = ?
                        """).param(issueItemId).query(UUID.class).optional();
    }

    public void insertIssueItem(ConsumableIssueItem item) {
        jdbc.sql("""
                        INSERT INTO material_svc.consumable_issue_item
                            (id, issue_id, consumable_id, batch_id, batch_no, expire_date, quantity,
                             traced_quantity, unit_price, amount)
                        VALUES (:id, :issueId, :consumableId, :batchId, :batchNo,
                                CAST(:expireDate AS date), :quantity, 0, :unitPrice, :amount)
                        """)
                .param("id", item.id()).param("issueId", item.issueId())
                .param("consumableId", item.consumableId()).param("batchId", item.batchId())
                .param("batchNo", item.batchNo()).param("expireDate", item.expireDate())
                .param("quantity", item.quantity()).param("unitPrice", item.unitPrice())
                .param("amount", item.amount()).update();
    }

    public Optional<ConsumableIssue> findIssue(UUID id) {
        return jdbc.sql("""
                        SELECT id, issue_no, dept_id, dept_name, purpose, request_id, operator,
                               remark, total_amount, created_at
                          FROM material_svc.consumable_issue WHERE id = ?
                        """).param(id).query(ISSUE_MAPPER).optional();
    }

    /** 幂等查询：同一科室同一 requestId 已存在的出库单。 */
    public Optional<ConsumableIssue> findIssueByRequestId(UUID deptId, String requestId) {
        return jdbc.sql("""
                        SELECT id, issue_no, dept_id, dept_name, purpose, request_id, operator,
                               remark, total_amount, created_at
                          FROM material_svc.consumable_issue
                         WHERE dept_id = :deptId AND request_id = :requestId
                        """).param("deptId", deptId).param("requestId", requestId)
                .query(ISSUE_MAPPER).optional();
    }

    /** 回写出库总额（明细逐批拆分后才知道总额，故先插单头再回填）。 */
    public void updateIssueTotal(UUID issueId, BigDecimal total) {
        jdbc.sql("""
                        UPDATE material_svc.consumable_issue SET total_amount = :total
                         WHERE id = :id
                        """).param("total", total).param("id", issueId).update();
    }

    /** 出库单列表（G50）：{@code campusCsv} 为空串表示跨院区角色不过滤。 */
    public List<ConsumableIssue> listIssues(UUID deptId, Instant from, Instant to,
                                            String campusCsv, int limit) {
        return jdbc.sql("""
                        SELECT id, issue_no, dept_id, dept_name, purpose, request_id, operator,
                               remark, total_amount, created_at
                          FROM material_svc.consumable_issue
                         WHERE (CAST(:deptId AS uuid) IS NULL OR dept_id = CAST(:deptId AS uuid))
                           AND (CAST(:from AS timestamptz) IS NULL
                                OR created_at >= CAST(:from AS timestamptz))
                           AND (CAST(:to AS timestamptz) IS NULL
                                OR created_at < CAST(:to AS timestamptz))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("deptId", deptId).param("from", timestamp(from))
                .param("to", timestamp(to))
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit)
                .query(ISSUE_MAPPER).list();
    }

    public List<ConsumableIssueItem> listIssueItems(UUID issueId) {
        return jdbc.sql("""
                        SELECT i.id, i.issue_id, i.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, i.batch_id, i.batch_no, i.expire_date,
                               i.quantity, i.traced_quantity, i.unit_price, i.amount
                          FROM material_svc.consumable_issue_item i
                          JOIN material_svc.md_consumable c ON c.id = i.consumable_id
                         WHERE i.issue_id = ?
                         ORDER BY c.code, i.batch_no
                        """).param(issueId).query(ISSUE_ITEM_MAPPER).list();
    }

    public Optional<ConsumableIssueItem> findIssueItem(UUID id) {
        return jdbc.sql("""
                        SELECT i.id, i.issue_id, i.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, i.batch_id, i.batch_no, i.expire_date,
                               i.quantity, i.traced_quantity, i.unit_price, i.amount
                          FROM material_svc.consumable_issue_item i
                          JOIN material_svc.md_consumable c ON c.id = i.consumable_id
                         WHERE i.id = ?
                        """).param(id).query(ISSUE_ITEM_MAPPER).optional();
    }

    /** 已追溯数量累加（守卫：累计不得超过出库数量）。 */
    public boolean addTracedQuantity(UUID issueItemId, int quantity) {
        return jdbc.sql("""
                        UPDATE material_svc.consumable_issue_item
                           SET traced_quantity = traced_quantity + :qty
                         WHERE id = :id AND traced_quantity + :qty <= quantity
                        """).param("qty", quantity).param("id", issueItemId).update() > 0;
    }

    // ---------------------------------------------------------------- 追溯

    public void insertTrace(ConsumableTrace trace) {
        jdbc.sql("""
                        INSERT INTO material_svc.consumable_trace
                            (id, issue_item_id, consumable_id, consumable_code, consumable_name,
                             batch_no, expire_date, quantity, patient_id, patient_no, visit_id,
                             surgery_id, operator, used_at, remark)
                        VALUES (:id, :issueItemId, :consumableId, :code, :name, :batchNo,
                                CAST(:expireDate AS date), :quantity, :patientId, :patientNo,
                                CAST(:visitId AS uuid), CAST(:surgeryId AS uuid), :operator,
                                CAST(:usedAt AS timestamptz), CAST(:remark AS text))
                        """)
                .param("id", trace.id()).param("issueItemId", trace.issueItemId())
                .param("consumableId", trace.consumableId()).param("code", trace.consumableCode())
                .param("name", trace.consumableName()).param("batchNo", trace.batchNo())
                .param("expireDate", trace.expireDate()).param("quantity", trace.quantity())
                .param("patientId", trace.patientId()).param("patientNo", trace.patientNo())
                .param("visitId", trace.visitId()).param("surgeryId", trace.surgeryId())
                .param("operator", trace.operator())
                .param("usedAt", timestamp(trace.usedAt())).param("remark", trace.remark())
                .update();
    }

    /** 追溯列表（G50）：经出库明细回溯出库单院区。 */
    public List<ConsumableTrace> listTraces(String batchNo, String patientNo, UUID consumableId,
                                             Instant from, Instant to, String campusCsv, int limit) {
        String patientPattern = patientNo == null || patientNo.isBlank()
                ? null : "%" + patientNo.trim() + "%";
        return jdbc.sql("""
                        SELECT t.id, t.issue_item_id, t.consumable_id, t.consumable_code,
                               t.consumable_name, t.batch_no, t.expire_date, t.quantity,
                               t.patient_id, t.patient_no, t.visit_id, t.surgery_id, t.operator,
                               t.used_at, t.remark, t.created_at
                          FROM material_svc.consumable_trace t
                          JOIN material_svc.consumable_issue_item it ON it.id = t.issue_item_id
                          JOIN material_svc.consumable_issue i ON i.id = it.issue_id
                         WHERE (CAST(:batchNo AS text) IS NULL
                                OR t.batch_no = CAST(:batchNo AS text))
                           AND (CAST(:patientPattern AS text) IS NULL
                                OR t.patient_no ILIKE CAST(:patientPattern AS text))
                           AND (CAST(:consumableId AS uuid) IS NULL
                                OR t.consumable_id = CAST(:consumableId AS uuid))
                           AND (CAST(:from AS timestamptz) IS NULL
                                OR t.used_at >= CAST(:from AS timestamptz))
                           AND (CAST(:to AS timestamptz) IS NULL
                                OR t.used_at < CAST(:to AS timestamptz))
                           AND (CAST(:campusCsv AS text) = ''
                                OR i.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY t.used_at DESC
                         LIMIT :limit
                        """)
                .param("batchNo", blankToNull(batchNo)).param("patientPattern", patientPattern)
                .param("consumableId", consumableId).param("from", timestamp(from))
                .param("to", timestamp(to))
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit)
                .query(TRACE_MAPPER).list();
    }

    /** 批次召回统计（G50）：只统计当前账号可见院区的使用记录。 */
    public RecallStats recallStats(String batchNo, String campusCsv) {
        return jdbc.sql("""
                        SELECT count(*) AS trace_count,
                               COALESCE(sum(t.quantity), 0) AS total_quantity,
                               count(DISTINCT t.patient_id) AS patient_count,
                               count(DISTINCT t.surgery_id) AS surgery_count
                          FROM material_svc.consumable_trace t
                          JOIN material_svc.consumable_issue_item it ON it.id = t.issue_item_id
                          JOIN material_svc.consumable_issue i ON i.id = it.issue_id
                         WHERE t.batch_no = :batchNo
                           AND (CAST(:campusCsv AS text) = ''
                                OR i.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                        """).param("batchNo", batchNo)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query((rs, i) -> new RecallStats(rs.getLong("trace_count"),
                        rs.getInt("total_quantity"), rs.getLong("patient_count"),
                        rs.getLong("surgery_count"))).single();
    }

    // ---------------------------------------------------------------- 科室二级库

    /** 科室二级库累加（同科室 + 耗材 + 批次 upsert）。出库成功时调用。 */
    public void upsertDeptConsumableStock(UUID deptId, UUID consumableId, UUID batchId,
                                          int quantity) {
        jdbc.sql("""
                        INSERT INTO material_svc.dept_consumable_stock
                            (id, dept_id, consumable_id, batch_id, quantity, version)
                        VALUES (:id, :deptId, :consumableId, :batchId, :qty, 0)
                        ON CONFLICT (dept_id, consumable_id, batch_id) DO UPDATE
                           SET quantity = material_svc.dept_consumable_stock.quantity
                                          + EXCLUDED.quantity,
                               version = material_svc.dept_consumable_stock.version + 1,
                               updated_at = now()
                        """)
                .param("id", UUID.randomUUID()).param("deptId", deptId)
                .param("consumableId", consumableId).param("batchId", batchId)
                .param("qty", quantity).update();
    }

    /** 科室二级库原子扣减（余量不足返回 false，不产生负库存）。 */
    public boolean deductDeptConsumableStock(UUID deptId, UUID consumableId, UUID batchId,
                                             int quantity) {
        return jdbc.sql("""
                        UPDATE material_svc.dept_consumable_stock
                           SET quantity = quantity - :qty, version = version + 1, updated_at = now()
                         WHERE dept_id = :deptId AND consumable_id = :consumableId
                           AND batch_id = :batchId AND quantity >= :qty
                        """)
                .param("deptId", deptId).param("consumableId", consumableId)
                .param("batchId", batchId).param("qty", quantity).update() > 0;
    }

    public Optional<DeptConsumableStock> findDeptConsumableStock(UUID deptId, UUID consumableId,
                                                                 UUID batchId) {
        return jdbc.sql("""
                        SELECT s.id, s.dept_id, s.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, c.spec, c.unit, s.batch_id,
                               b.batch_no, b.expire_date, s.quantity, s.version
                          FROM material_svc.dept_consumable_stock s
                          JOIN material_svc.md_consumable c ON c.id = s.consumable_id
                          JOIN material_svc.consumable_batch b ON b.id = s.batch_id
                         WHERE s.dept_id = :deptId AND s.consumable_id = :consumableId
                           AND s.batch_id = :batchId
                        """)
                .param("deptId", deptId).param("consumableId", consumableId)
                .param("batchId", batchId).query(DEPT_STOCK_MAPPER).optional();
    }

    /** 科室二级库列表：只列有余量的批次。 */
    public List<DeptConsumableStock> listDeptConsumableStock(UUID deptId, String keyword,
                                                             int limit) {
        String pattern = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
        return jdbc.sql("""
                        SELECT s.id, s.dept_id, s.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, c.spec, c.unit, s.batch_id,
                               b.batch_no, b.expire_date, s.quantity, s.version
                          FROM material_svc.dept_consumable_stock s
                          JOIN material_svc.md_consumable c ON c.id = s.consumable_id
                          JOIN material_svc.consumable_batch b ON b.id = s.batch_id
                         WHERE s.dept_id = :deptId
                           AND s.quantity > 0
                           AND (CAST(:pattern AS text) IS NULL
                                OR c.name ILIKE CAST(:pattern AS text)
                                OR c.code ILIKE CAST(:pattern AS text)
                                OR b.batch_no ILIKE CAST(:pattern AS text))
                         ORDER BY c.code, b.expire_date NULLS LAST, b.batch_no
                         LIMIT :limit
                        """)
                .param("deptId", deptId).param("pattern", pattern).param("limit", limit)
                .query(DEPT_STOCK_MAPPER).list();
    }

    // ---------------------------------------------------------------- 未使用退回

    public void insertReturn(ConsumableReturn r, UUID campusId) {
        jdbc.sql("""
                        INSERT INTO material_svc.consumable_return
                            (id, return_no, status, dept_id, reason, created_by, version, campus_id)
                        VALUES (:id, :no, :status, :deptId, :reason, :createdBy, 0, :campusId)
                        """)
                .param("id", r.id()).param("no", r.returnNo()).param("status", r.status())
                .param("deptId", r.deptId()).param("reason", r.reason())
                .param("createdBy", r.createdBy()).param("campusId", campusId).update();
    }

    /** 退回单所属院区；用于院区级越权判定（不存在返回空）。 */
    public Optional<UUID> findReturnCampus(UUID returnId) {
        return jdbc.sql("""
                        SELECT campus_id FROM material_svc.consumable_return WHERE id = ?
                        """).param(returnId).query(UUID.class).optional();
    }

    public Optional<ConsumableReturn> findReturn(UUID id) {
        return jdbc.sql("""
                        SELECT id, return_no, status, dept_id, reason, created_by, reviewed_by,
                               reviewed_at, review_note, cancelled_by, cancelled_at,
                               cancel_reason, version, created_at
                          FROM material_svc.consumable_return WHERE id = ?
                        """).param(id).query(RETURN_MAPPER).optional();
    }

    /** 加行锁读取退回单；审批/驳回/撤回前必须调用。 */
    public Optional<ConsumableReturn> lockReturn(UUID id) {
        return jdbc.sql("""
                        SELECT id, return_no, status, dept_id, reason, created_by, reviewed_by,
                               reviewed_at, review_note, cancelled_by, cancelled_at,
                               cancel_reason, version, created_at
                          FROM material_svc.consumable_return WHERE id = ? FOR UPDATE
                        """).param(id).query(RETURN_MAPPER).optional();
    }

    /** 退回单列表（G50）：{@code campusCsv} 为空串表示跨院区角色不过滤。 */
    public List<ConsumableReturn> listReturns(String status, UUID deptId, String campusCsv,
                                              int limit) {
        return jdbc.sql("""
                        SELECT id, return_no, status, dept_id, reason, created_by, reviewed_by,
                               reviewed_at, review_note, cancelled_by, cancelled_at,
                               cancel_reason, version, created_at
                          FROM material_svc.consumable_return
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:deptId AS uuid) IS NULL OR dept_id = CAST(:deptId AS uuid))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("deptId", deptId)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit)
                .query(RETURN_MAPPER).list();
    }

    public void insertReturnItem(ConsumableReturnItem item) {
        jdbc.sql("""
                        INSERT INTO material_svc.consumable_return_item
                            (id, return_id, consumable_id, batch_id, quantity)
                        VALUES (:id, :returnId, :consumableId, :batchId, :qty)
                        """)
                .param("id", item.id()).param("returnId", item.returnId())
                .param("consumableId", item.consumableId()).param("batchId", item.batchId())
                .param("qty", item.quantity()).update();
    }

    public List<ConsumableReturnItem> listReturnItems(UUID returnId) {
        return jdbc.sql("""
                        SELECT i.id, i.return_id, i.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, c.spec, c.unit, i.batch_id,
                               b.batch_no, b.expire_date, i.quantity
                          FROM material_svc.consumable_return_item i
                          JOIN material_svc.md_consumable c ON c.id = i.consumable_id
                          JOIN material_svc.consumable_batch b ON b.id = i.batch_id
                         WHERE i.return_id = ?
                         ORDER BY c.code, b.batch_no
                        """).param(returnId).query(RETURN_ITEM_MAPPER).list();
    }

    /** 审批 CAS：仅 SUBMITTED 可流转。 */
    public boolean reviewReturn(UUID id, long expectedVersion, String actor, boolean approved,
                                String note) {
        return jdbc.sql("""
                        UPDATE material_svc.consumable_return
                           SET status = :status, reviewed_by = :actor, reviewed_at = now(),
                               review_note = CAST(:note AS text), version = version + 1,
                               updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("status", approved ? ConsumableReturn.APPROVED
                        : ConsumableReturn.REJECTED)
                .param("actor", actor).param("note", note)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    public boolean cancelReturn(UUID id, long expectedVersion, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE material_svc.consumable_return
                           SET status = 'CANCELLED', cancelled_by = :actor, cancelled_at = now(),
                               cancel_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("actor", actor).param("reason", reason)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 跨科室调拨（G12）

    public void insertDeptTransfer(DeptTransfer t, UUID campusId) {
        jdbc.sql("""
                        INSERT INTO material_svc.dept_transfer
                            (id, transfer_no, status, from_dept_id, to_dept_id, reason, note,
                             created_by, version, campus_id)
                        VALUES (:id, :no, :status, :fromDeptId, :toDeptId, :reason,
                                CAST(:note AS text), :createdBy, 0, :campusId)
                        """)
                .param("id", t.id()).param("no", t.transferNo()).param("status", t.status())
                .param("fromDeptId", t.fromDeptId()).param("toDeptId", t.toDeptId())
                .param("reason", t.reason()).param("note", t.note())
                .param("createdBy", t.createdBy()).param("campusId", campusId).update();
    }

    /** 调拨单所属院区；用于院区级越权判定（不存在返回空）。 */
    public Optional<UUID> findDeptTransferCampus(UUID transferId) {
        return jdbc.sql("""
                        SELECT campus_id FROM material_svc.dept_transfer WHERE id = ?
                        """).param(transferId).query(UUID.class).optional();
    }

    public Optional<DeptTransfer> findDeptTransfer(UUID id) {
        return jdbc.sql("""
                        SELECT id, transfer_no, status, from_dept_id, to_dept_id, reason, note,
                               created_by, accepted_by, accepted_at, rejected_by, rejected_at,
                               reject_reason, cancelled_by, cancelled_at, cancel_reason,
                               version, created_at
                          FROM material_svc.dept_transfer WHERE id = ?
                        """).param(id).query(DEPT_TRANSFER_MAPPER).optional();
    }

    /** 加行锁读取调拨单；接收/拒收/撤回前必须调用。 */
    public Optional<DeptTransfer> lockDeptTransfer(UUID id) {
        return jdbc.sql("""
                        SELECT id, transfer_no, status, from_dept_id, to_dept_id, reason, note,
                               created_by, accepted_by, accepted_at, rejected_by, rejected_at,
                               reject_reason, cancelled_by, cancelled_at, cancel_reason,
                               version, created_at
                          FROM material_svc.dept_transfer WHERE id = ? FOR UPDATE
                        """).param(id).query(DEPT_TRANSFER_MAPPER).optional();
    }

    /** 调拨单列表（G50）：{@code campusCsv} 为空串表示跨院区角色不过滤。 */
    public List<DeptTransfer> listDeptTransfers(String status, UUID deptId, String campusCsv,
                                                int limit) {
        return jdbc.sql("""
                        SELECT id, transfer_no, status, from_dept_id, to_dept_id, reason, note,
                               created_by, accepted_by, accepted_at, rejected_by, rejected_at,
                               reject_reason, cancelled_by, cancelled_at, cancel_reason,
                               version, created_at
                          FROM material_svc.dept_transfer
                         WHERE (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:deptId AS uuid) IS NULL
                                OR from_dept_id = CAST(:deptId AS uuid)
                                OR to_dept_id = CAST(:deptId AS uuid))
                           AND (CAST(:campusCsv AS text) = ''
                                OR campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("status", status).param("deptId", deptId)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .param("limit", limit)
                .query(DEPT_TRANSFER_MAPPER).list();
    }

    public void insertDeptTransferItem(DeptTransferItem item) {
        jdbc.sql("""
                        INSERT INTO material_svc.dept_transfer_item
                            (id, transfer_id, consumable_id, batch_id, quantity)
                        VALUES (:id, :transferId, :consumableId, :batchId, :qty)
                        """)
                .param("id", item.id()).param("transferId", item.transferId())
                .param("consumableId", item.consumableId()).param("batchId", item.batchId())
                .param("qty", item.quantity()).update();
    }

    public List<DeptTransferItem> listDeptTransferItems(UUID transferId) {
        return jdbc.sql("""
                        SELECT i.id, i.transfer_id, i.consumable_id, c.code AS consumable_code,
                               c.name AS consumable_name, c.spec, c.unit, i.batch_id,
                               b.batch_no, b.expire_date, i.quantity
                          FROM material_svc.dept_transfer_item i
                          JOIN material_svc.md_consumable c ON c.id = i.consumable_id
                          JOIN material_svc.consumable_batch b ON b.id = i.batch_id
                         WHERE i.transfer_id = ?
                         ORDER BY c.code, b.batch_no
                        """).param(transferId).query(DEPT_TRANSFER_ITEM_MAPPER).list();
    }

    /** 接收 CAS：仅 SUBMITTED 可流转。 */
    public boolean acceptDeptTransfer(UUID id, long expectedVersion, String actor) {
        return jdbc.sql("""
                        UPDATE material_svc.dept_transfer
                           SET status = 'ACCEPTED', accepted_by = :actor, accepted_at = now(),
                               version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("actor", actor).param("id", id).param("version", expectedVersion)
                .update() > 0;
    }

    public boolean rejectDeptTransfer(UUID id, long expectedVersion, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE material_svc.dept_transfer
                           SET status = 'REJECTED', rejected_by = :actor, rejected_at = now(),
                               reject_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("actor", actor).param("reason", reason)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    public boolean cancelDeptTransfer(UUID id, long expectedVersion, String actor, String reason) {
        return jdbc.sql("""
                        UPDATE material_svc.dept_transfer
                           SET status = 'CANCELLED', cancelled_by = :actor, cancelled_at = now(),
                               cancel_reason = :reason, version = version + 1, updated_at = now()
                         WHERE id = :id AND status = 'SUBMITTED' AND version = :version
                        """)
                .param("actor", actor).param("reason", reason)
                .param("id", id).param("version", expectedVersion).update() > 0;
    }

    // ---------------------------------------------------------------- 出库趋势（G15）

    /** 出库日趋势（G50）：按院区收敛后再按出库时刻归日。 */
    public List<DailyIssueTotal> dailyIssueTotals(OffsetDateTime from, OffsetDateTime to,
                                                  String zone, String campusCsv) {
        return jdbc.sql("""
                        WITH issue_qty AS (
                            SELECT issue_id, sum(quantity) AS qty
                              FROM material_svc.consumable_issue_item
                             GROUP BY issue_id
                        )
                        SELECT (i.created_at AT TIME ZONE CAST(:zone AS text))::date AS day,
                               count(*) AS issue_count,
                               COALESCE(sum(q.qty), 0)::int AS item_quantity,
                               COALESCE(sum(i.total_amount), 0) AS total_amount
                          FROM material_svc.consumable_issue i
                          LEFT JOIN issue_qty q ON q.issue_id = i.id
                         WHERE i.created_at >= :fromTs AND i.created_at < :toTs
                           AND (CAST(:campusCsv AS text) = ''
                                OR i.campus_id = ANY (string_to_array(CAST(:campusCsv AS text), ',')::uuid[]))
                         GROUP BY 1
                         ORDER BY 1
                        """)
                .param("fromTs", from).param("toTs", to).param("zone", zone)
                .param("campusCsv", campusCsv == null ? "" : campusCsv)
                .query((rs, i) -> new DailyIssueTotal(
                        rs.getDate("day").toLocalDate(), rs.getInt("issue_count"),
                        rs.getInt("item_quantity"), rs.getBigDecimal("total_amount")))
                .list();
    }

    // ---------------------------------------------------------------- 日快照（G18）

    /** 写入/覆盖单日单指标快照；唯一键 (snapshot_date, metric) 保证幂等。 */
    public void upsertReportSnapshot(UUID id, LocalDate day, String metric, BigDecimal value,
                                     String detail, String source) {
        jdbc.sql("""
                        INSERT INTO material_svc.report_snapshot
                            (id, snapshot_date, metric, value, detail, source)
                        VALUES (:id, :day, :metric, :value, CAST(:detail AS jsonb), :source)
                        ON CONFLICT (snapshot_date, metric) DO UPDATE
                           SET value = EXCLUDED.value,
                               detail = EXCLUDED.detail,
                               source = EXCLUDED.source,
                               updated_at = now()
                        """)
                .param("id", id).param("day", day).param("metric", metric)
                .param("value", value).param("detail", detail).param("source", source)
                .update();
    }

    /** 快照查询：metric 为空返回全部；按日期倒序、指标正序。 */
    public List<ReportSnapshot> listReportSnapshots(LocalDate from, LocalDate to, String metric) {
        String metricKey = (metric == null || metric.isBlank()) ? null : metric.trim();
        return jdbc.sql("""
                        SELECT snapshot_date, metric, value, detail::text AS detail,
                               source, updated_at
                          FROM material_svc.report_snapshot
                         WHERE snapshot_date >= :from AND snapshot_date <= :to
                           AND (CAST(:metric AS text) IS NULL OR metric = CAST(:metric AS text))
                         ORDER BY snapshot_date DESC, metric
                        """)
                .param("from", from).param("to", to).param("metric", metricKey)
                .query((rs, i) -> new ReportSnapshot(
                        rs.getDate("snapshot_date").toLocalDate(), rs.getString("metric"),
                        rs.getBigDecimal("value"), rs.getString("detail"),
                        rs.getString("source"), instant(rs, "updated_at")))
                .list();
    }

    // ---------------------------------------------------------------- 汇总

    public BigDecimal stockValue() {
        BigDecimal value = jdbc.sql("""
                        SELECT COALESCE(sum(b.quantity * b.unit_price), 0)
                          FROM material_svc.consumable_batch b WHERE b.quantity > 0
                        """).query(BigDecimal.class).single();
        return value == null ? BigDecimal.ZERO : value;
    }

    public long countConsumables(boolean enabledOnly) {
        Long count = jdbc.sql("SELECT count(*) FROM material_svc.md_consumable"
                        + (enabledOnly ? " WHERE enabled" : ""))
                .query(Long.class).single();
        return count == null ? 0 : count;
    }

    public long countIssuesSince(Instant from) {
        Long count = jdbc.sql("""
                        SELECT count(*) FROM material_svc.consumable_issue
                         WHERE created_at >= :from
                        """).param("from", timestamp(from)).query(Long.class).single();
        return count == null ? 0 : count;
    }

    public long countTracesSince(Instant from) {
        Long count = jdbc.sql("""
                        SELECT count(*) FROM material_svc.consumable_trace
                         WHERE used_at >= :from
                        """).param("from", timestamp(from)).query(Long.class).single();
        return count == null ? 0 : count;
    }

    // ---------------------------------------------------------------- 审计

    public void logChange(String actor, String action, String targetType, String targetId,
                          String detailJson) {
        jdbc.sql("""
                        INSERT INTO material_svc.material_change_log
                            (actor, action, target_type, target_id, detail)
                        VALUES (:actor, :action, :type, :id, CAST(:detail AS jsonb))
                        """)
                .param("actor", actor).param("action", action).param("type", targetType)
                .param("id", targetId).param("detail", detailJson).update();
    }

    private static java.sql.Timestamp timestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 召回统计行。 */
    public record RecallStats(long traceCount, int totalQuantity, long patientCount,
                              long surgeryCount) {
    }
}
