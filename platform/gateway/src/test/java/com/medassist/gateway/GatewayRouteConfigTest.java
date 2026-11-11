package com.medassist.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由剥前缀回归测试。
 *
 * <p>背景：clinical 服务的控制器自带 {@code /clinical} 前缀，网关若剥 3 段
 * （api / v2 / clinical）会把请求打成 {@code /wards}，导致临床接口全量 404。
 * 这类错误只在真实联调时暴露，因此用配置级测试兜住。
 */
class GatewayRouteConfigTest {

    /** {@code ${ENV:30000}} 形态的占位符：取冒号后的默认值参与校验。 */
    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{[^:}]+:(\\d+)}");

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfig() {
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            assertThat(in).as("application.yml 必须在 classpath 上").isNotNull();
            return new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("读取 application.yml 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> route(Map<String, Object> config, String id) {
        Map<String, Object> spring = (Map<String, Object>) config.get("spring");
        Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
        Map<String, Object> gateway = (Map<String, Object>) cloud.get("gateway");
        Map<String, Object> server = (Map<String, Object>) gateway.get("server");
        Map<String, Object> webflux = (Map<String, Object>) server.get("webflux");
        List<Map<String, Object>> routes = (List<Map<String, Object>>) webflux.get("routes");
        return routes.stream()
                .filter(r -> id.equals(r.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少路由：" + id));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> routes(Map<String, Object> config) {
        Map<String, Object> spring = (Map<String, Object>) config.get("spring");
        Map<String, Object> cloud = (Map<String, Object>) spring.get("cloud");
        Map<String, Object> gateway = (Map<String, Object>) cloud.get("gateway");
        Map<String, Object> server = (Map<String, Object>) gateway.get("server");
        Map<String, Object> webflux = (Map<String, Object>) server.get("webflux");
        return (List<Map<String, Object>>) webflux.get("routes");
    }

    @Test
    @DisplayName("所有平台路由都必须配置下游超时（慢下游不能占满网关连接池）")
    void platformRoutesHaveTimeouts() {
        List<Map<String, Object>> platformRoutes = routes(loadConfig()).stream()
                .filter(r -> String.valueOf(r.get("id")).startsWith("platform-"))
                .toList();
        assertThat(platformRoutes).hasSize(14);

        for (Map<String, Object> r : platformRoutes) {
            assertThat(r.get("metadata")).as("路由 %s 缺少 metadata", r.get("id")).isNotNull();
            Map<String, Object> metadata = (Map<String, Object>) r.get("metadata");
            assertThat(metadata.get("response-timeout"))
                    .as("路由 %s 缺少 response-timeout", r.get("id")).isNotNull();
            assertThat(asMillis(metadata.get("response-timeout")))
                    .as("路由 %s 的 response-timeout 必须在 1s~120s", r.get("id"))
                    .isBetween(1_000L, 120_000L);
            assertThat(asMillis(metadata.get("connect-timeout")))
                    .as("路由 %s 的 connect-timeout 必须在 0.5s~10s", r.get("id"))
                    .isBetween(500L, 10_000L);
        }
    }

    /**
     * 配置里的超时可能是 {@code ${ENV:30000}} 占位符（YAML 层是 String，Spring 绑定后才变 Integer）。
     * 测试直接强转 Number 会 ClassCastException，因此要同时支持字面量与占位符默认值。
     */
    private static long asMillis(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(raw).trim();
        java.util.regex.Matcher matcher = PLACEHOLDER.matcher(text);
        if (matcher.matches()) {
            text = matcher.group(1);
        }
        return Long.parseLong(text);
    }

    @Test
    @DisplayName("clinical 路由只剥 /api/v2（控制器自带 /clinical 前缀）")
    void clinicalRouteStripsTwoSegments() {
        Map<String, Object> route = route(loadConfig(), "platform-clinical-v2");

        assertThat((List<String>) route.get("predicates"))
                .containsExactly("Path=/api/v2/clinical/**");
        assertThat((List<String>) route.get("filters")).contains("StripPrefix=2");
    }

    @Test
    @DisplayName("pharmacy 路由只剥 /api/v2（控制器自带 /pharmacy 前缀）")
    void pharmacyRouteStripsTwoSegments() {
        Map<String, Object> route = route(loadConfig(), "platform-pharmacy-v2");

        assertThat((List<String>) route.get("predicates"))
                .containsExactly("Path=/api/v2/pharmacy/**");
        assertThat((List<String>) route.get("filters")).contains("StripPrefix=2");
    }

    @Test
    @DisplayName("tech 路由只剥 /api/v2（控制器自带 /tech 前缀）")
    void techRouteStripsTwoSegments() {
        Map<String, Object> route = route(loadConfig(), "platform-tech-v2");

        assertThat((List<String>) route.get("predicates"))
                .containsExactly("Path=/api/v2/tech/**");
        assertThat((List<String>) route.get("filters")).contains("StripPrefix=2");
    }

    @Test
    @DisplayName("emr 路由只剥 /api/v2（控制器自带 /emr 前缀）")
    void emrRouteStripsTwoSegments() {
        Map<String, Object> route = route(loadConfig(), "platform-emr-v2");

        assertThat((List<String>) route.get("predicates"))
                .containsExactly("Path=/api/v2/emr/**");
        assertThat((List<String>) route.get("filters")).contains("StripPrefix=2");
    }

    @Test
    @DisplayName("insurance 路由只剥 /api/v2（控制器自带 /insurance 前缀）")
    void insuranceRouteStripsTwoSegments() {
        Map<String, Object> route = route(loadConfig(), "platform-insurance-v2");

        assertThat((List<String>) route.get("predicates"))
                .containsExactly("Path=/api/v2/insurance/**");
        assertThat((List<String>) route.get("filters")).contains("StripPrefix=2");
    }

    @Test
    @DisplayName("material 路由只剥 /api/v2（控制器自带 /material 前缀）")
    void materialRouteStripsTwoSegments() {
        Map<String, Object> route = route(loadConfig(), "platform-material-v2");

        assertThat((List<String>) route.get("predicates"))
                .containsExactly("Path=/api/v2/material/**");
        assertThat((List<String>) route.get("filters")).contains("StripPrefix=2");
    }

    @Test
    @DisplayName("kb 路由只剥 /api/v2（控制器自带 /kb 前缀，知识库接口不能全量 404）")
    void kbRouteStripsTwoSegments() {
        Map<String, Object> route = route(loadConfig(), "platform-kb-v2");

        assertThat((List<String>) route.get("predicates"))
                .containsExactly("Path=/api/v2/kb/**");
        assertThat((List<String>) route.get("filters")).contains("StripPrefix=2");
    }

    @Test
    @DisplayName("empi / auth / audit 路由仍剥 3 段（控制器无服务前缀）")
    void otherRoutesStripThreeSegments() {
        Map<String, Object> config = loadConfig();
        assertThat((List<String>) route(config, "platform-empi-v2").get("filters"))
                .contains("StripPrefix=3");
        assertThat((List<String>) route(config, "platform-auth-v2").get("filters"))
                .contains("StripPrefix=3");
        assertThat((List<String>) route(config, "platform-audit-v2").get("filters"))
                .contains("StripPrefix=3");
    }

    @Test
    @DisplayName("internal 路径必须在网关被拒（删掉这行不会有人发现，所以用测试锁住）")
    void internalPathsAreDenied() {
        Map<String, Object> gateway = medassistGateway(loadConfig());
        Map<String, Object> auth = (Map<String, Object>) gateway.get("auth");

        assertThat((List<String>) auth.get("deny-paths"))
                .contains("/api/v2/*/internal/**");
    }

    @Test
    @DisplayName("根级 /internal/** 也必须被拒（防新增 catch-all 路由后内部接口裸奔）")
    void rootInternalPathsAreDenied() {
        Map<String, Object> auth = (Map<String, Object>) medassistGateway(loadConfig()).get("auth");

        assertThat((List<String>) auth.get("deny-paths"))
                .as("HIS 工作台外部通道不得复用 /internal/**；内部接口必须 fail-closed")
                .contains("/internal/**");
    }

    @Test
    @DisplayName("API 文档端点必须在网关被拒（防 APP_ENV 误配成 development 后裸奔）")
    void apiDocsAreDenied() {
        Map<String, Object> auth = (Map<String, Object>) medassistGateway(loadConfig()).get("auth");

        assertThat((List<String>) auth.get("deny-paths"))
                .contains("/docs/**", "/redoc/**", "/openapi.json");
    }

    @Test
    @DisplayName("HIS 工作台 AI 通道有独立限流，且排在 clinical 兜底规则之前")
    void aiAssistRulePrecedesClinicalCatchAll() {
        Map<String, Object> sentinel = (Map<String, Object>) medassistGateway(loadConfig()).get("sentinel");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) sentinel.get("flow-rules");

        List<String> paths = rules.stream().map(r -> (String) r.get("path")).toList();
        int aiAssist = paths.indexOf("/api/v2/clinical/ai-assist");
        int clinicalCatchAll = paths.indexOf("/api/v2/clinical");
        assertThat(aiAssist).as("缺少 HIS → AI 通道的独立限流规则").isGreaterThanOrEqualTo(0);
        assertThat(aiAssist).as("前缀匹配按列表顺序命中，专属规则必须在兜底之前")
                .isLessThan(clinicalCatchAll);
    }

    @Test
    @DisplayName("重型查询（欠费预警）限流规则必须排在 /api/v2/clinical 之前")
    void heavyQueryRulesPrecedeCatchAll() {
        Map<String, Object> sentinel = (Map<String, Object>) medassistGateway(loadConfig()).get("sentinel");
        List<Map<String, Object>> rules = (List<Map<String, Object>>) sentinel.get("flow-rules");

        List<String> paths = rules.stream().map(r -> (String) r.get("path")).toList();
        int alerts = paths.indexOf("/api/v2/clinical/inpatient/alerts");
        int catchAll = paths.indexOf("/api/v2/clinical");
        assertThat(alerts).as("缺少欠费预警独立限流规则").isGreaterThanOrEqualTo(0);
        assertThat(alerts).as("前缀匹配按列表顺序命中，具体规则必须在前").isLessThan(catchAll);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> medassistGateway(Map<String, Object> config) {
        Map<String, Object> medassist = (Map<String, Object>) config.get("medassist");
        return (Map<String, Object>) medassist.get("gateway");
    }
}
