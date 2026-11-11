package com.medassist.migration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 迁移工具边界配置。
 *
 * <p>这些不是"可调参数"，而是安全与性能边界：
 * <ul>
 *   <li>{@code maxRows}：单批上限。迁移必须分批，单批过大既拖长事务锁表，也让失败重试代价过高；</li>
 *   <li>{@code previewSampleRows}：批次详情默认返回行数，避免一次把上万行 JSON 全推给前端；</li>
 *   <li>{@code maxBatchPageSize}：批次列表上限；</li>
 *   <li>{@code auditListDefault/Max}：审计查询分页边界。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "medassist.migration")
public class MigrationProperties {

    private int maxRows = 5000;
    private int previewSampleRows = 100;
    private int maxBatchPageSize = 200;
    private int auditListDefault = 50;
    private int auditListMax = 200;

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getPreviewSampleRows() {
        return previewSampleRows;
    }

    public void setPreviewSampleRows(int previewSampleRows) {
        this.previewSampleRows = previewSampleRows;
    }

    public int getMaxBatchPageSize() {
        return maxBatchPageSize;
    }

    public void setMaxBatchPageSize(int maxBatchPageSize) {
        this.maxBatchPageSize = maxBatchPageSize;
    }

    public int getAuditListDefault() {
        return auditListDefault;
    }

    public void setAuditListDefault(int auditListDefault) {
        this.auditListDefault = auditListDefault;
    }

    public int getAuditListMax() {
        return auditListMax;
    }

    public void setAuditListMax(int auditListMax) {
        this.auditListMax = auditListMax;
    }
}
