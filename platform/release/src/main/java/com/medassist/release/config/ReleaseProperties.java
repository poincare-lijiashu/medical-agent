package com.medassist.release.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 发布边界配置。
 *
 * <p>{@code prodChecklist} 是生产部署的强制检查项：少一项就拒绝记录为成功，
 * 避免"发布完了再补检查单"；可按院方流程通过环境变量覆盖。
 */
@ConfigurationProperties(prefix = "medassist.release")
public class ReleaseProperties {

    private List<String> prodChecklist = List.of(
            "backup", "rollback_plan", "smoke_test", "oncall_confirmed", "change_ticket");
    private int maxPageSize = 200;
    private int auditListDefault = 50;
    private int auditListMax = 200;
    /**
     * 生产部署要求的灾备演练有效期（天，§297）。
     *
     * <p>超过该窗口的演练视为"没有演练"：恢复能力会随系统演进而衰减，
     * 三个月前的通过记录不能为今天的上线背书。
     */
    private int drillMaxAgeDays = 90;

    public List<String> getProdChecklist() {
        return prodChecklist;
    }

    public void setProdChecklist(List<String> prodChecklist) {
        this.prodChecklist = prodChecklist;
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

    public int getDrillMaxAgeDays() {
        return drillMaxAgeDays;
    }

    public void setDrillMaxAgeDays(int drillMaxAgeDays) {
        this.drillMaxAgeDays = drillMaxAgeDays;
    }
}
