package com.medassist.auth.service;

import com.medassist.auth.security.S3RequestSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * 基于 S3 协议的备份仓库实现（第 286 批）。
 *
 * <p>只做两件事：探测桶是否存在（HEAD）、不存在则创建（PUT）。这两步覆盖了
 * "开通即具备独立备份仓库"的交付要求；真正的备份/恢复由 Cell 内的 pgBackRest 执行。
 *
 * <p>边界与取舍：
 * <ul>
 *   <li>访问密钥来自部署 Secret（{@code BACKUP_S3_ACCESS_KEY/SECRET_KEY}），
 *       不随租户下发、不落库；</li>
 *   <li>超时必须短（3s 连接 / 6s 请求）：对象存储抖动不能挂住开通线程；</li>
 *   <li>异常消息可展示给运维，但不含密钥、不含请求头。</li>
 * </ul>
 */
@Component
public class S3BackupRepository implements BackupRepositoryGateway {

    private static final Logger log = LoggerFactory.getLogger(S3BackupRepository.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);

    private final S3RequestSigner signer;
    private final String accessKey;
    private final String secretKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    public S3BackupRepository(
            S3RequestSigner signer,
            @Value("${medassist.backup.access-key:${BACKUP_S3_ACCESS_KEY:minioadmin}}") String accessKey,
            @Value("${medassist.backup.secret-key:${BACKUP_S3_SECRET_KEY:minioadmin}}") String secretKey) {
        this.signer = signer;
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
    }

    @Override
    public Result ensureBucket(String endpoint, String region, String bucket) {
        long start = System.nanoTime();
        Endpoint target = Endpoint.parse(endpoint, bucket);
        String zone = region == null || region.isBlank() ? "us-east-1" : region.trim();

        int head = send("HEAD", target, zone);
        if (head == 200) {
            return new Result(true, false, elapsedMs(start));
        }
        if (head == 404) {
            int put = send("PUT", target, zone);
            if (put == 200 || put == 201 || put == 204) {
                log.info("backup.bucket.created bucket={} latencyMs={}", bucket, elapsedMs(start));
                return new Result(false, true, elapsedMs(start));
            }
            // 并发开通时另一实例刚建好：409/已存在按成功处理
            if (put == 409) {
                return new Result(true, false, elapsedMs(start));
            }
            throw new BackupRepositoryGateway.BackupRepositoryException(
                    "创建备份桶失败：HTTP " + put + "（检查访问密钥的 s3:CreateBucket 权限）");
        }
        if (head == 403) {
            throw new BackupRepositoryGateway.BackupRepositoryException(
                    "对象存储拒绝访问：检查备份访问密钥与桶策略");
        }
        throw new BackupRepositoryGateway.BackupRepositoryException("探测备份桶失败：HTTP " + head);
    }

    // ---------------------------------------------------------------- 内部

    private int send(String method, Endpoint target, String region) {
        String payloadHash = S3RequestSigner.emptyPayloadHash();
        S3RequestSigner.Signed signed = signer.sign(method, target.path(), target.host(), region,
                accessKey, secretKey, Instant.now(), payloadHash);
        HttpRequest request = HttpRequest.newBuilder(target.uri())
                .timeout(REQUEST_TIMEOUT)
                .header("x-amz-content-sha256", signed.payloadHash())
                .header("x-amz-date", signed.amzDate())
                .header("Authorization", signed.authorization())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> response = http.send(request,
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupRepositoryGateway.BackupRepositoryException("备份仓库操作被中断");
        } catch (Exception e) {
            throw new BackupRepositoryGateway.BackupRepositoryException(
                    "备份仓库不可达：" + e.getClass().getSimpleName()
                            + "（检查端点和网络策略）");
        }
    }

    private static String trimSlash(String value) {
        String trimmed = value == null ? "" : value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    /** 桶级请求的目标：URI、签名用 host（含端口）与 canonical path。 */
    private record Endpoint(URI uri, String host, String path) {

        static Endpoint parse(String endpoint, String bucket) {
            String base = trimSlash(endpoint);
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                throw new BackupRepositoryGateway.BackupRepositoryException(
                        "备份端点必须是 http(s) URL：" + (base.isEmpty() ? "(空)" : base));
            }
            URI uri;
            try {
                uri = URI.create(base + "/" + bucket);
            } catch (IllegalArgumentException e) {
                throw new BackupRepositoryGateway.BackupRepositoryException("备份端点格式不合法");
            }
            String host = uri.getRawAuthority();
            if (host == null || host.isBlank()) {
                throw new BackupRepositoryGateway.BackupRepositoryException("备份端点缺少主机名");
            }
            return new Endpoint(uri, host, "/" + bucket);
        }
    }
}
