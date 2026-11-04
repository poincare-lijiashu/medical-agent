package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.client.ClinicalClient;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.ChangeRequest;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.QcResult;
import com.medassist.emr.domain.EmrModel.QcRule;
import com.medassist.emr.domain.EmrModel.RecordHit;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.domain.EmrModel.Template;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.AmendReq;
import com.medassist.emr.web.EmrDtos.ChangeRequestReq;
import com.medassist.emr.web.EmrDtos.CreateRecordReq;
import com.medassist.emr.web.EmrDtos.HashVerify;
import com.medassist.emr.web.EmrDtos.QcReport;
import com.medassist.emr.web.EmrDtos.RecordView;
import com.medassist.emr.web.EmrDtos.ReviewReq;
import com.medassist.emr.web.EmrDtos.SaveContentReq;
import com.medassist.emr.web.EmrDtos.SubmitResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 病历书写、质控、签名与归档。
 *
 * <p>核心不变量：
 * <ol>
 *   <li>只有 DRAFT 可改正文；SUBMITTED 只能签名；SIGNED 只能归档或修订；ARCHIVED 终态；</li>
 *   <li>提交前必须过质控，FATAL 项未通过直接 409（不落库，响应里返回明细）；</li>
 *   <li>签名时对 contentText 计算 SHA-256 并落库，事后可用 verify 接口校验是否被改；</li>
 *   <li>修订保留旧版本到 record_amendment，revision+1 并清空签名（必须重新签）。</li>
 * </ol>
 */
@Service
public class RecordService {

    private final EmrRepository repo;
    private final PatientClient patientClient;
    private final ClinicalClient clinicalClient;
    private final CaSignatureService caSignatureService;
    private final ObjectMapper objectMapper;

    /** 时限类规则拿不到入院时间时的"未评估"结果后缀（与真实超时区分，便于统计）。 */
    static final String NOT_EVALUATED_SUFFIX = ":NOT_EVALUATED";

    /** CDR 检索关键词长度上限（N-08）。 */
    static final int MAX_KEYWORD_LENGTH = 200;

    public RecordService(EmrRepository repo, PatientClient patientClient,
                         ClinicalClient clinicalClient, CaSignatureService caSignatureService,
                         ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.clinicalClient = clinicalClient;
        this.caSignatureService = caSignatureService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 书写

    @Transactional
    public RecordView create(CreateRecordReq req, String actor) {
        Template template = repo.findTemplateByCode(req.templateCode().trim())
                .filter(Template::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_TEMPLATE_NOT_FOUND,
                        "模板不存在或已停用：" + req.templateCode()));
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());
        Map<String, String> content = req.content() == null ? Map.of() : req.content();
        String contentText = render(template.schemaJson(), content);

