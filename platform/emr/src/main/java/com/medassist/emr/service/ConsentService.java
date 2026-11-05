package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.emr.client.ClinicalClient;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.ConsentRecord;
import com.medassist.emr.domain.EmrModel.ConsentTemplate;
import com.medassist.emr.repo.ConsentRepository;
import com.medassist.emr.service.CaSignatureProvider.CaSignCommand;
import com.medassist.emr.service.CaSignatureProvider.CaSignResult;
import com.medassist.emr.web.ConsentDtos.ConsentView;
import com.medassist.emr.web.ConsentDtos.CreateConsentReq;
import com.medassist.emr.web.ConsentDtos.CreateTemplateReq;
import com.medassist.emr.web.ConsentDtos.RevokeReq;
import com.medassist.emr.web.ConsentDtos.SignReq;
import com.medassist.emr.web.ConsentDtos.TemplateView;
import com.medassist.emr.web.ConsentDtos.VerifyView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 电子知情同意书（G44）。
 *
 * <p>不变量：
 * <ol>
 *   <li>只有 PUBLISHED 模板可以创建同意书；创建时固化模板名/版本/正文并计算 SHA-256；</li>
 *   <li>DRAFT → SIGNED 必须填写签署人与关系；CA 模式保存证书序列号/算法/签名值；</li>
 *   <li>SIGNED → REVOKED 必须写原因；签/撤都走状态 + version CAS；</li>
 *   <li>验证接口重算正文哈希，CA 模式再验签名，任一不符返回 CONSENT_SIGNATURE_INVALID。</li>
 * </ol>
 */
@Service
public class ConsentService {

    private static final int LIST_LIMIT = 200;
    private static final Set<String> CONSENT_STATUSES = Set.of(
            ConsentRecord.DRAFT, ConsentRecord.SIGNED, ConsentRecord.REVOKED);
    private static final Set<String> TEMPLATE_STATUSES = Set.of(
            ConsentTemplate.DRAFT, ConsentTemplate.PUBLISHED, ConsentTemplate.DISABLED);

    private final ConsentRepository repo;
    private final PatientClient patientClient;
    private final ClinicalClient clinicalClient;
    private final CaSignatureProvider caProvider;
    private final ObjectMapper objectMapper;
    /** CA 签署要"锁外外呼、锁内落库"，必须显式管理事务（G75）。 */
    private final TransactionTemplate tx;

    public ConsentService(ConsentRepository repo, PatientClient patientClient,
                          ClinicalClient clinicalClient, CaSignatureProvider caProvider,
                          ObjectMapper objectMapper,
                          org.springframework.transaction.PlatformTransactionManager txManager) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.clinicalClient = clinicalClient;
        this.caProvider = caProvider;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    // ---------------------------------------------------------------- templates

