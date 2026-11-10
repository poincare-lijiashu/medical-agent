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
 * <p>网关把 {@code /internal/**} 屏蔽在公网之外，仅服务间/内网可访问；
 * 这里的"内部"指**前端 HIS 工作台经网关白名单调用**，令牌仍是医护端 JWT 透传——
 * 不引入服务账号，AI 侧按同一身份做权限与配额（model_quota / rate_limit）。
 *
 * <p>响应语义：AI 未配置 503、无令牌 401、AI 拒绝 422、AI 故障 503/504，
 * 由各服务的 {@code GlobalExceptionHandler} 统一映射为稳定错误码。
 */
@RestController
@RequestMapping("/internal/ai")
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
