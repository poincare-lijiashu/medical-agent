package com.medassist.tech.pacs;

import java.util.List;

/**
 * DIMSE（DICOM 网络协议）网关。
 *
 * <p>与 DICOMweb 的分工：
 * <ul>
 *   <li>DICOMweb（QIDO/WADO）负责**浏览器阅片**与按 UID 调阅；</li>
 *   <li>DIMSE 负责**院内系统间**的查询与传输：C-ECHO 连通性、C-FIND 对账、
 *       C-STORE 推送、C-MOVE 让 PACS 把检查推到指定 AE。</li>
 * </ul>
 *
 * <p>本接口只描述能力，不暴露主机/端口/AE：远端目标全部来自配置，
 * 请求方不能指定，避免把服务变成任意 DICOM 转发器。
 */
public interface PacsDimseGateway {

    /** 是否已启用且配置完整。 */
    boolean enabled();

    /** 配置的远端 AE（审计用）。 */
    String remoteAe();

    /** 配置的远端主机（审计用）。 */
    String remoteHost();

    /** 目的地是否在 C-MOVE 白名单内（fail-closed）。 */
    boolean allowsDestination(String destinationAe);

    /** 单个 DICOM 文件允许的最大字节数。 */
    long maxStoreBytes();

    EchoResult echo();

    List<StudyResult> findStudies(StudyQuery query);

    List<SeriesResult> findSeries(String studyInstanceUid);

    MoveResult moveStudy(String studyInstanceUid, String destinationAe);

    /** 把一个 DICOM 实例推送到已配置的远端 AE。 */
    StoreResult store(byte[] dicom);

    record EchoResult(String remoteAe, String host, int port, int statusCode, long latencyMs) {
    }

    /** C-FIND 条件；日期为 DICOM DA 格式（yyyyMMdd），空表示不限。 */
    record StudyQuery(String patientId, String patientName, String accessionNumber,
                      String studyDateFrom, String studyDateTo, String modality) {
    }

    record StudyResult(String studyInstanceUid, String patientId, String patientName,
                       String accessionNumber, String studyDate, String studyTime,
                       String description, String modalities, Integer seriesCount,
                       Integer instanceCount) {
    }

    record SeriesResult(String seriesInstanceUid, String modality, Integer seriesNumber,
                        String description, Integer instanceCount) {
    }

    record MoveResult(int statusCode, int completed, int failed, int warning) {
    }

    record StoreResult(int statusCode, String sopClassUid, String sopInstanceUid, long bytes) {
    }
}