        // 就诊画像：归属患者（防止病历挂到别人就诊上）+ 入院时间（书写时限基准）
        Optional<ClinicalClient.VisitRef> visitRef = req.visitId() == null
                ? Optional.empty() : clinicalClient.visit(req.visitId());
        visitRef.map(ClinicalClient.VisitRef::patientId)
                .filter(owner -> !patient.id().equals(owner))
                .ifPresent(owner -> {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "就诊号 " + req.visitId() + " 不属于该患者，请核对后重新开立病历");
                });
        Instant visitStartedAt = visitRef.map(ClinicalClient.VisitRef::admittedAt).orElse(null);

        MedicalRecord record = new MedicalRecord(UUID.randomUUID(), repo.nextRecordNo(),
                patient.id(), patient.patientNo(), req.visitId(), blankToNull(req.visitNo()),
                req.deptId(), template.docType(), template.templateCode(),
                req.title() == null || req.title().isBlank()
                        ? template.name() + " " + patient.name() : req.title().trim(),
                toJson(content), contentText, actor, MedicalRecord.DRAFT, 1,
                null, null, null, null, 0L, null);
        repo.insertRecord(record, visitStartedAt);
        audit(actor, "RECORD_CREATE", "RECORD", record.id().toString(), Map.of(
                "recordNo", record.recordNo(),
                // P1-S4：审计明细不留明文患者号
                "patientNo", com.medassist.common.security.Masks.patientNo(patient.patientNo()),
                "docType", record.docType(), "templateCode", record.templateCode()));
        return detail(record.id());
    }

    /** 保存草稿（仅 DRAFT；必须带版本号防并发覆盖）。 */
    @Transactional
    public RecordView save(UUID id, SaveContentReq req, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!MedicalRecord.DRAFT.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有草稿可以修改正文；已提交/已签名病历请走修订，当前：" + record.status());
        }
        Template template = repo.findTemplateByCode(record.templateCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_TEMPLATE_NOT_FOUND));
        String contentText = render(template.schemaJson(), req.content());
        if (!repo.updateDraftContent(id, toJson(req.content()), contentText, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历已被他人修改，请刷新后重试");
        }
        audit(actor, "RECORD_SAVE", "RECORD", id.toString(), Map.of("recordNo", record.recordNo()));
        return detail(id);
    }

    // ---------------------------------------------------------------- 提交（质控）

    @Transactional
    public SubmitResult submit(UUID id, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!MedicalRecord.DRAFT.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有草稿可以提交，当前：" + record.status());
        }
        Map<String, String> content = fromJson(record.content());
        QcReport qc = evaluate(record, content, Instant.now(), resolveVisitStartedAt(record, true));
        if (qc.fatalCount() > 0) {
            String detail = qc.results().stream()
                    .filter(r -> !r.passed() && QcRule.FATAL.equals(r.severity()))
                    .limit(3)
                    .map(QcResult::message)
                    .reduce((a, b) -> a + "；" + b).orElse("");
            throw new BusinessException(ErrorCode.EMR_QC_BLOCKED, "质控未通过：" + detail);
        }
        for (QcResult result : qc.results()) {
            repo.insertQcResult(result);
        }
        if (!repo.updateStatus(id, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
        }
        audit(actor, "RECORD_SUBMIT", "RECORD", id.toString(), Map.of(
                "recordNo", record.recordNo(), "fatal", qc.fatalCount(),
                "major", qc.majorCount(), "minor", qc.minorCount()));
        return new SubmitResult(detail(id), qc);
    }

    /** 提交前预览质控结果（不落库）。 */
    public QcReport previewQc(UUID id) {
        MedicalRecord record = repo.findRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        return evaluate(record, fromJson(record.content()), Instant.now(),
                resolveVisitStartedAt(record, false));
    }

    /**
     * 解析入院时间：优先用创建时固化的值；为空且 visitId 非空时回源临床域。
     *
     * <p>回源只解决"创建时临床域短暂不可用"的瞬时故障，避免时限质控永久跳过；
     * preview 不落库（只读语义），submit 回源成功才补写固化。
     */
    private Optional<Instant> resolveVisitStartedAt(MedicalRecord record, boolean persist) {
        Optional<Instant> cached = repo.visitStartedAt(record.id());
        if (cached.isPresent() || record.visitId() == null) {
            return cached;
        }
        Optional<Instant> fetched = clinicalClient.admittedAt(record.visitId());
        if (fetched.isPresent() && persist && repo.bindVisitStartedAt(record.id(), fetched.get())) {
            audit("system", "RECORD_TIMELINESS_BIND", "RECORD", record.id().toString(),
                    Map.of("recordNo", record.recordNo(), "visitId", record.visitId().toString()));
        }
        return fetched;
    }

    // ---------------------------------------------------------------- 签名 / 归档

    /**
     * 签名（默认书写医师级别，兼容旧调用）。
     */
    @Transactional
    public RecordView sign(UUID id, String actor) {
        return sign(id, RecordSignature.AUTHOR, actor, false);
    }

    /**
     * 三级签名：书写医师 → 主治医师 → 主任医师，必须逐级签署。
     *
     * <p>上级签名不改正文，因此沿用当前 revision 已落库的内容哈希；
     * 若正文被修订（revision+1）签名记录随之作废，必须从书写医师重新签起。
     */
    @Transactional
    public RecordView sign(UUID id, String level, String actor) {
        return sign(id, level, actor, false);
    }

    /**
     * 签名并可选获取 CA 电子签名。
     *
     * <p>{@code caSign=true} 时 CA 适配层在同一事务内签署当前内容哈希；
     * CA 服务失败会抛出异常并回滚病历签名，避免出现"无 CA 证书的已签病历"。
     */
    @Transactional
    public RecordView sign(UUID id, String level, String actor, boolean caSign) {
        String signType = (level == null || level.isBlank())
                ? RecordSignature.AUTHOR : level.trim().toUpperCase();
        int targetLevel = RecordSignature.levelOf(signType);
        if (targetLevel == 0) {
            throw new BusinessException(ErrorCode.EMR_SIGN_LEVEL_INVALID,
                    "签名级别不合法：" + level);
        }
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        int currentLevel = repo.currentSignLevel(id, record.revision());
        if (targetLevel != currentLevel + 1) {
            throw new BusinessException(ErrorCode.EMR_SIGN_LEVEL_INVALID,
                    "当前已签到「" + RecordSignature.levelName(currentLevel) + "」，"
                            + "下一步应签「" + RecordSignature.levelName(currentLevel + 1) + "」");
        }
        ensureSignerIdentity(record, targetLevel, currentLevel, actor);

        String hash;
        if (targetLevel == 1) {
            if (!MedicalRecord.SUBMITTED.equals(record.status())) {
                throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                        "只有已提交的病历可以签名，当前：" + record.status());
            }
            hash = sha256(record.contentText());
            if (!repo.signRecord(id, actor, hash, record.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
            }
        } else {
            if (!MedicalRecord.SIGNED.equals(record.status())) {
                throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                        "上级签名前必须先完成下级签名，当前：" + record.status());
            }
            hash = record.contentHash() == null ? sha256(record.contentText()) : record.contentHash();
            if (!repo.countersign(id, actor, hash, record.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
            }
        }
        RecordSignature signature = new RecordSignature(UUID.randomUUID(), id,
                record.revision(), actor, signType, null, hash);
        repo.insertSignature(signature);
        if (caSign) {
            caSignatureService.sign(record, signature, actor);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recordNo", record.recordNo());
        detail.put("revision", record.revision());
        detail.put("level", targetLevel);
        detail.put("signType", signType);
        detail.put("hash", hash);
        detail.put("caSign", caSign);
        audit(actor, "RECORD_SIGN", "RECORD", id.toString(), detail);
        return detail(id);
    }

    /**
     * 签署人身份约束（不需要职称数据即可执行的最小合规校验）。
     *
     * <ul>
     *   <li>书写医师级：必须由病历书写人本人签署，不允许他人代签 AUTHOR；</li>
     *   <li>上级签名：不得由书写人本人签，也不得与上一级签署人同一人——
     *       否则"逐级审签"退化为一个人连签三级。</li>
     * </ul>
     * 职称校验（主治才能签 ATTENDING 等）待 HR 组织数据接入后补。
     */
    private void ensureSignerIdentity(MedicalRecord record, int targetLevel, int currentLevel,
                                      String actor) {
        String author = record.author();
        if (targetLevel == 1) {
            if (author != null && !author.equals(actor)) {
                throw new BusinessException(ErrorCode.EMR_SIGN_LEVEL_INVALID,
                        "病历须由书写医师本人签名：书写医师 " + author + "，当前登录 " + actor);
            }
            return;
        }
        if (author != null && author.equals(actor)) {
            throw new BusinessException(ErrorCode.EMR_SIGN_LEVEL_INVALID,
                    "「" + RecordSignature.levelName(targetLevel) + "」签名不能由书写医师本人完成，须上级医师审签");
        }
        String previousSigner = repo.listSignatures(record.id()).stream()
                .filter(s -> s.revision() == record.revision())
                .filter(s -> RecordSignature.levelOf(s.signType()) == currentLevel)
                .map(RecordSignature::signer)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (previousSigner != null && previousSigner.equals(actor)) {
            throw new BusinessException(ErrorCode.EMR_SIGN_LEVEL_INVALID,
                    "「" + RecordSignature.levelName(targetLevel) + "」签名必须由不同医师完成："
                            + RecordSignature.levelName(currentLevel) + "已由 " + previousSigner + " 签署");
        }
    }

    @Transactional
    public RecordView archive(UUID id, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!MedicalRecord.SIGNED.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有已签名病历可以归档，当前：" + record.status());
        }
        // 未配置归档签名策略时拒绝归档（fail-closed），避免漏配的文书类型被"书写医师签完即归档"
        int required = repo.requiredSignLevel(record.docType())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_SIGN_LEVEL_INVALID,
                        "「" + RecordPrintRenderer.docTypeName(record.docType())
                                + "」未配置归档签名策略，请联系病案室配置 sign_policy"));
        int current = repo.currentSignLevel(id, record.revision());
        if (current < required) {
            throw new BusinessException(ErrorCode.EMR_SIGN_LEVEL_INVALID,
                    "「" + RecordPrintRenderer.docTypeName(record.docType()) + "」归档需完成"
                            + RecordSignature.levelName(required) + "签名，当前仅签到"
                            + RecordSignature.levelName(current));
        }
        if (!repo.archiveRecord(id, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recordNo", record.recordNo());
        detail.put("revision", record.revision());
        detail.put("signLevel", current);
        // 该版本若由病案修改申请产生，归档审计直接带上申请单号，免去跨表人工拼接
        repo.findAmendmentByRevision(id, record.revision())
                .map(Amendment::changeRequestId)
                .ifPresent(requestId -> detail.put("changeRequestId", requestId.toString()));
        audit(actor, "RECORD_ARCHIVE", "RECORD", id.toString(), detail);
        return detail(id);
    }

    // ---------------------------------------------------------------- 修订

    /**
     * 修订：签名后修改必须留痕。
     *
     * <p>旧版本写入 record_amendment，正文覆盖为 revision+1，状态回到 SUBMITTED 并清空签名——
     * 修改过的病历必须重新签名。已归档病历不允许修订，须走病案修改申请（申请→审批）流程。
     * 修订留痕的 author 恒取病历书写人（record.author），不取最后签名人，避免责任链错位。
     */
    @Transactional
    public RecordView amend(UUID id, AmendReq req, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (record.archived()) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "已归档病历不允许修订，请走病案修改流程");
        }
        if (!MedicalRecord.SIGNED.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有已签名病历需要修订；草稿请直接保存，当前：" + record.status());
        }
        Template template = repo.findTemplateByCode(record.templateCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_TEMPLATE_NOT_FOUND));
        String newText = render(template.schemaJson(), req.content());

        // 先留旧版本（含旧哈希），再覆盖正文；author 恒取书写人，不取最后签名人
        repo.insertAmendment(new Amendment(UUID.randomUUID(), id, record.revision(),
                record.content(), record.contentText(),
                record.contentHash() == null ? sha256(record.contentText()) : record.contentHash(),
                record.author(), req.reason().trim(), null, null));

        if (!repo.applyAmendment(id, toJson(req.content()), newText, record.version(), record.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
        }
        audit(actor, "RECORD_AMEND", "RECORD", id.toString(), Map.of(
                "recordNo", record.recordNo(), "fromRevision", record.revision(),
                "reason", req.reason().trim()));
        return detail(id);
    }

    // ---------------------------------------------------------------- 归档后修改申请

    /**
     * 归档后修改申请：先申请、后审批，审批通过才改正文。
     *
     * <p>同一份病历同时只允许一条待审批申请（数据库唯一索引兜底）。
     * 被驳回或撤回后可以带 {@code resubmitOf} 重新申请，便于追溯同一诉求的完整处理轨迹。
     */
    @Transactional
    public ChangeRequest requestChange(UUID recordId, ChangeRequestReq req, String actor) {
        MedicalRecord record = repo.lockRecord(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!record.archived()) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有已归档病历需要走病案修改申请；未归档病历请直接修订，当前：" + record.status());
        }
        if (repo.hasPendingChangeRequest(recordId)) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_INVALID,
                    "该病历已有待审批的修改申请，请等待审批结果");
        }
        UUID resubmitOf = validateResubmit(recordId, req.resubmitOf());
        Template template = repo.findTemplateByCode(record.templateCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_TEMPLATE_NOT_FOUND));
        String text = render(template.schemaJson(), req.content());
        ChangeRequest request = new ChangeRequest(UUID.randomUUID(), recordId, record.recordNo(),
                req.reason().trim(), toJson(req.content()), text, ChangeRequest.PENDING,
                actor, null, null, null, null, null, resubmitOf);
        repo.insertChangeRequest(request);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recordNo", record.recordNo());
        detail.put("revision", record.revision());
        detail.put("reason", req.reason().trim());
        if (resubmitOf != null) {
            detail.put("resubmitOf", resubmitOf.toString());
        }
        audit(actor, "RECORD_CHANGE_REQUEST", "RECORD", recordId.toString(), detail);
        return request;
    }

    /** 重提关联校验：必须同病历、且原申请已被驳回或撤回。 */
    private UUID validateResubmit(UUID recordId, UUID resubmitOf) {
        if (resubmitOf == null) {
            return null;
        }
        ChangeRequest previous = repo.lockChangeRequest(resubmitOf)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_NOT_FOUND));
        if (!previous.recordId().equals(recordId)) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_INVALID,
                    "只能关联同一份病历的历史申请");
        }
        if (!ChangeRequest.REJECTED.equals(previous.status())
                && !ChangeRequest.WITHDRAWN.equals(previous.status())) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_INVALID,
                    "只能关联已驳回或已撤回的申请，当前：" + previous.status());
        }
        return resubmitOf;
    }

    /**
     * 申请人撤回待审批申请：只有申请人本人、且仍处于 PENDING 时可用。
     *
     * <p>撤回后释放"同一病历一条 PENDING"的通道，可重新申请（带 resubmitOf 关联）。
     */
    @Transactional
    public ChangeRequest withdrawChange(UUID recordId, UUID requestId, String actor) {
        ChangeRequest request = repo.lockChangeRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_NOT_FOUND));
        if (!request.recordId().equals(recordId)) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_NOT_FOUND);
        }
        if (!ChangeRequest.PENDING.equals(request.status())) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_INVALID,
                    "该申请已处理（" + request.status() + "），不能撤回");
        }
        if (!request.requester().equals(actor)) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_INVALID,
                    "只有申请人本人可以撤回申请");
        }
        if (!repo.withdrawChangeRequest(requestId, actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该申请已被处理，请刷新后重试");
        }
        audit(actor, "RECORD_CHANGE_WITHDRAW", "RECORD", recordId.toString(), Map.of(
                "recordNo", request.recordNo(), "requestId", requestId.toString(),
                "reason", request.reason()));
        return repo.lockChangeRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_NOT_FOUND));
    }

    /** 审批通过：旧版本留档、应用新内容、revision+1 并回到 SUBMITTED（需重新签名与归档）。 */
    @Transactional
    public RecordView approveChange(UUID recordId, UUID requestId, ReviewReq req, String actor) {
        ChangeRequest request = lockRequestOfRecord(recordId, requestId);
        ensureDifferentReviewer(request, actor);
        MedicalRecord record = repo.lockRecord(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!record.archived()) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "病历已不在归档状态（可能已被其他申请激活），请刷新后重试");
        }
        repo.insertAmendment(new Amendment(UUID.randomUUID(), recordId, record.revision(),
                record.content(), record.contentText(),
                record.contentHash() == null ? sha256(record.contentText()) : record.contentHash(),
                record.author(),
                "病案修改申请审批通过：" + request.reason(), requestId, null));
        if (!repo.applyAmendment(recordId, request.proposedContent(), request.proposedText(),
                record.version(), MedicalRecord.ARCHIVED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
        }
        int newRevision = record.revision() + 1;
        if (!repo.reviewChangeRequest(requestId, ChangeRequest.APPROVED, actor,
                blankToNull(req == null ? null : req.comment()), newRevision)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该申请已被处理，请刷新后重试");
        }
        audit(actor, "RECORD_CHANGE_APPROVE", "RECORD", recordId.toString(), Map.of(
                "recordNo", record.recordNo(), "requestId", requestId.toString(),
                "fromRevision", record.revision(), "toRevision", newRevision));
        return detail(recordId);
    }

    /** 审批驳回：只改申请状态，病历保持归档不变。 */
    @Transactional
    public ChangeRequest rejectChange(UUID recordId, UUID requestId, ReviewReq req, String actor) {
        ChangeRequest request = lockRequestOfRecord(recordId, requestId);
        ensureDifferentReviewer(request, actor);
        if (!repo.reviewChangeRequest(requestId, ChangeRequest.REJECTED, actor,
                blankToNull(req == null ? null : req.comment()), null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该申请已被处理，请刷新后重试");
        }
        audit(actor, "RECORD_CHANGE_REJECT", "RECORD", recordId.toString(), Map.of(
                "recordNo", request.recordNo(), "requestId", requestId.toString(),
                "reason", request.reason()));
        return repo.lockChangeRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_NOT_FOUND));
    }

    public List<ChangeRequest> listChanges(UUID recordId) {
        repo.findRecord(recordId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        return repo.listChangeRequests(recordId);
    }

    private ChangeRequest lockRequestOfRecord(UUID recordId, UUID requestId) {
        ChangeRequest request = repo.lockChangeRequest(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_NOT_FOUND));
        if (!request.recordId().equals(recordId)) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_NOT_FOUND);
        }
        if (!ChangeRequest.PENDING.equals(request.status())) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_INVALID,
                    "该申请已处理（" + request.status() + "），不能重复审批");
        }
        return request;
    }

    /** 四眼原则：申请人不得审批自己的申请。 */
    private void ensureDifferentReviewer(ChangeRequest request, String actor) {
        if (request.requester().equals(actor)) {
            throw new BusinessException(ErrorCode.EMR_CHANGE_REQUEST_INVALID,
                    "申请人不得审批自己提交的病案修改申请，请由病案质控人员审批");
        }
    }

    // ---------------------------------------------------------------- 查询

    /**
     * CDR 检索：关键词可空，其余条件均可叠加。
     *
     * <p>关键词同时用于两路检索：标题/正文模糊匹配，病历号/患者号前缀匹配。
     * 检索本身也要留痕（谁、什么条件、命中多少条），用于事后核查 PHI 访问。
     */
    public List<RecordHit> search(String q, UUID patientId, String docType, String status,
                                  LocalDate from, LocalDate to, Integer limit, String actor) {
        String keyword = (q == null || q.isBlank()) ? null : q.trim();
        // 超长关键词既无检索意义，又会放大 ILIKE/trgm 的计算量：明确拒绝而不是静默截断
        if (keyword != null && keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "检索关键词不得超过 " + MAX_KEYWORD_LENGTH + " 个字符");
        }
        String escaped = keyword == null ? null : escapeLike(keyword);
        String pattern = escaped == null ? null : "%" + escaped + "%";
        String prefix = escaped == null ? null : escaped + "%";
        String normalizedDocType = blankToNull(docType) == null
                ? null : docType.trim().toUpperCase();
        String normalizedStatus = (status == null || status.isBlank())
                ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<RecordHit> hits = repo.searchRecords(pattern, prefix, patientId, normalizedDocType,
                normalizedStatus, from, to, size);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("keyword", keyword == null ? "" : keyword);
        detail.put("patientId", patientId == null ? "" : patientId.toString());
        detail.put("docType", normalizedDocType == null ? "" : normalizedDocType);
        detail.put("status", normalizedStatus == null ? "" : normalizedStatus);
        detail.put("hits", hits.size());
        audit(actor, "RECORD_SEARCH", "MEDICAL_RECORD", "search", detail);
        return hits;
    }

    /** 打印视图（HTML，浏览器打印为 PDF）；未归档文书带"未归档"水印，并留痕。 */
    public String printHtml(UUID id, String actor) {
        MedicalRecord record = repo.findRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        String html = RecordPrintRenderer.render(record, repo.listSignatures(id));
        audit(actor, "RECORD_PRINT", "MEDICAL_RECORD", id.toString(), Map.of(
                "recordNo", record.recordNo(), "status", record.status(),
                "revision", record.revision()));
        return html;
    }

    /** 转义 LIKE 通配符，避免用户输入 % 或 _ 变成全表匹配。 */
    private String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }


    public RecordView detail(UUID id) {
        MedicalRecord record = repo.findRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        return new RecordView(record, repo.listSignatures(id), repo.listAmendments(id),
                repo.listQcResults(id, record.revision()));
    }

    public List<MedicalRecord> list(String status, UUID patientId, UUID visitId, Integer limit) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listRecords(normalized, patientId, visitId, size);
    }

    /** 校验内容哈希：重新计算正文哈希，并核对当前版本各级签名保存的哈希是否一致。 */
    public HashVerify verify(UUID id) {
        MedicalRecord record = repo.findRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        String recomputed = sha256(record.contentText());
        String stored = record.contentHash();
        boolean valid = stored != null && stored.equals(recomputed);
        List<String> mismatches = repo.listSignatures(id).stream()
                .filter(s -> s.revision() == record.revision())
                .filter(s -> !recomputed.equals(s.contentHash()))
                .map(s -> s.signType() + ":" + s.signer())
                .toList();
        return new HashVerify(valid, stored, recomputed, mismatches.isEmpty(), mismatches);
    }

    // ---------------------------------------------------------------- 质控引擎

    /**
     * 内置四类规则，避免引入表达式引擎（表达式求值是可注入面，且难以审计）。
     *
     * <p>SIGNATURE_REQUIRED 在提交阶段不适用（此时还没有签名），留待归档前校验；
     * TIMELINESS 以"入院时间 → 提交时刻"计算，没有入院信息（门诊/上游不可用）时跳过。
     * TIMELINESS 只对首版（revision == 1）评估：修订重提交时早已超时，再报一遍只会淹没真正的缺陷。
     */
    private QcReport evaluate(MedicalRecord record, Map<String, String> content, Instant now,
                              Optional<Instant> visitStartedAt) {
        List<QcRule> rules = repo.listQcRules(record.docType());
        List<QcResult> results = new ArrayList<>();
        int fatal = 0;
        int major = 0;
        int minor = 0;
        for (QcRule rule : rules) {
            Boolean passed;
            String message = rule.message();
            if (QcRule.TIMELINESS.equals(rule.ruleType())) {
                if (record.revision() > 1) {
                    continue;
                }
                if (visitStartedAt == null || visitStartedAt.isEmpty()) {
                    // 明确记录"未评估"：早期直接 continue，会让时限质控在配置错误时静默消失，
                    // 报表看起来"全部通过"。现在至少留一条可查的待核查项。
                    results.add(new QcResult(UUID.randomUUID(), record.id(), record.revision(),
                            rule.ruleCode() + NOT_EVALUATED_SUFFIX, QcRule.MINOR, false,
                            rule.message() + "（未评估：未取到入院时间，请核对就诊号与临床域连通性）"));
                    minor++;
                    continue;
                } else {
                    int limit = rule.threshold() == null ? 24 : rule.threshold();
                    Duration elapsed = Duration.between(visitStartedAt.get(), now);
                    if (elapsed.isNegative()) {
                        elapsed = Duration.ZERO;
                    }
                    // 精确比较：8h59m 必须判为超时，不能因为 toHours() 截断而放宽近 1 小时
                    passed = elapsed.compareTo(Duration.ofHours(limit)) <= 0;
                    if (!passed) {
                        message = rule.message() + "（已 " + elapsed.toHours() + " 小时 "
                                + (elapsed.toMinutes() % 60) + " 分钟）";
                    }
                }
            } else {
                passed = switch (rule.ruleType()) {
                    case QcRule.REQUIRED_FIELD -> notBlank(content.get(rule.fieldKey()));
                    case QcRule.MIN_LENGTH -> {
                        String v = content.get(rule.fieldKey());
                        int min = rule.threshold() == null ? 1 : rule.threshold();
                        yield v != null && v.trim().length() >= min;
                    }
                    default -> null;
                };
            }
            if (passed == null) {
                continue;
            }
            if (!passed) {
                if (QcRule.FATAL.equals(rule.severity())) {
                    fatal++;
                } else if (QcRule.MAJOR.equals(rule.severity())) {
                    major++;
                } else {
                    minor++;
                }
            }
            results.add(new QcResult(UUID.randomUUID(), record.id(), record.revision(),
                    rule.ruleCode(), rule.severity(), passed, message));
        }
        return new QcReport(fatal == 0, fatal, major, minor, results);
    }

    // ---------------------------------------------------------------- 渲染 / 哈希

    /**
     * 渲染确定性文本：按模板字段顺序输出"标签：值"，模板外的字段按 key 排序追加。
     *
     * <p>空值也输出（保持行数稳定），保证"同样内容 → 同样哈希"。
     */
    String render(String schemaJson, Map<String, String> content) {
        StringBuilder sb = new StringBuilder();
        Set<String> used = new HashSet<>();
        List<Map<?, ?>> fields = parseSchema(schemaJson);
        for (Map<?, ?> field : fields) {
            Object keyObj = field.get("key");
            if (keyObj == null) {
                continue;
            }
            String key = String.valueOf(keyObj);
            String label = field.get("label") == null ? key : String.valueOf(field.get("label"));
            String value = content.getOrDefault(key, "");
            sb.append(label).append("：").append(value == null ? "" : value).append('\n');
            used.add(key);
        }
        content.entrySet().stream()
                .filter(e -> !used.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("：")
                        .append(e.getValue() == null ? "" : e.getValue()).append('\n'));
        return sb.toString();
    }

    private List<Map<?, ?>> parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(schemaJson, List.class);
        } catch (JacksonException e) {
            // 模板损坏时退化为"仅按 key 排序渲染"，不能因为渲染失败挡住病历书写
            return List.of();
        }
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Map<String, String> content) {
        try {
            return objectMapper.writeValueAsString(content == null ? Map.of() : content);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "病历内容无法序列化");
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
