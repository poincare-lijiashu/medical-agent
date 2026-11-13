package com.medassist.fhir.xds;

import java.util.Set;

/**
 * XDS 文档共享的固定编码与边界。
 *
 * <p>为什么集中在一个类：文档类型/格式/保密级别会被注册、检索、CDA 生成、
 * 前端筛选同时引用。分散写字符串，迟早出现"注册用 LOINC、检索用别名"的漂移。
 *
 * <p>编码来源：
 * <ul>
 *   <li>{@code classCode}/{@code typeCode}：LOINC；</li>
 *   <li>{@code formatCode}：IHE 文档格式码（CDA 结构化体 / IHE 检验文档）；</li>
 *   <li>{@code confidentialityCode}：HL7 Table 0907。</li>
 * </ul>
 */
public final class XdsCodes {

    /** 出院小结（LOINC 18842-5）。 */
    public static final String DISCHARGE_CLASS = "18842-5";
    public static final String DISCHARGE_CLASS_DISPLAY = "Discharge summary";
    public static final String DISCHARGE_TYPE = "34133-9";
    public static final String DISCHARGE_TYPE_DISPLAY = "Summarization of episode note";

    /** 检验报告（LOINC 11502-2）。 */
    public static final String LAB_CLASS = "11502-2";
    public static final String LAB_CLASS_DISPLAY = "Laboratory report";
    public static final String LAB_TYPE = "11502-2";
    public static final String LAB_TYPE_DISPLAY = "Laboratory report";

    /** CDA R2 结构化体（IHE/HL7 通用格式码）。 */
    public static final String CDA_FORMAT = "urn:hl7-org:sdwg:ccda-structuredBody:2.1";
    public static final String CDA_FORMAT_DISPLAY = "CDA R2 structured body";
    /** IHE 检验文档格式码。 */
    public static final String LAB_FORMAT = "urn:ihe:lab:xd-lab:2008";
    public static final String LAB_FORMAT_DISPLAY = "IHE Laboratory Document";

    public static final String CDA_MIME = "text/xml";
    public static final String DEFAULT_LANGUAGE = "zh-CN";

    /** 允许注册的内容类型：先收窄到可校验的文本/PDF，避免变成任意文件中转站。 */
    public static final Set<String> ALLOWED_MIME = Set.of(
            "text/xml", "application/xml", "application/pdf", "text/plain");

    /** 保密级别：N 正常 / R 限制 / V 很敏感 / M 仅限特定人员。 */
    public static final Set<String> CONFIDENTIALITY = Set.of("N", "R", "V", "M");

    private XdsCodes() {
    }
}
