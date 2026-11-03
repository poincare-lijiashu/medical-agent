package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Consultation;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ConsultationDtos.CancelConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.CompleteConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.CreateConsultationReq;
import com.medassist.clinical.web.ConsultationDtos.RejectConsultationReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会诊：跨科室只读授权的业务入口。
 *
 * <p>这套接口解决的是"受邀科室能不能看这位患者"的问题，而不是"会诊记录怎么存"。
 * 因此每个状态迁移都同时做两件事：改状态、写审计；授权是否生效完全由
 * {@code status IN (REQUESTED, ACCEPTED)} 决定，{@link VisitAccessGuard#requireRead}
 * 直接读这张表，不存在第二套授权开关。
 *
 * <p>权限分工：
 * <ul>
 *   <li>发起 / 撤回：主诊科室（或全院角色）；</li>
 *   <li>受理 / 拒绝 / 完成：受邀科室（或全院角色）；</li>
 *   <li>查看：主诊科室、受邀科室（含历史）、全院角色。</li>
 * </ul>
 */
@Service
public class ConsultationService {

    private static final int DEFAULT_LIMIT = 50;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ClinicalRepository repo;
    private final VisitAccessGuard guard;

    public ConsultationService(ClinicalRepository repo, VisitAccessGuard guard) {
        this.repo = repo;
        this.guard = guard;
    }

    /** 发起会诊：主诊科室把该就诊只读授权给受邀科室。 */
    @Transactional
    public Consultation request(DataScope scope, UUID visitId, CreateConsultationReq req,
                                String actor) {
        Visit visit = guard.require(scope, visitId);
        if (req.toDeptId().equals(visit.deptId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "受邀科室不能是主诊科室本身");
        }
        Dept target = repo.findDept(req.toDeptId())
                .filter(Dept::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "受邀科室不存在或已停用"));
        Consultation created = new Consultation(UUID.randomUUID(), repo.nextConsultationNo(),
                visitId, visit.patientId(), visit.patientNo(), visit.deptId(), target.id(),
                req.reason().trim(), Consultation.REQUESTED, actor, Instant.now(),
                null, null, null, null, null, null, null, null, null, 0L, Instant.now());
        try {
            repo.insertConsultation(created);
        } catch (DataIntegrityViolationException e) {
            // ux_consultation_active：同一就诊对同一科室只能有一条进行中的会诊
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该科室对此就诊已有进行中的会诊，请勿重复申请");
        }
        audit(actor, "CONSULTATION_REQUEST", created,
                Map.of("toDeptId", target.id().toString(), "reason", created.reason()));
        return created;
    }

    /** 按就诊查会诊记录：主诊科室、受邀科室、全院角色可见。 */
    public List<Consultation> listByVisit(DataScope scope, UUID visitId) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        boolean visible = scope.canSeeDepartment(visit.deptId())
                || repo.existsActiveConsultation(visitId, scope.deptCodes());
        if (!visible) {
            // 行级权限规则：不可见一律伪装成不存在，避免枚举
            throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
        }
        return repo.listConsultationsByVisit(visitId, DEFAULT_LIMIT);
    }

    /** 会诊收件箱：受邀科室的待处理 / 进行中会诊；全院角色看全部。 */
    public List<Consultation> inbox(DataScope scope, String status, Integer limit) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, 200);
        if (scope.allDepartments()) {
            return repo.listConsultations(normalized, size);
        }
        return repo.listConsultationsInbox(scope.deptCodes(), normalized, size);
    }

    /** 受邀科室受理（行锁已串行化并发受理，无需客户端再传版本）。 */
    @Transactional
    public Consultation accept(DataScope scope, UUID id, String actor) {
        Consultation current = lockForInvitedDept(scope, id);
        requireStatus(current, Consultation.REQUESTED, "只有待受理的会诊可以受理");
        if (!repo.acceptConsultation(id, actor, current.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "会诊状态已变化，请刷新后重试");
        }
        audit(actor, "CONSULTATION_ACCEPT", current, Map.of("version", current.version()));
        return repo.findConsultation(id).orElseThrow();
    }

    /** 受邀科室拒绝（必须写理由）。 */
    @Transactional
    public Consultation reject(DataScope scope, UUID id, RejectConsultationReq req, String actor) {
        Consultation current = lockForInvitedDept(scope, id);
        requireStatus(current, Consultation.REQUESTED, "只有待受理的会诊可以拒绝");
        String reason = req.reason().trim();
        if (reason.isEmpty()) {
            // Controller 的 @NotBlank 已挡一次；服务层再兜一次，避免其他调用方绕过校验
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "拒绝会诊必须填写理由");
        }
        if (!repo.rejectConsultation(id, actor, reason, current.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "会诊状态已变化，请刷新后重试");
        }
        audit(actor, "CONSULTATION_REJECT", current, Map.of("reason", reason));
        return repo.findConsultation(id).orElseThrow();
    }

    /** 受邀科室完成会诊并给出意见；完成后只读授权立即收回。 */
    @Transactional
    public Consultation complete(DataScope scope, UUID id, CompleteConsultationReq req,
                                 String actor) {
        Consultation current = lockForInvitedDept(scope, id);
        requireStatus(current, Consultation.ACCEPTED, "只有已受理的会诊可以完成");
        if (!repo.completeConsultation(id, actor, req.conclusion().trim(), req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "会诊状态已变化，请刷新后重试");
        }
        audit(actor, "CONSULTATION_COMPLETE", current, Map.of("conclusion", req.conclusion()));
        return repo.findConsultation(id).orElseThrow();
    }

    /** 主诊科室撤回进行中的会诊（患者转院、病情变化等）。 */
    @Transactional
    public Consultation cancel(DataScope scope, UUID id, CancelConsultationReq req, String actor) {
        Consultation current = repo.lockConsultation(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该会诊申请"));
        if (!scope.canSeeDepartment(current.fromDeptId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该会诊申请");
        }
        if (!current.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该会诊已结束，不能撤回");
        }
        if (!repo.cancelConsultation(id, actor, req.reason().trim(), req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "会诊状态已变化，请刷新后重试");
        }
        audit(actor, "CONSULTATION_CANCEL", current, Map.of("reason", req.reason()));
        return repo.findConsultation(id).orElseThrow();
    }

    /** 受邀科室才能受理/拒绝/完成；越权按"不存在"处理。 */
    private Consultation lockForInvitedDept(DataScope scope, UUID id) {
        Consultation current = repo.lockConsultation(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该会诊申请"));
        if (!scope.canSeeDepartment(current.toDeptId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到该会诊申请");
        }
        return current;
    }

    private void requireStatus(Consultation current, String expected, String message) {
        if (!expected.equals(current.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    message + "（当前状态：" + current.status() + "）");
        }
    }

    private void audit(String actor, String action, Consultation c, Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("consultationNo", c.consultationNo());
        payload.put("visitId", c.visitId() == null ? null : c.visitId().toString());
        payload.putAll(detail);
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (RuntimeException e) {
            json = "{\"consultationNo\":\"\"}";
        }
        repo.logChange(actor, action, "CONSULTATION", c.id().toString(), json);
    }
}
