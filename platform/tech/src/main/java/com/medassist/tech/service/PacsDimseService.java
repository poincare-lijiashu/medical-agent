package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.Masks;
import com.medassist.tech.pacs.PacsDimseGateway;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.PacsDimseDtos.EchoView;
import com.medassist.tech.web.PacsDimseDtos.FindStudiesReq;
import com.medassist.tech.web.PacsDimseDtos.MoveView;
import com.medassist.tech.web.PacsDimseDtos.SeriesView;
import com.medassist.tech.web.PacsDimseDtos.StoreView;
import com.medassist.tech.web.PacsDimseDtos.StudyView;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PACS DIMSE 编排：参数校验、脱敏、审计与指标。
 *
 * <p>与 {@link PacsDicomService} 的边界：这里不做 DICOM 协议细节（在
 * {@link PacsDimseGateway}），只负责"该不该做、做完记什么"。
 *
 * <p>审计失败不阻断操作：影像可能已经推到对端，回滚不了；此时返回失败反而
 * 让调用方误以为"没推"。审计写失败会以 ERROR 日志留痕，由运维补录。
 */
@Service
public class PacsDimseService {

    private static final Logger log = LoggerFactory.getLogger(PacsDimseService.class);
    private static final Pattern UID = Pattern.compile("[0-9]+(\\.[0-9]+)+");
    private static final DateTimeFormatter DICOM_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int MAX_DETAIL_CHARS = 300;

    private final PacsDimseGateway gateway;
    private final TechRepository repo;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meter;

    public PacsDimseService(PacsDimseGateway gateway, TechRepository repo,
                            ObjectMapper objectMapper, MeterRegistry meter) {
        this.gateway = gateway;
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.meter = meter;
    }

    public EchoView echo(String actor) {
        String operator = requireActor(actor);
        requireEnabled();
        long start = System.nanoTime();
        try {
            PacsDimseGateway.EchoResult result = gateway.echo();
            long elapsed = elapsedMillis(start);
            audit(operator, "ECHO", "OK", result.statusCode(), elapsed, null, null,
                    Map.of("latencyMs", result.latencyMs(), "remoteAe", result.remoteAe()));
            metric("echo", "ok", elapsed);
            return new EchoView(result.remoteAe(), result.host(), result.port(),
                    result.statusCode(), result.latencyMs());
        } catch (BusinessException e) {
            long elapsed = elapsedMillis(start);
            auditFailure(operator, "ECHO", elapsed, e);
            metric("echo", "failed", elapsed);
            throw e;
        }
    }

    public List<StudyView> findStudies(FindStudiesReq request, String actor, boolean phiReadable) {
        String operator = requireActor(actor);
        requireEnabled();
        if (request.studyDateFrom() != null && request.studyDateTo() != null
                && request.studyDateFrom().isAfter(request.studyDateTo())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "检查日期起始不能晚于结束日期");
        }
        PacsDimseGateway.StudyQuery query = new PacsDimseGateway.StudyQuery(
                trimToNull(request.patientId()),
                trimToNull(request.patientName()),
                trimToNull(request.accessionNumber()),
                dicomDate(request.studyDateFrom()),
                dicomDate(request.studyDateTo()),
                upperToNull(request.modality()));

