package com.medassist.kb.config;

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
 * 迁移专用入口（Helm pre-install / pre-upgrade Hook Job 使用）。
 *
 * <pre>
 * java -jar platform-kb.jar --medassist.migrate-only=true --server.port=0
 * </pre>
 *
 * <p>没有这个 Runner 时，迁移 Job 会启动 Web 容器并常驻，直到 activeDeadlineSeconds 超时。
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
