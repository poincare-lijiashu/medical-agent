package com.medassist.auth.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * SSO 契约（G42）。
 */
public final class SsoDtos {

    private SsoDtos() {
    }

    /** 登录页展示用：只暴露非敏感的客户端配置，绝不返回 client_secret。 */
    public record ConfigView(boolean enabled, String provider, String issuer,
                             String clientId, String redirectUri, List<String> scopes) {
    }

    /** 可选覆盖回调地址；只允许与配置完全一致，防止开放重定向。 */
    public record AuthorizeReq(@Size(max = 512) String redirectUri) {
    }

    public record AuthorizeView(String authorizeUrl, String state) {
    }

    public record CallbackReq(@NotBlank @Size(max = 4096) String code,
                              @NotBlank @Size(max = 512) String state) {
    }
}
