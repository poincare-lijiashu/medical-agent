package com.medassist.masterdata.config;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 主数据服务配置。
 *
 * <p>分页与导入上限不是体验参数而是安全参数：导入接口一次可以提交数千行，
 * 不设上限会把数据库连接、审计写入与内存同时打满；分页不设上限则一次请求
 * 可以导出全院字典。
 */
@ConfigurationProperties(prefix = "medassist.masterdata")
public class MasterDataProperties {

    /** 未显式传 size 时的条数。 */
    private int defaultPageSize = 20;

    /** 单页最大条数（与平台 PageResult 上限一致）。 */
    private int maxPageSize = 200;

    /** 单次导入最大行数。 */
    private int maxImportRows = 5000;

    /** 预检结果中返回的样例行数（其余落库可查）。 */
    private int previewSampleRows = 20;

    /** 版本历史单页最大条数。 */
    private int maxVersionPageSize = 100;

    /** 源表同步"过期"阈值（天）：超过该天数未同步会在质量看板标记 stale。 */
    private int syncStaleDays = 7;

    /** 审计查询默认/最大条数。 */
    private int auditListDefault = 50;
    private int auditListMax = 200;

    public int pageSize(Integer requested) {
        int size = requested == null ? defaultPageSize : requested;
        if (size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "每页条数必须是大于 0 的整数");
        }
        return Math.min(size, maxPageSize);
    }

    public int page(Integer requested) {
        int page = requested == null ? 1 : requested;
        if (page < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "页码从 1 开始");
        }
        return page;
    }

    public int offset(int page, int size) {
        long offset = (long) (page - 1) * size;
        if (offset > 100_000L) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "翻页过深，请缩小检索范围（如增加域或关键字条件）");
        }
        return (int) offset;
    }

    public int importRows(int requested) {
        if (requested < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "导入内容不能为空");
        }
        if (requested > maxImportRows) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "单次导入最多 " + maxImportRows + " 行，请拆分后再导入");
        }
        return requested;
    }

    public int auditLimit(Integer requested) {
        int size = requested == null ? auditListDefault : requested;
        if (size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "查询条数必须是大于 0 的整数");
        }
        return Math.min(size, auditListMax);
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getMaxImportRows() {
        return maxImportRows;
    }

    public void setMaxImportRows(int maxImportRows) {
        this.maxImportRows = maxImportRows;
    }

    public int getPreviewSampleRows() {
        return previewSampleRows;
    }

    public void setPreviewSampleRows(int previewSampleRows) {
        this.previewSampleRows = previewSampleRows;
    }

    public int getMaxVersionPageSize() {
        return maxVersionPageSize;
    }

    public void setMaxVersionPageSize(int maxVersionPageSize) {
        this.maxVersionPageSize = maxVersionPageSize;
    }

    public int getSyncStaleDays() {
        return syncStaleDays;
    }

    public void setSyncStaleDays(int syncStaleDays) {
        this.syncStaleDays = syncStaleDays;
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
