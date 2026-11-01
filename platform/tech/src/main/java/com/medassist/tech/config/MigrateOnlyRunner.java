package com.medassist.tech.config;

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
 * 迁移专用入口（Helm pre-install / pre-upgrade Hook Job 使用），
 * 避免多副本滚动发布时争抢 Flyway 迁移锁。
 *
 * <pre>
 * java -jar platform-tech.jar --medassist.migrate-only=true --server.port=0
 * </pre>
 *
 * <p>没有这个 Runner 时，迁移 Job 会正常启动 Web 容器并常驻，
 * 直到 activeDeadlineSeconds 超时失败，把整个 PreSync 拖垮（第六轮 MF-11）。
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
        try (Connection connection = dataSource.getConnection()) {
            log.info("migrate.only.completed url={} catalog={}",
                    connection.getMetaData().getURL(), connection.getCatalog());
        }
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
