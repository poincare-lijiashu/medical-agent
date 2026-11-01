package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.PreopDiscussion;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryConsent;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PreopDtos.ConsentReq;
import com.medassist.clinical.web.PreopDtos.PreopReq;
import com.medassist.clinical.web.PreopDtos.PreopSummary;
import com.medassist.clinical.web.PreopDtos.RevokeConsentReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 术前管理：术前讨论 + 知情同意 + 术前核查清单。
 *
 * <p>规矩：
 * <ol>
 *   <li>术前讨论只能在计划态书写；签署需要主持人、参加人、手术方案三项齐全，签署后冻结；</li>
 *   <li>同意书插入即"已签署事实"，不提供修改接口；写错只能**作废（留原因）后重签**，
 *       同一类型同时只能有一份有效记录（部分唯一索引兜底）；</li>
 *   <li>手术与麻醉知情同意是开台硬门槛，由 {@link SurgeryService#start} 校验；这里只负责提供事实。</li>
 * </ol>
 *
 * <p>为什么不做"同意书模板渲染"：模板与电子签名证书属于病历文书子系统的职责，
 * 本域只保证"谁、何时、为什么类型、签了谁的同意"这些事实可信可追溯。
 */
@Service
public class PreopService {

    /** 开台必须具备的同意书类型。 */
    static final List<String> REQUIRED_CONSENTS =
            List.of(SurgeryConsent.SURGERY, SurgeryConsent.ANESTHESIA);

    private final ClinicalRepository repo;

    public PreopService(ClinicalRepository repo) {
        this.repo = repo;
    }

    // ---------------------------------------------------------------- 术前讨论

    public PreopDiscussion getDiscussion(UUID caseId) {
        requireCase(caseId);
        return repo.findPreopByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚无术前讨论记录"));
    }

    @Transactional
    public PreopDiscussion saveDiscussion(UUID caseId, PreopReq req, String actor) {
        SurgeryCase surgery = requireCase(caseId);
        if (!SurgeryCase.PLANNED.equals(surgery.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "术前讨论只能在手术开始前书写，当前：" + surgery.status());
        }
        PreopDiscussion existing = repo.findPreopByCase(caseId).orElse(null);
        if (existing != null && PreopDiscussion.SIGNED.equals(existing.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "术前讨论已签署，不能再修改（如需更正请走病案修改审批）");
        }
        if (existing == null) {
            PreopDiscussion created = new PreopDiscussion(UUID.randomUUID(), caseId,
                    req.discussionAt(), blankToNull(req.moderator()), blankToNull(req.participants()),
                    blankToNull(req.diagnosisSummary()), blankToNull(req.procedurePlan()),
                    blankToNull(req.risks()), blankToNull(req.anesthesiaPlan()),
                    blankToNull(req.bloodPreparation()), PreopDiscussion.DRAFT, null, null,
                    actor, 0L, Instant.now());
            try {
                repo.insertPreop(created);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "该手术已有术前讨论记录，请刷新后重试");
            }
            audit(actor, "PREOP_DISCUSSION_SAVE", created.id(), surgery.caseNo() + " create");
            return created;
        }
        if (req.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "更新术前讨论必须携带 version（乐观锁）");
        }
        PreopDiscussion updated = new PreopDiscussion(existing.id(), caseId,
                req.discussionAt(), blankToNull(req.moderator()), blankToNull(req.participants()),
                blankToNull(req.diagnosisSummary()), blankToNull(req.procedurePlan()),
                blankToNull(req.risks()), blankToNull(req.anesthesiaPlan()),
                blankToNull(req.bloodPreparation()), existing.status(), existing.signedBy(),
                existing.signedAt(), existing.createdBy(), existing.version(), existing.createdAt());
        if (!repo.updatePreopDraft(updated, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "术前讨论已被他人修改，请刷新后重试");
        }
        audit(actor, "PREOP_DISCUSSION_SAVE", existing.id(), surgery.caseNo() + " update");
        return repo.findPreopByCase(caseId).orElseThrow();
    }

    @Transactional
    public PreopDiscussion signDiscussion(UUID caseId, long version, String actor) {
        SurgeryCase surgery = requireCase(caseId);
        PreopDiscussion record = repo.findPreopByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "该手术尚无术前讨论记录"));
        if (PreopDiscussion.SIGNED.equals(record.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "术前讨论已签署，请勿重复签署");
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(record.moderator())) {
            missing.add("主持人");
        }
        if (isBlank(record.participants())) {
            missing.add("参加人");
        }
        if (isBlank(record.procedurePlan())) {
            missing.add("手术方案");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "签署前必须填写：" + String.join("、", missing));
        }
        if (!repo.signPreop(record.id(), actor, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "术前讨论已被他人修改，请刷新后重试");
        }
        audit(actor, "PREOP_DISCUSSION_SIGN", record.id(), surgery.caseNo());
        return repo.findPreopByCase(caseId).orElseThrow();
    }

    // ---------------------------------------------------------------- 知情同意

    public List<SurgeryConsent> consents(UUID caseId) {
        requireCase(caseId);
        return repo.listConsentsByCase(caseId);
    }

    @Transactional
    public SurgeryConsent addConsent(UUID caseId, ConsentReq req, String actor) {
        SurgeryCase surgery = requireCase(caseId);
        // 已开台后不允许补签：否则"术中/术后补一张术前同意书"即可绕过开台门槛
        if (SurgeryCase.IN_PROGRESS.equals(surgery.status())
                || SurgeryCase.COMPLETED.equals(surgery.status())
                || SurgeryCase.CANCELLED.equals(surgery.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "手术已开始/已结束/已取消，不能再新增知情同意书" +
                            "（如确需补签，请走病案修改审批并说明原因）");
        }
        String type = req.consentType().trim().toUpperCase();
        if (repo.findActiveConsent(caseId, type).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该手术已有有效的「" + type + "」同意书；如需重签请先作废原记录");
        }
        // 签署时间一律服务端取当前时间：客户端传值等于允许倒签（法律文书时间不得伪造）
        SurgeryConsent consent = new SurgeryConsent(UUID.randomUUID(), caseId, type,
                req.signerName().trim(), blankToNull(req.signerRelation()), Instant.now(),
                blankToNull(req.witness()), blankToNull(req.notes()), null, null, actor, 0L,
                Instant.now());
        try {
            repo.insertConsent(consent);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "该类型同意书已存在，请刷新后重试");
        }
        audit(actor, "SURGERY_CONSENT_ADD", consent.id(), surgery.caseNo() + " " + type);
        return consent;
    }

    @Transactional
    public SurgeryConsent revokeConsent(UUID caseId, UUID consentId, RevokeConsentReq req,
                                        String actor) {
        SurgeryCase surgery = requireCase(caseId);
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "作废同意书必须填写原因");
        }
        SurgeryConsent consent = repo.listConsentsByCase(caseId).stream()
                .filter(c -> c.id().equals(consentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该同意书"));
        if (!consent.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该同意书已作废");
        }
        if (!repo.revokeConsent(consentId, reason, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "同意书已被他人修改，请刷新后重试");
        }
        audit(actor, "SURGERY_CONSENT_REVOKE", consentId, surgery.caseNo() + " " + reason);
        return repo.listConsentsByCase(caseId).stream()
                .filter(c -> c.id().equals(consentId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该同意书"));
    }

    // ---------------------------------------------------------------- 术前核查清单

    public PreopSummary summary(UUID caseId) {
        SurgeryCase surgery = requireCase(caseId);
        List<SurgeryConsent> consents = repo.listConsentsByCase(caseId);
        List<String> activeTypes = consents.stream().filter(SurgeryConsent::active)
                .map(SurgeryConsent::consentType).toList();
        List<String> missing = REQUIRED_CONSENTS.stream()
                .filter(type -> !activeTypes.contains(type))
                .toList();
        boolean planned = SurgeryCase.PLANNED.equals(surgery.status());
        return new PreopSummary(caseId, surgery.status(), repo.findPreopByCase(caseId).orElse(null),
                consents, missing, planned && missing.isEmpty());
    }

    // ---------------------------------------------------------------- 内部工具

    private SurgeryCase requireCase(UUID caseId) {
        return repo.findSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
    }

    private void audit(String actor, String action, UUID targetId, String detail) {
        String targetType = action.startsWith("SURGERY_CONSENT")
                ? "SURGERY_CONSENT" : "PREOP_DISCUSSION";
        repo.logChange(actor, action, targetType, targetId.toString(),
                "{\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
