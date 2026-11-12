package com.medassist.auth.domain;

/**
 * 单院备份计划（第 286 批）。
 *
 * <p>备份不是"控制面替院方存数据"，而是控制面在开通时替这家医院**建立一套可恢复的仓库**：
 * 独立桶、独立 stanza、独立保留窗口。坐标与状态写进该院自己的 {@code auth_svc.tenant_backup}，
 * 恢复演练与巡检都直连 Cell，控制面离线也不影响院方自救。
 *
 * <p>字段约束（服务端归一化后再落库，见 {@code ProvisioningService}）：
 * <ul>
 *   <li>{@code bucket}：S3 桶名规范（3–63 位小写字母/数字/点/短横线，首尾为字母或数字）；</li>
 *   <li>{@code stanza}：pgBackRest stanza，默认等于租户编码，只允许小写字母/数字/短横线；</li>
 *   <li>{@code pitrDays}：1–35，超出范围直接拒绝——保留窗口是合同项，不接受"越大越好"；</li>
 *   <li>{@code encryption}：{@code AES-256-CBC} 或 {@code NONE}（离线演示可用 NONE，生产必须加密）。</li>
 * </ul>
 */
public record BackupPlan(
        String tenantCode,
        String endpoint,
        String bucket,
        String region,
        String stanza,
        int pitrDays,
        String encryption
) {
}
