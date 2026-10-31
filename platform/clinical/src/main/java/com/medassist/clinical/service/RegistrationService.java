package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Slot;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.CreateSlotReq;
import com.medassist.clinical.web.ClinicalDtos.RegisterReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 挂号与就诊状态机。
 *
 * <p>并发与一致性设计：
 * <ol>
 *   <li>号源扣减先 {@code SELECT ... FOR UPDATE} 再加 CAS（{@code booked < total}），
 *       即便多实例同时抢最后一个号，也只会成功一个；</li>
 *   <li>同一患者同日同科室重复挂号由"应用层查询 + 数据库部分唯一索引"双保险拦截；</li>
 *   <li>就诊状态迁移全部带期望状态 + 版本号，杜绝"取消后又被接诊"的并发窗口；</li>
 *   <li>挂号即生成收费单（诊查费），收费单在此时落库，后续医嘱只做增量，避免结算时才发现无单。</li>
 * </ol>
 */
@Service
public class RegistrationService {

    /** 诊查费项目编码（主数据缺失时挂号直接失败，不能"免费挂号"）。 */
    static final String ITEM_GENERAL = "REG-GENERAL";
    static final String ITEM_EMERGENCY = "REG-EMERGENCY";

    private final ClinicalRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public RegistrationService(ClinicalRepository repo, PatientClient patientClient,
                               ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 号源

    @Transactional
    public Slot createSlot(CreateSlotReq req, String actor) {
        Dept dept = requireDept(req.deptId());
        String period = req.period() == null ? "" : req.period().trim().toUpperCase();
        if (!List.of("AM", "PM", "NIGHT").contains(period)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "时段只能是 AM / PM / NIGHT");
        }
        if (req.workDate().isBefore(LocalDate.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能为过去的日期建号源");
        }
        Slot slot = new Slot(UUID.randomUUID(), dept.id(), req.doctorId().trim(), req.workDate(),
                period, req.total(), 0, 0L);
        try {
            repo.insertSlot(slot);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "该科室/医生/日期/时段已存在号源");
        }
        audit(actor, "SLOT_CREATE", "SLOT", slot.id().toString(),
                Map.of("deptCode", dept.deptCode(), "doctorId", slot.doctorId(),
                        "workDate", slot.workDate().toString(), "period", period, "total", req.total()));
        return slot;
    }

    // ---------------------------------------------------------------- 挂号

    @Transactional
    public Visit register(RegisterReq req, String actor) {
        Dept dept = requireDept(req.deptId());
        String visitType = req.visitType() == null || req.visitType().isBlank()
                ? "OUTPATIENT" : req.visitType().trim().toUpperCase();
        if (!List.of("OUTPATIENT", "EMERGENCY").contains(visitType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "就诊类型只能是 OUTPATIENT / EMERGENCY");
        }

        // 1) 患者必须存在且为有效档案（EMPI 不可用则挂号失败，绝不产生无主就诊）
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());

