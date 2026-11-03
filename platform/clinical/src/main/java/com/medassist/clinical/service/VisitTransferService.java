package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitTransfer;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.TransferVisitReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 转科：变更就诊的主诊科室。
 *
 * <p>转科不是"再开一次挂号"，而是同一次住院的科室归属变更：
 * 医嘱、诊断、收费都继续挂在原 visit 上，只有 {@code visit.dept_id} 改变。
 * 因此这里只做一件事——把访问权的归属从 A 科室搬到 B 科室，并把搬家过程写进
 * {@code visit_transfer}（append-only）与审计。
 *
 * <p>三个必须同时成立的前提：
 * <ol>
 *   <li>就诊在接诊中且确有在院登记（出院/门诊不能转科）；</li>
 *   <li>目标科室是启用中的住院科室，患者落到目标科室的病区床位；</li>
 *   <li>原主诊科室发起的进行中会诊随转科一并撤回（否则受邀科室仍持只读权）。</li>
 * </ol>
 *
 * <p>权限：写走 {@link VisitAccessGuard#require} 的同一规则（只认当前主诊科室），
 * 越权一律 404，避免用 visitId 探测其他科室的住院患者。
 */
@Service
public class VisitTransferService {

    private static final int DEFAULT_LIMIT = 50;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ClinicalRepository repo;
    private final VisitAccessGuard guard;

    public VisitTransferService(ClinicalRepository repo, VisitAccessGuard guard) {
        this.repo = repo;
        this.guard = guard;
    }

    /**
     * 转科：锁就诊 → 校验在院与目标科室 → 换床 → 改主诊科室 → 撤回会诊 → 落转科记录。
     *
     * <p>锁顺序固定为 admission → visit（与出院一致），保证同一就诊的并发转科只成功一次；
     * 所有 CAS 失败都回滚整个事务，不会出现"床位占了、科室没改"的中间态。
     */
    @Transactional
    public VisitTransfer transfer(DataScope scope, UUID visitId, TransferVisitReq req, String actor) {
        // 锁顺序必须是 admission → visit：住院出院（InpatientService.discharge）就是先锁
        // admission 再改 visit，反过来会在"转科 + 出院"并发时形成 ABBA 死锁。
        // 先做一次无锁读，只为了尽早把越权请求挡在加锁之前。
        Visit snapshot = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!scope.canSeeDepartment(snapshot.deptId())) {
            throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
        }
        Admission admission = repo.lockActiveAdmissionByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "患者不在院，不能转科"));
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!scope.canSeeDepartment(visit.deptId())) {
            // 加锁期间可能已被其他账号转走：以加锁后的科室为准重新判定
            throw new BusinessException(ErrorCode.VISIT_NOT_FOUND);
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            // Controller 的 @NotBlank 已挡一次；服务层再兜一次，避免其他调用方绕过校验
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "转科原因必填");
        }
        if (req.toDeptId().equals(visit.deptId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标科室与当前主诊科室相同");
        }
        if (!Visit.IN_TREATMENT.equals(visit.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有接诊中的就诊可以转科");
        }
        Dept target = repo.findDept(req.toDeptId())
                .filter(Dept::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "目标科室不存在或已停用"));
        if (!inpatient(target)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标科室不是住院科室");
        }

        BedTarget targetBed = lockTargetBed(req.bedId(), admission, target);

        if (targetBed != null) {
            moveBed(admission, targetBed, target, actor);
        }
        if (!repo.updateVisitDepartment(visitId, target.id(), visit.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "就诊信息已变化，请刷新后重试");
        }

        String cancelReason = "转科至" + target.name() + "：" + reason;
        int cancelled = repo.cancelActiveConsultationsForVisit(visitId, actor, cancelReason);
        VisitTransfer transfer = new VisitTransfer(UUID.randomUUID(), visitId, visit.deptId(),
                target.id(), reason, actor, Instant.now(), 0L);
        repo.insertVisitTransfer(transfer);
        audit(actor, transfer, target, admission,
                targetBed == null ? null : targetBed.bed(), cancelled);
        return transfer;
    }

    /** 转科记录：本院该就诊的历次转科（新主诊科室或会诊期只读科室可见）。 */
    public List<VisitTransfer> list(DataScope scope, UUID visitId) {
        guard.requireRead(scope, visitId);
        return repo.listTransfersByVisit(visitId, DEFAULT_LIMIT);
    }

    /**
     * 锁目标床位并校验归属。
     *
     * <p>已分床的在院患者必须指定目标床位——患者不能"挂在原病区、归属新科室"，
     * 那样原床位被永久占用，新病区又无法按床收治。
     */
    private BedTarget lockTargetBed(UUID bedId, Admission admission, Dept target) {
        if (bedId == null) {
            if (admission.bedId() != null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "在院患者转科需同时指定目标科室的床位");
            }
            return null;
        }
        Bed bed = repo.lockBed(bedId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "床位不存在"));
        if (!bed.free()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "床位 " + bed.bedNo() + " 当前状态为 " + bed.status() + "，不可占用");
        }
        Ward ward = repo.findWard(bed.wardId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "病区不存在"));
        if (!ward.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标病区已停用");
        }
        if (!ward.deptId().equals(target.id())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标床位不属于目标科室的病区");
        }
        return new BedTarget(bed, ward);
    }

    /** 占新床 → 改登记病区/床位 → 释放原床；同一事务内完成，任一 CAS 失败全部回滚。 */
    private void moveBed(Admission admission, BedTarget targetBed, Dept target, String actor) {
        if (!repo.occupyBed(targetBed.bed().id(), targetBed.bed().version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "床位已被占用，请刷新后重试");
        }
        if (!repo.updateAdmissionWardAndBed(admission.id(), targetBed.ward().id(),
                targetBed.bed().id(), admission.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "住院登记已被他人修改，请刷新后重试");
        }
        if (admission.bedId() != null) {
            Bed from = repo.lockBed(admission.bedId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "原床位不存在"));
            if (!repo.freeBed(from.id(), from.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "原床位状态异常，请刷新后重试");
            }
        }
        repo.logBedTransfer(admission.id(), admission.bedId(), targetBed.bed().id(),
                "转科至" + target.name(), actor);
    }

    private record BedTarget(Bed bed, Ward ward) {
    }

    private boolean inpatient(Dept dept) {
        return "INPATIENT".equals(dept.type()) || "BOTH".equals(dept.type());
    }

    private void audit(String actor, VisitTransfer t, Dept target, Admission admission,
                       Bed bed, int cancelled) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("visitId", t.visitId().toString());
        payload.put("fromDeptId", t.fromDeptId().toString());
        payload.put("toDeptId", t.toDeptId().toString());
        payload.put("toDeptName", target.name());
        payload.put("reason", t.reason());
        payload.put("admissionId", admission.id().toString());
        payload.put("bedId", bed == null ? null : bed.id().toString());
        payload.put("cancelledConsultations", cancelled);
        String json;
        try {
            json = JSON.writeValueAsString(payload);
        } catch (RuntimeException e) {
            json = "{\"visitId\":\"" + t.visitId() + "\"}";
        }
        repo.logChange(actor, "VISIT_TRANSFER", "VISIT", t.visitId().toString(), json);
    }
}
