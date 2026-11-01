package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.client.TechOrderClient;
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
 * 医嘱 → 下游（药房处方 / 医技申请）转送编排（HIS 主链路）。
 *
 * <p>转送规则（按医嘱类型）：
 * <ul>
 *   <li>{@code DRUG} → 药房生成待审处方；</li>
 *   <li>{@code EXAM} → 医技生成检查/检验申请；</li>
 *   <li>其他类型（治疗、耗材）暂不转送，{@code transfer_status} 保持 NONE。</li>
 * </ul>
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>转送在医嘱本地事务<b>提交后</b>执行——下游抖动不能把"医师已开的医嘱"回滚掉；</li>
 *   <li>失败只把 {@code transfer_status} 置 FAILED，医嘱依然有效、照常计费，
 *       由 {@code transfer-retry} 重试（下游按 orderId 幂等）；</li>
 *   <li>已撤销/已计费的医嘱不允许再补转送——撤销后补开单据会让患者拿到已停的服务。</li>
 * </ol>
 */
@Service
public class OrderTransferService {

    private static final Logger log = LoggerFactory.getLogger(OrderTransferService.class);

    /** 下游单据类型：药房处方。 */
    public static final String EXTERNAL_PRESCRIPTION = "PRESCRIPTION";
    /** 下游单据类型：医技申请。 */
    public static final String EXTERNAL_EXAM_APPLICATION = "EXAM_APPLICATION";

    private static final int MAX_ERROR_LENGTH = 200;

    private final ClinicalRepository repo;
    private final PharmacyOrderClient pharmacyClient;
    private final TechOrderClient techClient;
    private final ObjectMapper objectMapper;

