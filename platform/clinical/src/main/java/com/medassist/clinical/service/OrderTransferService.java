package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.client.TechOrderClient;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
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
        if (order.drug() && order.longTerm()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "长期药品医嘱不自动转药房（一次转送只能发一天量），请按日请领或改开临时医嘱");
        }
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
     * 医嘱撤销/停嘱 → 下游单据作废（严格模式：失败抛出）。
     *
     * <p>下游作废是幂等的：未建单的 orderId 返回 {@code cancelled=false}，无副作用。
     * 因此 PENDING / FAILED 也必须调用——下游可能已经建单，只是本地没回写成功
     * （第六轮 MF-02：本地记 FAILED 后撤销静默跳过，处方仍可发药）。
     *
     * <p>严格模式只给补偿 worker 与"必须确保作废"的调用方使用；停嘱/撤销主流程走
     * {@link #cancelDownstreamBestEffort}，避免处方已发药时把医疗决定一起回滚（MF-03）。
     */
    public void cancelDownstream(VisitOrder order, String reason, String actor) {
        if (!hasDownstream(order)) {
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

    /**
     * 停嘱/撤销后的下游作废（尽力而为，**不抛异常**）。
     *
     * <p>医疗决定必须落库：处方已发药/已采集时下游 409，此时把作废登记成补偿任务
     * 交给 worker 重试（下游退药/退费后即可收敛），而不是让停嘱整体回滚。
     *
     * @return true 表示本次已作废或无需作废；false 表示已登记补偿任务待重试
     */
    public boolean cancelDownstreamBestEffort(VisitOrder order, String reason, String actor) {
        if (!hasDownstream(order)) {
            return true;
        }
        try {
            cancelDownstream(order, reason, actor);
            return true;
        } catch (RuntimeException e) {
            String error = truncate(e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
            log.warn("clinical.order_transfer_cancel_deferred orderId={} err={}", order.id(), error);
            repo.insertTransferTask(UUID.randomUUID(), order.id(), order.visitId(),
                    order.drug() ? "PHARMACY" : "TECH", reason, actor);
            audit(actor, "ORDER_TRANSFER_CANCEL_DEFERRED", order.id().toString(), Map.of(
                    "orderNo", order.orderNo(), "reason", reason, "error", error));
            return false;
        }
    }

    /**
     * 重试单条补偿任务（worker 调用）。
     *
     * @return true 表示已收敛（DONE 或已记为 FAILED 等待人工）
     */
    public boolean retryTask(UUID taskId, UUID orderId, UUID visitId, String channel,
                             String reason, int maxAttempts) {
        try {
            VisitOrder order = repo.findOrderById(orderId).orElse(null);
            if (order == null) {
                repo.completeTransferTask(taskId);
                return true;
            }
            cancelDownstream(order, reason, "transfer-worker");
            repo.completeTransferTask(taskId);
            return true;
        } catch (RuntimeException e) {
            String error = truncate(e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
            log.warn("clinical.order_transfer_cancel_retry_failed orderId={} channel={} err={}",
                    orderId, channel, error);
            repo.failTransferTask(taskId, error, maxAttempts);
            return false;
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 是否可能已有下游单据。
     *
     * <p>长嘱药品按日请领会生成多张处方，且不写 {@code transfer_status}（它不是"一次性转送"），
     * 停嘱时仍必须去药房作废"未发药的那几张"，不能因为状态是 NONE 就跳过。
     */
    private static boolean hasDownstream(VisitOrder order) {
        return !VisitOrder.TRANSFER_NONE.equals(order.transferStatus())
                || (order.drug() && order.longTerm());
    }

    /**
     * 需要转送下游的医嘱类型（药品→药房，检查→医技）。
     *
     * <p>长期药品医嘱<b>不自动转药房</b>（B2）：转送一次只能生成一张处方、发一天的量，
     * 而长嘱要连续执行多天；按日请领/摆药链路尚未建设，静默只发一天会导致后续几天无药。
     * 宁可让病区看到"未生成处方"并人工处理，也不要出现账实与用药都错的隐性缺陷。
     */
    private static boolean transferable(VisitOrder order) {
        if (order.drug() && order.longTerm()) {
            return false;
        }
        return order.drug() || "EXAM".equals(order.orderType());
    }

    /**
     * 提交后真正调用下游。
     *
     * <p>必须先重读医嘱：开嘱事务已提交，visit 行锁释放，撤销/停嘱可能刚好发生。
     * 若此刻医嘱已撤销/已停嘱，直接放弃转送——否则会给已经停掉的医嘱开出可发药处方
     * （第六轮 MF-01）。
     */
    private void transferNow(VisitOrder stale, Visit visit, String actor) {
        VisitOrder order = repo.findOrderById(stale.id()).orElse(null);
        if (order == null) {
            return;
        }
        // 只有"仍待转送"的医嘱才继续：已撤销/已停嘱/已被他人转送（含并发重复投递）一律不动
        if (!VisitOrder.PLACED.equals(order.status()) || order.stopDate() != null
                || !VisitOrder.TRANSFER_PENDING.equals(order.transferStatus())) {
            audit(actor, "ORDER_TRANSFER_SKIPPED", order.id().toString(), Map.of(
                    "orderNo", order.orderNo(), "status", order.status(),
                    "stopped", order.stopDate() != null,
                    "transferStatus", String.valueOf(order.transferStatus())));
            return;
        }
        if (order.drug()) {
            transferToPharmacy(order, visit, actor);
        } else if ("EXAM".equals(order.orderType())) {
            transferToTech(order, visit, actor);
        }
    }

    private void transferToPharmacy(VisitOrder order, Visit visit, String actor) {
        PharmacyOrderClient.RxRef ref = null;
        try {
            // 临时医嘱 = 一次性供药，供药日期取开嘱日（与药房 (orderId, supplyDate) 幂等键配合）
            LocalDate supplyDate = order.startDate() == null ? BusinessTime.today() : order.startDate();
            ref = pharmacyClient.createFromOrder(order.id(),
                    visit.patientId(), visit.id(), visit.visitNo(), visit.deptId(),
                    order.orderedBy(), supplyDate,
                    List.of(new PharmacyOrderClient.Line(order.itemCode(), order.quantity(),
                            order.dose(), order.frequency(), null, null, null, null)));
            if (!repo.markOrderTransferred(order.id(), EXTERNAL_PRESCRIPTION, ref.prescriptionId(),
                    ref.prescriptionNo())) {
                // 下游已建单，但本地已不能标记（医嘱在这期间被撤销/停嘱）：必须反向作废
                compensateAfterStaleTransfer(order, EXTERNAL_PRESCRIPTION, actor);
                return;
            }
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
            if (!repo.markOrderTransferred(order.id(), EXTERNAL_EXAM_APPLICATION,
                    ref.applicationId(), ref.applicationNo())) {
                compensateAfterStaleTransfer(order, EXTERNAL_EXAM_APPLICATION, actor);
                return;
            }
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

    /**
     * 下游已建单但本地未能回写（医嘱已被撤销/停嘱）：立即反向作废；
     * 作废失败则登记补偿任务，绝不留下"医嘱已停、处方可发药"的状态。
     */
    private void compensateAfterStaleTransfer(VisitOrder order, String externalType, String actor) {
        String reason = "医嘱在转送期间已被撤销/停嘱，自动作废";
        // 并发情况下可能另一事务已成功回写 TRANSFERRED：此时绝不能把别人建好的单据作废
        VisitOrder latest = repo.findOrderById(order.id()).orElse(null);
        if (latest == null || VisitOrder.TRANSFERRED.equals(latest.transferStatus())) {
            return;
        }
        log.warn("clinical.order_transfer_stale orderId={} type={}", order.id(), externalType);
        boolean cancelled = false;
        try {
            if (EXTERNAL_PRESCRIPTION.equals(externalType)) {
                cancelled = pharmacyClient.cancelByOrder(order.id(), reason, actor).cancelled();
            } else {
                cancelled = techClient.cancelByOrder(order.id(), reason, actor).cancelled();
            }
        } catch (RuntimeException e) {
            log.warn("clinical.order_transfer_stale_cancel_failed orderId={} err={}",
                    order.id(), e.getMessage());
        }
        if (!cancelled) {
            repo.insertTransferTask(UUID.randomUUID(), order.id(), order.visitId(),
                    EXTERNAL_PRESCRIPTION.equals(externalType) ? "PHARMACY" : "TECH", reason, actor);
        }
        repo.updateOrderTransfer(order.id(), VisitOrder.TRANSFER_FAILED, externalType, null, null,
                truncate(reason));
        audit(actor, "ORDER_TRANSFER_STALE", order.id().toString(), Map.of(
                "orderNo", order.orderNo(), "externalType", externalType,
                "cancelConfirmed", cancelled));
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
