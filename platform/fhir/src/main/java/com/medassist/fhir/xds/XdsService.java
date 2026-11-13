package com.medassist.fhir.xds;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Masks;
import com.medassist.fhir.config.FhirProperties;
import com.medassist.fhir.xds.CdaDocumentBuilder.CdaDocument;
import com.medassist.fhir.xds.XdsDtos.DeprecateRequest;
import com.medassist.fhir.xds.XdsDtos.DocumentSummary;
import com.medassist.fhir.xds.XdsDtos.ProvideRequest;
import com.medassist.fhir.xds.XdsDtos.ProvideResult;
import com.medassist.fhir.xds.XdsDtos.QueryResult;
import com.medassist.fhir.xds.XdsDtos.Retrieval;
import com.medassist.fhir.xds.XdsDtos.XdsSummary;
import com.medassist.fhir.xds.XdsRepository.ContentRow;
import com.medassist.fhir.xds.XdsRepository.DocumentRow;
import com.medassist.fhir.xds.XdsRepository.Page;
import com.medassist.fhir.xds.XdsRepository.PatientRow;
import com.medassist.fhir.xds.XdsRepository.QueryParams;
import com.medassist.fhir.xds.XdsRepository.SummaryCounts;
import com.medassist.fhir.xds.XdsSources.DiagnosisRow;
import com.medassist.fhir.xds.XdsSources.DischargeSource;
import com.medassist.fhir.xds.XdsSources.LabSource;
import com.medassist.fhir.xds.XdsSources.MedicationRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * XDS 文档共享编排。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>内容与元数据一致</b>：注册时按字节计算 SHA-256 与大小，客户端给了就比对；
 *       调阅时再用库内内容重算，任何一边被改都会拒绝返回；</li>
 *   <li><b>唯一标识不可重复</b>：uniqueId 重复直接 409，不覆盖历史文档；</li>
 *   <li><b>审计与注册同事务</b>：注册成功但审计没写成，等于"文档存在却查不到谁提交的"，
 *       所以成功审计放在注册事务内；失败审计在回滚后单独落，避免被一起回滚。</li>
 * </ol>
 */
@Service
public class XdsService {

    private static final Logger log = LoggerFactory.getLogger(XdsService.class);
    private static final String GENERATOR_SOURCE = "urn:medassist:document-generator";
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_OFFSET = 10_000;

    private final XdsRepository repository;
    private final CdaDocumentBuilder builder;
    private final FhirProperties properties;
    private final XdsMetrics metrics;
    private final TransactionTemplate tx;

