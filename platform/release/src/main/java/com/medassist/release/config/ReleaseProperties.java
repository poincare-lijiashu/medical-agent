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
}
