package com.medassist.auth.web.dto;

import java.time.Instant;
import java.util.List;

/** Cell 迁移台账出入参（§282）。 */
public final class CellMigrationDtos {

    private CellMigrationDtos() {
    }

    /**
     * 台账快照。
     *
     * @param tenantCode 租户
     * @param syncedAt   最近一次从该 Cell 拉取的时间；null 表示从未同步
     * @param reachable  该 Cell 数据库当前是否可达（不可达时返回缓存 + 原因，不抛 500）
     * @param error      不可达原因（脱敏后的短文案）
     * @param items      每服务最新一版迁移
     * @param hasFailure 是否存在失败迁移（失败必须显式暴露）
     */
    public record CellMigrationView(
            String tenantCode,
            Instant syncedAt,
            boolean reachable,
            String error,
            boolean hasFailure,
            List<Item> items
    ) {
    }

    public record Item(String service, String version, String description,
                       boolean success, Instant installedOn) {
    }
}