    @Transactional
    public TemplateView createTemplate(CreateTemplateReq req, String actor) {
        String code = req.templateCode().trim();
        if (repo.findTemplateByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "模板编码已存在：" + code);
        }
        ConsentTemplate template = new ConsentTemplate(UUID.randomUUID(), code, req.name().trim(),
                req.procedureType().trim().toUpperCase(), req.content().trim(), 1,
                ConsentTemplate.DRAFT, actor, null, null, 0L);
        repo.insertTemplate(template);
        audit(actor, "CONSENT_TEMPLATE_CREATE", "CONSENT_TEMPLATE", template.id().toString(),
                Map.of("templateCode", code, "procedureType", template.procedureType()));
        return view(repo.findTemplate(template.id()).orElseThrow());
    }

    @Transactional
    public TemplateView publishTemplate(UUID id, long version, String actor) {
        ConsentTemplate template = repo.findTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_TEMPLATE_NOT_FOUND));
        if (!ConsentTemplate.DRAFT.equals(template.status())) {
            throw new BusinessException(ErrorCode.CONSENT_TEMPLATE_STATUS_INVALID,
                    "只有草稿模板可以发布，当前：" + template.status());
        }
        if (!repo.updateTemplateStatus(id, ConsentTemplate.DRAFT, ConsentTemplate.PUBLISHED, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板已被修改，请刷新后重试");
        }
        audit(actor, "CONSENT_TEMPLATE_PUBLISH", "CONSENT_TEMPLATE", id.toString(),
                Map.of("templateCode", template.templateCode()));
        return view(repo.findTemplate(id).orElseThrow());
    }

    @Transactional
    public TemplateView disableTemplate(UUID id, long version, String actor) {
        ConsentTemplate template = repo.findTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_TEMPLATE_NOT_FOUND));
        if (!ConsentTemplate.PUBLISHED.equals(template.status())) {
            throw new BusinessException(ErrorCode.CONSENT_TEMPLATE_STATUS_INVALID,
                    "只有已发布模板可以停用，当前：" + template.status());
        }
        if (!repo.updateTemplateStatus(id, ConsentTemplate.PUBLISHED, ConsentTemplate.DISABLED, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板已被修改，请刷新后重试");
        }
        audit(actor, "CONSENT_TEMPLATE_DISABLE", "CONSENT_TEMPLATE", id.toString(),
                Map.of("templateCode", template.templateCode()));
        return view(repo.findTemplate(id).orElseThrow());
    }

    public List<TemplateView> templates(String status, int limit) {
        String normalized = normalizeStatus(status, TEMPLATE_STATUSES);
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        return repo.listTemplates(normalized, size).stream().map(ConsentService::view).toList();
    }

    // ---------------------------------------------------------------- consents

    @Transactional
    public ConsentView create(CreateConsentReq req, String actor) {
        return create(req, actor, com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID);
    }

    /** 创建同意书并固化院区归属（G45）。 */
    @Transactional
    public ConsentView create(CreateConsentReq req, String actor, UUID campusId) {
        ConsentTemplate template = repo.findTemplateByCode(req.templateCode().trim())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_TEMPLATE_NOT_FOUND));
        if (!template.published()) {
            throw new BusinessException(ErrorCode.CONSENT_TEMPLATE_NOT_FOUND,
                    "模板未发布，不能创建同意书：" + template.templateCode());
        }
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());
        ClinicalClient.VisitRef visit = clinicalClient.visit(req.visitId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "就诊不存在或临床服务暂不可用，请稍后重试"));
        if (!patient.id().equals(visit.patientId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "就诊号不属于该患者，请核对后重新创建同意书");
        }

        String procedure = req.procedureName().trim();
        String contentText = render(template.content(), patient.name(), procedure, actor);
        String hash = RecordService.sha256(contentText);
        ConsentRecord consent = new ConsentRecord(UUID.randomUUID(), repo.nextConsentNo(),
                template.id(), template.templateCode(), template.templateVersion(), template.name(),
                patient.id(), patient.patientNo(), patient.name(), req.visitId(), procedure,
                contentText, hash, ConsentRecord.DRAFT, null, null, null, actor,
                null, null, null, null, null, null, null, null, actor, null, null, 0L);
        repo.insertConsent(consent, campusId);
        audit(actor, "CONSENT_CREATE", "CONSENT", consent.id().toString(), Map.of(
                "consentNo", consent.consentNo(), "patientNo",
                com.medassist.common.security.Masks.patientNo(patient.patientNo()),
                "templateCode", template.templateCode()));
        return view(repo.findConsent(consent.id()).orElseThrow());
    }

    /**
     * 签署（CA 模式）。
     *
     * <p>G75 起 CA 外呼放在行锁之外：原实现先 {@code lockConsent} 再在事务里调厂商
     * HTTP，厂商慢几秒这份同意书的行锁就占几秒，同单据的撤销/重签全部排队。
     * 同意书正文与内容哈希在创建时固化、签署不改正文，因此"锁外按快照签、锁内复核
     * 状态与版本再落库"不会产生哈希漂移；CA 失败也不再生任何写操作。
     */
    public ConsentView sign(UUID id, SignReq req, String actor) {
        long expectedVersion = requireVersion(req.version());
        String signer = trimToNull(req.patientSigner());
        String relation = trimToNull(req.patientRelation());
        if (signer == null || relation == null) {
            throw new BusinessException(ErrorCode.CONSENT_SIGNER_REQUIRED);
        }
        // ① 无锁快照：状态与内容哈希只读校验
        ConsentRecord snapshot = repo.findConsent(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_NOT_FOUND));
        if (!snapshot.draft()) {
            throw new BusinessException(ErrorCode.CONSENT_STATUS_INVALID,
                    "只有草稿同意书可以签署，当前：" + snapshot.status());
        }
        // ② 锁外外呼 CA
        boolean caSign = Boolean.TRUE.equals(req.caSign());
        CaSignResult caResult = caSign
                ? caProvider.sign(new CaSignCommand(snapshot.consentNo(),
                        snapshot.contentHash(), signer, "CONSENT"))
                : null;
        // ③ 短事务：加锁复核状态与版本后落库
        return tx.execute(status -> signInTransaction(id, req, actor, expectedVersion,
                signer, relation, caResult));
    }

    /** 签署落库（调用方负责事务边界；方法内先取同意书行锁）。 */
    private ConsentView signInTransaction(UUID id, SignReq req, String actor, long expectedVersion,
                                          String signer, String relation, CaSignResult caResult) {
        ConsentRecord consent = repo.lockConsent(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_NOT_FOUND));
        if (!consent.draft()) {
            throw new BusinessException(ErrorCode.CONSENT_STATUS_INVALID,
                    "只有草稿同意书可以签署，当前：" + consent.status());
        }
        String doctor = trimToNull(req.doctorName());
        if (doctor == null) {
            doctor = consent.doctorName();
        }

        String method = caResult == null ? ConsentRecord.SIGN_PAPER : ConsentRecord.SIGN_CA;
        String certificateSerial = caResult == null ? null : caResult.certificateSerial();
        String signatureValue = caResult == null ? null : caResult.signatureValue();
        String algorithm = caResult == null ? null : caResult.algorithm();
        Instant signedAt = caResult == null ? Instant.now() : caResult.signedAt();

        if (!repo.signConsent(id, expectedVersion, signer, relation, trimToNull(req.witnessName()),
                doctor, signedAt, method, certificateSerial, signatureValue, algorithm)) {
            throw new BusinessException(ErrorCode.CONFLICT, "同意书已被他人修改，请刷新后重试");
        }
        audit(actor, "CONSENT_SIGN", "CONSENT", id.toString(), Map.of(
                "consentNo", consent.consentNo(), "method", method,
                "patientSigner", signer, "certificateSerial",
                certificateSerial == null ? "" : certificateSerial));
        return view(repo.findConsent(id).orElseThrow());
    }

    @Transactional
    public ConsentView revoke(UUID id, RevokeReq req, String actor) {
        long expectedVersion = requireVersion(req.version());
        String reason = trimToNull(req.reason());
        if (reason == null) {
            throw new BusinessException(ErrorCode.CONSENT_REVOKE_REASON_REQUIRED);
        }
        ConsentRecord consent = repo.lockConsent(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_NOT_FOUND));
        if (!consent.signed()) {
            throw new BusinessException(ErrorCode.CONSENT_STATUS_INVALID,
                    "只有已签署同意书可以撤销，当前：" + consent.status());
        }
        if (!repo.revokeConsent(id, expectedVersion, reason, actor, Instant.now())) {
            throw new BusinessException(ErrorCode.CONFLICT, "同意书已被他人修改，请刷新后重试");
        }
        audit(actor, "CONSENT_REVOKE", "CONSENT", id.toString(),
                Map.of("consentNo", consent.consentNo(), "reason", reason));
        return view(repo.findConsent(id).orElseThrow());
    }

    public ConsentView detail(UUID id) {
        return view(repo.findConsent(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_NOT_FOUND)));
    }

    public List<ConsentView> list(UUID patientId, String status, int limit) {
        return list(patientId, status, limit, Set.of());
    }

    /** 列表（G45）：campusIds 非空时只返回可见院区。 */
    public List<ConsentView> list(UUID patientId, String status, int limit, Set<UUID> campusIds) {
        String normalized = normalizeStatus(status, CONSENT_STATUSES);
        int size = limit <= 0 ? 100 : Math.min(limit, LIST_LIMIT);
        return repo.listConsents(patientId, normalized, size, campusCsv(campusIds)).stream()
                .map(ConsentService::view).toList();
    }

    /** 院区集合转 SQL 过滤串（G71 统一走 CampusFilters）。 */
    private static String campusCsv(Set<UUID> campusIds) {
        return com.medassist.common.security.CampusFilters.csv(campusIds);
    }

    /** 验签：正文哈希必须一致；CA 签名再验签名值。 */
    public VerifyView verify(UUID id) {
        ConsentRecord consent = repo.findConsent(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_NOT_FOUND));
        boolean hashValid = consent.contentHash() != null
                && consent.contentHash().equals(RecordService.sha256(consent.contentText()));
        Boolean signatureValid = null;
        if (ConsentRecord.SIGN_CA.equals(consent.signMethod())) {
            signatureValid = hashValid && caProvider.verify(consent.contentHash(),
                    consent.patientSigner(), "CONSENT", consent.signedAt(),
                    consent.algorithm(), consent.signatureValue());
        }
        boolean ok = hashValid && (signatureValid == null || signatureValid);
        if (!ok) {
            throw new BusinessException(ErrorCode.CONSENT_SIGNATURE_INVALID);
        }
        return new VerifyView(true, signatureValid, consent.status());
    }

    public ConsentRepository.ConsentBoard board() {
        LocalDate today = BusinessTime.today();
        OffsetDateTime from = today.atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        OffsetDateTime to = today.plusDays(1).atStartOfDay(BusinessTime.zone()).toOffsetDateTime();
        return repo.board(from, to);
    }

    /** 打印视图：归档/纸质留存用；所有动态内容做 HTML 转义。 */
    public String printHtml(UUID id, String actor) {
        ConsentRecord c = repo.findConsent(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSENT_NOT_FOUND));
        audit(actor, "CONSENT_PRINT", "CONSENT", id.toString(), Map.of("consentNo", c.consentNo()));
        String body = HtmlUtils.htmlEscape(c.contentText()).replace("\n", "<br/>");
        return """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"/>
                <title>%s %s</title>
                <style>
                  body{font-family:"Songti SC","SimSun",serif;color:#111;margin:36px;line-height:1.75}
                  h1{font-size:20px;text-align:center;margin:0 0 6px}
                  .meta{text-align:center;color:#555;font-size:12px;margin-bottom:22px}
                  .content{font-size:14px;white-space:normal}
                  .sign{margin-top:36px;display:grid;gap:10px;font-size:13px}
                  .foot{margin-top:28px;color:#777;font-size:11px;border-top:1px solid #ddd;padding-top:8px}
                </style></head><body>
                <h1>%s</h1>
                <div class="meta">%s · %s · 患者 %s（%s）</div>
                <div class="content">%s</div>
                <div class="sign">
                  <div>患者/家属签署：%s（%s）</div>
                  <div>见证人：%s</div>
                  <div>医师：%s</div>
                  <div>签署时间：%s</div>
                  <div>签署方式：%s%s</div>
                </div>
                <div class="foot">内容哈希 SHA-256：%s</div>
                </body></html>
                """.formatted(
                HtmlUtils.htmlEscape(c.templateName()), HtmlUtils.htmlEscape(c.consentNo()),
                HtmlUtils.htmlEscape(c.templateName()), HtmlUtils.htmlEscape(c.consentNo()),
                HtmlUtils.htmlEscape(c.procedureName()),
                HtmlUtils.htmlEscape(c.patientName()), HtmlUtils.htmlEscape(c.patientNo()),
                body,
                nullToDash(c.patientSigner()), nullToDash(c.patientRelation()),
                nullToDash(c.witnessName()), nullToDash(c.doctorName()),
                c.signedAt() == null ? "—" : c.signedAt().toString(),
                nullToDash(c.signMethod()),
                c.certificateSerial() == null ? "" : "（证书 " + c.certificateSerial() + "）",
                c.contentHash());
    }

    // ---------------------------------------------------------------- 内部

    private static ConsentView view(ConsentRecord c) {
        return new ConsentView(c.id(), c.consentNo(), c.templateId(), c.templateCode(),
                c.templateVersion(), c.templateName(), c.patientId(), c.patientNo(),
                c.patientName(), c.visitId(), c.procedureName(), c.contentText(),
                c.contentHash(), c.status(), c.patientSigner(), c.patientRelation(),
                c.witnessName(), c.doctorName(), c.signedAt(), c.signMethod(),
                c.certificateSerial(), c.signatureValue(), c.algorithm(), c.revokeReason(),
                c.revokedBy(), c.revokedAt(), c.createdBy(), c.createdAt(), c.updatedAt(),
                c.version());
    }

    private static TemplateView view(ConsentTemplate t) {
        return new TemplateView(t.id(), t.templateCode(), t.name(), t.procedureType(),
                t.content(), t.templateVersion(), t.status(), t.createdBy(), t.createdAt(),
                t.updatedAt(), t.version());
    }

    /**
     * 渲染同意书正文。
     *
     * <p>{@code {{date}}} 取业务时区（G76）：这是患者签署时看到的日期，
     * 也是日后举证"哪天签的"的依据，不能随容器默认时区漂移。
     */
    private static String render(String template, String patientName, String procedure,
                                 String doctorName) {
        return template.replace("{{patientName}}", patientName)
                .replace("{{procedureName}}", procedure)
                .replace("{{doctorName}}", doctorName == null ? "" : doctorName)
                .replace("{{date}}", BusinessTime.today().toString());
    }

    private static String normalizeStatus(String status, Set<String> allowed) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "状态只能是 " + String.join(" / ", allowed));
        }
        return normalized;
    }

    private static long requireVersion(Long version) {
        if (version == null || version < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少 version，请刷新后重试");
        }
        return version;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : HtmlUtils.htmlEscape(value);
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
