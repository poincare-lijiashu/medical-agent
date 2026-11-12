package com.medassist.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cell 台账的 JDBC 连接串拼装（§282）。
 *
 * <p>这段逻辑看着琐碎，但错了就是"连错库"：漏端口会连到 5432（院方库不在那儿），
 * 漏超时会在对端静默丢包时把控制面线程挂死。
 */
class CellMigrationServiceTest {

    @Test
    @DisplayName("host:port 形式：端口被正确拆分")
    void parsesHostWithPort() {
        String url = CellMigrationService.jdbcUrl("10.0.2.12:5433", "medagent");

        assertThat(url).startsWith("jdbc:postgresql://10.0.2.12:5433/medagent?");
        assertThat(url).contains("connectTimeout=5").contains("socketTimeout=10");
    }

    @Test
    @DisplayName("只给 host：默认 5432")
    void defaultsPort() {
        assertThat(CellMigrationService.jdbcUrl("pg-hospital-b", null))
                .startsWith("jdbc:postgresql://pg-hospital-b:5432/medagent?");
    }

    @Test
    @DisplayName("库名为空时回落到 medagent，绝不生成空库名")
    void defaultsDatabase() {
        assertThat(CellMigrationService.jdbcUrl("10.0.0.1", "  "))
                .contains("/medagent?");
    }
}