    public XdsService(XdsRepository repository, CdaDocumentBuilder builder,
                      FhirProperties properties, XdsMetrics metrics,
                      PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.builder = builder;
        this.properties = properties;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------ ITI-41

    public ProvideResult provide(ProvideRequest req, XdsCall call) {
        String uniqueId = null;
        UUID patientId = null;
        try {
            RegisterCommand command = fromRequest(req, call);
            uniqueId = command.uniqueId();
            patientId = command.patient().id();
            RegisterCommand finalCommand = command;
            ProvideResult result = inTx(() -> register(finalCommand, call, "PROVIDE"));
            metrics.provided(command.classCode(), "ok");
            return result;
        } catch (BusinessException e) {
            safeAudit(call, "PROVIDE", uniqueId, patientId, e.errorCode(), null);
            metrics.provided("unknown", "error");
            throw e;
        }
    }

    // ------------------------------------------------------------ ITI-18

    public QueryResult query(String patient, String uniqueId, String classCode, String status,
                             String from, String to, String keyword, Integer limit, Integer offset,
                             XdsCall call) {
        Instant fromInstant = XdsValidation.parseQueryInstant(from, "creationTimeFrom");
        Instant toInstant = XdsValidation.parseQueryInstant(to, "creationTimeTo");
        if ((patient == null || patient.isBlank())
                && (uniqueId == null || uniqueId.isBlank())
                && fromInstant == null && toInstant == null) {
            throw new BusinessException(ErrorCode.XDS_QUERY_TOO_BROAD);
        }
        String normalizedStatus = normalizeStatus(status);
        int pageSize = limit == null ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        if (pageSize < 1) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID, "limit 必须大于 0");
        }
        int pageOffset = offset == null ? 0 : offset;
        if (pageOffset < 0 || pageOffset > MAX_OFFSET) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    "offset 必须在 0–" + MAX_OFFSET + " 之间");
        }
        String uid = uniqueId == null || uniqueId.isBlank()
                ? null : XdsValidation.requireUniqueId(uniqueId, "uniqueId");
        String patientValue = patient == null || patient.isBlank() ? null : patient.trim();
        String classValue = classCode == null || classCode.isBlank() ? null : classCode.trim();
        String keywordValue = keyword == null || keyword.isBlank() ? null : keyword.trim();

        QueryParams params = new QueryParams(patientValue, uid, classValue, normalizedStatus,
                fromInstant, toInstant, keywordValue, pageSize, pageOffset);
        Page<DocumentRow> page = repository.query(params, call.campusFilter());
        List<DocumentSummary> items = page.rows().stream()
                .map(row -> toSummary(row, call.phiReadable()))
                .toList();
        repository.audit(call.actor(), "QUERY", uid, null, summaryOf(patientValue, classValue,
                        fromInstant, toInstant, keywordValue), (int) Math.min(page.total(),
                        Integer.MAX_VALUE), "OK", call.clientIp(), call.traceId());
        metrics.queried("ok");
        return new QueryResult(items, page.total(), pageSize, pageOffset);
    }

    // ------------------------------------------------------------ ITI-43

    public Retrieval retrieve(String uniqueId, XdsCall call) {
        String uid = XdsValidation.requireUniqueId(uniqueId, "uniqueId");
        DocumentRow document = repository.findDocument(uid, call.campusFilter()).orElse(null);
        if (document == null) {
            repository.audit(call.actor(), "RETRIEVE", uid, null, null, 0, "NOT_FOUND",
                    call.clientIp(), call.traceId());
            metrics.retrieved("not_found");
            throw new BusinessException(ErrorCode.XDS_DOCUMENT_NOT_FOUND);
        }
        if (!call.canAccess(documentCampus(document))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权调阅其他院区的共享文档");
        }
        ContentRow content = repository.findContent(document.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.XDS_CONTENT_INVALID,
                        "共享文档内容缺失，无法调阅"));
        String actualHash = XdsValidation.sha256Hex(content.content());
        boolean consistent = content.sizeBytes() == document.sizeBytes()
                && content.content().length == document.sizeBytes()
                && XdsValidation.hashEquals(content.hashSha256(), document.hashSha256())
                && XdsValidation.hashEquals(actualHash, document.hashSha256());
        if (!consistent) {
            repository.audit(call.actor(), "RETRIEVE", uid, document.patientId(), null, 0,
                    "HASH_MISMATCH", call.clientIp(), call.traceId());
            metrics.hashMismatch();
            metrics.retrieved("hash_mismatch");
            log.error("xds.hash_mismatch uniqueId={} metadataHash={} contentHash={} size={}/{}",
                    uid, document.hashSha256(), content.hashSha256(),
                    document.sizeBytes(), content.content().length);
            throw new BusinessException(ErrorCode.XDS_HASH_MISMATCH);
        }
        repository.audit(call.actor(), "RETRIEVE", uid, document.patientId(), null, 1, "OK",
                call.clientIp(), call.traceId());
        metrics.retrieved("ok");
        return new Retrieval(document.uniqueId(), document.title(), document.mimeType(),
                document.hashSha256(), document.sizeBytes(), document.availabilityStatus(),
                document.creationTime(), content.content());
    }

    // ------------------------------------------------------------ 文档生成

    public ProvideResult generateDischargeSummary(UUID visitId, XdsCall call) {
        try {
            DischargeSource source = repository.findDischargeSource(visitId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.XDS_SOURCE_NOT_FOUND,
                            "未找到该就诊记录，无法生成出院小结"));
            if (source.admissionId() == null) {
                throw new BusinessException(ErrorCode.XDS_SOURCE_INVALID,
                        "该就诊不是住院就诊，出院小结只能按住院登记生成");
            }
            UUID campusId = source.campusId() == null ? DataScope.DEFAULT_CAMPUS_ID
                    : source.campusId();
            requireCampus(campusId, call);

            List<DiagnosisRow> diagnoses = repository.findDiagnoses(source.visitId());
            List<MedicationRow> medications =
                    repository.findDischargeMedications(source.admissionId());
            String documentUid = "urn:uuid:" + UUID.randomUUID();
            Instant now = Instant.now();
            CdaDocument cda = builder.dischargeSummary(source, diagnoses, medications,
                    documentUid, documentUid, 1, now);

            RegisterCommand command = fromGenerated(source.patient().id(), source.patient().patientNo(),
                    campusId, documentUid, documentUid, 1, cda);
            ProvideResult result = inTx(() -> register(command, call, "GENERATE"));
            metrics.generated(XdsCodes.DISCHARGE_CLASS, "ok");
            return result;
        } catch (BusinessException e) {
            safeAudit(call, "GENERATE", null, null, e.errorCode(), "discharge");
            metrics.generated(XdsCodes.DISCHARGE_CLASS, "error");
            throw e;
        }
    }

    public ProvideResult generateLabReport(UUID reportId, XdsCall call) {
        try {
            LabSource source = repository.findLabSource(reportId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.XDS_SOURCE_NOT_FOUND,
                            "未找到该检验报告"));
            if (!"FINAL".equals(source.reportStatus()) && !"AMENDED".equals(source.reportStatus())) {
                throw new BusinessException(ErrorCode.XDS_SOURCE_INVALID,
                        "报告尚未签发，不能生成共享文档；请先在医技系统完成审核签发");
            }
            UUID campusId = source.campusId() == null ? DataScope.DEFAULT_CAMPUS_ID
                    : source.campusId();
            requireCampus(campusId, call);

            String documentUid = "urn:uuid:" + UUID.randomUUID();
            Instant now = Instant.now();
            CdaDocument cda = builder.labReport(source, documentUid, documentUid, 1, now);
            RegisterCommand command = fromGenerated(source.patientId(), source.patientNo(),
                    campusId, documentUid, documentUid, 1, cda);
            ProvideResult result = inTx(() -> register(command, call, "GENERATE"));
            metrics.generated(XdsCodes.LAB_CLASS, "ok");
            return result;
        } catch (BusinessException e) {
            safeAudit(call, "GENERATE", null, null, e.errorCode(), "lab");
            metrics.generated(XdsCodes.LAB_CLASS, "error");
            throw e;
        }
    }

    // ------------------------------------------------------------ ITI-62

    public DocumentSummary deprecate(String uniqueId, DeprecateRequest req, XdsCall call) {
        String uid = XdsValidation.requireUniqueId(uniqueId, "uniqueId");
        String reason = XdsValidation.requireText(req.reason(), "停用原因", 256);
        if (reason.length() < 2) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID, "停用原因至少 2 个字符");
        }
        DocumentRow document = repository.findDocument(uid, call.campusFilter())
                .orElseThrow(() -> {
                    repository.audit(call.actor(), "DEPRECATE", uid, null, null, 0, "NOT_FOUND",
                            call.clientIp(), call.traceId());
                    return new BusinessException(ErrorCode.XDS_DOCUMENT_NOT_FOUND);
                });
        if (!call.canAccess(documentCampus(document))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权停用其他院区的共享文档");
        }
        if (!"APPROVED".equals(document.availabilityStatus())) {
            throw new BusinessException(ErrorCode.XDS_STATUS_INVALID,
                    "该文档已停用，不能重复停用");
        }
        Integer updated = inTx(() -> {
            int rows = repository.deprecate(uid, call.actor(), reason, req.rowVersion());
            if (rows == 1) {
                repository.audit(call.actor(), "DEPRECATE", uid, document.patientId(),
                        null, 1, "OK", call.clientIp(), call.traceId());
            }
            return rows;
        });
        if (updated == null || updated == 0) {
            metrics.deprecated("conflict");
            throw new BusinessException(ErrorCode.CONFLICT,
                    "文档已被他人更新，请刷新后重试");
        }
        metrics.deprecated("ok");
        DocumentRow refreshed = repository.findDocument(uid, call.campusFilter())
                .orElseThrow(() -> new BusinessException(ErrorCode.XDS_DOCUMENT_NOT_FOUND));
        return toSummary(refreshed, call.phiReadable());
    }

    // ------------------------------------------------------------ 汇总

    public XdsSummary summary(XdsCall call) {
        SummaryCounts counts = repository.summary(call.campusFilter());
        return new XdsSummary(counts.total(), counts.approved(), counts.deprecated(),
                counts.dischargeSummaries(), counts.labReports(), counts.todayRegistered(),
                counts.todayRetrieved());
    }

    // ------------------------------------------------------------ 内部

    private RegisterCommand fromRequest(ProvideRequest req, XdsCall call) {
        String patientKey = XdsValidation.requireText(req.patientId(), "patientId", 128);
        PatientRow patient = repository.findPatient(patientKey)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATIENT_NOT_FOUND));
        UUID campusId = XdsValidation.optionalUuid(req.campusId(), "campusId");
        if (campusId == null) {
            campusId = DataScope.DEFAULT_CAMPUS_ID;
        }
        requireCampus(campusId, call);

        String uniqueId = XdsValidation.requireUniqueId(req.uniqueId(), "uniqueId");
        String logicalId = req.logicalId() == null || req.logicalId().isBlank()
                ? uniqueId : XdsValidation.requireUniqueId(req.logicalId(), "logicalId");
        int documentVersion = req.documentVersion() == null ? 1 : req.documentVersion();
        if (documentVersion < 1 || documentVersion > 9999) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    "documentVersion 必须在 1–9999 之间");
        }
        String submissionSetUid = req.submissionSetUid() == null || req.submissionSetUid().isBlank()
                ? "urn:uuid:" + UUID.randomUUID()
                : XdsValidation.requireUniqueId(req.submissionSetUid(), "submissionSetUid");
        String sourceId = XdsValidation.requireText(req.sourceId(), "sourceId", 128);
        String classCode = XdsValidation.requireText(req.classCode(), "classCode", 64);
        String classDisplay = XdsValidation.optionalText(req.classDisplay(), "classDisplay", 200);
        String typeCode = XdsValidation.optionalText(req.typeCode(), "typeCode", 64);
        String typeDisplay = XdsValidation.optionalText(req.typeDisplay(), "typeDisplay", 200);
        String formatCode = XdsValidation.requireText(req.formatCode(), "formatCode", 200);
        String formatDisplay = XdsValidation.optionalText(req.formatDisplay(), "formatDisplay", 200);
        List<String> eventCodes = XdsValidation.optionalCodes(req.eventCodes(), "eventCodes", 10);
        Instant creationTime = req.creationTime() == null || req.creationTime().isBlank()
                ? Instant.now() : XdsValidation.requireInstant(req.creationTime(), "creationTime");
        Instant serviceStart = XdsValidation.optionalInstant(req.serviceStartTime(),
                "serviceStartTime");
        Instant serviceStop = XdsValidation.optionalInstant(req.serviceStopTime(), "serviceStopTime");
        if (serviceStart != null && serviceStop != null && serviceStop.isBefore(serviceStart)) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    "serviceStopTime 不能早于 serviceStartTime");
        }
        String title = XdsValidation.requireText(req.title(), "title", 200);
        String comments = XdsValidation.optionalText(req.comments(), "comments", 500);
        String confidentiality = req.confidentialityCode() == null
                || req.confidentialityCode().isBlank()
                ? "N" : req.confidentialityCode().trim().toUpperCase();
        if (!XdsCodes.CONFIDENTIALITY.contains(confidentiality)) {
            throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    "confidentialityCode 只能是 N/R/V/M");
        }
        String language = req.languageCode() == null || req.languageCode().isBlank()
                ? XdsCodes.DEFAULT_LANGUAGE : req.languageCode().trim();
        String mimeType = normalizeMime(req.mimeType());
        byte[] content = decodeContent(req.contentBase64());
        String hash = XdsValidation.sha256Hex(content);
        if (req.hashSha256() != null && !req.hashSha256().isBlank()) {
            String declared = XdsValidation.requireHash(req.hashSha256(), "hashSha256");
            if (!XdsValidation.hashEquals(declared, hash)) {
                throw new BusinessException(ErrorCode.XDS_HASH_MISMATCH);
            }
        }
        if (req.sizeBytes() != null && req.sizeBytes() != content.length) {
            throw new BusinessException(ErrorCode.XDS_SIZE_MISMATCH,
                    "登记大小 " + req.sizeBytes() + " 字节，实际 " + content.length + " 字节");
        }
        return new RegisterCommand(uniqueId, logicalId, documentVersion, submissionSetUid,
                sourceId, patient, campusId, classCode, classDisplay, typeCode, typeDisplay,
                formatCode, formatDisplay, eventCodes, creationTime, serviceStart, serviceStop,
                XdsValidation.optionalText(req.facilityTypeCode(), "facilityTypeCode", 64),
                XdsValidation.optionalText(req.practiceSettingCode(), "practiceSettingCode", 64),
                XdsValidation.optionalText(req.authorPerson(), "authorPerson", 128),
                XdsValidation.optionalText(req.authorInstitution(), "authorInstitution", 200),
                XdsValidation.optionalText(req.authorRole(), "authorRole", 64),
                title, comments, confidentiality, language, mimeType, content, hash);
    }

    private RegisterCommand fromGenerated(UUID patientId, String patientNo, UUID campusId,
                                          String uniqueId, String logicalId, int version,
                                          CdaDocument cda) {
        String hash = XdsValidation.sha256Hex(cda.content());
        return new RegisterCommand(uniqueId, logicalId, version,
                "urn:uuid:" + UUID.randomUUID(), GENERATOR_SOURCE,
                new PatientRow(patientId, patientNo, null, null, null), campusId,
                cda.classCode(), cda.classDisplay(), cda.typeCode(), cda.typeDisplay(),
                cda.formatCode(), cda.formatDisplay(), List.of(), Instant.now(),
                cda.serviceStart(), cda.serviceStop(), null, null, cda.authorPerson(),
                cda.authorInstitution(), null, cda.title(), null,
                cda.confidentialityCode(), XdsCodes.DEFAULT_LANGUAGE, cda.mimeType(),
                cda.content(), hash);
    }

    private ProvideResult register(RegisterCommand cmd, XdsCall call, String auditAction) {
        if (repository.documentExists(cmd.uniqueId())) {
            throw new BusinessException(ErrorCode.XDS_DOCUMENT_DUPLICATE);
        }
        if (repository.submissionSetExists(cmd.submissionSetUid())) {
            throw new BusinessException(ErrorCode.XDS_DOCUMENT_DUPLICATE,
                    "该提交集唯一标识已存在，请换一个 submissionSetUid");
        }
        UUID submissionSetId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        try {
            repository.insertSubmissionSet(submissionSetId, cmd.submissionSetUid(),
                    cmd.sourceId(), cmd.patient().id(), cmd.patient().patientNo(),
                    cmd.creationTime(), cmd.authorPerson(), cmd.authorInstitution(),
                    cmd.title(), null, call.actor());
            repository.insertDocument(documentId, cmd.uniqueId(), cmd.logicalId(),
                    cmd.documentVersion(), submissionSetId, cmd.patient().id(),
                    cmd.patient().patientNo(), cmd.campusId(), cmd.classCode(),
                    cmd.classDisplay(), cmd.typeCode(), cmd.typeDisplay(), cmd.formatCode(),
                    cmd.formatDisplay(), cmd.eventCodes(), cmd.creationTime(),
                    cmd.serviceStart(), cmd.serviceStop(), cmd.facilityTypeCode(),
                    cmd.practiceSettingCode(), cmd.authorPerson(), cmd.authorInstitution(),
                    cmd.authorRole(), cmd.title(), cmd.comments(), cmd.confidentialityCode(),
                    cmd.languageCode(), cmd.mimeType(), cmd.content().length, cmd.hashSha256(),
                    call.actor());
            repository.insertContent(documentId, cmd.content(), cmd.hashSha256(),
                    cmd.content().length);
            repository.audit(call.actor(), auditAction, cmd.uniqueId(), cmd.patient().id(),
                    null, 1, "OK", call.clientIp(), call.traceId());
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.XDS_DOCUMENT_DUPLICATE);
        }
        return new ProvideResult(submissionSetId.toString(), cmd.submissionSetUid(),
                documentId.toString(), cmd.uniqueId(), cmd.hashSha256(),
                cmd.content().length, "APPROVED");
    }

    private DocumentSummary toSummary(DocumentRow row, boolean phiReadable) {
        return new DocumentSummary(row.id().toString(), row.uniqueId(), row.logicalId(),
                row.documentVersion(), row.patientId().toString(),
                phiReadable ? row.patientNo() : Masks.patientNo(row.patientNo()),
                row.classCode(), row.classDisplay(), row.typeCode(), row.typeDisplay(),
                row.formatCode(), row.formatDisplay(), row.eventCodes(), row.title(),
                row.comments(), row.authorPerson(), row.authorInstitution(),
                row.confidentialityCode(), row.languageCode(), row.mimeType(), row.sizeBytes(),
                row.hashSha256(), row.availabilityStatus(), row.creationTime(),
                row.serviceStart(), row.serviceStop(), row.registeredAt(), row.rowVersion());
    }

    private UUID documentCampus(DocumentRow row) {
        return row.campusId();
    }

    private void requireCampus(UUID campusId, XdsCall call) {
        if (!call.canAccess(campusId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权操作其他院区的共享文档");
        }
    }

    private static String normalizeMime(String raw) {
        String mime = XdsValidation.requireText(raw, "mimeType", 100).toLowerCase();
        int semicolon = mime.indexOf(';');
        if (semicolon > 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        if (!XdsCodes.ALLOWED_MIME.contains(mime)) {
            throw new BusinessException(ErrorCode.XDS_CONTENT_INVALID,
                    "不支持的内容类型：" + mime + "（允许 text/xml、application/xml、application/pdf、text/plain）");
        }
        return mime;
    }

    private byte[] decodeContent(String base64) {
        if (base64 == null || base64.isBlank()) {
            throw new BusinessException(ErrorCode.XDS_CONTENT_INVALID, "文档内容不能为空");
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.XDS_CONTENT_INVALID,
                    "文档内容不是合法的 Base64");
        }
        if (content.length == 0) {
            throw new BusinessException(ErrorCode.XDS_CONTENT_INVALID, "文档内容不能为空");
        }
        if (content.length > properties.getXdsMaxDocumentBytes()) {
            throw new BusinessException(ErrorCode.XDS_CONTENT_INVALID,
                    "文档超过单份上限 " + properties.getXdsMaxDocumentBytes() + " 字节");
        }
        return content;
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "APPROVED";
        }
        String value = status.trim().toUpperCase();
        return switch (value) {
            case "APPROVED", "DEPRECATED", "ALL" -> value;
            default -> throw new BusinessException(ErrorCode.XDS_METADATA_INVALID,
                    "status 只能是 APPROVED / DEPRECATED / ALL");
        };
    }

    private static String summaryOf(String patient, String classCode, Instant from, Instant to,
                                    String keyword) {
        StringBuilder sb = new StringBuilder();
        if (patient != null) {
            sb.append("patient,");
        }
        if (classCode != null) {
            sb.append("classCode,");
        }
        if (from != null) {
            sb.append("creationTimeFrom,");
        }
        if (to != null) {
            sb.append("creationTimeTo,");
        }
        if (keyword != null) {
            sb.append("q,");
        }
        return sb.isEmpty() ? "" : sb.substring(0, sb.length() - 1);
    }

    private void safeAudit(XdsCall call, String action, String documentUid, UUID patientId,
                           ErrorCode code, String note) {
        try {
            repository.audit(call.actor(), action, documentUid, patientId,
                    note, 0, classify(code), call.clientIp(), call.traceId());
        } catch (RuntimeException e) {
            log.warn("xds.audit_failed action={} uid={} code={}", action, documentUid, code, e);
        }
    }

    private static String classify(ErrorCode code) {
        if (code == null) {
            return "ERROR";
        }
        return switch (code) {
            case XDS_HASH_MISMATCH -> "HASH_MISMATCH";
            case XDS_DOCUMENT_DUPLICATE -> "CONFLICT";
            case XDS_DOCUMENT_NOT_FOUND, XDS_SOURCE_NOT_FOUND, PATIENT_NOT_FOUND -> "NOT_FOUND";
            case XDS_METADATA_INVALID, XDS_CONTENT_INVALID, XDS_SIZE_MISMATCH,
                 XDS_QUERY_TOO_BROAD, XDS_SOURCE_INVALID -> "BAD_REQUEST";
            case FORBIDDEN -> "FORBIDDEN";
            default -> "ERROR";
        };
    }

    private <T> T inTx(Supplier<T> action) {
        return tx.execute(status -> action.get());
    }

    /** 注册所需的完整字段（从请求或 CDA 生成结果构造）。 */
    private record RegisterCommand(
            String uniqueId, String logicalId, int documentVersion, String submissionSetUid,
            String sourceId, PatientRow patient, UUID campusId,
            String classCode, String classDisplay, String typeCode, String typeDisplay,
            String formatCode, String formatDisplay, List<String> eventCodes,
            Instant creationTime, Instant serviceStart, Instant serviceStop,
            String facilityTypeCode, String practiceSettingCode, String authorPerson,
            String authorInstitution, String authorRole, String title, String comments,
            String confidentialityCode, String languageCode, String mimeType,
            byte[] content, String hashSha256) {
    }
}
