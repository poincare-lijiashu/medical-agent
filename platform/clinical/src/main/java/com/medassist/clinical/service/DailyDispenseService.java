package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.domain.ClinicalModel.DailyDispenseRequest;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.WardMedReceipt;
import com.medassist.clinical.domain.ClinicalModel.WardMedReturn;
import com.medassist.clinical.domain.ClinicalModel.WardStockBalance;
import com.medassist.clinical.domain.ClinicalModel.WardStockTxn;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.DailyDispenseItem;
import com.medassist.clinical.web.ClinicalDtos.DailyDispenseResult;
import com.medassist.clinical.web.ClinicalDtos.DispenseBoard;
import com.medassist.clinical.web.ClinicalDtos.DispenseBoardItem;
import com.medassist.clinical.web.ClinicalDtos.WardReceiptReq;
import com.medassist.clinical.web.ClinicalDtos.WardReceiptView;
import com.medassist.clinical.web.ClinicalDtos.WardReturnReq;
import com.medassist.clinical.web.ClinicalDtos.WardReturnView;
import com.medassist.clinical.web.ClinicalDtos.WardStockItem;
import com.medassist.clinical.web.ClinicalDtos.WardStockTxnView;
import com.medassist.clinical.web.ClinicalDtos.WardStockView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 长期药品医嘱"按日请领"（B2 后续）。
 *
 * <p>为什么长嘱药品不随开嘱自动转药房：一张处方只能供一天的量，而长嘱要连续执行多天；
 * 自动转一次会让病区误以为后续几天的药已发。改为病区每天显式请领：
 * <ul>
 *   <li>只取"当日有效"的长嘱药品（start ≤ date ≤ stop，且 PLACED）；</li>
 *   <li>数量 = 医嘱的每日总量（{@code quantity}），频次次数只作执行依据；</li>
 *   <li>药房按 {@code (orderId, supplyDate)} 幂等：重复请领返回同一张处方（duplicate=true）。</li>
 * </ul>
 *
 * <p>费用不在药房记账（处方 billingMode=ORDER），仍由临床按日计费（{@code daily-bill}）。
 */
@Service
public class DailyDispenseService {

    private static final Logger log = LoggerFactory.getLogger(DailyDispenseService.class);

    private final ClinicalRepository repo;
    private final PharmacyOrderClient pharmacyClient;
    private final ObjectMapper objectMapper;

    public DailyDispenseService(ClinicalRepository repo, PharmacyOrderClient pharmacyClient,
                                ObjectMapper objectMapper) {
        this.repo = repo;
        this.pharmacyClient = pharmacyClient;
        this.objectMapper = objectMapper;
    }

