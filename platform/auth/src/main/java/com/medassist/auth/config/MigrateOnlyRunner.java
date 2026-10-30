package com.medassist.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 迁移专用入口（K8s/Helm pre-install / pre-upgrade Hook Job 使用）。
 *
 * <p>为什么需要：Flyway 在应用启动时执行迁移。多副本滚动更新时若干个 Pod 会在同一时刻
 * 争抢 schema 历史表锁，先到的执行迁移、后到的可能超时重启（CrashLoop 噪声）。
 * 生产做法是<b>把迁移从应用进程里拿出来</b>，由一次性 Job 在发布前完成，应用侧
 * 以 {@code SPRING_FLYWAY_ENABLED=false} 启动。
 *
 * <p>用法（Job 容器内）：
 * <pre>
 * java -jar platform-auth.jar --medassist.migrate-only=true --server.port=0 \
 *      --spring.flyway.enabled=true
 * </pre>
 * Flyway 在上下文初始化阶段完成迁移，随后本 Runner 关闭上下文并以 0 退出；
 * 迁移失败时上下文启动直接抛错，Job 以非 0 退出，Helm Hook 阻塞发布。
 *
 * <p>本类只在显式开启该属性时装配，正常运行路径零影响。
 */
@Component
@ConditionalOnProperty(prefix = "medassist", name = "migrate-only", havingValue = "true")
public class MigrateOnlyRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrateOnlyRunner.class);

    private final ApplicationContext context;
    private final DataSource dataSource;

    public MigrateOnlyRunner(ApplicationContext context, DataSource dataSource) {
        this.context = context;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 能取到连接即证明数据源与迁移已完成（Flyway 在 Bean 初始化阶段跑完）
        try (Connection connection = dataSource.getConnection()) {
            log.info("migrate.only.completed url={} catalog={}",
                    connection.getMetaData().getURL(), connection.getCatalog());
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
