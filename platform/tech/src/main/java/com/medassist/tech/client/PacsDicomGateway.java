package com.medassist.tech.client;

import java.util.List;

/**
 * PACS DICOMweb 访问边界（QIDO-RS 查询序列 / WADO-RS 取影像）。
 *
 * <p>抽出接口的目的：业务侧（阅片台）只依赖"能否列出序列 / 取到影像"，
 * 单测可注入假实现，不需要真的起一个 PACS。
 */
public interface PacsDicomGateway {

    /** PACS 是否已配置（未配置时调阅接口应返回 503，而不是空列表）。 */
    boolean configured();

    /** QIDO-RS：按 StudyInstanceUID 查询序列。 */
    List<SeriesPayload> listSeries(String studyInstanceUid);

    /** WADO-RS：取单张 DICOM 影像字节流。 */
    byte[] fetchInstance(String studyInstanceUid, String seriesInstanceUid, String sopInstanceUid);

    /** QIDO-RS 序列元数据（只保留阅片台需要的字段）。 */
    record SeriesPayload(String seriesInstanceUid, Integer seriesNumber, String modality,
                         String bodyPart, String description, Integer instanceCount) {
    }
}
