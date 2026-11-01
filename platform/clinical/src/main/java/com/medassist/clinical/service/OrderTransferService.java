package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 医嘱 → 药房转送编排（HIS 主链路）。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>转送在医嘱本地事务<b>提交后</b>执行——药房抖动不能把"医师已开的医嘱"回滚掉；</li>
 *   <li>失败只把 {@code transfer_status} 置 FAILED，医嘱依然有效、照常计费，
 *       由 {@code transfer-retry} 重试（药房侧按 orderId 幂等）；</li>
 *   <li>已撤销/已计费的医嘱不允许再补转送——撤销后补开处方会让患者拿到已停的药。</li>
 * </ol>
 */
@Service
public class OrderTransferService {

    private static final Logger log = LoggerFactory.getLogger(OrderTransferService.class);

    /** 下游单据类型：药房处方。 */
    public static final String EXTERNAL_PRESCRIPTION = "PRESCRIPTION";

    private static final int MAX_ERROR_LENGTH = 200;

    private final ClinicalRepository repo;
    private final PharmacyOrderClient client;
    private final ObjectMapper objectMapper;

    public OrderTransferService(ClinicalRepository repo, PharmacyOrderClient client,
                                ObjectMapper objectMapper) {
        this.repo = repo;
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * 在开嘱事务内登记转送：药品医嘱置 PENDING，事务提交后调用药房。
     *
     * @return 药品医嘱带 {@code transferStatus=PENDING} 的副本（非药品医嘱原样返回）
     */
    public List<VisitOrder> schedule(Visit visit, List<VisitOrder> orders, String actor) {
        List<VisitOrder> drugOrders = orders.stream().filter(VisitOrder::drug).toList();
        if (drugOrders.isEmpty()) {
            return orders;
        }
        for (VisitOrder order : drugOrders) {
            repo.updateOrderTransfer(order.id(), VisitOrder.TRANSFER_PENDING, null, null, null, null);
        }
        List<VisitOrder> marked = orders.stream()
                .map(o -> o.drug() ? o.withTransferStatus(VisitOrder.TRANSFER_PENDING) : o)
                .toList();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务（单测/脚本直调）：立即执行，行为与提交后一致
            drugOrders.forEach(order -> transferNow(order, visit, actor));
            return marked;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                drugOrders.forEach(order -> transferNow(order, visit, actor));
            }
        });
        return marked;
    }

    /**
     * 重试转送（医护端与运维共用）。
     *
     * <p>已转送的直接返回（幂等）；药房侧也按 orderId 幂等，即使本地状态因崩溃停在
     * PENDING，重试也不会产生第二张处方。
     */
    public VisitOrder retry(UUID visitId, UUID orderId, String actor) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        VisitOrder order = repo.findOrder(visitId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该医嘱"));
        if (!order.drug()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有药品医嘱需要转送药房");
        }
        if (VisitOrder.CANCELLED.equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "已撤销的医嘱不能重试转送");
        }
        if (VisitOrder.CHARGED.equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID,
                    "已计费医嘱不能重试转送（处方缺失请由药房手工补开）");
        }
        if (order.transferred()) {
            return order;
        }
        repo.updateOrderTransfer(orderId, VisitOrder.TRANSFER_PENDING, null, null, null, null);
        transferNow(order.withTransferStatus(VisitOrder.TRANSFER_PENDING), visit, actor);
        return repo.findOrder(visitId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该医嘱"));
    }

    // ---------------------------------------------------------------- 内部

    private void transferNow(VisitOrder order, Visit visit, String actor) {
        try {
            PharmacyOrderClient.RxRef ref = client.createFromOrder(order.id(), visit.patientId(),
                    visit.id(), visit.visitNo(), visit.deptId(), order.orderedBy(),
                    List.of(new PharmacyOrderClient.Line(order.itemCode(), order.quantity(),
                            order.dose(), order.frequency(), null, null, null, null)));
            repo.updateOrderTransfer(order.id(), VisitOrder.TRANSFERRED,
                    EXTERNAL_PRESCRIPTION, ref.prescriptionId(), ref.prescriptionNo(), null);
            audit(actor, "ORDER_TRANSFER", order.id().toString(), Map.of(
                    "orderNo", order.orderNo(), "prescriptionId", ref.prescriptionId().toString(),
                    "prescriptionNo", ref.prescriptionNo() == null ? "" : ref.prescriptionNo(),
                    "duplicate", ref.duplicate(), "totalAmount", ref.totalAmount()));
        } catch (RuntimeException e) {
            String reason = truncate(e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
            log.warn("clinical.order_transfer_failed orderId={} err={}", order.id(), reason);
            repo.updateOrderTransfer(order.id(), VisitOrder.TRANSFER_FAILED,
                    EXTERNAL_PRESCRIPTION, null, null, reason);
            audit(actor, "ORDER_TRANSFER_FAILED", order.id().toString(), Map.of(
                    "orderNo", order.orderNo(), "reason", reason));
        }
    }

    private static String truncate(String message) {
        return message.length() <= MAX_ERROR_LENGTH ? message
                : message.substring(0, MAX_ERROR_LENGTH);
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        String who = actor == null || actor.isBlank() ? "clinical" : actor;
        try {
            repo.logChange(who, action, "VISIT_ORDER", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(who, action, "VISIT_ORDER", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
