package com.medassist.clinical.web;

import com.medassist.clinical.client.AiAssistClient;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HIS → AI 中台反向调用入口（评测「HIS 深度集成」：医生在 HIS 界面内直接用 AI）。
 *
 * <p><b>两个映射，用途不同（第 173 批）</b>：
 * <ul>
 *   <li>{@code /clinical/ai-assist/**}：**HIS 工作台经网关调用**的外部通道。
 *       网关路由 {@code /api/v2/clinical/**} 剥两段后到达这里；必须携带医护端 JWT
 *       （网关统一鉴权 + Sentinel 独立限流 {@code SENTINEL_QPS_AI_ASSIST}），
 *       令牌原样透传给 AI 中台，由 AI 侧按同一身份做角色校验与 model_quota 配额。</li>
 *   <li>{@code /internal/ai/**}：服务间内部通道，网关 denylist 永久 404
 *       （{@code /api/v2/* /internal/**} 与 {@code /internal/**}），仅同命名空间 Pod 可达。</li>
 * </ul>
 *
 * <p>响应语义：AI 未配置 503、无令牌 401、AI 拒绝 422、AI 故障 503/504，
 * 由各服务的 {@code GlobalExceptionHandler} 统一映射为稳定错误码。
 */
@RestController
@RequestMapping({"/clinical/ai-assist", "/internal/ai"})
public class AiAssistController {

    private final AiAssistClient client;

    public AiAssistController(AiAssistClient client) {
        this.client = client;
    }

    @PostMapping("/qc-check")
    public Map<String, Object> qcCheck(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        return client.qcCheck(bearer(authorization), body);
    }

    @PostMapping("/rx-check")
    public Map<String, Object> rxCheck(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> body) {
        return client.rxCheck(bearer(authorization), body);
    }

    /** 只取 Bearer 值；缺头/其它方案一律返回空串，由客户端转 401（不在这里抛，便于单测）。 */
    public static String bearer(String authorization) {
        if (authorization == null) {
            return "";
        }
        String v = authorization.trim();
        return v.regionMatches(true, 0, "Bearer ", 0, 7) ? v.substring(7).trim() : "";
    }
}
