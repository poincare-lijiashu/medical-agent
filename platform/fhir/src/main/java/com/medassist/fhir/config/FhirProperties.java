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

    /** XDS 单份文档上限：默认 5MB，超过直接拒绝（既防内存打爆，也符合最小必要）。 */
    private int xdsMaxDocumentBytes = 5 * 1024 * 1024;

    /** 文档中的机构 OID 根（院方在区域平台注册的 OID）；默认值仅用于本地联调。 */
    private String xdsRootOid = "1.2.156.112.1.1";

    /** 文档保管机构名称（写入 CDA custodian）。 */
    private String xdsCustodianName = "铭医医院信息平台";

    /** 出院小结 CDA 模板 OID（WS 445 出院小结）；上线前由院方按属地规范核对。 */
    private String xdsDischargeTemplateId = "2.16.156.10011.2.1.1.31";

    /** 检验报告 CDA 模板 OID（WS 445 检验报告）。 */
    private String xdsLabTemplateId = "2.16.156.10011.2.1.1.28";

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

    public int getXdsMaxDocumentBytes() {
        return xdsMaxDocumentBytes;
    }

    public void setXdsMaxDocumentBytes(int xdsMaxDocumentBytes) {
        this.xdsMaxDocumentBytes = xdsMaxDocumentBytes;
    }

    public String getXdsRootOid() {
        return xdsRootOid;
    }

    public void setXdsRootOid(String xdsRootOid) {
        this.xdsRootOid = xdsRootOid;
    }

    public String getXdsCustodianName() {
        return xdsCustodianName;
    }

    public void setXdsCustodianName(String xdsCustodianName) {
        this.xdsCustodianName = xdsCustodianName;
    }

    public String getXdsDischargeTemplateId() {
        return xdsDischargeTemplateId;
    }

    public void setXdsDischargeTemplateId(String xdsDischargeTemplateId) {
        this.xdsDischargeTemplateId = xdsDischargeTemplateId;
    }

    public String getXdsLabTemplateId() {
        return xdsLabTemplateId;
    }

    public void setXdsLabTemplateId(String xdsLabTemplateId) {
        this.xdsLabTemplateId = xdsLabTemplateId;
    }
}
