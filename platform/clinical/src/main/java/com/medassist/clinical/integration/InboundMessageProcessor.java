package com.medassist.clinical.integration;

import com.medassist.clinical.domain.IntegrationInboundMessage;

/**
 * 入站消息处理器 SPI（第 258 批）。
 *
 * <p>接入层只负责"收下来、去重、留证据"；真正的业务落库由各域实现本接口。
 * 没有处理器时消息保持 RECEIVED，不会假装处理成功。
 */
public interface InboundMessageProcessor {

    boolean supports(String systemCode, String messageType);

    /**
     * 处理一条入站消息；抛异常表示处理失败（消息标记 FAILED，保留原文可重放）。
     */
    void process(IntegrationInboundMessage message);
}
