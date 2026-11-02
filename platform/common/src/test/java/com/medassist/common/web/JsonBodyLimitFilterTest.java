package com.medassist.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * JSON 请求体上限（P1-6）：超限 413、正常 JSON 放行、multipart 不受影响。
 */
class JsonBodyLimitFilterTest {

    private static final int LIMIT = 1024;

    private static JsonBodyLimitFilter filter() {
        return new JsonBodyLimitFilter(LIMIT);
    }

    private static MockHttpServletRequest jsonPost(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v2/insurance/items/import");
        request.setContentType("application/json");
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    @DisplayName("JSON 超过上限：413 且不进入业务链")
    void rejectsOversizedJson() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter().doFilter(jsonPost(LIMIT + 1), response, chain);

        assertEquals(413, response.getStatus());
        assertEquals(null, chain.getRequest(), "超限请求不得进入业务链");
    }

    @Test
    @DisplayName("JSON 等于/小于上限：正常放行")
    void allowsJsonWithinLimit() throws Exception {
        for (int size : new int[]{0, LIMIT / 2, LIMIT}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter().doFilter(jsonPost(size), response, chain);
            assertNotNull(chain.getRequest(), "size=" + size + " 应放行");
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    @DisplayName("multipart 上传不受 JSON 体积上限影响（知识库文档有独立校验）")
    void ignoresMultipartUploads() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v2/kb/documents");
        request.setContentType("multipart/form-data; boundary=----x");
        request.setContent(new byte[LIMIT * 4]);

        MockFilterChain chain = new MockFilterChain();
        filter().doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest(), "multipart 必须放行，由各自的上传大小校验处理");
    }

    @Test
    @DisplayName("非 JSON（表单/纯文本）不受影响")
    void ignoresNonJsonBodies() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v2/auth/login");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent(new byte[LIMIT * 4]);

        MockFilterChain chain = new MockFilterChain();
        filter().doFilter(request, new MockHttpServletResponse(), chain);

        assertNotNull(chain.getRequest());
    }
}
