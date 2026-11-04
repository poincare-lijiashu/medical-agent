package com.medassist.clinical.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 报表订阅邮件发送器（G23）。
 *
 * <p>SMTP 配置由部署注入（{@code medassist.report-email.*}）；未配置 host/from 时视为不可用，
 * 创建 EMAIL 订阅会被拒绝，避免"订阅建了但永远发不出去"。
 *
 * <p>邮件为 HTML 正文 + CSV 附件（UTF-8，带 BOM 便于 Excel 直接打开）。
 * 发送失败抛 RuntimeException，由投递服务落 FAILED 并保留原因，管理员可手动重试。
 */
@Component
public class ReportEmailSender {

    private static final Logger log = LoggerFactory.getLogger(ReportEmailSender.class);

    private final JavaMailSenderImpl sender;
    private final String from;
    private final boolean configured;

    public ReportEmailSender(@Value("${medassist.report-email.host:}") String host,
                             @Value("${medassist.report-email.port:25}") int port,
                             @Value("${medassist.report-email.username:}") String username,
                             @Value("${medassist.report-email.password:}") String password,
                             @Value("${medassist.report-email.from:}") String from,
                             @Value("${medassist.report-email.starttls:false}") boolean starttls,
                             @Value("${medassist.report-email.ssl:false}") boolean ssl,
                             @Value("${medassist.report-email.timeout-ms:5000}") int timeoutMs) {
        this.from = from == null ? "" : from.trim();
        String normalizedHost = host == null ? "" : host.trim();
        this.configured = !normalizedHost.isEmpty() && !this.from.isEmpty();
        if (!configured) {
            this.sender = null;
            return;
        }
        JavaMailSenderImpl impl = new JavaMailSenderImpl();
        impl.setHost(normalizedHost);
        impl.setPort(port);
        impl.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (username != null && !username.isBlank()) {
            impl.setUsername(username.trim());
            impl.setPassword(password == null ? "" : password);
        }
        Properties props = impl.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(username != null && !username.isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));
        this.sender = impl;
    }

    /** 是否已配置可用的 SMTP（host 与 from 均非空）。 */
    public boolean configured() {
        return configured;
    }

    /**
     * 发送 HTML 邮件（带一个 CSV 附件）。
     *
     * @param recipients 收件人（逗号/分号分隔，服务层已校验）
     */
    public void send(String recipients, String subject, String html,
                     String attachmentName, byte[] attachment) {
        if (!configured) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "未配置邮件服务器（medassist.report-email.host/from），暂不能投递 EMAIL 订阅");
        }
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipients.split("\\s*[;,]+\\s*"));
            helper.setSubject(subject);
            helper.setText(html, true);
            helper.addAttachment(attachmentName,
                    new ByteArrayResource(attachment), "text/csv;charset=UTF-8");
            sender.send(message);
            log.info("clinical.report_email.sent recipients={} subject={}", recipients, subject);
        } catch (MessagingException e) {
            throw new IllegalStateException("邮件发送失败：" + e.getMessage(), e);
        }
    }
}
