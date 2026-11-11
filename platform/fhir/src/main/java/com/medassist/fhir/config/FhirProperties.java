package com.medassist.fhir.config;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FHIR 开放层配置。
 *
 * <p>分页边界不是"体验参数"而是安全参数：FHIR 的 {@code _count} 与 {@code _offset}
 * 由调用方决定，若不设上限，一次 {@code GET /Patient} 就能把整院患者拉走。
 */
@ConfigurationProperties(prefix = "medassist.fhir")
public class FhirProperties {

    /** 未显式传 _count 时的条数。 */
    private int defaultPageSize = 20;

    /** 单页最大条数：超过按此截断（FHIR 允许服务端覆盖客户端请求的 _count）。 */
    private int maxPageSize = 100;

    /** _offset 上限：深度翻页会拖垮数据库，超过直接拒绝而不是静默返回空。 */
    private int maxOffset = 10_000;

    /** Bundle.fullUrl 前缀；留空表示按当前请求推断。 */
    private String baseUrl = "";

    public int pageSize(Integer requested) {
        int size = requested == null ? defaultPageSize : requested;
        if (size < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "_count 必须是大于 0 的整数");
        }
        return Math.min(size, maxPageSize);
    }

    public int offset(Integer requested) {
        int offset = requested == null ? 0 : requested;
        if (offset < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "_offset 不能为负数");
        }
        if (offset > maxOffset) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "_offset 超过上限 " + maxOffset + "，请缩小检索范围（如增加 date/patient 条件）");
        }
        return offset;
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

    public int getMaxOffset() {
        return maxOffset;
    }

    public void setMaxOffset(int maxOffset) {
        this.maxOffset = maxOffset;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
