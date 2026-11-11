package com.medassist.clinical.integration;

import com.medassist.clinical.domain.IntegrationMessage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 集成通道装配。
 */
@Configuration
@EnableConfigurationProperties(IntegrationProperties.class)
public class IntegrationConfig {

    /** 出站发送器：按消息的 system_code 选择对端地址。 */
    public interface IntegrationSender {
        /**
         * 发送一条消息，返回对端回执号（用于对账）。
         *
         * @throws IntegrationSendException 发送失败（网络/超时/对端拒绝），消息会被重试
         */
        String send(IntegrationMessage message);
    }
}
