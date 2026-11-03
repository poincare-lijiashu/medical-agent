package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.domain.ClinicalModel.SurgeryLevel;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 手术分级与审批（手术麻醉域第四批）。
 *
 * <p>规矩：
 * <ol>
 *   <li>一、二级手术由具备相应资质的医师决定，落库即为 {@code NOT_REQUIRED}；</li>
 *   <li>三、四级手术必须提交审批：{@code PENDING → APPROVED / REJECTED}，
 *       驳回必须写原因，重新申请会清空上一次的审批结论；</li>
 *   <li>审批遵循**四眼原则**：申请人不能审批自己提交的分级申请；</li>
 *   <li>分级只在手术开始前可调整；开台时由 {@link SurgeryService} 调用
 *       {@link #requireStartApproved(UUID)} 做硬门槛校验。</li>
 * </ol>
 *
 * <p>为什么四眼原则要做在服务层而不是只靠权限点：权限点只能区分"能不能审批"，
 * 区分不了"能不能审批这一条"。管理员天然同时拥有申请与审批权限，
 * 若不校验申请人与审批人，分级审批就退化成一次点击。
 */
@Service
public class SurgeryLevelService {

    private final ClinicalRepository repo;

    public SurgeryLevelService(ClinicalRepository repo) {
        this.repo = repo;
    }

    /** 查询分级；未分级返回 {@code null}（"未分级"是正常业务状态，不是错误）。 */
    public SurgeryLevel get(UUID caseId) {
        requireCase(caseId);
        return repo.findSurgeryLevel(caseId).orElse(null);
    }

    /**
     * 提交/调整分级。
     *
     * <p>1~2 级自动免审批；3~4 级置为待审批。已审批通过的同一分级不允许重复提交，
     * 审批中的同一分级不允许重复提交（防止用"重新提交"改写申请人以绕过四眼原则）。
     */
    @Transactional
    public SurgeryLevel setLevel(UUID caseId, Integer level, Long version, String actor) {
        SurgeryCase surgery = lockCase(caseId);
        if (!SurgeryCase.PLANNED.equals(surgery.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "手术分级只能在手术开始前确定，当前状态：" + surgery.status());
        }
        int value = level == null ? 0 : level;
        if (value < SurgeryLevel.MIN_LEVEL || value > SurgeryLevel.MAX_LEVEL) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "手术分级只能是 1~4 级");
        }
        String status = SurgeryLevel.initialStatus(value);
        SurgeryLevel existing = repo.findSurgeryLevel(caseId).orElse(null);
        if (existing == null) {
            SurgeryLevel created = new SurgeryLevel(UUID.randomUUID(), caseId, value, status,
                    actor, Instant.now(), null, null, null, actor, 0L, Instant.now());
            try {
                repo.insertSurgeryLevel(created);
            } catch (DataIntegrityViolationException e) {
                // ux_surgery_level_case：并发首次提交
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该手术已有分级记录，请刷新后重试");
            }
            audit(actor, "SURGERY_LEVEL_SET", created.id(), surgery.caseNo(),
                    "level=" + value + " status=" + status);
            return repo.findSurgeryLevel(caseId).orElse(created);
        }
        if (existing.level() == value) {
            if (SurgeryLevel.APPROVED.equals(existing.approvalStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该分级已审批通过，无需重复提交");
            }
            if (SurgeryLevel.PENDING.equals(existing.approvalStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该分级申请正在审批中，请等待审批结果或调整分级后再提交");
            }
        }
        if (version == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "调整手术分级必须携带 version（乐观锁）");
        }
        if (!repo.resetSurgeryLevel(caseId, value, status, actor, version)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "手术分级已被他人修改，请刷新后重试");
        }
        audit(actor, "SURGERY_LEVEL_SET", existing.id(), surgery.caseNo(),
                "level=" + existing.level() + "->" + value + " status=" + status);
        return repo.findSurgeryLevel(caseId).orElseThrow();
    }

    /** 审批通过：只有待审批的三/四级分级可以审批，且审批人不得是申请人。 */
    @Transactional
    public SurgeryLevel approve(UUID caseId, long version, String actor) {
        SurgeryCase surgery = lockCase(caseId);
        SurgeryLevel record = requireLevel(caseId);
        if (record.level() < SurgeryLevel.APPROVAL_THRESHOLD) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "一、二级手术无需审批");
        }
        requirePending(record);
        requireFourEyes(record, actor);
        if (!repo.approveSurgeryLevel(caseId, actor, version)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "手术分级已被他人修改，请刷新后重试");
        }
        audit(actor, "SURGERY_LEVEL_APPROVE", record.id(), surgery.caseNo(),
                "level=" + record.level());
        return repo.findSurgeryLevel(caseId).orElseThrow();
    }

    /** 驳回：必须写原因，同样受四眼原则约束。 */
    @Transactional
    public SurgeryLevel reject(UUID caseId, long version, String reason, String actor) {
        SurgeryCase surgery = lockCase(caseId);
        SurgeryLevel record = requireLevel(caseId);
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "驳回手术分级必须填写原因");
        }
        if (record.level() < SurgeryLevel.APPROVAL_THRESHOLD) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "一、二级手术无需审批");
        }
        requirePending(record);
        requireFourEyes(record, actor);
        if (!repo.rejectSurgeryLevel(caseId, text, actor, version)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "手术分级已被他人修改，请刷新后重试");
        }
        audit(actor, "SURGERY_LEVEL_REJECT", record.id(), surgery.caseNo(),
                "level=" + record.level() + " reason=" + text);
        return repo.findSurgeryLevel(caseId).orElseThrow();
    }

    /**
     * 开台硬门槛：未分级、审批中、已驳回一律拒绝。
     *
     * <p>刻意不做成配置开关：分级审批是医疗质量安全核心制度，
     * "能配置关掉"本身就是合规风险（与知情同意门槛同一取舍）。
     */
    public void requireStartApproved(UUID caseId) {
        SurgeryLevel level = repo.findSurgeryLevel(caseId).orElse(null);
        if (level == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "开台前必须完成手术分级（当前未分级）");
        }
        if (level.clearedForStart()) {
            return;
        }
        if (SurgeryLevel.PENDING.equals(level.approvalStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "三级及以上手术需审批通过后才能开台（当前审批中，分级："
                            + level.level() + " 级）");
        }
        if (SurgeryLevel.REJECTED.equals(level.approvalStatus())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "手术分级申请已被驳回（" + level.rejectReason() + "），请调整后重新申请");
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                "当前手术分级状态不允许开台：" + level.approvalStatus());
    }

    // ---------------------------------------------------------------- 内部工具

    private SurgeryCase requireCase(UUID caseId) {
        return repo.findSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
    }

    /**
     * 写路径的归属校验：必须与开台（{@code SurgeryService.start}）持同一把行锁。
     *
     * <p>为什么不能只做只读校验：开台事务先读"已审批通过"再落状态，
     * 如果改分级的事务不加锁，它可以在两者之间把分级改回待审批并提交，
     * 开台仍然成功——审批门槛被静默绕过，而审计里两条记录都"合规"。
     *
     * <p>锁顺序与开台一致（都是先锁 surgery_case，再动 surgery_level），不会死锁。
     */
    private SurgeryCase lockCase(UUID caseId) {
        return repo.lockSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
    }

    private SurgeryLevel requireLevel(UUID caseId) {
        return repo.findSurgeryLevel(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "该手术尚未提交分级"));
    }

    private static void requirePending(SurgeryLevel record) {
        if (!SurgeryLevel.PENDING.equals(record.approvalStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "只有待审批的分级可以审批，当前状态：" + record.approvalStatus());
        }
    }

    private static void requireFourEyes(SurgeryLevel record, String actor) {
        if (actor != null && actor.equals(record.appliedBy())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "分级审批遵循四眼原则：申请人不能审批自己提交的申请");
        }
    }

    private void audit(String actor, String action, UUID levelId, String caseNo, String detail) {
        repo.logChange(actor, action, "SURGERY_LEVEL", levelId.toString(),
                "{\"caseNo\":\"" + caseNo + "\",\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
