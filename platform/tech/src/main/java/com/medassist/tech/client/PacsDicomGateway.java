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

    /** QIDO-RS：分页查询序列下的影像（按 InstanceNumber 排序）。 */
    List<InstanceMeta> listInstances(String studyInstanceUid, String seriesInstanceUid,
                                     int limit, int offset);

    /** WADO-RS：取单张 DICOM 影像字节流。 */
    byte[] fetchInstance(String studyInstanceUid, String seriesInstanceUid, String sopInstanceUid);

    /** WADO-RS rendered：取浏览器可直接显示的预览图（JPEG）。 */
    byte[] fetchRendered(String studyInstanceUid, String seriesInstanceUid, String sopInstanceUid);

    /** QIDO-RS 序列元数据（只保留阅片台需要的字段）。 */
    record SeriesPayload(String seriesInstanceUid, Integer seriesNumber, String modality,
                         String bodyPart, String description, Integer instanceCount) {
    }

    /** QIDO-RS 影像元数据（分页浏览与预览定位用）。 */
    record InstanceMeta(String sopInstanceUid, Integer instanceNumber, String sopClassUid) {
    }
}
