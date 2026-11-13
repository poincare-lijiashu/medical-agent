package com.medassist.ops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 运营支撑边界配置。
 *
 * <p>{@code slaScanMs} 是超时扫描周期：太频繁会无谓扫表，太稀疏会让"超时"发现滞后；
 * 默认 60 秒与工单 SLA 的最小粒度（分钟）匹配。
 * {@code previewRows} 是详情页默认返回的事件条数，避免长工单一次拉几千条。
 */
@ConfigurationProperties(prefix = "medassist.ops")
public class OpsProperties {

    private long slaScanMs = 60_000L;
    private int previewRows = 100;
    private int maxPageSize = 200;
    private int auditListDefault = 50;
    private int auditListMax = 200;
    /** 实施上线就绪的最低加权得分（§298）；未达到不能批准上线。 */
    private int implMinScore = 90;
    /** 培训考核通过率门槛（百分比，§298）；按唯一学员统计。 */
    private int implTrainingThreshold = 95;

    public long getSlaScanMs() {
        return slaScanMs;
    }

    public void setSlaScanMs(long slaScanMs) {
        this.slaScanMs = slaScanMs;
    }

    public int getPreviewRows() {
        return previewRows;
    }

    public void setPreviewRows(int previewRows) {
        this.previewRows = previewRows;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
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

    public int getImplMinScore() {
        return implMinScore;
    }

    public void setImplMinScore(int implMinScore) {
        this.implMinScore = implMinScore;
    }

    public int getImplTrainingThreshold() {
        return implTrainingThreshold;
    }

    public void setImplTrainingThreshold(int implTrainingThreshold) {
        this.implTrainingThreshold = implTrainingThreshold;
    }
}
