package com.medassist.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 写操作身份守卫（E1）：缺 X-Actor / 空白 / 伪造 "system" 一律 400；
 * GET、内部通道、登录与健康检查不受影响。
 */
class ActorGuardFilterTest {

    private final ActorGuardFilter filter = new ActorGuardFilter();

    private MockHttpServletResponse run(String method, String uri, String actor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (actor != null) {
            request.addHeader("X-Actor", actor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        // 放行时 MockFilterChain 会记录请求；被拒绝时不会
        response.setHeader("X-Chain-Passed", String.valueOf(chain.getRequest() != null));
        return response;
    }

    private static boolean passed(MockHttpServletResponse response) {
        return "true".equals(response.getHeader("X-Chain-Passed"));
    }

    @Test
    @DisplayName("写操作缺少 X-Actor → 400，且不进入业务链")
    void writeWithoutActorIsRejected() throws Exception {
        MockHttpServletResponse response = run("POST", "/clinical/visits/1/orders", null);

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("X-Actor"));
        assertEquals("false", response.getHeader("X-Chain-Passed"));
    }

    @Test
    @DisplayName("写操作伪造 X-Actor: system → 400（历史兜底值不算真实操作者）")
    void legacySystemActorIsRejected() throws Exception {
        MockHttpServletResponse response = run("PUT", "/patients/1", "system");

        assertEquals(400, response.getStatus());
        assertEquals("false", response.getHeader("X-Chain-Passed"));
    }

    @Test
    @DisplayName("写操作 X-Actor 为空白 → 400")
    void blankActorIsRejected() throws Exception {
        MockHttpServletResponse response = run("PATCH", "/kb/documents/1", "   ");

        assertEquals(400, response.getStatus());
        assertEquals("false", response.getHeader("X-Chain-Passed"));
    }

    @Test
    @DisplayName("写操作带真实操作者 → 放行")
    void writeWithActorPasses() throws Exception {
        MockHttpServletResponse response = run("DELETE", "/pharmacy/prescriptions/1", "pharm01");

        assertEquals(200, response.getStatus());
        assertEquals("true", response.getHeader("X-Chain-Passed"));
    }

    @Test
    @DisplayName("GET 不需要 X-Actor（读操作不产生归因）")
    void readWithoutActorPasses() throws Exception {
        MockHttpServletResponse response = run("GET", "/clinical/visits/1", null);

        assertEquals(200, response.getStatus());
        assertEquals("true", response.getHeader("X-Chain-Passed"));
    }

    @Test
    @DisplayName("内部通道 /actuator 不需要 X-Actor（各有自己的鉴权边界）")
    void internalAndActuatorAreExempt() throws Exception {
        assertEquals("true", run("POST", "/internal/visits/1/charges", null).getHeader("X-Chain-Passed"));
        assertEquals("true", run("POST", "/actuator/refresh", null).getHeader("X-Chain-Passed"));
    }

    @Test
    @DisplayName("登录与刷新不需要 X-Actor（此时还没有身份）")
    void authEntryPointsAreExempt() throws Exception {
        assertEquals("true", run("POST", "/auth/login", null).getHeader("X-Chain-Passed"));
        assertEquals("true", run("POST", "/auth/refresh", null).getHeader("X-Chain-Passed"));
    }

    @Test
    @DisplayName("上下文路径被剔除后再判定（部署在 /medassist 下同样生效）")
    void contextPathIsStripped() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/medassist/clinical/orders");
        request.setContextPath("/medassist");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(400, response.getStatus());
        assertNull(chain.getRequest());
        assertNotNull(response.getContentAsString());
    }
}
