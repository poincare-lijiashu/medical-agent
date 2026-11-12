package com.medassist.auth.service;

/**
 * 备份仓库端口（第 286 批）。
 *
 * <p>把"建桶/探桶"收在接口后面，理由与 {@link CellDatabaseGateway} 相同：
 * 开通编排必须能被单元测试，不能因为测试环境没有对象存储就跳过这一步。
 *
 * <p>实现约定：<b>不吞异常</b>。仓库不可用必须抛 {@link BackupRepositoryException}
 * （消息可展示给运维，但不含访问密钥），由开通步骤决定"阻塞还是降级"。
 */
public interface BackupRepositoryGateway {

    /**
     * 确保该院独立桶存在：存在则跳过，不存在则创建。
     *
     * @param endpoint 对象存储端点（如 {@code http://minio:9000}）
     * @param region   SigV4 签名区域（MinIO 通常用 {@code us-east-1}）
     * @param bucket   桶名（已由编排归一化校验）
     */
    Result ensureBucket(String endpoint, String region, String bucket);

    /**
     * @param existed 调用前是否已存在
     * @param created 本次是否真的创建
     * @param latencyMs 探测 + 创建的总耗时
     */
    record Result(boolean existed, boolean created, long latencyMs) {
    }

    /** 备份仓库不可用（网络/鉴权/权限/协议错误）。 */
    class BackupRepositoryException extends RuntimeException {
        public BackupRepositoryException(String message) {
            super(message);
        }
    }
}
