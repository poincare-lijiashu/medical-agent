package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.time.BusinessTime;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionReq;
import com.medassist.clinical.web.ClinicalDtos.NursingExecutionView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 护理执行记录（长嘱逐日执行）。
 *
 * <p>为什么必须做：按日计费如果只看医嘱有效期，就会出现"患者拒绝/未执行但照常收费"，
 * 这是医保飞检与物价检查的典型扣分项。执行记录与计费在同一条链路上：
 * 护理站记录 REFUSED / OMITTED 后，当日的按日计费自动跳过该医嘱。
 *
 * <p>追加式：同一医嘱同一天只允许一条记录（数据库唯一键），更正走后续的更正流程，
 * 不允许覆盖历史。
 */
@Service
public class NursingService {

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public NursingService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public NursingExecutionView record(UUID admissionId, NursingExecutionReq req, String actor) {
        Admission admission = repo.lockAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        if (!admission.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该住院登记已出院或已取消，不能记录护理执行");
        }
        LocalDate execDate = req.execDate() == null ? BusinessTime.today() : req.execDate();
        if (execDate.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "不能为未来日期（" + execDate + "）记录护理执行");
        }
        VisitOrder order = repo.findOrder(admission.visitId(), req.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND,
                        "医嘱不存在或不属于该住院就诊"));
        if (!order.longTerm()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有长期医嘱需要逐日记录执行");
        }
        if (!order.billableOn(execDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "该长嘱在 " + execDate + " 不在有效期内（可能尚未开始、已停嘱或已撤销）");
        }
        String status = normalizeStatus(req.status());

        // 已按日计费后再补记"未执行/拒绝"会让当日多收，且当前没有红冲流程：
        // 直接拒绝，提示先走冲正（避免账实不符被医保飞检扣分）。
        if (!NursingExecution.EXECUTED.equals(status) && repo.existsDailyCharge(order.id(), execDate)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该医嘱 " + execDate + " 已计费，不能再记为 " + status + "；请先冲正费用再更正执行记录");
        }

        // 先查后插：PostgreSQL 语句失败后事务会进入 aborted 状态，
        // 若在 catch 里再查库会二次失败（实测 500），因此重复判定必须放在写入之前。
        repo.findNursingExecution(order.id(), execDate).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该医嘱 " + execDate + " 已有执行记录（" + existing.status() + "），不得重复记录");
        });

        NursingExecution execution = new NursingExecution(UUID.randomUUID(), admission.id(),
                admission.visitId(), order.id(), execDate, status, actor,
                blankToNull(req.note()), Instant.now());
        try {
            repo.insertNursingExecution(execution);
        } catch (DataIntegrityViolationException e) {
            // 并发写入时唯一键兜底；此处不再查库（事务已 aborted）
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该医嘱 " + execDate + " 已有执行记录，不得重复记录");
        }

        audit(actor, "NURSING_EXECUTE", "ADMISSION", admission.id().toString(), Map.of(
                "admissionNo", admission.admissionNo(), "orderNo", order.orderNo(),
                "itemName", order.itemName(), "execDate", execDate.toString(), "status", status));
        return toView(execution, order);
    }

    /** 执行记录列表；date 为空返回全部。 */
    public List<NursingExecutionView> list(UUID admissionId, LocalDate date) {
        Admission admission = repo.findAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        Map<UUID, VisitOrder> orders = repo.listOrders(admission.visitId(), null).stream()
                .collect(Collectors.toMap(VisitOrder::id, Function.identity(), (a, b) -> a));
        return repo.listNursingExecutions(admission.visitId(), date).stream()
                .map(e -> toView(e, orders.get(e.orderId())))
                .toList();
    }

    // ---------------------------------------------------------------- 内部

    private static String normalizeStatus(String raw) {
        String status = raw.trim().toUpperCase();
        if (!NursingExecution.EXECUTED.equals(status) && !NursingExecution.REFUSED.equals(status)
                && !NursingExecution.OMITTED.equals(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "执行状态只能是 EXECUTED（已执行）/ REFUSED（患者拒绝）/ OMITTED（未执行）");
        }
        return status;
    }

    private NursingExecutionView toView(NursingExecution e, VisitOrder order) {
        return new NursingExecutionView(e.id(), e.admissionId(), e.orderId(),
                order == null ? "" : order.orderNo(), order == null ? "" : order.itemName(),
                e.execDate(), e.status(), e.executor(), e.note(), e.createdAt());
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
