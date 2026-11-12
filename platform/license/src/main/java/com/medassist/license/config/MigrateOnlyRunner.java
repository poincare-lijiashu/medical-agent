package com.medassist.license.config;

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
 * <p>本服务此前缺少该入口：迁移只在应用启动时执行，多副本滚动更新会争抢
 * schema 历史表锁；按 Cell 交付（ADR-001）时更需要在启动业务进程之前
 * 先把库升级到目标版本。行为与其余服务一致：迁移完成后直接退出。
 *
 * <p>用法（Job 容器内）：
 * <pre>
 * java -jar platform-license.jar --medassist.migrate-only=true --server.port=0 \
 *      --spring.flyway.enabled=true
 * </pre>
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