    public DailyDispenseResult request(UUID visitId, LocalDate date, String actor) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!"INPATIENT".equals(visit.visitType())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "只有住院就诊支持按日请领");
        }
        if (!visit.active()) {
            throw new BusinessException(ErrorCode.VISIT_STATUS_INVALID, "就诊已结束，不能请领药品");
        }
        LocalDate day = date == null ? BusinessTime.today() : date;
        if (day.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能对未来日期请领药品");
        }

        List<VisitOrder> orders = repo.listOrders(visitId, null).stream()
                .filter(VisitOrder::drug)
                .filter(VisitOrder::longTerm)
                .filter(o -> o.billableOn(day))
                .toList();

        List<DailyDispenseItem> items = new ArrayList<>(orders.size());
        int created = 0;
        int duplicate = 0;
        int failed = 0;
        for (VisitOrder order : orders) {
            try {
                PharmacyOrderClient.RxRef ref = pharmacyClient.createFromOrder(order.id(),
                        visit.patientId(), visit.id(), visit.visitNo(), visit.deptId(),
                        order.orderedBy(), day,
                        // 结构化剂量（单次剂量/单位/每日次数）要求三者同时提供；临床长嘱只有
                        // 自由文本剂量，这里不做伪结构化，只传每日总量 + 频次文本。
                        List.of(new PharmacyOrderClient.Line(order.itemCode(), order.quantity(),
                                order.dose(), order.frequency(), 1, null, null, null)));
                // 请领台账：药房已建单必须落库，否则病区看板只能显示"未请领"
                repo.upsertDailyDispenseRequest(UUID.randomUUID(), order.id(), visit.id(), day,
                        order.quantity(), ref.prescriptionId(), ref.prescriptionNo(),
                        DailyDispenseRequest.REQUESTED, null, actor);
                if (ref.duplicate()) {
                    duplicate++;
                } else {
                    created++;
                }
                items.add(new DailyDispenseItem(order.id(), order.orderNo(), order.itemCode(),
                        order.itemName(), order.quantity(), ref.prescriptionId(),
                        ref.prescriptionNo(), ref.duplicate(), "REQUESTED", null));
            } catch (RuntimeException e) {
                failed++;
                String reason = e.getMessage() == null ? e.getClass().getSimpleName()
                        : truncate(e.getMessage());
                log.warn("clinical.daily_dispense_failed orderId={} date={} err={}",
                        order.id(), day, reason);
                repo.upsertDailyDispenseRequest(UUID.randomUUID(), order.id(), visit.id(), day,
                        order.quantity(), null, null, DailyDispenseRequest.FAILED, reason, actor);
                items.add(new DailyDispenseItem(order.id(), order.orderNo(), order.itemCode(),
                        order.itemName(), order.quantity(), null, null, false, "FAILED", reason));
            }
        }

        audit(actor, visit, day, created, duplicate, failed, orders.size());
        // 全部失败且确实有需要请领的医嘱：明确 503，不能让病区以为"已提交"
        if (!orders.isEmpty() && failed == orders.size()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "药房服务暂不可用，本次请领全部失败，请稍后重试");
        }
        return new DailyDispenseResult(day, orders.size(), created, duplicate, failed, items);
    }

    /**
     * 病区收药确认（按 {@code dispenseId} 幂等）。
     *
     * <p>只允许对"当日已成功请领"的长期药品医嘱登记；数量不得超过请领数量。
     * 重复提交同一发药单返回已有记录（网络重试安全），数量/医嘱不一致则 409。
     */
    @Transactional
    public WardReceiptView receive(UUID visitId, UUID orderId, WardReceiptReq req, String actor) {
        repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        VisitOrder order = repo.findOrder(visitId, orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该医嘱"));
        if (!order.drug() || !order.longTerm()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有长期药品医嘱需要病区收药确认");
        }
        if (req.supplyDate().isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能对未来的供药日期收药");
        }
        DailyDispenseRequest request = repo.findDailyDispenseRequest(orderId, req.supplyDate())
                .filter(r -> r.prescriptionId() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "该医嘱当日尚未成功请领，不能收药"));

        // 幂等优先：同一发药单的重试必须原样返回。若先做数量校验，
        // 第一次已把当日额度收满时，重试会被误判成"超过请领数量"（422）。
        Optional<WardMedReceipt> existing = repo.findWardReceiptByDispense(req.dispenseId());
        if (existing.isPresent()) {
            WardMedReceipt receipt = existing.get();
            if (!receipt.orderId().equals(orderId) || receipt.quantity() != req.quantity()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该发药单已登记收药（医嘱/数量不同），请核对后刷新");
            }
            return toView(receipt);
        }

        // 累计上限：收药是按发药单登记的多行，上限必须按"当日合计"判断；
        // 换一个 dispenseId 再登记也必须受同一上限约束（V27 触发器兜底并发）。
        int received = repo.sumWardReceiptQuantity(orderId, req.supplyDate());
        if (received + req.quantity() > request.quantity()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "累计收药数量不能超过当日请领数量（请领 " + request.quantity()
                            + "，已收 " + received + "，本次 " + req.quantity() + "）");
        }

        try {
            repo.insertWardReceipt(UUID.randomUUID(), request.id(), orderId, visitId,
                    req.dispenseId(), req.supplyDate(), req.quantity(), actor);
        } catch (DataIntegrityViolationException e) {
            // 并发窗口：另一事务刚收满当日额度（V27 触发器拒绝），或同一发药单被并发登记
            throw new BusinessException(ErrorCode.CONFLICT,
                    "收药数量已变化（可能已超过当日请领量），请刷新后重试");
        }
        WardMedReceipt saved = repo.findWardReceiptByDispense(req.dispenseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "收药登记失败，请重试"));
        if (!saved.orderId().equals(orderId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该发药单已登记到其它医嘱，不能重复收货");
        }
        // 收药即入库：病区二级库存只认流水，重复收药由 (receipt_id, 'IN') 唯一索引挡掉
        repo.insertWardStockTxn(new WardStockTxn(UUID.randomUUID(), visitId, orderId, saved.id(),
                null, order.itemCode(), order.itemName(), saved.quantity(), WardStockTxn.IN,
                saved.supplyDate(), actorName(actor), Instant.now()));
        audit(actor, "WARD_MED_RECEIPT", visitId.toString(), Map.of(
                "orderId", orderId.toString(), "dispenseId", req.dispenseId().toString(),
                "supplyDate", req.supplyDate().toString(), "quantity", req.quantity()));
        return toView(saved);
    }

    /**
     * 病区退药（未执行才可退）。
     *
     * <p>规则：
     * <ul>
     *   <li>只能退"当日护理执行 REFUSED / OMITTED"的药品（EXECUTED 说明药已使用，退药即虚记）；</li>
     *   <li>累计退药量不得超过收药量，数据库 CAS 兜底防并发超退；</li>
     *   <li>按 {@code returnId} 幂等：重试返回同一条退药记录，参数不一致则 409。</li>
     * </ul>
     *
     * <p>一次退药、三处同步：病区二级库存回冲 + 药房批次库存回补 + 医嘱金额回冲（B3）。
     * 本地写入与本事务同生共死；药房推送到**提交后**执行（药房会回调临床冲减金额，
     * 事务内调用会因 visit 行锁互相等待），失败只记审计并可由同一 returnId 重试自愈。
     */
    @Transactional
    public WardReturnView returnToPharmacy(UUID visitId, UUID receiptId, WardReturnReq req,
                                            String actor) {
        repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        WardMedReceipt receipt = repo.lockWardReceipt(receiptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该收药记录"));
        if (!receipt.visitId().equals(visitId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "收药记录不属于该就诊");
        }

        // 幂等优先：重复提交即使在"已全退"之后也必须原样返回，不能报超退
        Optional<WardMedReturn> existing = repo.findWardReturn(req.returnId());
        if (existing.isPresent()) {
            WardMedReturn ret = existing.get();
            if (!ret.receiptId().equals(receiptId) || ret.quantity() != req.quantity()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该退药单号已登记（收药记录/数量不同），请核对后刷新");
            }
            // 上一次可能"本地成功、药房失败"：用同一 returnId 再推一次即可自愈（两侧幂等）
            VisitOrder replayOrder = repo.findOrder(visitId, receipt.orderId()).orElse(null);
            if (replayOrder != null) {
                afterCommit(() -> pushToPharmacy(receipt.orderId(), receipt.supplyDate(),
                        replayOrder.itemCode(), ret.quantity(), ret.reason(),
                        ret.id(), actor));
            }
            return toReturnView(ret, receipt);
        }

        VisitOrder order = repo.findOrder(visitId, receipt.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该医嘱"));
        NursingExecution execution = repo.findNursingExecution(receipt.orderId(),
                        receipt.supplyDate())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "该医嘱 " + receipt.supplyDate() + " 尚无护理执行记录，不能退药"));
        if (NursingExecution.EXECUTED.equals(execution.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "护理已执行（EXECUTED），药品已使用，不能退药");
        }

        int remaining = receipt.stockBalance();
        if (req.quantity() > remaining) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退药数量不能超过可退数量（" + remaining + "）");
        }
        if (!repo.addReturnedQuantity(receiptId, req.quantity())) {
            throw new BusinessException(ErrorCode.CONFLICT, "退药数量已变化，请刷新后重试");
        }

        WardMedReturn saved = new WardMedReturn(req.returnId(), receiptId, receipt.orderId(),
                visitId, receipt.supplyDate(), req.quantity(), blankToNull(req.reason()),
                actorName(actor));
        repo.insertWardReturn(saved);
        repo.insertWardStockTxn(new WardStockTxn(UUID.randomUUID(), visitId, receipt.orderId(),
                receiptId, saved.id(), order.itemCode(), order.itemName(), req.quantity(),
                WardStockTxn.RETURN, receipt.supplyDate(), actorName(actor), Instant.now()));

        // 药房批次库存回补 + 医嘱金额回冲：由药房按其发药批次分摊（临床不掌握批次）。
        // **必须在本地事务提交后**再调用：药房退药会回调临床做金额冲减，若临床还在事务内
        // （持有 visit 行锁），回调会等锁直到超时，形成"临床等药房、药房等临床"的环路。
        // 失败不静默：记 ERROR + 审计，护士用同一 returnId 重试即可自愈（两侧都幂等）。
        afterCommit(() -> pushToPharmacy(receipt.orderId(), receipt.supplyDate(), order.itemCode(),
                req.quantity(), blankToNull(req.reason()), req.returnId(), actor));

        audit(actor, "WARD_MED_RETURN", visitId.toString(), Map.of(
                "receiptId", receiptId.toString(), "orderId", receipt.orderId().toString(),
                "supplyDate", receipt.supplyDate().toString(), "quantity", req.quantity(),
                "nursingStatus", execution.status()));

        int returned = receipt.returnedQuantity() + req.quantity();
        String status = returned >= receipt.quantity()
                ? WardMedReceipt.RETURNED : WardMedReceipt.RECEIVED;
        WardMedReceipt updated = new WardMedReceipt(receipt.id(), receipt.requestId(),
                receipt.orderId(), receipt.visitId(), receipt.dispenseId(), receipt.supplyDate(),
                receipt.quantity(), returned, status, receipt.receivedBy());
        return toReturnView(saved, updated);
    }

    /** 病区二级库存：按药品汇总 + 最近流水（跨日期累计，余额为 0 的药品不返回）。 */
    public WardStockView stock(UUID visitId) {
        repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        List<WardStockBalance> balances = repo.listWardStockBalances(visitId);
        List<WardStockItem> items = balances.stream().map(b -> new WardStockItem(b.drugCode(),
                b.drugName(), b.receivedQuantity(), b.returnedQuantity(), b.balance())).toList();
        int total = items.stream().mapToInt(WardStockItem::balance).sum();
        List<WardStockTxnView> txns = repo.listWardStockTxns(visitId).stream()
                .map(t -> new WardStockTxnView(t.id(), t.drugCode(), t.drugName(), t.quantity(),
                        t.txnType(), t.supplyDate(), t.orderId(), t.operator(), t.createdAt()))
                .toList();
        return new WardStockView(visitId, total, items, txns);
    }

    /**
     * 病区备药看板：请领 → 处方 → 收药 → 护理执行，一行看完。
     *
     * <p>包含"当日有效"或"当日有请领/收药记录"的长期药品医嘱（停嘱后仍能看到历史痕迹）。
     */
    public DispenseBoard board(UUID visitId, LocalDate date) {
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        LocalDate day = date == null ? BusinessTime.today() : date;
        if (day.isAfter(BusinessTime.today())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不能查看未来日期的备药看板");
        }

        Map<UUID, DailyDispenseRequest> requests = repo.listDailyDispenseRequests(visitId, day)
                .stream().collect(Collectors.toMap(DailyDispenseRequest::orderId, r -> r, (a, b) -> a));
        Map<UUID, List<WardMedReceipt>> receipts = repo.listWardReceipts(visitId, day)
                .stream().collect(Collectors.groupingBy(WardMedReceipt::orderId));
        Map<UUID, String> nursing = repo.listNursingExecutions(visitId, day).stream()
                .collect(Collectors.toMap(NursingExecution::orderId,
                        NursingExecution::status, (a, b) -> a));

        List<DispenseBoardItem> items = repo.listOrders(visitId, null).stream()
                .filter(VisitOrder::drug)
                .filter(VisitOrder::longTerm)
                .filter(o -> o.billableOn(day) || requests.containsKey(o.id())
                        || receipts.containsKey(o.id()))
                .map(o -> {
                    DailyDispenseRequest request = requests.get(o.id());
                    List<WardMedReceipt> dayReceipts = receipts.getOrDefault(o.id(), List.of());
                    int received = dayReceipts.stream().mapToInt(WardMedReceipt::quantity).sum();
                    int returned = dayReceipts.stream()
                            .mapToInt(WardMedReceipt::returnedQuantity).sum();
                    int balance = received - returned;
                    String nursingStatus = nursing.getOrDefault(o.id(), "NOT_EXECUTED");
                    boolean unexecuted = NursingExecution.REFUSED.equals(nursingStatus)
                            || NursingExecution.OMITTED.equals(nursingStatus);
                    boolean returnable = unexecuted && balance > 0;
                    return new DispenseBoardItem(o.id(), o.orderNo(), o.itemCode(), o.itemName(),
                            o.quantity(), o.frequency(), o.frequencyTimes(),
                            request == null ? "NOT_REQUESTED" : request.status(),
                            request == null ? null : request.prescriptionId(),
                            request == null ? null : request.prescriptionNo(),
                            request == null ? null : request.errorMessage(),
                            received, returned, balance, nursingStatus, returnable,
                            returnHint(nursingStatus, balance));
                })
                .toList();

        int requested = (int) items.stream()
                .filter(i -> DailyDispenseRequest.REQUESTED.equals(i.requestStatus())).count();
        int received = (int) items.stream().filter(i -> i.receivedQuantity() > 0).count();
        int failed = (int) items.stream()
                .filter(i -> DailyDispenseRequest.FAILED.equals(i.requestStatus())).count();
        return new DispenseBoard(day, items.size(), requested, received, failed, items);
    }

    private WardReceiptView toView(WardMedReceipt receipt) {
        return new WardReceiptView(receipt.id(), receipt.dispenseId(), receipt.orderId(),
                receipt.supplyDate(), receipt.quantity(), receipt.status(), receipt.receivedBy());
    }

    private WardReturnView toReturnView(WardMedReturn ret, WardMedReceipt receipt) {
        return new WardReturnView(ret.id(), ret.receiptId(), ret.orderId(), ret.supplyDate(),
                ret.quantity(), receipt.quantity(), receipt.returnedQuantity(),
                receipt.stockBalance(), receipt.status(), ret.returnedBy());
    }

    /** 看板提示：只有"未执行 + 还有库存"才提示可退，避免护士误退已使用的药。 */
    private static String returnHint(String nursingStatus, int balance) {
        if (balance <= 0) {
            return null;
        }
        if (NursingExecution.REFUSED.equals(nursingStatus)
                || NursingExecution.OMITTED.equals(nursingStatus)) {
            return "护理未执行，可退回病区库存";
        }
        if (NursingExecution.EXECUTED.equals(nursingStatus)) {
            return "护理已执行，不可退药";
        }
        return null;
    }

    private static String actorName(String actor) {
        return actor == null || actor.isBlank() ? "ward" : actor;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 把退药推给药房（批次库存回补 + 医嘱金额回冲）。
     *
     * <p>必须在本地事务提交后调用：药房退药会在自己的提交后回调临床冲减金额，
     * 若此时临床还持有 visit 行锁，回调会等锁到超时（实测表现为退药接口 503）。
     */
    private void pushToPharmacy(UUID orderId, LocalDate supplyDate, String drugCode, int quantity,
                                String reason, UUID returnId, String actor) {
        try {
            PharmacyOrderClient.WardReturnRef ref = pharmacyClient.returnWardDrug(orderId,
                    supplyDate, drugCode, quantity, reason, returnId, actorName(actor));
            audit(actor, "WARD_MED_RETURN_PHARMACY", orderId.toString(), Map.of(
                    "returnId", returnId.toString(), "dispenseId", ref.dispenseId().toString(),
                    "returned", ref.returnedQuantity(), "remaining", ref.remainingReturnable()));
        } catch (RuntimeException e) {
            // 不静默：审计记失败，护士用同一 returnId 重试即可自愈（临床/药房两侧都幂等）
            log.error("clinical.ward_return_pharmacy_failed orderId={} returnId={} err={}",
                    orderId, returnId, e.getMessage());
            audit(actor, "WARD_MED_RETURN_PHARMACY_FAILED", orderId.toString(), Map.of(
                    "returnId", returnId.toString(),
                    "error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** 有事务则挂到提交后执行；无事务（单测/直调）立即执行。 */
    private static void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                    action.run();
                                }
                            });
        } else {
            action.run();
        }
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor == null || actor.isBlank() ? "ward" : actor, action,
                    "VISIT", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor == null || actor.isBlank() ? "ward" : actor, action,
                    "VISIT", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }

    private void audit(String actor, Visit visit, LocalDate day, int created, int duplicate,
                       int failed, int requested) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("visitNo", visit.visitNo());
        detail.put("date", day.toString());
        detail.put("requested", requested);
        detail.put("created", created);
        detail.put("duplicate", duplicate);
        detail.put("failed", failed);
        try {
            repo.logChange(actor == null || actor.isBlank() ? "ward" : actor,
                    "DAILY_DISPENSE_REQUEST", "VISIT", visit.id().toString(),
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor == null || actor.isBlank() ? "ward" : actor,
                    "DAILY_DISPENSE_REQUEST", "VISIT", visit.id().toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }

    private static String truncate(String message) {
        return message.length() <= 200 ? message : message.substring(0, 200);
    }
}