        // 2) 号源：锁行 → 校验 → CAS 占号
        LocalDate visitDate = req.visitDate() == null ? LocalDate.now() : req.visitDate();
        Slot slot = null;
        if (req.slotId() != null) {
            slot = repo.lockSlot(req.slotId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.REGISTRATION_NO_SLOT, "号源不存在或已撤销"));
            if (!slot.deptId().equals(dept.id())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "号源与挂号科室不一致");
            }
            if (!slot.doctorId().equals(req.doctorId().trim())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "号源与医生不一致（代诊请另建号源）");
            }
            if (!slot.workDate().equals(visitDate)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "号源日期与就诊日期不一致");
            }
            if (slot.full()) {
                throw new BusinessException(ErrorCode.REGISTRATION_NO_SLOT);
            }
        }

        // 3) 重复挂号：同日同科室只允许一个在途就诊（数据库部分唯一索引兜底）
        repo.findActiveVisit(patient.id(), dept.id(), visitDate).ifPresent(v -> {
            throw new BusinessException(ErrorCode.REGISTRATION_DUPLICATED,
                    "该患者当日该科室已有在途就诊：" + v.visitNo());
        });

        if (slot != null && !repo.occupySlot(slot.id(), slot.version())) {
            throw new BusinessException(ErrorCode.REGISTRATION_NO_SLOT);
        }

        // 4) 建就诊
        Visit visit = new Visit(UUID.randomUUID(), repo.nextVisitNo(), patient.id(), patient.patientNo(),
                dept.id(), req.doctorId().trim(), slot == null ? null : slot.id(), visitType,
                Visit.REGISTERED, visitDate, 0L);
        try {
            repo.insertVisit(visit);
        } catch (DataIntegrityViolationException e) {
            // 并发下"查询未命中但插入撞唯一索引"：给出与预检一致的语义，而不是泛化 409
            throw new BusinessException(ErrorCode.REGISTRATION_DUPLICATED,
                    "该患者当日该科室已有在途门诊就诊，请勿重复挂号");
        }
        // 5) 挂号即生成收费单（诊查费），后续医嘱只做增量
        ChargeItemDef fee = repo.findChargeItem("EMERGENCY".equals(visitType) ? ITEM_EMERGENCY : ITEM_GENERAL)
                .filter(ChargeItemDef::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "诊查费项目未配置或已停用，请联系物价管理员"));
        Charge charge = new Charge(UUID.randomUUID(), repo.nextChargeNo(), visit.id(),
                fee.price(), Charge.UNPAID, null, null, 0L);
        repo.insertCharge(charge);
        repo.insertChargeItemWithoutOrder(UUID.randomUUID(), charge.id(), fee.itemCode(),
                fee.name(), fee.category(), fee.price());

        audit(actor, "REGISTER", "VISIT", visit.id().toString(), Map.of(
                "visitNo", visit.visitNo(), "patientNo", patient.patientNo(),
                "deptCode", dept.deptCode(), "doctorId", visit.doctorId(),
                "visitType", visitType, "slotId", String.valueOf(visit.slotId()),
                "chargeNo", charge.chargeNo(), "fee", fee.price()));
        return visit;
    }

    /** 开始接诊：REGISTERED → IN_TREATMENT。 */
    @Transactional
    public Visit start(UUID visitId, String actor) {
        Visit v = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!Visit.REGISTERED.equals(v.status())) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID,
                    "只有已挂号（REGISTERED）的就诊可以开始接诊，当前：" + v.status());
        }
        if (!repo.updateVisitStatus(v.id(), Visit.REGISTERED, Visit.IN_TREATMENT, v.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        audit(actor, "VISIT_START", "VISIT", visitId.toString(), Map.of("visitNo", v.visitNo()));
        return repo.findVisit(visitId).orElseThrow();
    }

    /** 结束就诊：IN_TREATMENT → FINISHED。 */
    @Transactional
    public Visit finish(UUID visitId, String actor) {
        Visit v = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!Visit.IN_TREATMENT.equals(v.status())) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID,
                    "只有接诊中（IN_TREATMENT）的就诊可以结束，当前：" + v.status());
        }
        if (!repo.updateVisitStatus(v.id(), Visit.IN_TREATMENT, Visit.FINISHED, v.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        audit(actor, "VISIT_FINISH", "VISIT", visitId.toString(), Map.of("visitNo", v.visitNo()));
        return repo.findVisit(visitId).orElseThrow();
    }

    /**
     * 取消挂号：REGISTERED → CANCELLED，并释放号源。
     *
     * <p>已开医嘱或已收费一律拒绝：退费是独立流程（需要财务凭证），不能靠"取消挂号"绕过。
     */
    @Transactional
    public Visit cancel(UUID visitId, String actor) {
        Visit v = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!Visit.REGISTERED.equals(v.status())) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID,
                    "只有未接诊（REGISTERED）的就诊可以取消挂号，当前：" + v.status());
        }
        if (!repo.listOrders(visitId, null).isEmpty()) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "该就诊已开立医嘱，不能直接取消挂号");
        }
        repo.findChargeByVisit(visitId).ifPresent(c -> {
            if (Charge.PAID.equals(c.status())) {
                throw new BusinessException(ErrorCode.CHARGE_STATUS_INVALID, "该就诊已收费，请走退费流程");
            }
        });
        if (!repo.updateVisitStatus(v.id(), Visit.REGISTERED, Visit.CANCELLED, v.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        if (v.slotId() != null) {
            repo.releaseSlot(v.slotId());
        }
        audit(actor, "VISIT_CANCEL", "VISIT", visitId.toString(), Map.of(
                "visitNo", v.visitNo(), "slotReleased", v.slotId() != null));
        return repo.findVisit(visitId).orElseThrow();
    }

    public Visit detail(UUID visitId) {
        return repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
    }

    // ---------------------------------------------------------------- 内部

    private Dept requireDept(UUID deptId) {
        Dept dept = repo.findDept(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "科室不存在"));
        if (!dept.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "科室已停用，不能挂号");
        }
        return dept;
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
