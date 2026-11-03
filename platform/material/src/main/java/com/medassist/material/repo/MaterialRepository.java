package com.medassist.material.repo;

import com.medassist.common.time.BusinessTime;
import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.domain.MaterialModel.ConsumableIssue;
import com.medassist.material.domain.MaterialModel.ConsumableIssueItem;
import com.medassist.material.domain.MaterialModel.ConsumableTrace;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
    public boolean insertIssue(ConsumableIssue issue) {
        return jdbc.sql("""
                        INSERT INTO material_svc.consumable_issue
                            (id, issue_no, dept_id, dept_name, purpose, request_id, operator, remark,
                             total_amount)
                        VALUES (:id, :no, :deptId, CAST(:deptName AS text), :purpose, :requestId,
                                :operator, CAST(:remark AS text), :total)
                        ON CONFLICT (dept_id, request_id) DO NOTHING
                        """)
                .param("id", issue.id()).param("no", issue.issueNo())
                .param("deptId", issue.deptId()).param("deptName", issue.deptName())
                .param("purpose", issue.purpose()).param("requestId", issue.requestId())
                .param("operator", issue.operator()).param("remark", issue.remark())
                .param("total", issue.totalAmount()).update() > 0;
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

    public List<ConsumableIssue> listIssues(UUID deptId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT id, issue_no, dept_id, dept_name, purpose, request_id, operator,
                               remark, total_amount, created_at
                          FROM material_svc.consumable_issue
                         WHERE (CAST(:deptId AS uuid) IS NULL OR dept_id = CAST(:deptId AS uuid))
                           AND (CAST(:from AS timestamptz) IS NULL
                                OR created_at >= CAST(:from AS timestamptz))
                           AND (CAST(:to AS timestamptz) IS NULL
                                OR created_at < CAST(:to AS timestamptz))
                         ORDER BY created_at DESC
                         LIMIT :limit
                        """)
                .param("deptId", deptId).param("from", timestamp(from))
                .param("to", timestamp(to)).param("limit", limit)
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

    public List<ConsumableTrace> listTraces(String batchNo, String patientNo, UUID consumableId,
                                             Instant from, Instant to, int limit) {
        String patientPattern = patientNo == null || patientNo.isBlank()
                ? null : "%" + patientNo.trim() + "%";
        return jdbc.sql("""
                        SELECT id, issue_item_id, consumable_id, consumable_code, consumable_name,
                               batch_no, expire_date, quantity, patient_id, patient_no, visit_id,
                               surgery_id, operator, used_at, remark, created_at
                          FROM material_svc.consumable_trace
                         WHERE (CAST(:batchNo AS text) IS NULL
                                OR batch_no = CAST(:batchNo AS text))
                           AND (CAST(:patientPattern AS text) IS NULL
                                OR patient_no ILIKE CAST(:patientPattern AS text))
                           AND (CAST(:consumableId AS uuid) IS NULL
                                OR consumable_id = CAST(:consumableId AS uuid))
                           AND (CAST(:from AS timestamptz) IS NULL
                                OR used_at >= CAST(:from AS timestamptz))
                           AND (CAST(:to AS timestamptz) IS NULL
                                OR used_at < CAST(:to AS timestamptz))
                         ORDER BY used_at DESC
                         LIMIT :limit
                        """)
                .param("batchNo", blankToNull(batchNo)).param("patientPattern", patientPattern)
                .param("consumableId", consumableId).param("from", timestamp(from))
                .param("to", timestamp(to)).param("limit", limit)
                .query(TRACE_MAPPER).list();
    }

    /** 批次召回统计：一片耗材批次影响多少患者/手术，是召回决策的第一口径。 */
    public RecallStats recallStats(String batchNo) {
        return jdbc.sql("""
                        SELECT count(*) AS trace_count,
                               COALESCE(sum(quantity), 0) AS total_quantity,
                               count(DISTINCT patient_id) AS patient_count,
                               count(DISTINCT surgery_id) AS surgery_count
                          FROM material_svc.consumable_trace
                         WHERE batch_no = :batchNo
                        """).param("batchNo", batchNo)
                .query((rs, i) -> new RecallStats(rs.getLong("trace_count"),
                        rs.getInt("total_quantity"), rs.getLong("patient_count"),
                        rs.getLong("surgery_count"))).single();
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

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 召回统计行。 */
    public record RecallStats(long traceCount, int totalQuantity, long patientCount,
                              long surgeryCount) {
    }
}
