package com.medassist.clinical.service;

import com.medassist.clinical.client.PharmacyOrderClient;
import com.medassist.clinical.domain.ClinicalModel.DailyDispenseRequest;
import com.medassist.clinical.domain.ClinicalModel.NursingExecution;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.WardMedReceipt;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.DailyDispenseItem;
import com.medassist.clinical.web.ClinicalDtos.DailyDispenseResult;
import com.medassist.clinical.web.ClinicalDtos.DispenseBoard;
import com.medassist.clinical.web.ClinicalDtos.DispenseBoardItem;
import com.medassist.clinical.web.ClinicalDtos.WardReceiptReq;
import com.medassist.clinical.web.ClinicalDtos.WardReceiptView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
        if (req.quantity() > request.quantity()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "收药数量不能超过当日请领数量（" + request.quantity() + "）");
        }

        Optional<WardMedReceipt> existing = repo.findWardReceiptByDispense(req.dispenseId());
        if (existing.isPresent()) {
            WardMedReceipt receipt = existing.get();
            if (!receipt.orderId().equals(orderId) || receipt.quantity() != req.quantity()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该发药单已登记收药（医嘱/数量不同），请核对后刷新");
            }
            return toView(receipt);
        }

        repo.insertWardReceipt(UUID.randomUUID(), request.id(), orderId, visitId,
                req.dispenseId(), req.supplyDate(), req.quantity(), actor);
        WardMedReceipt saved = repo.findWardReceiptByDispense(req.dispenseId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
                        "收药登记失败，请重试"));
        if (!saved.orderId().equals(orderId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该发药单已登记到其它医嘱，不能重复收货");
        }
        audit(actor, "WARD_MED_RECEIPT", visitId.toString(), Map.of(
                "orderId", orderId.toString(), "dispenseId", req.dispenseId().toString(),
                "supplyDate", req.supplyDate().toString(), "quantity", req.quantity()));
        return toView(saved);
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
                    int received = receipts.getOrDefault(o.id(), List.of()).stream()
                            .mapToInt(WardMedReceipt::quantity).sum();
                    return new DispenseBoardItem(o.id(), o.orderNo(), o.itemCode(), o.itemName(),
                            o.quantity(), o.frequency(), o.frequencyTimes(),
                            request == null ? "NOT_REQUESTED" : request.status(),
                            request == null ? null : request.prescriptionId(),
                            request == null ? null : request.prescriptionNo(),
                            request == null ? null : request.errorMessage(),
                            received, nursing.getOrDefault(o.id(), "NOT_EXECUTED"));
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
