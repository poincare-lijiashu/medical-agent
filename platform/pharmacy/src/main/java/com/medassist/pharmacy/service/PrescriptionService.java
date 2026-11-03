package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.client.PatientClient;
import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseItem;
import com.medassist.pharmacy.domain.PharmacyModel.DispenseReturn;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticRegister;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionAudit;
import com.medassist.pharmacy.domain.PharmacyModel.PrescriptionItem;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PharmacyDtos.CancelFromOrderReq;
import com.medassist.pharmacy.web.PharmacyDtos.CreateFromOrderReq;
import com.medassist.pharmacy.web.PharmacyDtos.CreatePrescriptionReq;
import com.medassist.pharmacy.web.PharmacyDtos.DispenseView;
import com.medassist.pharmacy.web.PharmacyDtos.OrderPrescriptionView;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionCancelView;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionLine;
import com.medassist.pharmacy.web.PharmacyDtos.PrescriptionView;
import com.medassist.pharmacy.web.PharmacyDtos.ReturnReq;
import com.medassist.pharmacy.web.PharmacyDtos.WardReturnReq;
import com.medassist.pharmacy.web.PharmacyDtos.WardReturnView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 处方与发药。
 *
 * <p>业务闭环：开方（PENDING_REVIEW）→ 药师审核（APPROVED / REJECTED）→ 发药（DISPENSED）→ 退药。
 *
 * <p>关键约束（都在数据库层有兜底）：
 * <ul>
 *   <li>一张处方只能发一次药（{@code dispense.prescription_id} 唯一）；</li>
 *   <li>发药按近效期优先（FEFO），过期批次不出库；扣减是原子 UPDATE，库存不会为负；</li>
 *   <li>麻醉/精神类药品必须双人核对（核对人 ≠ 发药人），这是法规要求，不能只写在 SOP 里；</li>
 *   <li>退药不得超过已发数量，回补原批次，不新建批次。</li>
 * </ul>
 */
@Service
public class PrescriptionService {

    private final PharmacyRepository repo;
    private final PatientClient patientClient;
    private final PharmacyBillingService billing;
    private final PrescriptionAuditService auditService;
    private final NarcoticService narcoticService;
    private final ObjectMapper objectMapper;

