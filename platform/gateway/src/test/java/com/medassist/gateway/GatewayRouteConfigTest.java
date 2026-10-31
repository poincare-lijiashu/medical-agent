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
