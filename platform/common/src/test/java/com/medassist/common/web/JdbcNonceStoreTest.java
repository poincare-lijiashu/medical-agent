package com.medassist.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 共享 nonce 存储（P0-S5）的语义与守卫：
 * 影响行数 1 = 新鲜、0 = 重放；schema 白名单；数据库异常必须向外抛（由过滤器 fail-closed）。
 */
class JdbcNonceStoreTest {

    private static JdbcClient jdbcReturning(int... affectedRows) {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        int[] remaining = affectedRows.clone();
        int[] cursor = {0};
        when(spec.update()).thenAnswer(invocation ->
                cursor[0] < remaining.length ? remaining[cursor[0]++] : 0);
        return jdbc;
    }

    @Test
    void firstInsertIsFreshAndSecondIsReplay() {
        JdbcNonceStore store = new JdbcNonceStore(jdbcReturning(1, 0), "pharmacy_svc", 180);
        assertEquals(NonceStore.Outcome.FRESH, store.register("nonce-1"));
        assertEquals(NonceStore.Outcome.REPLAY, store.register("nonce-1"));
    }

    @Test
    void databaseFailurePropagatesForFailClosedHandling() {
        JdbcClient jdbc = mock(JdbcClient.class);
        JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);
        when(spec.update()).thenThrow(new IllegalStateException("db down"));
        JdbcNonceStore store = new JdbcNonceStore(jdbc, "tech_svc", 180);
        assertThrows(IllegalStateException.class, () -> store.register("nonce-x"));
    }

    @Test
    void rejectsSchemaNameOutsideIdentifierWhitelist() {
        JdbcClient jdbc = mock(JdbcClient.class);
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcNonceStore(jdbc, "public; DROP TABLE x", 180));
        assertThrows(IllegalArgumentException.class, () -> new JdbcNonceStore(jdbc, null, 180));
    }
}
