package com.medassist.clinical.repo;

import com.medassist.clinical.domain.ReportModel.CategoryAmount;
import com.medassist.clinical.domain.ReportModel.DeptRevenue;
import com.medassist.clinical.domain.ReportModel.VisitCount;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;

/**
 * 运营指标聚合查询（只读）。
 *
 * <p>时间窗一律由调用方换算成绝对时刻（业务时区）后传入，避免 `date` 与 `timestamptz`
 * 隐式转换依赖数据库会话时区（与第四轮 SF-06 同口径）。
 */
@Repository
public class ReportRepository {

    private final JdbcClient jdbc;

    public ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 就诊人次：按 visit_type + status 分组（visit_date 归属）。 */
    public List<VisitCount> visitCounts(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                        SELECT visit_type, status, count(*) AS cnt
                          FROM clinical_svc.visit
                         WHERE visit_date >= :from AND visit_date <= :to
                         GROUP BY visit_type, status
                        """)
                .param("from", from).param("to", to)
                .query((rs, i) -> new VisitCount(rs.getString("visit_type"),
                        rs.getString("status"), rs.getInt("cnt")))
                .list();
    }

    /**
     * 期间费用总额（费用发生口径，charge_item.created_at）。
     *
     * <p>排除 REFUNDED 收费单的明细：这些费用已作废，计入总额会让"总费用"与
     * "实收+未结+退费"对不上，并虚增药占比（第五轮 R5-S5）。
     */
    public BigDecimal totalCharge(OffsetDateTime fromTs, OffsetDateTime toTs) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(ci.amount), 0)
                          FROM clinical_svc.charge_item ci
                          JOIN clinical_svc.charge ch ON ch.id = ci.charge_id
                         WHERE ci.created_at >= :fromTs AND ci.created_at < :toTs
                           AND ch.status <> 'REFUNDED'
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .query(BigDecimal.class).single();
    }

    /** 按费用分类汇总（同样排除已退费收费单，口径与总额一致）。 */
    public List<CategoryAmount> chargeByCategory(OffsetDateTime fromTs, OffsetDateTime toTs) {
        return jdbc.sql("""
                        SELECT ci.category, COALESCE(SUM(ci.amount), 0) AS total
                          FROM clinical_svc.charge_item ci
                          JOIN clinical_svc.charge ch ON ch.id = ci.charge_id
                         WHERE ci.created_at >= :fromTs AND ci.created_at < :toTs
                           AND ch.status <> 'REFUNDED'
                         GROUP BY ci.category
                         ORDER BY total DESC, ci.category
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .query((rs, i) -> new CategoryAmount(rs.getString("category"),
                        rs.getBigDecimal("total")))
                .list();
    }

    /** 收费单金额：按状态汇总（created_at 归属期间）。 */
    public List<ChargeStatusSum> chargeByStatus(OffsetDateTime fromTs, OffsetDateTime toTs) {
        return jdbc.sql("""
                        SELECT status, COALESCE(SUM(total_amount), 0) AS total
                          FROM clinical_svc.charge
                         WHERE created_at >= :fromTs AND created_at < :toTs
                         GROUP BY status
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .query((rs, i) -> new ChargeStatusSum(rs.getString("status"),
                        rs.getBigDecimal("total")))
                .list();
    }

    public record ChargeStatusSum(String status, BigDecimal total) {
    }

    /** 科室收入（费用明细 join 就诊取科室；排除已退费收费单，与总额口径一致）。 */
    public List<DeptRevenue> revenueByDept(OffsetDateTime fromTs, OffsetDateTime toTs) {
        return jdbc.sql("""
                        SELECT v.dept_id::text AS dept_id, d.name AS dept_name,
                               COALESCE(SUM(ci.amount), 0) AS total
                          FROM clinical_svc.charge_item ci
                          JOIN clinical_svc.charge ch ON ch.id = ci.charge_id
                          JOIN clinical_svc.visit v ON v.id = ch.visit_id
                          LEFT JOIN clinical_svc.md_dept d ON d.id = v.dept_id
                         WHERE ci.created_at >= :fromTs AND ci.created_at < :toTs
                           AND ch.status <> 'REFUNDED'
                         GROUP BY v.dept_id, d.name
                         ORDER BY total DESC
                        """)
                .param("fromTs", fromTs).param("toTs", toTs)
                .query((rs, i) -> new DeptRevenue(rs.getString("dept_id"),
                        rs.getString("dept_name"), rs.getBigDecimal("total")))
                .list();
    }
}
