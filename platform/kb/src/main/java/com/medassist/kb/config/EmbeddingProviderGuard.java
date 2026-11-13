package com.medassist.kb.config;

import com.medassist.common.security.ModelProviderPolicy;
import com.medassist.common.security.SecurityDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 向量模型提供方合规守卫（评测 D-29）。
 *
 * <p>问题：{@code KB_EMBEDDING_BASE_URL} 缺省是 {@code https://api.openai.com/v1}，
 * 一旦开启向量化，知识库正文（可能含病历片段）就直发境外端点；
 * Python 中台的 provider 合规分级管不到 Java 侧的这条链路。
 *
 * <p>行为（与 Python {@code model_policy} 同一张域名表，见
 * {@link ModelProviderPolicy}）：
 * <ul>
 *   <li>未开启 embedding → 不校验（不发请求就没有数据出境）；</li>
 *   <li>本地/国产（local/domestic）→ 记录提供方分类，放行；</li>
 *   <li>境外/未知 → 生产环境<b>拒绝启动</b>；非生产仅告警；</li>
 *   <li>显式 {@code medassist.ai.allow-foreign-provider=true} 时放行并告警（留出合规审批通道）。</li>
 * </ul>
 */
@Component
public class EmbeddingProviderGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingProviderGuard.class);

    /** 显式放行开关：必须在合规审批后由运维注入，不接受默认值。 */
    public static final String ALLOW_FOREIGN_PROPERTY = "medassist.ai.allow-foreign-provider";

    private final Environment environment;

    public EmbeddingProviderGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean enabled = environment.getProperty("medassist.kb.embedding.enabled",
                Boolean.class, false);
        if (!enabled) {
            return;
        }
        String baseUrl = environment.getProperty("medassist.kb.embedding.base-url", "");
        String classification = ModelProviderPolicy.classify(baseUrl);
        String host = ModelProviderPolicy.host(baseUrl);
        if (ModelProviderPolicy.ALLOWED_CLASSES.contains(classification)) {
            log.info("kb.embedding.provider class={} host={}", classification, host);
            return;
        }
        boolean allowForeign = environment.getProperty(ALLOW_FOREIGN_PROPERTY, Boolean.class, false);
        String message = "知识库向量模型指向不允许的提供方：" + (host.isEmpty() ? "未配置主机" : host)
                + "（分类=" + classification + "；仅允许本地部署或国产模型）";
        if (SecurityDefaults.isProduction(environment) && !allowForeign) {
            throw new IllegalStateException(message + "，服务拒绝启动。"
                    + "请改到院内/国产端点，或经合规审批后设置 "
                    + "MEDASSIST_AI_ALLOWFOREIGNPROVIDER=true");
        }
        log.warn("{}{}", message, allowForeign
                ? "（已显式放行 " + ALLOW_FOREIGN_PROPERTY + "=true，请确认合规结论已归档）"
                : "（非生产环境仅告警；生产环境将拒绝启动）");
    }
}