        long start = System.nanoTime();
        try {
            List<PacsDimseGateway.StudyResult> results = gateway.findStudies(query);
            long elapsed = elapsedMillis(start);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("accessionNumber", query.accessionNumber());
            detail.put("results", results.size());
            audit(operator, "FIND_STUDY", "OK", 0, elapsed, null, null, detail);
            metric("find_study", "ok", elapsed);
            return results.stream().map(result -> toView(result, phiReadable)).toList();
        } catch (BusinessException e) {
            long elapsed = elapsedMillis(start);
            auditFailure(operator, "FIND_STUDY", elapsed, e);
            metric("find_study", "failed", elapsed);
            throw e;
        }
    }

    public List<SeriesView> findSeries(String studyInstanceUid, String actor) {
        String operator = requireActor(actor);
        requireEnabled();
        String studyUid = requireUid(studyInstanceUid);
        long start = System.nanoTime();
        try {
            List<PacsDimseGateway.SeriesResult> results = gateway.findSeries(studyUid);
            long elapsed = elapsedMillis(start);
            audit(operator, "FIND_SERIES", "OK", 0, elapsed, 1, null,
                    Map.of("studyInstanceUid", studyUid, "results", results.size()));
            metric("find_series", "ok", elapsed);
            return results.stream()
                    .map(r -> new SeriesView(r.seriesInstanceUid(), r.modality(),
                            r.seriesNumber(), r.description(), r.instanceCount()))
                    .toList();
        } catch (BusinessException e) {
            long elapsed = elapsedMillis(start);
            auditFailure(operator, "FIND_SERIES", elapsed, e);
            metric("find_series", "failed", elapsed);
            throw e;
        }
    }

    public MoveView move(String studyInstanceUid, String destinationAe, String actor) {
        String operator = requireActor(actor);
        requireEnabled();
        String studyUid = requireUid(studyInstanceUid);
        String destination = destinationAe == null ? "" : destinationAe.trim();
        if (!gateway.allowsDestination(destination)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "C-MOVE 目的地未在白名单内：" + destination
                            + "（由 tech.pacs.dimse.move-destinations 配置，禁止任意推送）");
        }

        long start = System.nanoTime();
        PacsDimseGateway.MoveResult result;
        try {
            result = gateway.moveStudy(studyUid, destination);
        } catch (BusinessException e) {
            long elapsed = elapsedMillis(start);
            auditFailure(operator, "MOVE", elapsed, e);
            metric("move", "failed", elapsed);
            throw e;
        }
        long elapsed = elapsedMillis(start);
        boolean ok = result.statusCode() == 0;
        int subOperations = result.completed() + result.failed() + result.warning();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("studyInstanceUid", studyUid);
        detail.put("destinationAe", destination);
        detail.put("completed", result.completed());
        detail.put("failed", result.failed());
        detail.put("warning", result.warning());
        audit(operator, "MOVE", ok ? "OK" : "FAILED", result.statusCode(), elapsed,
                1, subOperations, detail);
        metric("move", ok ? "ok" : "failed", elapsed);
        if (!ok) {
            throw new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED,
                    "C-MOVE 返回状态 " + hex(result.statusCode())
                            + "（完成 " + result.completed() + "，失败 " + result.failed()
                            + "，警告 " + result.warning() + "）");
        }
        return new MoveView(studyUid, destination, result.statusCode(),
                result.completed(), result.failed(), result.warning());
    }

    public StoreView store(MultipartFile file, String actor) {
        String operator = requireActor(actor);
        requireEnabled();
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "DICOM 文件为空");
        }
        if (file.getSize() > gateway.maxStoreBytes()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "DICOM 文件超过上限 " + gateway.maxStoreBytes() + " 字节");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "读取上传文件失败");
        }

        long start = System.nanoTime();
        PacsDimseGateway.StoreResult result;
        try {
            result = gateway.store(bytes);
        } catch (BusinessException e) {
            long elapsed = elapsedMillis(start);
            auditFailure(operator, "STORE", elapsed, e);
            metric("store", "failed", elapsed);
            throw e;
        }
        long elapsed = elapsedMillis(start);
        boolean ok = result.statusCode() == 0;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sopClassUid", result.sopClassUid());
        detail.put("sopInstanceUid", result.sopInstanceUid());
        detail.put("bytes", result.bytes());
        audit(operator, "STORE", ok ? "OK" : "FAILED", result.statusCode(), elapsed,
                null, 1, detail);
        metric("store", ok ? "ok" : "failed", elapsed);
        if (!ok) {
            throw new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED,
                    "C-STORE 返回状态 " + hex(result.statusCode()) + "，影像未确认接收");
        }
        return new StoreView(result.sopClassUid(), result.sopInstanceUid(),
                result.bytes(), result.statusCode());
    }

    // ---------------------------------------------------------------- 内部

    private void requireEnabled() {
        if (!gateway.enabled()) {
            throw new BusinessException(ErrorCode.TECH_PACS_NOT_CONFIGURED,
                    "PACS DIMSE 未启用：请配置 tech.pacs.dimse.enabled/host（影像仍可走 DICOMWeb 调阅）");
        }
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "缺少操作人：DIMSE 传输必须记录操作人");
        }
        return actor.trim();
    }

    private static String requireUid(String value) {
        String uid = value == null ? "" : value.trim();
        if (!UID.matcher(uid).matches() || uid.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "StudyInstanceUID 不是合法的 DICOM UID（数字与点，长度不超过 64）");
        }
        return uid;
    }

    private static StudyView toView(PacsDimseGateway.StudyResult result, boolean phiReadable) {
        String patientId = phiReadable ? result.patientId() : Masks.patientNo(result.patientId());
        String patientName = phiReadable ? result.patientName()
                : Masks.patientName(result.patientName());
        return new StudyView(result.studyInstanceUid(), patientId, patientName,
                result.accessionNumber(), result.studyDate(), result.studyTime(),
                result.description(), result.modalities(), result.seriesCount(),
                result.instanceCount());
    }

    private void auditFailure(String actor, String operation, long elapsed, BusinessException e) {
        audit(actor, operation, "FAILED", -1, elapsed, null, null,
                Map.of("error", safe(e.getMessage())));
    }

    private void audit(String actor, String operation, String outcome, Integer statusCode,
                       long durationMs, Integer studies, Integer instances,
                       Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JacksonException e) {
            detailJson = "{\"_detail_serialize_failed\":true}";
        }
        try {
            repo.insertPacsDimse(actor, operation, gateway.remoteAe(), gateway.remoteHost(),
                    studies, instances, outcome, statusCode,
                    (int) Math.min(durationMs, Integer.MAX_VALUE), detailJson);
        } catch (RuntimeException e) {
            log.error("pacs.dimse.audit_failed operation={} actor={} outcome={}",
                    operation, actor, outcome, e);
        }
    }

    private void metric(String operation, String outcome, long durationMs) {
        meter.counter("medassist.pacs.dimse.operations",
                "operation", operation, "outcome", outcome).increment();
        meter.timer("medassist.pacs.dimse.duration", "operation", operation)
                .record(Duration.ofMillis(durationMs));
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String dicomDate(LocalDate date) {
        return date == null ? null : DICOM_DATE.format(date);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upperToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String safe(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_DETAIL_CHARS
                ? message : message.substring(0, MAX_DETAIL_CHARS);
    }

    private static String hex(int status) {
        return "0x" + String.format(Locale.ROOT, "%04X", status);
    }
}
