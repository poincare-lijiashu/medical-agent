package com.medassist.tech.pacs;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.config.TechProperties;
import jakarta.annotation.PreDestroy;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.DataWriterAdapter;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 基于 dcm4che 的 DIMSE SCU 实现。
 *
 * <p>每次调用新建一条 Association，用完立即 release——DICOM 关联是有状态的，
 * 复用同一条关联在并发/超时下容易把响应串到下一条请求上；影像网关的调用频率
 * 远低于业务接口，这点建连成本值得。
 *
 * <p>线程池为守护线程：服务停机时不会因为 dcm4che 的后台调度线程而挂住。
 */
@Component
public class Dcm4cheDimseClient implements PacsDimseGateway {

    private static final Logger log = LoggerFactory.getLogger(Dcm4cheDimseClient.class);

    /** 单次 C-FIND 最多返回条数：防止对端返回海量结果把内存打满。 */
    static final int MAX_FIND_RESULTS = 500;

    private final TechProperties.Dimse config;
    private final Device device;
    private final Connection local;
    private final ApplicationEntity ae;
    private final ScheduledExecutorService executor;

    public Dcm4cheDimseClient(TechProperties properties) {
        this.config = properties.pacsOrDefault().dimseOrDefault();
        this.executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "dimse-scu");
            thread.setDaemon(true);
            return thread;
        });
        this.device = new Device("MEDASSIST_DIMSE_SCU");
        this.device.setScheduledExecutor(executor);
        this.device.setExecutor(executor);
        this.local = new Connection();
        this.local.setConnectTimeout(millis(config.connectTimeoutOrDefault()));
        this.local.setRequestTimeout(millis(config.requestTimeoutOrDefault()));
        this.local.setResponseTimeout(millis(config.requestTimeoutOrDefault()));
        this.local.setReleaseTimeout(5_000);
        this.local.setTcpNoDelay(true);
        this.ae = new ApplicationEntity(config.callingAeOrDefault());
        this.ae.setAssociationInitiator(true);
        this.ae.setAssociationAcceptor(false);
        this.ae.addConnection(local);
        this.device.addConnection(local);
        this.device.addApplicationEntity(ae);
    }

    @Override
    public boolean enabled() {
        return config.enabledOrDefault() && !config.hostOrEmpty().isEmpty();
    }

    @Override
    public String remoteAe() {
        return config.calledAeOrDefault();
    }

    @Override
    public String remoteHost() {
        return config.hostOrEmpty();
    }

    @Override
    public boolean allowsDestination(String destinationAe) {
        return destinationAe != null && !destinationAe.isBlank()
                && config.allowedMoveDestinations()
                .contains(destinationAe.trim().toUpperCase(Locale.ROOT));
    }

    @Override
    public long maxStoreBytes() {
        return config.maxFileBytesOrDefault();
    }

    @Override
    public EchoResult echo() {
        long start = System.nanoTime();
        Association association = open(UID.Verification, UID.ImplicitVRLittleEndian);
        try {
            DimseRSP response = association.cecho();
            response.next();
            int status = status(response);
            long elapsed = elapsedMillis(start);
            if (status != 0) {
                throw upstream("C-ECHO 被拒绝", status, null);
            }
            return new EchoResult(association.getCalledAET(), config.hostOrEmpty(),
                    config.portOrDefault(), status, elapsed);
        } catch (IOException | InterruptedException e) {
            throw upstream("C-ECHO 连接失败", -1, e);
        } finally {
            release(association);
        }
    }

    @Override
    public List<StudyResult> findStudies(StudyQuery query) {
        Attributes keys = studyKeys(query);
        Association association = open(UID.StudyRootQueryRetrieveInformationModelFind,
                UID.ImplicitVRLittleEndian);
        try {
            DimseRSP response = association.cfind(
                    UID.StudyRootQueryRetrieveInformationModelFind, Priority.NORMAL,
                    keys, UID.ImplicitVRLittleEndian, 0);
            List<StudyResult> results = new ArrayList<>();
            while (response.next()) {
                Attributes dataset = response.getDataset();
                if (dataset != null) {
                    results.add(toStudy(dataset));
                }
                if (results.size() >= MAX_FIND_RESULTS) {
                    log.warn("pacs.dimse.find.truncated limit={}", MAX_FIND_RESULTS);
                    break;
                }
            }
            int status = status(response);
            if (status != 0) {
                throw upstream("C-FIND 返回失败", status, null);
            }
            return results;
        } catch (IOException | InterruptedException e) {
            throw upstream("C-FIND 连接失败", -1, e);
        } finally {
            release(association);
        }
    }

    @Override
    public List<SeriesResult> findSeries(String studyInstanceUid) {
        Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, "SERIES");
        keys.setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUid);
        keys.setString(Tag.SeriesInstanceUID, VR.UI, "");
        keys.setString(Tag.Modality, VR.CS, "");
        keys.setString(Tag.SeriesNumber, VR.IS, "");
        keys.setString(Tag.SeriesDescription, VR.LO, "");
        keys.setString(Tag.NumberOfSeriesRelatedInstances, VR.IS, "");

        Association association = open(UID.StudyRootQueryRetrieveInformationModelFind,
                UID.ImplicitVRLittleEndian);
        try {
            DimseRSP response = association.cfind(
                    UID.StudyRootQueryRetrieveInformationModelFind, Priority.NORMAL,
                    keys, UID.ImplicitVRLittleEndian, 0);
            List<SeriesResult> results = new ArrayList<>();
            while (response.next()) {
                Attributes dataset = response.getDataset();
                if (dataset != null) {
                    results.add(toSeries(dataset));
                }
                if (results.size() >= MAX_FIND_RESULTS) {
                    log.warn("pacs.dimse.find-series.truncated limit={}", MAX_FIND_RESULTS);
                    break;
                }
            }
            int status = status(response);
            if (status != 0) {
                throw upstream("C-FIND(序列) 返回失败", status, null);
            }
            return results;
        } catch (IOException | InterruptedException e) {
            throw upstream("C-FIND(序列) 连接失败", -1, e);
        } finally {
            release(association);
        }
    }

    @Override
    public MoveResult moveStudy(String studyInstanceUid, String destinationAe) {
        Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        keys.setString(Tag.StudyInstanceUID, VR.UI, studyInstanceUid);

        Association association = open(UID.StudyRootQueryRetrieveInformationModelMove,
                UID.ImplicitVRLittleEndian);
        try {
            DimseRSP response = association.cmove(
                    UID.StudyRootQueryRetrieveInformationModelMove, Priority.NORMAL,
                    keys, UID.ImplicitVRLittleEndian, destinationAe);
            while (response.next()) {
                // C-MOVE 可能先回 pending（子操作进度），最后一帧才是最终状态
            }
            Attributes command = response.getCommand();
            return new MoveResult(
                    command.getInt(Tag.Status, -1),
                    command.getInt(Tag.NumberOfCompletedSuboperations, 0),
                    command.getInt(Tag.NumberOfFailedSuboperations, 0),
                    command.getInt(Tag.NumberOfWarningSuboperations, 0));
        } catch (IOException | InterruptedException e) {
            throw upstream("C-MOVE 连接失败", -1, e);
        } finally {
            release(association);
        }
    }

    @Override
    public StoreResult store(byte[] dicom) {
        if (dicom == null || dicom.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "DICOM 文件为空");
        }
        if (dicom.length > config.maxFileBytesOrDefault()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "DICOM 文件超过上限 " + config.maxFileBytesOrDefault() + " 字节");
        }
        Attributes dataset;
        String transferSyntax;
        try (DicomInputStream input = new DicomInputStream(new ByteArrayInputStream(dicom))) {
            input.setIncludeBulkData(DicomInputStream.IncludeBulkData.YES);
            input.readFileMetaInformation();
            dataset = input.readDataset();
            transferSyntax = input.getTransferSyntax();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "无法解析 DICOM 文件：请确认是带 DICM 前缀的 DICOM 实例");
        }
        String sopClassUid = dataset.getString(Tag.SOPClassUID);
        String sopInstanceUid = dataset.getString(Tag.SOPInstanceUID);
        if (sopClassUid == null || sopClassUid.isBlank()
                || sopInstanceUid == null || sopInstanceUid.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "DICOM 文件缺少 SOPClassUID/SOPInstanceUID，不能推送");
        }

        Association association = open(sopClassUid, transferSyntax);
        try {
            DimseRSP response = association.cstore(sopClassUid, sopInstanceUid, Priority.NORMAL,
                    DataWriterAdapter.forAttributes(dataset), transferSyntax);
            response.next();
            int status = status(response);
            return new StoreResult(status, sopClassUid, sopInstanceUid, dicom.length);
        } catch (IOException | InterruptedException e) {
            throw upstream("C-STORE 连接失败", -1, e);
        } finally {
            release(association);
        }
    }

    // ---------------------------------------------------------------- 内部

    private Association open(String abstractSyntax, String... transferSyntaxes) {
        if (!enabled()) {
            throw new BusinessException(ErrorCode.TECH_PACS_NOT_CONFIGURED,
                    "PACS DIMSE 未启用：请配置 tech.pacs.dimse.enabled/host（影像仍可走 DICOMweb 调阅）");
        }
        try {
            Connection remote = new Connection();
            remote.setHostname(config.hostOrEmpty());
            remote.setPort(config.portOrDefault());
            remote.setConnectTimeout(millis(config.connectTimeoutOrDefault()));

            AAssociateRQ request = new AAssociateRQ();
            request.setCallingAET(config.callingAeOrDefault());
            request.setCalledAET(config.calledAeOrDefault());
            request.addPresentationContext(new PresentationContext(1, abstractSyntax,
                    transferSyntaxes == null || transferSyntaxes.length == 0
                            ? new String[]{UID.ImplicitVRLittleEndian} : transferSyntaxes));
            return ae.connect(local, remote, request);
        } catch (IOException e) {
            throw upstream("无法连接 PACS（" + config.hostOrEmpty() + ":" + config.portOrDefault()
                    + "，AE " + config.calledAeOrDefault() + "）", -1, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw upstream("连接 PACS 被中断", -1, e);
        } catch (GeneralSecurityException | org.dcm4che3.net.IncompatibleConnectionException e) {
            throw upstream("DICOM 关联协商失败", -1, e);
        }
    }

    private static int status(DimseRSP response) {
        Attributes command = response.getCommand();
        return command == null ? -1 : command.getInt(Tag.Status, -1);
    }

    private static void release(Association association) {
        if (association == null) {
            return;
        }
        try {
            association.release();
        } catch (IOException e) {
            association.abort();
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static int millis(java.time.Duration duration) {
        long value = duration.toMillis();
        return (int) Math.max(1, Math.min(value, Integer.MAX_VALUE));
    }

    private static BusinessException upstream(String message, int status, Exception cause) {
        String detail = status >= 0
                ? message + "（状态 0x" + String.format(Locale.ROOT, "%04X", status) + "）"
                : message;
        if (cause != null) {
            detail = detail + "：" + (cause.getMessage() == null
                    ? cause.getClass().getSimpleName() : cause.getMessage());
        }
        return new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED, detail);
    }

    /** C-FIND 的 Study 级检索键（静态可见，便于单测断言，不需要真实 PACS）。 */
    static Attributes studyKeys(StudyQuery query) {
        Attributes keys = new Attributes();
        keys.setString(Tag.QueryRetrieveLevel, VR.CS, "STUDY");
        if (notBlank(query.patientId())) {
            keys.setString(Tag.PatientID, VR.LO, query.patientId());
        }
        if (notBlank(query.patientName())) {
            keys.setString(Tag.PatientName, VR.PN, query.patientName());
        }
        if (notBlank(query.accessionNumber())) {
            keys.setString(Tag.AccessionNumber, VR.SH, query.accessionNumber());
        }
        if (notBlank(query.studyDateFrom()) || notBlank(query.studyDateTo())) {
            String from = notBlank(query.studyDateFrom()) ? query.studyDateFrom() : "";
            String to = notBlank(query.studyDateTo()) ? query.studyDateTo() : "";
            keys.setString(Tag.StudyDate, VR.DA, from + "-" + to);
        }
        if (notBlank(query.modality())) {
            keys.setString(Tag.ModalitiesInStudy, VR.CS, query.modality());
        }
        keys.setString(Tag.StudyInstanceUID, VR.UI, "");
        keys.setString(Tag.StudyDescription, VR.LO, "");
        keys.setString(Tag.StudyTime, VR.TM, "");
        keys.setString(Tag.NumberOfStudyRelatedSeries, VR.IS, "");
        keys.setString(Tag.NumberOfStudyRelatedInstances, VR.IS, "");
        return keys;
    }

    static StudyResult toStudy(Attributes dataset) {
        return new StudyResult(
                dataset.getString(Tag.StudyInstanceUID),
                dataset.getString(Tag.PatientID),
                normalizePersonName(dataset.getString(Tag.PatientName)),
                dataset.getString(Tag.AccessionNumber),
                dataset.getString(Tag.StudyDate),
                dataset.getString(Tag.StudyTime),
                dataset.getString(Tag.StudyDescription),
                join(dataset.getStrings(Tag.ModalitiesInStudy)),
                intOrNull(dataset, Tag.NumberOfStudyRelatedSeries),
                intOrNull(dataset, Tag.NumberOfStudyRelatedInstances));
    }

    static SeriesResult toSeries(Attributes dataset) {
        return new SeriesResult(
                dataset.getString(Tag.SeriesInstanceUID),
                dataset.getString(Tag.Modality),
                intOrNull(dataset, Tag.SeriesNumber),
                dataset.getString(Tag.SeriesDescription),
                intOrNull(dataset, Tag.NumberOfSeriesRelatedInstances));
    }

    private static Integer intOrNull(Attributes dataset, int tag) {
        int value = dataset.getInt(tag, -1);
        return value < 0 ? null : value;
    }

    private static String join(String[] values) {
        return values == null || values.length == 0 ? null : String.join("\\", values);
    }

    /** DICOM PN 用 {@code ^} 分隔姓/名；展示时还原为空格。 */
    private static String normalizePersonName(String value) {
        return value == null ? null : value.replace('^', ' ').trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
