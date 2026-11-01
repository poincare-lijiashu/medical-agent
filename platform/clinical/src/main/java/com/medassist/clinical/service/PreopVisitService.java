package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.PreopVisit;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryLevel;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PreopVisitDtos.PreopVisitReq;
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
 * 术前访视与麻醉评估。
 *
 * <p>规矩：
 * <ol>
 *   <li>只能在手术开始前填写（PLANNED）；签署需要"访视医师 + ASA + Mallampati + 禁食时长 + 结论"齐全；</li>
 *   <li>签署后由触发器冻结，改只能走病案修改审批；</li>
 *   <li>开台门槛：**三级及以上手术必须有已签署的访视单**；任何级别只要签署结论为
 *       {@code UNFIT}（不宜手术）就禁止开台——分级低不代表可以带着"不宜"结论上台。</li>
 * </ol>
 *
 * <p>为什么访视只对三、四级强制：一、二级手术量大，强制访视单会把门诊小手术堵在手术室门口；
 * 但"麻醉医师术前访视"本身仍是制度要求，系统用"可填写 + UNFIT 拦截"降低门槛而不是取消要求。
 */
@Service
public class PreopVisitService {

    private final ClinicalRepository repo;

    public PreopVisitService(ClinicalRepository repo) {
        this.repo = repo;
    }

    /** 查询访视单；未填写返回 {@code null}（未填写是正常状态，不是错误）。 */
    public PreopVisit get(UUID caseId) {
        requireCase(caseId);
        return repo.findPreopVisit(caseId).orElse(null);
    }

    @Transactional
    public PreopVisit save(UUID caseId, PreopVisitReq req, String actor) {
        SurgeryCase surgery = requireCase(caseId);
        if (!SurgeryCase.PLANNED.equals(surgery.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "术前访视只能在手术开始前填写，当前状态：" + surgery.status());
        }
        PreopVisit existing = repo.findPreopVisit(caseId).orElse(null);
        if (existing != null && existing.signed()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "术前访视已签署，不能再修改（如需更正请走病案修改审批）");
        }
        if (existing == null) {
            PreopVisit created = new PreopVisit(UUID.randomUUID(), caseId, req.visitedAt(),
                    blankToNull(req.visitedBy()), blankToNull(req.anesthesiaPlan()),
                    blankToNull(req.asaGrade()), blankToNull(req.mallampati()),
                    blankToNull(req.airwayNote()), blankToNull(req.dentition()),
                    blankToNull(req.anesthesiaHistory()), blankToNull(req.allergyHistory()),
                    req.fastingSolidHours(), req.fastingClearHours(), blankToNull(req.conclusion()),
                    blankToNull(req.riskNote()), PreopVisit.DRAFT, null, null, actor, 0L,
                    Instant.now());
            try {
                repo.insertPreopVisit(created);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该手术已有术前访视记录，请刷新后重试");
            }
            audit(actor, "PREOP_VISIT_SAVE", created.id(), surgery.caseNo() + " create");
            return created;
        }
        if (req.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "更新术前访视必须携带 version（乐观锁）");
        }
        PreopVisit updated = new PreopVisit(existing.id(), caseId, req.visitedAt(),
                blankToNull(req.visitedBy()), blankToNull(req.anesthesiaPlan()),
                blankToNull(req.asaGrade()), blankToNull(req.mallampati()),
                blankToNull(req.airwayNote()), blankToNull(req.dentition()),
                blankToNull(req.anesthesiaHistory()), blankToNull(req.allergyHistory()),
                req.fastingSolidHours(), req.fastingClearHours(), blankToNull(req.conclusion()),
                blankToNull(req.riskNote()), existing.status(), existing.signedBy(),
                existing.signedAt(), existing.createdBy(), existing.version(),
                existing.createdAt());
        if (!repo.updatePreopVisitDraft(updated, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "术前访视已被他人修改，请刷新后重试");
        }
        audit(actor, "PREOP_VISIT_SAVE", existing.id(), surgery.caseNo() + " update");
        return repo.findPreopVisit(caseId).orElseThrow();
    }

    @Transactional
    public PreopVisit sign(UUID caseId, long version, String actor) {
        SurgeryCase surgery = requireCase(caseId);
        PreopVisit record = repo.findPreopVisit(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "该手术尚无术前访视记录"));
        if (record.signed()) {
            throw new BusinessException(ErrorCode.CONFLICT, "术前访视已签署，请勿重复签署");
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(record.visitedBy())) {
            missing.add("访视医师");
        }
        if (isBlank(record.asaGrade())) {
            missing.add("ASA 分级");
        }
        if (isBlank(record.mallampati())) {
            missing.add("Mallampati 分级");
        }
        if (record.fastingSolidHours() == null) {
            missing.add("禁食固体时长");
        }
        if (record.fastingClearHours() == null) {
            missing.add("禁清饮时长");
        }
        if (isBlank(record.conclusion())) {
            missing.add("麻醉结论");
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "签署前必须填写：" + String.join("、", missing));
        }
        if (!repo.signPreopVisit(record.id(), actor, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "术前访视已被他人修改，请刷新后重试");
        }
        audit(actor, "PREOP_VISIT_SIGN", record.id(), surgery.caseNo() + " " + record.conclusion());
        return repo.findPreopVisit(caseId).orElseThrow();
    }

    /**
     * 开台门槛。
     *
     * <p>三级及以上必须有已签署访视；任何级别"不宜手术"结论都拦截。
     */
    public void requireStartCleared(UUID caseId) {
        SurgeryLevel level = repo.findSurgeryLevel(caseId).orElse(null);
        boolean highLevel = level != null
                && level.level() >= SurgeryLevel.APPROVAL_THRESHOLD;
        PreopVisit visit = repo.findPreopVisit(caseId).orElse(null);
        if (visit == null) {
            if (highLevel) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "三级及以上手术开台前必须完成术前访视与麻醉评估（当前未填写）");
            }
            return;
        }
        if (!visit.signed()) {
            if (highLevel) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "术前访视与麻醉评估尚未签署，不能开台");
            }
            return;
        }
        if (visit.unfit()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "麻醉评估结论为不宜手术（UNFIT），请上级医师复核并重新评估后才能开台");
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private SurgeryCase requireCase(UUID caseId) {
        return repo.findSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
    }

    private void audit(String actor, String action, UUID visitId, String detail) {
        repo.logChange(actor, action, "PREOP_VISIT", visitId.toString(),
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