    public PrescriptionService(PharmacyRepository repo, PatientClient patientClient,
                               PharmacyBillingService billing, PrescriptionAuditService auditService,
                               NarcoticService narcoticService, ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.billing = billing;
        this.auditService = auditService;
        this.narcoticService = narcoticService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 开方

    @Transactional
    public PrescriptionView create(CreatePrescriptionReq req, String actor) {
        return createInternal(req, actor, null, null);
    }

    /**
     * 临床医嘱转处方（内网接口）。
     *
     * <p>幂等口径：同一 {@code orderId} 只生成一张处方；已存在时原样返回并标记
     * {@code duplicate=true}，临床侧重试不会产生重复处方。
     *
     * <p>计费口径：固定 {@code billing_mode=ORDER}——费用已在医嘱计价，药房不得再记一次。
     * 审方规则与手工开方完全一致（BLOCK 级直接拒绝，错误信息回传给临床侧展示）。
     */
    @Transactional
    public OrderPrescriptionView createFromOrder(CreateFromOrderReq req, String actor) {
        // 长嘱按日请领：幂等键是 (orderId, supplyDate)，同一天重复请求返回同一张处方
        Optional<Prescription> existing = repo.findPrescriptionBySourceOrder(req.orderId(),
                req.supplyDate());
        if (existing.isPresent()) {
            Prescription rx = existing.get();
            return new OrderPrescriptionView(rx.id(), rx.prescriptionNo(), req.orderId(), true,
                    rx.totalAmount(), rx.status());
        }
        String prescriber = blankToNull(req.prescriber());
        String effectiveActor = prescriber == null ? actor : prescriber;
        CreatePrescriptionReq delegated = new CreatePrescriptionReq(req.patientId(), req.visitId(),
                req.visitNo(), req.deptId(), Prescription.BILLING_ORDER, req.items());
        PrescriptionView view;
        try {
            view = createInternal(delegated, effectiveActor, req.orderId(), req.supplyDate());
        } catch (DataIntegrityViolationException e) {
            // 并发转送：唯一索引兜底。此处事务已不可用，明确回 409，临床侧重试即可拿到已有处方
            throw new BusinessException(ErrorCode.CONFLICT, "该医嘱当日处方已生成，请刷新后重试");
        }
        Prescription rx = view.prescription();
        return new OrderPrescriptionView(rx.id(), rx.prescriptionNo(), req.orderId(), false,
                rx.totalAmount(), rx.status());
    }

    /**
     * 按医嘱作废处方（医嘱撤销/停嘱联动，内网接口）。
     *
     * <p>安全规则：
     * <ul>
     *   <li>已发药（DISPENSED）不能作废——药已经在患者手里，只能走退药；</li>
     *   <li>找不到处方 = 该医嘱从未转送（或转送失败），返回 cancelled=false，不报错；</li>
     *   <li>已作废幂等返回（临床重试不会报错）。</li>
     * </ul>
     */
    @Transactional
    public PrescriptionCancelView cancelFromOrder(CancelFromOrderReq req, String actor) {
        List<Prescription> found = repo.lockPrescriptionsBySourceOrder(req.orderId());
        if (found.isEmpty()) {
            return new PrescriptionCancelView(null, null, null, false);
        }
        String who = blankToNull(req.actor()) == null ? actor : blankToNull(req.actor());
        String why = blankToNull(req.reason()) == null ? "医嘱撤销" : blankToNull(req.reason());

        Prescription firstCancelled = null;
        Prescription dispensed = null;
        for (Prescription rx : found) {
            if (Prescription.CANCELLED.equals(rx.status())) {
                continue;
            }
            if (Prescription.DISPENSED.equals(rx.status())) {
                // 已发药的留给退药流程；长嘱可能有"今天已发、明天未发"的多张处方
                dispensed = rx;
                continue;
            }
            if (!repo.cancelPrescription(rx.id(), rx.version(), why, who)) {
                throw new BusinessException(ErrorCode.CONFLICT, "处方状态已变化，请刷新后重试");
            }
            if (firstCancelled == null) {
                firstCancelled = rx;
            }
        }
        if (firstCancelled != null) {
            audit(who, "RX_CANCEL_FROM_ORDER", "PRESCRIPTION", firstCancelled.id().toString(),
                    Map.of("prescriptionNo", firstCancelled.prescriptionNo(),
                            "orderId", req.orderId().toString(), "reason", why,
                            "prescriptionCount", found.size()));
            return new PrescriptionCancelView(firstCancelled.id(), firstCancelled.prescriptionNo(),
                    Prescription.CANCELLED, true);
        }
        if (dispensed != null) {
            throw new BusinessException(ErrorCode.PRESCRIPTION_STATUS_INVALID,
                    "处方 " + dispensed.prescriptionNo() + " 已发药，不能作废（请走退药流程）");
        }
        // 全部已作废：幂等返回
        Prescription rx = found.get(found.size() - 1);
        return new PrescriptionCancelView(rx.id(), rx.prescriptionNo(), rx.status(), true);
    }

    private PrescriptionView createInternal(CreatePrescriptionReq req, String actor, UUID sourceOrderId,
                                            LocalDate supplyDate) {
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());
        String billingMode = billingModeOf(req.billingMode(), req.visitId());
        Integer ageYears = ageOf(patient);

        Set<String> seen = new HashSet<>();
        List<PrescriptionItem> items = new ArrayList<>();
        List<Drug> drugs = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        UUID prescriptionId = UUID.randomUUID();

        for (PrescriptionLine line : req.items()) {
            String code = line.drugCode().trim();
            if (!seen.add(code)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "同一药品不能重复开立（" + code + "），请合并数量");
            }
            Drug drug = repo.findDrugByCode(code)
                    .filter(Drug::enabled)
                    .orElseThrow(() -> new BusinessException(ErrorCode.DRUG_NOT_FOUND,
                            "药品不存在或已停用：" + code));
            validateDose(line);
            BigDecimal amount = drug.price()
                    .multiply(BigDecimal.valueOf(line.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal dailyDose = dailyDoseOf(line);
            items.add(new PrescriptionItem(UUID.randomUUID(), prescriptionId, drug.id(),
                    drug.drugCode(), drug.name(), drug.spec(), line.quantity(), drug.unit(),
                    blankToNull(line.dosage()), blankToNull(line.frequency()), line.days(),
                    drug.price(), amount,
                    line.singleDose(), blankToNull(line.doseUnit()), line.timesPerDay(), dailyDose));
            drugs.add(drug);
            total = total.add(amount);
        }

        // 麻醉药品双重门槛：处方权名单（人事资格）+ 上次空安瓿已回收
        requireNarcoticLicence(drugs, patient, actor);

        // 前置审方：BLOCK 级直接拒绝开方（避免患者拿到禁忌处方）
        List<PrescriptionAuditService.Finding> findings = auditService.evaluate(items, drugs, ageYears);
        if (auditService.hasBlock(findings)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "处方存在用药禁忌，请修改后重新开立：" + blockMessages(findings));
        }

        Prescription rx = new Prescription(prescriptionId, repo.nextPrescriptionNo(),
                patient.id(), patient.patientNo(), req.visitId(), blankToNull(req.visitNo()),
                req.deptId(), actor, Prescription.PENDING_REVIEW, null, null, null,
                total, 0L, null, billingMode, sourceOrderId, supplyDate);
        repo.insertPrescription(rx);
        for (PrescriptionItem item : items) {
            repo.insertPrescriptionItem(item);
        }
        auditService.record(prescriptionId, PrescriptionAudit.PHASE_CREATE, findings);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("prescriptionNo", rx.prescriptionNo());
        detail.put("patientNo", patient.patientNo());
        detail.put("itemCount", items.size());
        detail.put("totalAmount", total);
        detail.put("billingMode", billingMode);
        detail.put("auditFindings", findings.size());
        if (sourceOrderId != null) {
            detail.put("sourceOrderId", sourceOrderId.toString());
            detail.put("supplyDate", supplyDate == null ? "" : supplyDate.toString());
        }
        audit(actor, sourceOrderId == null ? "RX_CREATE" : "RX_CREATE_FROM_ORDER",
                "PRESCRIPTION", prescriptionId.toString(), detail);
        return new PrescriptionView(repo.findPrescription(prescriptionId).orElseThrow(), items);
    }

