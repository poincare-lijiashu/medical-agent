package com.medassist.auth.repo;

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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库 SQL 级集成测试：登录限流桶的原子占位（G62/G63）。
 *
 * <p>为什么必须真库：限流判定的正确性完全依赖
 * {@code INSERT ... ON CONFLICT DO UPDATE} 的行锁语义与 {@code make_interval} 窗口重置，
 * mock {@code JdbcClient} 只能验证"调用了什么方法"，验证不了"并发下是否串行"。
 *
 * <p>默认不跑（需要 Docker + 本地 {@code postgres:15-alpine}）：
 * {@code mvn -Ppostgres-it -pl auth -am test}。
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginThrottlePostgresIT {

    private static final PostgreSQLContainer<?> PG =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
                    .withDatabaseName("medagent_auth_it")
                    .withUsername("medagent")
                    .withPassword("medagent123")
                    .withReuse(false);

    private static final Duration WINDOW = Duration.ofMinutes(5);

    private JdbcClient jdbc;
    private TokenRepository repo;

    @BeforeAll
    void startDatabaseAndMigrate() {
        PG.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");

        // 真实迁移文件：验证的是会真正上线的那份 schema
        Flyway.configure()
                .dataSource(dataSource)
                .schemas("auth_svc")
                .defaultSchema("auth_svc")
                .locations("classpath:db/migration/auth")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        jdbc = JdbcClient.create(dataSource);
        repo = new TokenRepository(jdbc);
    }

    @AfterAll
    void stopDatabase() {
        PG.stop();
    }

    @Test
    @DisplayName("占位：首次为 1，后续自增；成功退款后计数减一且不为负")
    void claimIncrementsAndRefundDecrements() {
        assertThat(repo.claimThrottle("u:it-a|10.0.0.1", WINDOW)).isEqualTo(1);
        assertThat(repo.claimThrottle("u:it-a|10.0.0.1", WINDOW)).isEqualTo(2);

        repo.refundThrottle("u:it-a|10.0.0.1");
        assertThat(repo.claimThrottle("u:it-a|10.0.0.1", WINDOW)).isEqualTo(2);

        // 反复退款不能把计数打成负数（CHECK failures >= 0 由 GREATEST 兜底）
        repo.refundThrottle("u:it-a|10.0.0.1");
        repo.refundThrottle("u:it-a|10.0.0.1");
        repo.refundThrottle("u:it-a|10.0.0.1");
        assertThat(repo.claimThrottle("u:it-a|10.0.0.1", WINDOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("窗口过期：超过窗口后计数从 1 重新开始")
    void windowExpiryResetsCounter() {
        assertThat(repo.claimThrottle("i:10.0.0.2", WINDOW)).isEqualTo(1);
        jdbc.sql("""
                        UPDATE auth_svc.login_throttle
                           SET window_start = now() - interval '10 minutes'
                         WHERE bucket_key = 'i:10.0.0.2'
                        """).update();

        assertThat(repo.claimThrottle("i:10.0.0.2", WINDOW)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发：8 个线程同时占位，计数严格 1..8（无丢失更新、无重复值）")
    void concurrentClaimsAreSerialized() throws Exception {
        String key = "u:it-race|10.0.0.3";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return repo.claimThrottle(key, WINDOW);
                }));
            }
            start.countDown();
            Set<Integer> values = new HashSet<>();
            for (Future<Integer> future : futures) {
                values.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(values).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
        } finally {
            pool.shutdownNow();
        }
    }
}
