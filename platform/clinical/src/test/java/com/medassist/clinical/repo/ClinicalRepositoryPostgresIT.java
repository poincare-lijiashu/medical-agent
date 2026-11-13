package com.medassist.clinical.repo;

import com.medassist.clinical.domain.ClinicalModel.TransferTask;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库 SQL 级集成测试（意见书 P1-4）。
 *
 * <p>背景：全仓单测 100% mock {@code JdbcClient}，SQL 写错（漏 {@code booked < total}、
 * 漏 {@code version = :version}、漏 {@code returned_quantity + :qty <= quantity}）单测照样全绿。
 * 这里用 Testcontainers 起一次性 PostgreSQL，执行**真实迁移文件**建 schema，
 * 直接验证仓储层三条关键语句的真实语义与并发行为。
 *
 * <p>为什么默认不跑：需要 Docker 与本地 {@code postgres:15-alpine} 镜像。
 * 用 {@code mvn -Ppostgres-it -pl clinical test} 单独执行（{@code @Tag("postgres")}）。
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClinicalRepositoryPostgresIT {

    private static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("medagent_it")
                    .withUsername("medagent")
                    .withPassword("medagent123")
                    .withReuse(false);

    /** 迁移 V56/V57 回填用的默认院区（夹具与生产回填同口径）。 */
    private static final UUID CAMPUS = UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private JdbcClient jdbc;
    private ClinicalRepository repo;
    private UUID deptId;

    @BeforeAll
    void startDatabaseAndMigrate() {
        PG.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");

        // 与生产同一套迁移文件：验证的是"会真正上线的那份 schema"，不是手写 DDL
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("clinical_svc")
                .defaultSchema("clinical_svc")
                .locations("classpath:db/migration/clinical")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        jdbc = JdbcClient.create(dataSource);
        repo = new ClinicalRepository(jdbc);

        deptId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO clinical_svc.md_dept (id, dept_code, name)
                        VALUES (:id, :code, '集成测试科室')
                        """)
                .param("id", deptId)
                .param("code", "IT-" + deptId.toString().substring(0, 8))
                .update();
    }

    @AfterAll
    void stopDatabase() {
        PG.stop();
    }

    // ---------------------------------------------------------------- 号源 CAS

    @Test
    @DisplayName("号源：并发抢最后一个号只能成功一次，且 booked<total 与 version CAS 双守卫")
    void occupySlotIsAtomicUnderConcurrency() throws Exception {
        UUID slotId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO clinical_svc.reg_slot
                            (id, dept_id, doctor_id, work_date, period, total)
                        VALUES (:id, :dept, 'D-IT-001', :d, 'AM', 1)
                        """)
                .param("id", slotId).param("dept", deptId).param("d", LocalDate.now())
                .update();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    // 所有线程都拿同一个期望版本 0：只有第一个 UPDATE 能同时满足
                    // version=0 与 booked<total，其余必然失败
                    if (repo.occupySlot(slotId, 0L)) {
                        succeeded.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded.get()).as("并发抢号只能成功一次").isEqualTo(1);
        Map<String, Object> slot = jdbc.sql("""
                        SELECT booked, total, version FROM clinical_svc.reg_slot WHERE id = ?
                        """).param(slotId).query().singleRow();
        assertThat(((Number) slot.get("booked")).intValue()).isEqualTo(1);
        assertThat(((Number) slot.get("total")).intValue()).isEqualTo(1);
        assertThat(((Number) slot.get("version")).longValue()).isEqualTo(1L);

        // 满号后即使版本正确也必须失败（booked < total 守卫）
        assertThat(repo.occupySlot(slotId, 1L)).isFalse();
        assertThat(((Number) jdbc.sql("SELECT booked FROM clinical_svc.reg_slot WHERE id = ?")
                .param(slotId).query(Integer.class).single())).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 收费 CAS

    @Test
    @DisplayName("收费：版本不符不改行；版本正确才置 PAID 并累加金额；已支付不可重复支付")
    void markChargePaidIsVersionGuarded() {
        UUID visitId = UUID.randomUUID();
        insertVisit(visitId, "IT-V-" + visitId.toString().substring(0, 8));
        UUID chargeId = UUID.randomUUID();
        // V57 起 charge.campus_id NOT NULL：夹具必须显式写入院区，与 insertVisit 同口径
        jdbc.sql("""
                        INSERT INTO clinical_svc.charge (id, charge_no, visit_id, campus_id)
                        VALUES (:id, :no, :visit, :campus)
                        """)
                .param("id", chargeId)
                .param("no", "IT-C-" + chargeId.toString().substring(0, 8))
                .param("visit", visitId)
                .param("campus", CAMPUS)
                .update();

        // 期望版本不符：不更新
        assertThat(repo.markChargePaid(chargeId, new BigDecimal("10.00"), "CASH", 99L)).isFalse();
        assertThat(chargeStatus(chargeId)).isEqualTo("UNPAID");

        // 版本正确：置 PAID、金额累加、version++、paid_at 落时间
        assertThat(repo.markChargePaid(chargeId, new BigDecimal("10.00"), "CASH", 0L)).isTrue();
        Map<String, Object> paid = jdbc.sql("""
                        SELECT status, total_amount, version, paid_at, payment_method
                          FROM clinical_svc.charge WHERE id = ?
                        """).param(chargeId).query().singleRow();
        assertThat(paid.get("status")).isEqualTo("PAID");
        assertThat((BigDecimal) paid.get("total_amount")).isEqualByComparingTo("10.00");
        assertThat(((Number) paid.get("version")).longValue()).isEqualTo(1L);
        assertThat(paid.get("paid_at")).isNotNull();
        assertThat(paid.get("payment_method")).isEqualTo("CASH");

        // 已支付：即使版本正确也不允许二次支付（status='UNPAID' 守卫）
        assertThat(repo.markChargePaid(chargeId, new BigDecimal("5.00"), "CASH", 1L)).isFalse();
        assertThat((BigDecimal) jdbc.sql("""
                        SELECT total_amount FROM clinical_svc.charge WHERE id = ?
                        """).param(chargeId).query(BigDecimal.class).single())
                .isEqualByComparingTo("10.00");
    }

    // ---------------------------------------------------------------- 退药累计

    @Test
    @DisplayName("病区退药：累计不得超过收货量，达到上限置 RETURNED，超额调用不改行")
    void addReturnedQuantityCapsAtReceivedQuantity() {
        UUID visitId = UUID.randomUUID();
        insertVisit(visitId, "IT-V-" + visitId.toString().substring(0, 8));

        UUID orderId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO clinical_svc.visit_order
                            (id, order_no, visit_id, order_type, item_code, item_name,
                             quantity, unit, unit_price, amount, ordered_by)
                        VALUES (:id, :no, :visit, 'DRUG', 'IT-DRUG-1', '集成测试药品',
                                5, '支', 1.00, 5.00, 'it-tester')
                        """)
                .param("id", orderId)
                .param("no", "IT-O-" + orderId.toString().substring(0, 8))
                .param("visit", visitId)
                .update();

        UUID receiptId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO clinical_svc.ward_med_receipt
                            (id, order_id, visit_id, dispense_id, supply_date, quantity, received_by)
                        VALUES (:id, :order, :visit, :dispense, :d, 5, 'it-nurse')
                        """)
                .param("id", receiptId).param("order", orderId).param("visit", visitId)
                .param("dispense", UUID.randomUUID()).param("d", LocalDate.now())
                .update();

        assertThat(repo.addReturnedQuantity(receiptId, 3)).isTrue();
        assertThat(receiptState(receiptId)).containsEntry("returned_quantity", 3)
                .containsEntry("status", "RECEIVED");

        // 3 + 3 > 5：SQL 守卫必须挡住，不能靠数据库 CHECK 抛异常
        assertThat(repo.addReturnedQuantity(receiptId, 3)).isFalse();
        assertThat(receiptState(receiptId)).containsEntry("returned_quantity", 3);

        assertThat(repo.addReturnedQuantity(receiptId, 2)).isTrue();
        assertThat(receiptState(receiptId)).containsEntry("returned_quantity", 5)
                .containsEntry("status", "RETURNED");

        // 已全部退回后再退：必须失败（不能出现负库存或重复冲减）
        assertThat(repo.addReturnedQuantity(receiptId, 1)).isFalse();
        assertThat(receiptState(receiptId)).containsEntry("returned_quantity", 5);
    }

    // ---------------------------------------------------------------- 转送任务租约（P2-7）

    @Test
    @DisplayName("转送任务：抢占后同任务不会被其它 worker 拿到，租约过期自动释放，完成后清空租约")
    void claimPendingTransferTasksIsExclusiveAndLeaseExpires() {
        UUID visitId = UUID.randomUUID();
        insertVisit(visitId, "IT-V-" + visitId.toString().substring(0, 8));
        for (int i = 0; i < 2; i++) {
            repo.insertTransferTask(UUID.randomUUID(), UUID.randomUUID(), visitId, "PHARMACY",
                    "停嘱后作废失败", "it-tester");
        }

        List<TransferTask> first = repo.claimPendingTransferTasks("worker-1", 10, 60);
        assertThat(first).hasSize(2);
        assertThat(first).allSatisfy(t -> assertThat(t.channel()).isEqualTo("PHARMACY"));

        // 租约有效期内其它副本抢不到同一批任务（旧实现会拿到同样两行 → attempts 被放大）
        assertThat(repo.claimPendingTransferTasks("worker-2", 10, 60)).isEmpty();

        // 模拟实例崩溃：租约到期后任务自动回到可抢占状态
        jdbc.sql("""
                        UPDATE clinical_svc.order_transfer_task
                           SET locked_until = now() - interval '1 second'
                         WHERE locked_by = 'worker-1'
                        """).update();
        List<TransferTask> again = repo.claimPendingTransferTasks("worker-2", 10, 60);
        assertThat(again).hasSize(2);

        // 完成一条：状态 DONE 且租约清空，剩余一条仍可被下一个 worker 抢占
        UUID doneId = again.get(0).id();
        assertThat(repo.completeTransferTask(doneId)).isTrue();
        Map<String, Object> done = jdbc.sql("""
                        SELECT status, locked_until, locked_by, attempts
                          FROM clinical_svc.order_transfer_task WHERE id = ?
                        """).param(doneId).query().singleRow();
        assertThat(done.get("status")).isEqualTo("DONE");
        assertThat(done.get("locked_until")).isNull();
        assertThat(done.get("locked_by")).isNull();
        assertThat(((Number) done.get("attempts")).intValue()).isEqualTo(1);

        // 剩余一条仍在 worker-2 的租约内：下一个 worker 抢不到（这正是防重复处理的语义）
        assertThat(repo.claimPendingTransferTasks("worker-3", 10, 60)).isEmpty();

        // 租约到期后剩余任务才回到可抢占状态
        jdbc.sql("""
                        UPDATE clinical_svc.order_transfer_task
                           SET locked_until = now() - interval '1 second'
                         WHERE locked_by = 'worker-2'
                        """).update();
        assertThat(repo.claimPendingTransferTasks("worker-3", 10, 60)).hasSize(1);
    }

    // ---------------------------------------------------------------- 辅助

    private void insertVisit(UUID visitId, String visitNo) {
        // G46 起 visit.campus_id NOT NULL：夹具必须显式写入院区，否则真库门禁直接失败
        jdbc.sql("""
                        INSERT INTO clinical_svc.visit
                            (id, visit_no, patient_id, patient_no, dept_id, doctor_id, visit_date,
                             campus_id)
                        VALUES (:id, :no, :pid, 'IT-P-001', :dept, 'D-IT-001', :d, :campus)
                        """)
                .param("id", visitId)
                .param("no", visitNo)
                .param("pid", UUID.randomUUID())
                .param("dept", deptId)
                .param("d", LocalDate.now())
                .param("campus", CAMPUS)
                .update();
    }

    private String chargeStatus(UUID chargeId) {
        return jdbc.sql("SELECT status FROM clinical_svc.charge WHERE id = ?")
                .param(chargeId).query(String.class).single();
    }

    private Map<String, Object> receiptState(UUID receiptId) {
        return jdbc.sql("""
                        SELECT returned_quantity, status FROM clinical_svc.ward_med_receipt WHERE id = ?
                        """).param(receiptId).query().singleRow();
    }
}