    public OrderTransferService(ClinicalRepository repo, PharmacyOrderClient pharmacyClient,
                                TechOrderClient techClient, ObjectMapper objectMapper) {
        this.repo = repo;
        this.pharmacyClient = pharmacyClient;
        this.techClient = techClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 在开嘱事务内登记转送：需要转送的医嘱置 PENDING，事务提交后调用下游。
     *
     * @return 需转送医嘱带 {@code transferStatus=PENDING} 的副本（其他医嘱原样返回）
     */
    public List<VisitOrder> schedule(Visit visit, List<VisitOrder> orders, String actor) {
        List<VisitOrder> transferable = orders.stream().filter(OrderTransferService::transferable)
                .toList();
        if (transferable.isEmpty()) {
            return orders;
        }
        for (VisitOrder order : transferable) {
            repo.updateOrderTransfer(order.id(), VisitOrder.TRANSFER_PENDING, null, null, null, null);
        }
        List<VisitOrder> marked = orders.stream()
                .map(o -> transferable(o) ? o.withTransferStatus(VisitOrder.TRANSFER_PENDING) : o)
                .toList();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 无事务（单测/脚本直调）：立即执行，行为与提交后一致
            transferable.forEach(order -> transferNow(order, visit, actor));
            return marked;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                transferable.forEach(order -> transferNow(order, visit, actor));
            }
        });
        return marked;
    }

    /**
     * 重试转送（医护端与运维共用）。
     *
     * <p>已转送的直接返回（幂等）；下游也按 orderId 幂等，即使本地状态因崩溃停在
     * PENDING，重试也不会产生第二张处方/申请。
     */
    public VisitOrder retry(UUID visitId, UUID orderId, String actor) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        VisitOrder order = repo.findOrder(visitId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该医嘱"));
        if (!transferable(order)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有药品/检查类医嘱需要转送下游");
        }
        if (VisitOrder.CANCELLED.equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "已撤销的医嘱不能重试转送");
        }
        if (VisitOrder.CHARGED.equals(order.status())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID,
                    "已计费医嘱不能重试转送（单据缺失请由下游手工补开）");
        }
        if (order.transferred()) {
            return order;
        }
        repo.updateOrderTransfer(orderId, VisitOrder.TRANSFER_PENDING, null, null, null, null);
        transferNow(order.withTransferStatus(VisitOrder.TRANSFER_PENDING), visit, actor);
        return repo.findOrder(visitId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该医嘱"));
    }

    /**
     * 医嘱撤销/停嘱 → 下游单据作废（在撤销事务内同步执行）。
     *
     * <p>与转送相反，这里**失败必须抛出**：宁可撤销失败，也不能出现
     * "医嘱已撤销、下游还能按旧单据执行"的安全缺口。下游作废是幂等的，
     * 重试一定收敛；已发药/已采集的单据会被下游 409 拒绝（只能人工处理）。
     */
    public void cancelDownstream(VisitOrder order, String reason, String actor) {
        if (!order.transferred()) {
            return;
        }
        if (order.drug()) {
            PharmacyOrderClient.CancelRef ref = pharmacyClient.cancelByOrder(order.id(), reason, actor);
            audit(actor, ref.cancelled() ? "ORDER_TRANSFER_CANCEL" : "ORDER_TRANSFER_CANCEL_SKIPPED",
                    order.id().toString(), Map.of(
                            "orderNo", order.orderNo(),
                            "prescriptionId", ref.prescriptionId() == null
                                    ? "" : ref.prescriptionId().toString(),
                            "reason", reason));
            return;
        }
        if ("EXAM".equals(order.orderType())) {
            TechOrderClient.CancelRef ref = techClient.cancelByOrder(order.id(), reason, actor);
            audit(actor, ref.cancelled() ? "ORDER_TRANSFER_CANCEL" : "ORDER_TRANSFER_CANCEL_SKIPPED",
                    order.id().toString(), Map.of(
                            "orderNo", order.orderNo(),
                            "applicationId", ref.applicationId() == null
                                    ? "" : ref.applicationId().toString(),
                            "reason", reason));
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 需要转送下游的医嘱类型（药品→药房，检查→医技）。 */
    private static boolean transferable(VisitOrder order) {
        return order.drug() || "EXAM".equals(order.orderType());
    }

    private void transferNow(VisitOrder order, Visit visit, String actor) {
        if (order.drug()) {
            transferToPharmacy(order, visit, actor);
        } else if ("EXAM".equals(order.orderType())) {
            transferToTech(order, visit, actor);
        }
    }

    private void transferToPharmacy(VisitOrder order, Visit visit, String actor) {
        try {
            PharmacyOrderClient.RxRef ref = pharmacyClient.createFromOrder(order.id(),
                    visit.patientId(), visit.id(), visit.visitNo(), visit.deptId(),
                    order.orderedBy(),
                    List.of(new PharmacyOrderClient.Line(order.itemCode(), order.quantity(),
                            order.dose(), order.frequency(), null, null, null, null)));
            repo.updateOrderTransfer(order.id(), VisitOrder.TRANSFERRED,
                    EXTERNAL_PRESCRIPTION, ref.prescriptionId(), ref.prescriptionNo(), null);
            audit(actor, "ORDER_TRANSFER", order.id().toString(), Map.of(
                    "orderNo", order.orderNo(), "externalType", EXTERNAL_PRESCRIPTION,
                    "prescriptionId", ref.prescriptionId().toString(),
                    "prescriptionNo", ref.prescriptionNo() == null ? "" : ref.prescriptionNo(),
                    "duplicate", ref.duplicate(), "totalAmount", ref.totalAmount()));
        } catch (RuntimeException e) {
            markFailed(order, EXTERNAL_PRESCRIPTION, e, actor);
        }
    }

    private void transferToTech(VisitOrder order, Visit visit, String actor) {
        try {
            TechOrderClient.AppRef ref = techClient.createFromOrder(order.id(), visit.patientId(),
                    visit.id(), visit.visitNo(), visit.deptId(), order.orderedBy(),
                    order.itemCode(), null, "ROUTINE", null);
            repo.updateOrderTransfer(order.id(), VisitOrder.TRANSFERRED,
                    EXTERNAL_EXAM_APPLICATION, ref.applicationId(), ref.applicationNo(), null);
            audit(actor, "ORDER_TRANSFER", order.id().toString(), Map.of(
                    "orderNo", order.orderNo(), "externalType", EXTERNAL_EXAM_APPLICATION,
                    "applicationId", ref.applicationId().toString(),
                    "applicationNo", ref.applicationNo() == null ? "" : ref.applicationNo(),
                    "category", ref.category() == null ? "" : ref.category(),
                    "duplicate", ref.duplicate()));
        } catch (RuntimeException e) {
            markFailed(order, EXTERNAL_EXAM_APPLICATION, e, actor);
        }
    }

    private void markFailed(VisitOrder order, String externalType, RuntimeException e, String actor) {
        String reason = truncate(e.getMessage() == null ? e.getClass().getSimpleName()
                : e.getMessage());
        log.warn("clinical.order_transfer_failed orderId={} type={} err={}",
                order.id(), externalType, reason);
        repo.updateOrderTransfer(order.id(), VisitOrder.TRANSFER_FAILED,
                externalType, null, null, reason);
        audit(actor, "ORDER_TRANSFER_FAILED", order.id().toString(), Map.of(
                "orderNo", order.orderNo(), "externalType", externalType, "reason", reason));
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