    // ---------------------------------------------------------------- 审核

    @Transactional
    public PrescriptionView review(UUID prescriptionId, boolean approve, String reason, String actor) {
        Prescription rx = repo.lockPrescription(prescriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        if (!Prescription.PENDING_REVIEW.equals(rx.status())) {
            throw new BusinessException(ErrorCode.PRESCRIPTION_STATUS_INVALID,
                    "只有待审核处方可以审核，当前：" + rx.status());
        }
        // 审核复评：药品字典可能在开方后被修改（新增禁忌/调低剂量上限），必须按当前字典再判一次
        List<PrescriptionItem> reviewItems = repo.listPrescriptionItems(prescriptionId);
        List<Drug> reviewDrugs = reviewItems.stream()
                .map(i -> repo.findDrug(i.drugId()).orElse(null))
                .toList();
        List<PrescriptionAuditService.Finding> findings =
                auditService.evaluate(reviewItems, reviewDrugs, null);
        auditService.record(prescriptionId, PrescriptionAudit.PHASE_REVIEW, findings);
        if (approve && auditService.hasBlock(findings)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "处方存在严重用药禁忌，不能通过审核，请驳回并让医师修改后重开：" + blockMessages(findings));
        }
        String rejectReason = null;
        String next;
        if (approve) {
            next = Prescription.APPROVED;
        } else {
            if (reason == null || reason.isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "驳回必须填写理由");
            }
            next = Prescription.REJECTED;
            rejectReason = reason.trim();
        }
        if (!repo.updatePrescriptionStatus(prescriptionId, Prescription.PENDING_REVIEW, next,
                rx.version(), actor, rejectReason)) {
            throw new BusinessException(ErrorCode.CONFLICT, "处方状态已变化，请刷新后重试");
        }
        audit(actor, approve ? "RX_APPROVE" : "RX_REJECT", "PRESCRIPTION", prescriptionId.toString(),
                Map.of("prescriptionNo", rx.prescriptionNo(), "reason", rejectReason == null ? "" : rejectReason));
        return new PrescriptionView(repo.findPrescription(prescriptionId).orElseThrow(),
                repo.listPrescriptionItems(prescriptionId));
    }

    // ---------------------------------------------------------------- 发药

    @Transactional
    public DispenseView dispense(UUID prescriptionId, String checker, String actor) {
        Prescription rx = repo.lockPrescription(prescriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        if (!rx.dispensable()) {
            throw new BusinessException(ErrorCode.PRESCRIPTION_STATUS_INVALID,
                    "只有审核通过的处方可以发药，当前：" + rx.status());
        }
        if (repo.findDispenseByPrescription(prescriptionId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该处方已发过药，不能重复发药");
        }

        List<PrescriptionItem> items = repo.listPrescriptionItems(prescriptionId);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.PRESCRIPTION_STATUS_INVALID, "处方没有明细，不能发药");
        }
        Set<UUID> narcoticItemIds = narcoticItemIds(items);
        if (!narcoticItemIds.isEmpty()) {
            requireDoubleCheck(checker, actor);
        }

        UUID dispenseId = UUID.randomUUID();
        LocalDate today = BusinessTime.today();
        List<DispenseItem> dispensed = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (PrescriptionItem item : items) {
            int remaining = item.quantity();
            // FEFO：效期最近的批次先出；SQL 已过滤过期与零库存，这里再按业务日防御一次
            for (DrugBatch batch : repo.listAvailableBatches(item.drugId(), today)) {
                if (remaining <= 0) {
                    break;
                }
                if (!batch.availableOn(today)) {
                    continue;
                }
                int take = Math.min(remaining, batch.quantity());
                if (!repo.deductBatch(batch.id(), take)) {
                    // 并发窗口抢走了库存：整笔回滚，提示重试（不能部分发药）
                    throw new BusinessException(ErrorCode.CONFLICT,
                            "药品 " + item.drugName() + " 库存已被其他窗口占用，请重试");
                }
                BigDecimal amount = item.unitPrice()
                        .multiply(BigDecimal.valueOf(take))
                        .setScale(2, RoundingMode.HALF_UP);
                dispensed.add(new DispenseItem(UUID.randomUUID(), dispenseId, item.id(), item.drugId(),
                        batch.id(), batch.batchNo(), batch.expireDate(), take, 0,
                        item.unitPrice(), amount));
                total = total.add(amount);
                remaining -= take;
            }
            if (remaining > 0) {
                throw new BusinessException(ErrorCode.DRUG_STOCK_INSUFFICIENT,
                        "药品 " + item.drugName() + "（" + item.spec() + "）库存不足，还差 "
                                + remaining + " " + item.unit());
            }
        }

        String billingStatus = rx.chargeOnDispense()
                ? Dispense.BILLING_PENDING : Dispense.BILLING_NONE;
        Dispense dispense = new Dispense(dispenseId, repo.nextDispenseNo(), prescriptionId, actor,
                blankToNull(checker), Dispense.DISPENSED, total, null, 0L, billingStatus);
        try {
            repo.insertDispense(dispense);
        } catch (DataIntegrityViolationException e) {
            // 并发发药：dispense.prescription_id 唯一键兜底（事务回滚会恢复已扣库存）
            throw new BusinessException(ErrorCode.CONFLICT, "该处方已被其他窗口发药，请刷新后重试");
        }
        for (DispenseItem item : dispensed) {
            repo.insertDispenseItem(item);
        }
        if (!repo.updatePrescriptionStatus(prescriptionId, Prescription.APPROVED, Prescription.DISPENSED,
                rx.version(), null, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "处方状态已变化，请刷新后重试");
        }
        // 麻醉药品专册登记：与发药同事务，发药成功必然有册可查
        int narcoticRegistered = registerNarcotics(rx, items, dispensed, narcoticItemIds,
                actor, checker);

        audit(actor, "DISPENSE", "PRESCRIPTION", prescriptionId.toString(), Map.of(
                "prescriptionNo", rx.prescriptionNo(), "dispenseNo", dispense.dispenseNo(),
                "batchCount", dispensed.size(), "totalAmount", total,
                "checker", dispense.checker() == null ? "" : dispense.checker(),
                "narcoticRegistered", narcoticRegistered,
                "billingMode", rx.billingMode() == null ? Prescription.BILLING_ORDER : rx.billingMode()));
        // 计费在本地事务提交后执行：失败不回滚发药事实，置 CHARGE_FAILED 供补偿重试
        if (rx.chargeOnDispense()) {
            billing.scheduleCharge(dispense, rx.visitId(), dispensed, actor);
        }
        return dispenseDetail(dispenseId);
    }

    // ---------------------------------------------------------------- 退药

    @Transactional
    public DispenseView returnDrug(UUID dispenseId, ReturnReq req, String actor) {
        Dispense dispense = repo.lockDispense(dispenseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPENSE_NOT_FOUND));
        DispenseItem item = repo.findDispenseItem(req.dispenseItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPENSE_NOT_FOUND, "未找到该发药明细"));
        if (!item.dispenseId().equals(dispenseId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该发药明细不属于此发药单");
        }

        // 幂等优先：重试必须回到同一条退药记录。若把幂等判断放在"数量/状态"校验之后，
        // 第一次已把该明细退满，重试会被误判成"超过可退数量"（422），护士只能人工核对。
        UUID returnId = req.returnId();
        if (returnId == null) {
            // 契约层已 @NotNull；这里保留服务层兜底，避免直接调用方绕过校验后失去幂等
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "退药必须携带幂等键 returnId（重试要回到同一条退药记录）");
        }
        var existing = repo.findReturn(returnId);
        if (existing.isPresent()) {
            DispenseReturn ret = existing.get();
            if (!ret.dispenseId().equals(dispenseId)
                    || !ret.dispenseItemId().equals(req.dispenseItemId())
                    || ret.quantity() != req.quantity()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该退药单号已登记（发药单/明细/数量不同），请核对后刷新");
            }
            return dispenseDetail(dispenseId);
        }

        if (req.quantity() > item.returnable()) {
            throw new BusinessException(ErrorCode.DRUG_RETURN_EXCEEDS,
                    "退药数量超过可退数量（已发 " + item.quantity() + "，已退 "
                            + item.returnedQuantity() + "）");
        }
        if (!repo.addReturnedQuantity(item.id(), req.quantity())) {
            throw new BusinessException(ErrorCode.CONFLICT, "退药数量已变化，请刷新后重试");
        }
        // 回补原批次：保持批次可追溯，不新建批次
        if (!repo.addBatchQuantity(item.batchId(), req.quantity())) {
            throw new BusinessException(ErrorCode.CONFLICT, "原批次不存在，退药失败");
        }
        try {
            repo.insertReturn(new DispenseReturn(returnId, dispenseId, item.id(),
                    req.quantity(), req.reason().trim(), actor, null));
        } catch (DataIntegrityViolationException e) {
            // 并发双击：另一事务已用同一 returnId 写成功。整笔回滚（含库存回补），
            // 让调用方按幂等语义重新查询，而不是留下两笔库存流水。
            throw new BusinessException(ErrorCode.CONFLICT, "该退药单号正在处理中，请刷新后查看");
        }

        List<DispenseItem> all = repo.listDispenseItems(dispenseId);
        boolean allReturned = all.stream().allMatch(i -> i.returnedQuantity() >= i.quantity());
        boolean anyReturned = all.stream().anyMatch(i -> i.returnedQuantity() > 0);
        String next = allReturned ? Dispense.RETURNED
                : (anyReturned ? Dispense.PARTIAL_RETURNED : Dispense.DISPENSED);
        if (!next.equals(dispense.status())
                && !repo.updateDispenseStatus(dispenseId, next, dispense.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "发药单状态已变化，请刷新后重试");
        }
        // 全部退回 = 该处方已无在途药品：同步作废处方，否则临床停嘱/撤销的下游作废永远 409
        if (allReturned && repo.returnPrescription(dispense.prescriptionId(),
                "发药单 " + dispense.dispenseNo() + " 全部退回，自动作废处方", actor)) {
            audit(actor, "RX_CANCEL_ON_FULL_RETURN", "PRESCRIPTION",
                    dispense.prescriptionId().toString(),
                    Map.of("dispenseNo", dispense.dispenseNo()));
        }

        audit(actor, "DISPENSE_RETURN", "DISPENSE", dispenseId.toString(), Map.of(
                "dispenseNo", dispense.dispenseNo(), "batchNo", item.batchNo(),
                "quantity", req.quantity(), "amount", item.unitPrice()
                        .multiply(BigDecimal.valueOf(req.quantity())).setScale(2, RoundingMode.HALF_UP)));
        // 发药计费模式：退药后按"累计已退金额"冲减（未结算改账 / 已结算登记待退费）
        Prescription rx = repo.findPrescription(dispense.prescriptionId()).orElse(null);
        if (rx != null && rx.chargeOnDispense() && billable(dispense.billingStatus())) {
            billing.scheduleRefund(dispenseId, all, actor);
        } else if (rx != null && rx.sourceOrderId() != null) {
            // 医嘱计费模式（B3）：钱在临床结算时收，退药必须回冲医嘱金额，否则患者白付钱。
            // 长嘱必须带上 supplyDate，临床才能定位当日 DAILY:{orderId}:{date} 明细（P0-1）。
            billing.scheduleOrderRefund(dispenseId, rx.sourceOrderId(), rx.supplyDate(), all, actor);
        }
        return dispenseDetail(dispenseId);
    }

    /**
     * 病区退药（临床→药房，内网接口）：把"病区未执行的药"退回药房批次库存。
     *
     * <p>为什么必须由药房分摊：一次发药可能跨多个批次（FEFO），临床只认识"医嘱 + 药品编码"，
     * 只有药房知道该药发在哪些批次、每批还能退多少。
     *
     * <p>幂等：子退药单号由 {@code returnId + 发药明细 id} 派生，同一次病区退药重放时，
     * 已处理过的明细会被跳过、未处理完的继续退——既不会重复回补库存，也不会因为
     * "上一轮只退了一半"而永久卡住。
     *
     * <p>钱：复用 {@link #returnDrug}，因此退药会自动回冲医嘱金额（B3 链路），
     * 病区不再需要人工再去点一次"医嘱退费"。
     */
    @Transactional
    public WardReturnView returnWardDrug(WardReturnReq req, String actor) {
        Prescription rx = repo.findPrescriptionBySourceOrder(req.orderId(), req.supplyDate())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND,
                        "该医嘱 " + req.supplyDate() + " 没有处方，无法退回药房"));
        Dispense dispense = repo.findDispenseByPrescription(rx.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPENSE_NOT_FOUND,
                        "该处方尚未发药，无需药房退药"));
        List<DispenseItem> items = repo.listDispenseItems(dispense.id());
        Set<UUID> drugIds = new HashSet<>();
        for (PrescriptionItem item : repo.listPrescriptionItems(rx.id())) {
            if (req.drugCode().equals(item.drugCode())) {
                drugIds.add(item.drugId());
            }
        }
        if (drugIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "药品 " + req.drugCode() + " 不在该处方中，无法退回药房");
        }
        String reason = req.reason() == null || req.reason().isBlank()
                ? "病区退药" : req.reason().trim();

        int pending = req.quantity();
        int returned = 0;
        for (DispenseItem item : items) {
            if (pending <= 0) {
                break;
            }
            if (!drugIds.contains(item.drugId())) {
                continue;
            }
            UUID subReturnId = subReturnId(req.returnId(), item.id());
            Optional<DispenseReturn> applied = repo.findReturn(subReturnId);
            if (applied.isPresent()) {
                // 上一次调用已处理该批次（可能在其他批次中途失败后重试）
                pending -= applied.get().quantity();
                continue;
            }
            int take = Math.min(pending, item.returnable());
            if (take <= 0) {
                continue;
            }
            returnDrug(dispense.id(), new ReturnReq(item.id(), take, reason, subReturnId), actor);
            pending -= take;
            returned += take;
        }
        if (pending > 0) {
            throw new BusinessException(ErrorCode.DRUG_RETURN_EXCEEDS,
                    "药房可退数量不足（还差 " + pending
                            + "）：该药品可能已使用或已被其他退药单退回，请核对后处理");
        }
        int remaining = repo.listDispenseItems(dispense.id()).stream()
                .filter(i -> drugIds.contains(i.drugId()))
                .mapToInt(DispenseItem::returnable)
                .sum();
        return new WardReturnView(dispense.id(), req.drugCode(), returned, remaining);
    }

    /** 病区退药 → 子退药单号（稳定派生：同一病区退药单 + 同一发药明细永远得到同一个 id）。 */
    private static UUID subReturnId(UUID returnId, UUID dispenseItemId) {
        return UUID.nameUUIDFromBytes((returnId + ":" + dispenseItemId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- 查询
    public PrescriptionView detail(UUID prescriptionId) {
        Prescription rx = repo.findPrescription(prescriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        return new PrescriptionView(rx, repo.listPrescriptionItems(prescriptionId));
    }

    public DispenseView dispenseDetail(UUID dispenseId) {
        Dispense dispense = repo.findDispense(dispenseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DISPENSE_NOT_FOUND));
        return new DispenseView(dispense, repo.listDispenseItems(dispenseId), repo.listReturns(dispenseId));
    }

    /**
     * 按处方查发药单：药房窗口刷新后仍能对已发药处方做退药/核对，不需要另行记住发药单号。
     *
     * <p>处方不存在时抛 404（与其它处方接口一致）；处方存在但尚未发药时返回 {@code null}，
     * 前端据此展示"尚未发药"而非报错。两者语义必须区分，否则客户端拼错 ID 会静默显示为空。
     */
    public DispenseView dispenseByPrescription(UUID prescriptionId) {
        if (repo.findPrescription(prescriptionId).isEmpty()) {
            throw new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND);
        }
        return repo.findDispenseByPrescription(prescriptionId)
                .map(d -> dispenseDetail(d.id()))
                .orElse(null);
    }

    public List<Prescription> list(String status, UUID patientId, Integer limit) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listPrescriptions(normalized, patientId, size);
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 麻醉/精神类双人核对。
     *
     * <p>以当前字典为准而不是处方快照——药品被新标记为麻醉类时，历史处方发药也应触发双人核对（安全优先）。
     */
    private void requireDoubleCheck(String checker, String actor) {
        if (checker == null || checker.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "含麻醉/精神类药品的处方必须双人核对：请提供核对人（checker）");
        }
        if (checker.trim().equals(actor)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "核对人不能与发药人相同（双人核对要求两名不同药师）");
        }
    }

    /** 处方中麻醉药品明细的 id 集合（逐条查当前字典，明细通常 ≤10 条）。 */
    private Set<UUID> narcoticItemIds(List<PrescriptionItem> items) {
        Set<UUID> ids = new HashSet<>();
        for (PrescriptionItem item : items) {
            if (repo.findDrug(item.drugId()).map(Drug::narcotic).orElse(false)) {
                ids.add(item.id());
            }
        }
        return ids;
    }

    /**
     * 开方前的麻醉药品门槛：处方权名单 + 上次空安瓿已回收。
     *
     * <p>放在审方之前：没有处方权时连审方都不必跑，错误信息也更贴近根因。
     */
    private void requireNarcoticLicence(List<Drug> drugs, PatientClient.PatientRef patient,
                                        String actor) {
        List<Drug> narcotics = drugs.stream().filter(Drug::narcotic).toList();
        if (narcotics.isEmpty()) {
            return;
        }
        narcoticService.requireLicensed(actor);
        for (Drug drug : narcotics) {
            narcoticService.requireAmpouleReturned(patient.id(), drug);
        }
    }

    /** 按发药明细写专册（一条明细一行），返回登记条数。 */
    private int registerNarcotics(Prescription rx, List<PrescriptionItem> items,
                                  List<DispenseItem> dispensed, Set<UUID> narcoticItemIds,
                                  String actor, String checker) {
        if (narcoticItemIds.isEmpty()) {
            return 0;
        }
        Map<UUID, PrescriptionItem> byId = new LinkedHashMap<>();
        for (PrescriptionItem item : items) {
            byId.put(item.id(), item);
        }
        String safeChecker = checker == null ? "" : checker.trim();
        java.time.Instant at = java.time.Instant.now();
        int count = 0;
        for (DispenseItem di : dispensed) {
            PrescriptionItem pi = byId.get(di.prescriptionItemId());
            if (pi == null || !narcoticItemIds.contains(pi.id())) {
                continue;
            }
            repo.insertNarcoticRegister(new NarcoticRegister(UUID.randomUUID(), di.id(),
                    di.dispenseId(), rx.id(), rx.prescriptionNo(), rx.patientId(), rx.patientNo(),
                    pi.drugId(), pi.drugCode(), pi.drugName(), pi.spec(), di.quantity(), pi.unit(),
                    di.batchNo(), rx.prescriber(), actor, safeChecker, at, 0, null, null,
                    NarcoticRegister.PENDING_RETURN, 0L, at));
            count++;
        }
        return count;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 患者年龄（出生日期缺失时返回 null，年龄相关规则跳过）。 */
    private static Integer ageOf(PatientClient.PatientRef patient) {
        if (patient == null || patient.birthDate() == null) {
            return null;
        }
        return java.time.Period.between(patient.birthDate(), BusinessTime.today()).getYears();
    }

    /** 结构化剂量三项要么全给、要么全不给（避免"只填单次剂量"导致日剂量算错）。 */
    private static void validateDose(PrescriptionLine line) {
        boolean anyDose = line.singleDose() != null
                || (line.doseUnit() != null && !line.doseUnit().isBlank())
                || line.timesPerDay() != null;
        boolean complete = line.singleDose() != null
                && line.doseUnit() != null && !line.doseUnit().isBlank()
                && line.timesPerDay() != null;
        if (anyDose && !complete) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "结构化剂量需同时提供单次剂量、单位与每日次数（药品：" + line.drugCode() + "）");
        }
    }

    /** 日剂量 = 单次剂量 × 每日次数（与数据库生成列同口径）。 */
    private static BigDecimal dailyDoseOf(PrescriptionLine line) {
        if (line.singleDose() == null || line.timesPerDay() == null) {
            return null;
        }
        return line.singleDose().multiply(BigDecimal.valueOf(line.timesPerDay()))
                .setScale(3, RoundingMode.HALF_UP);
    }

    private static String blockMessages(List<PrescriptionAuditService.Finding> findings) {
        return findings.stream()
                .filter(f -> PrescriptionAudit.LEVEL_BLOCK.equals(f.level()))
                .map(PrescriptionAuditService.Finding::message)
                .distinct()
                .collect(java.util.stream.Collectors.joining("；"));
    }

    /** 审方记录查询（药师端展示）。 */
    public com.medassist.pharmacy.web.PharmacyDtos.PrescriptionAuditView auditView(UUID prescriptionId) {
        repo.findPrescription(prescriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRESCRIPTION_NOT_FOUND));
        return com.medassist.pharmacy.web.PharmacyDtos.PrescriptionAuditView
                .of(prescriptionId, auditService.list(prescriptionId));
    }

    /**
     * 计费模式归一化：默认 ORDER（由临床医嘱计价）。
     *
     * <p>DISPENSE 必须带 visitId——没有就诊就没有收费单可记，宁可开方时直接拒绝。
     */
    private static String billingModeOf(String requested, UUID visitId) {
        String mode = requested == null || requested.isBlank()
                ? Prescription.BILLING_ORDER : requested.trim().toUpperCase();
        if (Prescription.BILLING_DISPENSE.equals(mode) && visitId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "发药计费（billingMode=DISPENSE）必须关联临床就诊 visitId");
        }
        return mode;
    }

    /** 只有已计费/已有待退费的发药单才需要冲减；NONE/PENDING/CHARGE_FAILED 没有可冲的账。 */
    private static boolean billable(String billingStatus) {
        return Dispense.BILLING_CHARGED.equals(billingStatus)
                || Dispense.BILLING_REFUND_PENDING.equals(billingStatus)
                || Dispense.BILLING_REFUND_FAILED.equals(billingStatus);
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
