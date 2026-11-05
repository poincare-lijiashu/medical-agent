package com.medassist.insurance.repo;

import com.medassist.insurance.domain.InsuranceModel.RealtimeSettlement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库 SQL 级集成测试：实时结算"一单一次 SETTLE"与投递抢占（G56/G63）。
 *
 * <p>为什么必须真库：
 * <ul>
 *   <li>部分唯一索引 {@code ux_realtime_settle_once} 只存在于迁移文件里，mock 测不到；</li>
 *   <li>{@code claimRealtime} 的 {@code WHERE status IN (...) AND version = ?} 是并发安全的全部依据，
 *       只能在真实 PostgreSQL 上验证"第二次抢占必失败"。</li>
 * </ul>
 *
 * <p>默认不跑：{@code mvn -Ppostgres-it -pl insurance -am test}。
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealtimeSettlementPostgresIT {

    private static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("medagent_ins_it")
                    .withUsername("medagent")
                    .withPassword("medagent123")
                    .withReuse(false);

    private static final UUID MAIN_CAMPUS =
            UUID.fromString("a0000000-0000-0000-0000-000000000001");

    private JdbcClient jdbc;
    private InsuranceRepository repo;
    private UUID billId;
    private UUID visitId;

    @BeforeAll
    void startDatabaseAndMigrate() {
        PG.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbc = JdbcClient.create(dataSource);

        // V20 会从 clinical_svc.visit 回填院区：先建最小桩表，避免跨服务建库顺序耦合
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS clinical_svc").update();
        jdbc.sql("""
                        CREATE TABLE IF NOT EXISTS clinical_svc.visit (
                            id uuid PRIMARY KEY,
                            campus_id uuid
                        )
                        """).update();

        Flyway.configure()
                .dataSource(dataSource)
                .schemas("insurance_svc")
                .defaultSchema("insurance_svc")
                .locations("classpath:db/migration/insurance")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        repo = new InsuranceRepository(jdbc);
    }

    @AfterAll
    void stopDatabase() {
        PG.stop();
    }

    @BeforeEach
    void insertConfirmedBill() {
        billId = UUID.randomUUID();
        visitId = UUID.randomUUID();
        String suffix = billId.toString().substring(0, 8);
        jdbc.sql("""
                        INSERT INTO insurance_svc.settlement_bill
                            (id, bill_no, visit_id, patient_id, patient_no, service_date,
                             total_amount, eligible_amount, deductible, insurance_amount,
                             self_pay_amount, status, detail, created_by, confirmed_by,
                             confirmed_at, campus_id)
                        VALUES (:id, :no, :visitId, :pid, 'IT-P-001', :d,
                                100.00, 100.00, 0, 80.00,
                                20.00, 'CONFIRMED', '[]'::jsonb, 'it', 'it', now(), :campus)
                        """)
                .param("id", billId)
                .param("no", "IT-SB-" + suffix)
                .param("visitId", visitId)
                .param("pid", UUID.randomUUID())
                .param("d", java.time.LocalDate.now())
                .param("campus", MAIN_CAMPUS)
                .update();
    }

    @Test
    @DisplayName("一单一次 SETTLE：同清单第二笔 SETTLE 被部分唯一索引拒绝，PRE_SETTLE 不受限")
    void secondSettleForSameBillIsRejected() {
        assertThat(repo.insertRealtime(pending("REQ-1", RealtimeSettlement.SETTLE))).isTrue();
        // 换请求号也不能再开一笔出账交易（G56 的核心约束）
        assertThat(repo.insertRealtime(pending("REQ-2", RealtimeSettlement.SETTLE))).isFalse();
        // 预结算不占用 SETTLE 名额，且同清单多笔允许
        assertThat(repo.insertRealtime(pending("REQ-3", RealtimeSettlement.PRE_SETTLE))).isTrue();
        assertThat(repo.insertRealtime(pending("REQ-4", RealtimeSettlement.PRE_SETTLE))).isTrue();

        assertThat(repo.findSettleByBill(billId)).isPresent()
                .get().extracting(RealtimeSettlement::requestId).isEqualTo("REQ-1");
    }

    @Test
    @DisplayName("投递抢占：CAS 版本抢占只成功一次，且非 DELIVERING 状态不能回写结果")
    void claimIsExclusiveAndMarkRequiresDelivering() {
        assertThat(repo.insertRealtime(pending("REQ-C", RealtimeSettlement.SETTLE))).isTrue();
        RealtimeSettlement created = repo.findSettleByBill(billId).orElseThrow();
        assertThat(created.status()).isEqualTo(RealtimeSettlement.PENDING);
        assertThat(created.version()).isZero();

        assertThat(repo.claimRealtime(created.id(), created.version())).isTrue();
        RealtimeSettlement claimed = repo.findRealtimeById(created.id()).orElseThrow();
        assertThat(claimed.status()).isEqualTo(RealtimeSettlement.DELIVERING);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.version()).isEqualTo(1);

        // 第二个线程拿旧版本抢占：必失败（不会重复外呼省局）
        assertThat(repo.claimRealtime(created.id(), created.version())).isFalse();

        // 抢占后回写成功
        assertThat(repo.markRealtime(created.id(), RealtimeSettlement.SETTLED, 1, "EXT-1",
                null, "{}", 1L)).isTrue();
        // 已 SETTLED：再次回写被 "status = 'DELIVERING'" 守卫拒绝
        assertThat(repo.markRealtime(created.id(), RealtimeSettlement.FAILED, 2, null,
                "boom", "{}", 2L)).isFalse();
        assertThat(repo.findRealtimeById(created.id()).orElseThrow().status())
                .isEqualTo(RealtimeSettlement.SETTLED);
    }

    @Test
    @DisplayName("重试抢占：FAILED 可重新抢占，attempts 累加")
    void failedTransactionCanBeReclaimed() {
        assertThat(repo.insertRealtime(pending("REQ-F", RealtimeSettlement.SETTLE))).isTrue();
        RealtimeSettlement created = repo.findSettleByBill(billId).orElseThrow();

        assertThat(repo.claimRealtime(created.id(), created.version())).isTrue();
        assertThat(repo.markRealtime(created.id(), RealtimeSettlement.FAILED, 1, null,
                "网络超时", "{}", 1L)).isTrue();
        RealtimeSettlement failed = repo.findRealtimeById(created.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(RealtimeSettlement.FAILED);

        assertThat(repo.claimRealtime(failed.id(), failed.version())).isTrue();
        assertThat(repo.findRealtimeById(created.id()).orElseThrow().attempts()).isEqualTo(2);
    }

    private RealtimeSettlement pending(String requestId, String bizType) {
        return new RealtimeSettlement(UUID.randomUUID(), billId, visitId, requestId, bizType,
                "PROVINCE", RealtimeSettlement.PENDING, 0, null, null,
                "{\"requestId\":\"" + requestId + "\"}", null, "it", Instant.now(), null, 0L);
    }
}
