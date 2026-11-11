package com.medassist.clinical.integration;

/** 发送失败（网络、超时或对端拒绝）：消息进入重试队列而不是被丢弃。 */
public class IntegrationSendException extends RuntimeException {

    public IntegrationSendException(String message) {
        super(message);
    }

    public IntegrationSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
