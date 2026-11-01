package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.ChargeItemDef;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.time.BusinessTime;
import com.medassist.clinical.web.ClinicalDtos.OrderLine;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 医嘱开立与撤销。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>只有"接诊中（IN_TREATMENT）"的就诊能开医嘱——挂号未接诊就开药是典型越权路径；</li>
 *   <li>金额由服务端按收费项目主数据计算，请求体不接受价格（防篡改）；</li>
 *   <li>诊查费/挂号费类项目不能作为医嘱重复开立（防止一条就诊收两次诊查费）。</li>
 * </ol>
 */
@Service
public class OrderService {

    private final ClinicalRepository repo;
    private final OrderTransferService transfer;
    private final ObjectMapper objectMapper;

    public OrderService(ClinicalRepository repo, OrderTransferService transfer,
                        ObjectMapper objectMapper) {
        this.repo = repo;
        this.transfer = transfer;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<VisitOrder> place(UUID visitId, List<OrderLine> lines, String actor) {
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!Visit.IN_TREATMENT.equals(visit.status())) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID,
                    "只有接诊中的就诊可以开立医嘱，当前状态：" + visit.status() + "（请先开始接诊）");
        }

        List<VisitOrder> created = new ArrayList<>(lines.size());
        LocalDate today = BusinessTime.today();
        for (OrderLine line : lines) {
            ChargeItemDef item = repo.findChargeItem(line.itemCode().trim())
                    .filter(ChargeItemDef::enabled)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND,
                            "医嘱项目不存在或已停用：" + line.itemCode()));
            String orderType = mapOrderType(item);
            String mode = normalizeMode(line.orderMode(), visit);
            BigDecimal amount = item.price()
                    .multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            VisitOrder order = new VisitOrder(UUID.randomUUID(), repo.nextOrderNo(), visitId,
                    orderType, item.itemCode(), item.name(),
                    blankToNull(line.dose()), blankToNull(line.frequency()),
                    line.quantity(), item.unit(), item.price(), amount,
                    VisitOrder.PLACED, actor, 0L, mode, today, null,
                    VisitOrder.TRANSFER_NONE, null, null, null, null);
            repo.insertOrder(order);
            created.add(order);
        }

        // 药品医嘱：同一事务内标记 PENDING，提交后转送药房生成待审处方（失败不回滚医嘱）
        List<VisitOrder> result = transfer.schedule(visit, created, actor);

        audit(actor, "ORDER_PLACE", "VISIT", visitId.toString(), Map.of(
                "visitNo", visit.visitNo(), "count", result.size(),
                "orderNos", result.stream().map(VisitOrder::orderNo).toList(),
                "longCount", result.stream().filter(VisitOrder::longTerm).count(),
                "drugCount", result.stream().filter(VisitOrder::drug).count()));
        return result;
    }

    /** 停嘱：仅住院长嘱可停；停嘱当日仍计费，次日起不再计费。 */
    @Transactional
    public VisitOrder stop(UUID visitId, UUID orderId, String reason, String actor) {
        // 锁 visit：与 settle/dailyBill 串行，避免"停嘱的同时正在按日计费"读到中间态
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!visit.active()) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "就诊已结束，不能停嘱");
        }
        String why = (reason == null || reason.isBlank()) ? "医师停嘱" : reason.trim();
        if (!repo.stopLongOrder(orderId, visitId, BusinessTime.today(), actor, why)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID,
                    "只有未停止的住院长嘱可以停嘱（临时医嘱请走撤销）");
        }
        audit(actor, "ORDER_STOP", "VISIT", visitId.toString(),
                Map.of("visitNo", visit.visitNo(), "orderId", orderId.toString(), "reason", why));
        return repo.listOrders(visitId, null).stream()
                .filter(o -> o.id().equals(orderId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
    }

    /** 医嘱模式：默认临时；长嘱仅限住院。 */
    private String normalizeMode(String raw, Visit visit) {
        String mode = (raw == null || raw.isBlank()) ? VisitOrder.TEMP : raw.trim().toUpperCase();
        if (!VisitOrder.TEMP.equals(mode) && !VisitOrder.LONG.equals(mode)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "医嘱模式只能是 TEMP / LONG");
        }
        if (VisitOrder.LONG.equals(mode) && !"INPATIENT".equals(visit.visitType())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有住院就诊可以开立长嘱");
        }
        return mode;
    }

    @Transactional
    public VisitOrder cancel(UUID visitId, UUID orderId, String actor) {
        // 锁 visit：与 settle 串行，避免"结算读取 pending 的同时医嘱被撤销却仍被收费"
        Visit visit = repo.lockVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!visit.active()) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "就诊已结束或已取消，不能撤销医嘱");
        }
        if (!repo.cancelOrder(orderId, visitId)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID,
                    "只有未计费（PLACED）的医嘱可以撤销");
        }
        audit(actor, "ORDER_CANCEL", "VISIT", visitId.toString(),
                Map.of("visitNo", visit.visitNo(), "orderId", orderId.toString()));
        return repo.listOrders(visitId, null).stream()
                .filter(o -> o.id().equals(orderId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
    }

    public List<VisitOrder> list(UUID visitId) {
        repo.findVisit(visitId).orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        return repo.listOrders(visitId, null);
    }

    /** 收费项目类别 → 医嘱类型；诊查费/挂号费不允许作为医嘱。 */
    private String mapOrderType(ChargeItemDef item) {
        return switch (item.category()) {
            case "DRUG" -> "DRUG";
            case "TREATMENT" -> "TREATMENT";
            case "EXAM" -> "EXAM";
            case "MATERIAL" -> "MATERIAL";
            default -> throw new BusinessException(ErrorCode.ORDER_ITEM_NOT_FOUND,
                    "该项目（" + item.name() + "）属于" + item.category() + "，不能作为医嘱开立");
        };
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
