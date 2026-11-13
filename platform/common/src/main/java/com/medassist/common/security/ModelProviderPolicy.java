package com.medassist.common.security;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * AI 模型提供方合规分类（对齐 Python 中台 {@code backend/core/model_policy.py}，评测 D-29）。
 *
 * <p>为什么 Java 侧也要有：知识库向量化（{@code KB_EMBEDDING_BASE_URL}）默认指向
 * {@code api.openai.com}，而 Python 中台只拦自己发起的调用——"中台合规、平台直连境外"
 * 是同一份病历数据从两条路径出境，等于没有策略。
 *
 * <p>判定顺序（按 base_url 主机名）：
 * <ol>
 *   <li>回环 / 私网地址 → {@code local}；</li>
 *   <li>国产模型/云厂商域名后缀 → {@code domestic}；</li>
 *   <li>已知境外模型厂商 → {@code foreign}；</li>
 *   <li>其余 → {@code unknown}。</li>
 * </ol>
 * 只有 {@code local} / {@code domestic} 默认允许（fail-closed）。
 */
public final class ModelProviderPolicy {

    /** 国产模型 / 国内云厂商（后缀匹配，覆盖其 API 子域）。与 Python 侧保持同表。 */
    public static final Set<String> DOMESTIC_SUFFIXES = Set.of(
            "deepseek.com",
            "aliyuncs.com",
            "dashscope.aliyuncs.com",
            "bigmodel.cn",
            "zhipuai.cn",
            "moonshot.cn",
            "baichuan-ai.com",
            "minimax.chat",
            "minimaxi.com",
            "sensetime.com",
            "baidubce.com",
            "qianfan.baidubce.com",
            "tencentcloudapi.com",
            "hunyuan.tencent.com",
            "volces.com",
            "volcengine.com",
            "xfyun.cn",
            "iflytek.com",
            "siliconflow.cn",
            "modelscope.cn",
            "gitee.ai");

    /** 已知境外模型厂商：显式拒绝，避免"未知域名"里混入。 */
    public static final Set<String> FOREIGN_SUFFIXES = Set.of(
            "openai.com",
            "anthropic.com",
            "x.ai",
            "googleapis.com",
            "google.com",
            "azure.com",
            "cohere.ai",
            "mistral.ai",
            "groq.com",
            "together.ai",
            "perplexity.ai",
            "huggingface.co",
            "openrouter.ai");

    public static final Set<String> ALLOWED_CLASSES = Set.of("local", "domestic");

    private ModelProviderPolicy() {
    }

    /** 返回 local / domestic / foreign / unknown；空或非法 URL 视为 unknown。 */
    public static String classify(String baseUrl) {
        String host = host(baseUrl);
        if (host.isEmpty()) {
            return "unknown";
        }
        if (isPrivateHost(host)) {
            return "local";
        }
        if (matches(host, DOMESTIC_SUFFIXES)) {
            return "domestic";
        }
        if (matches(host, FOREIGN_SUFFIXES)) {
            return "foreign";
        }
        return "unknown";
    }

    public static boolean isAllowed(String baseUrl) {
        return ALLOWED_CLASSES.contains(classify(baseUrl));
    }

    /** 主机名（小写）；解析失败返回空串而不是抛异常（守卫不能因配置写错而静默放行）。 */
    public static String host(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost();
            // URI 对 "http://127.0.0.1:8080" 正常，对缺 scheme 的 "api.openai.com/v1" 返回 null
            if (host != null) {
                return host.toLowerCase(Locale.ROOT);
            }
            String path = uri.getPath();
            if (path != null && !path.isBlank()) {
                String first = path.split("/")[0];
                return first.toLowerCase(Locale.ROOT);
            }
            return "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean matches(String host, Set<String> suffixes) {
        for (String suffix : suffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrivateHost(String host) {
        if ("localhost".equals(host) || "::1".equals(host) || "[::1]".equals(host)) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            // 内网短主机名（如 kb-embedding、ollama）没有点，按本地部署处理
            return !host.contains(".");
        }
        try {
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            if (a == 127 || a == 10) {
                return true;
            }
            if (a == 172 && b >= 16 && b <= 31) {
                return true;
            }
            return a == 192 && b == 168;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
