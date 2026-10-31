package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.time.BusinessTime;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.ClinicalDtos.InpatientFinanceView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 住院费用看板与中途欠费预警。
 *
 * <p>口径（必须在所有报表里一致）：
 * <ul>
 *   <li>已计 = 该就诊全部收费单（含中途已结单）金额之和；</li>
 *   <li>已结 = status=PAID 的收费单金额之和；</li>
 *   <li>未结 = status=UNPAID 的收费单金额之和（单张，由部分唯一索引保证）；</li>
 *   <li>待计 = 尚未计费的<strong>临时</strong>医嘱金额；</li>
 *   <li>当日未计长嘱 = 当日有效、尚未按日计费且未被护理标记为未执行的长嘱金额；</li>
 *   <li>缺口 = max(0, 未结 + 待计 + 当日未计长嘱 − 预交金余额)，大于 0 即触发欠费预警。</li>
 * </ul>
 *
 * <p>把"当日未计长嘱"计入缺口，是为了避免欠费预警比实际消费晚一天。
 */
@Service
public class InpatientFinanceService {

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public InpatientFinanceService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 费用看板（读操作也落审计：等保要求"谁查看了费用数据"可追溯）。 */
    public InpatientFinanceView finance(UUID admissionId, String actor) {
        Admission admission = repo.findAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        InpatientFinanceView view = build(admission, BusinessTime.today());
        audit(actor, "FINANCE_READ", admissionId.toString(), Map.of(
                "admissionNo", admission.admissionNo(), "wardId", String.valueOf(admission.wardId()),
                "shortfall", view.shortfall()));
        return view;
    }

    /**
     * 欠费预警：只返回缺口大于 0 的在院患者，按缺口倒序（缺口大的先处理）。
     *
     * <p>数据范围：{@code wardId} 为空仅允许"全院查看"权限持有者调用（由 Controller 判定），
     * 其余角色必须指定自己负责的病区，避免任一护士枚举全院欠费患者。
     */
    public List<InpatientAlert> alerts(UUID wardId, String actor) {
        if (wardId != null) {
            repo.findWard(wardId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "病区不存在"));
        }
        List<InpatientAlert> list = repo.listActiveAdmissions(wardId).stream()
                .map(this::buildAlert)
                .filter(a -> a.shortfall().signum() > 0)
                .sorted(Comparator.comparing(InpatientAlert::shortfall).reversed())
                .toList();
        audit(actor, "ALERTS_READ", wardId == null ? "ALL" : wardId.toString(), Map.of(
                "wardId", String.valueOf(wardId), "count", list.size()));
        return list;
    }

    // ---------------------------------------------------------------- 内部

    private InpatientFinanceView build(Admission admission, LocalDate date) {
        Visit visit = repo.findVisit(admission.visitId()).orElse(null);
        Ward ward = repo.findWard(admission.wardId()).orElse(null);
        Bed bed = admission.bedId() == null ? null : repo.findBed(admission.bedId()).orElse(null);

        List<Charge> charges = repo.listCharges(admission.visitId());
        BigDecimal billedTotal = sumCharges(charges.stream()
                .filter(c -> !Charge.REFUNDED.equals(c.status())).toList());
        BigDecimal settledTotal = sumCharges(charges.stream()
                .filter(c -> Charge.PAID.equals(c.status())).toList());
        BigDecimal unpaidTotal = sumCharges(charges.stream()
                .filter(c -> Charge.UNPAID.equals(c.status())).toList());

        List<VisitOrder> pending = repo.listOrders(admission.visitId(), VisitOrder.PLACED).stream()
                .filter(o -> !o.longTerm())
                .toList();
        BigDecimal pendingAmount = sumOrders(pending);

        List<VisitOrder> unbilledLong = repo.listUnbilledDailyOrders(admission.visitId(), date);
        BigDecimal unbilledLongAmount = sumOrders(unbilledLong);

        BigDecimal depositBalance = repo.depositBalance(admission.id());
        BigDecimal shortfall = unpaidTotal.add(pendingAmount).add(unbilledLongAmount)
                .subtract(depositBalance).max(BigDecimal.ZERO);

        return new InpatientFinanceView(admission.id(), admission.admissionNo(),
                admission.visitId(), visit == null ? "" : visit.visitNo(), admission.patientNo(),
                admission.wardId(), ward == null ? "" : ward.name(),
                admission.bedId(), bed == null ? "" : bed.bedNo(), admission.status(),
                admission.admittedAt(),
                billedTotal, settledTotal, unpaidTotal, depositBalance,
                pendingAmount, pending.size(),
                unbilledLongAmount, unbilledLong.size(),
                repo.sumDailyBilled(admission.visitId(), date),
                shortfall, shortfall.signum() > 0);
    }

    private InpatientAlert buildAlert(Admission admission) {
        LocalDate today = BusinessTime.today();
        List<Charge> charges = repo.listCharges(admission.visitId());
        BigDecimal unpaidTotal = sumCharges(charges.stream()
                .filter(c -> Charge.UNPAID.equals(c.status())).toList());
        BigDecimal pendingAmount = sumOrders(repo.listOrders(admission.visitId(), VisitOrder.PLACED).stream()
                .filter(o -> !o.longTerm())
                .toList());
        BigDecimal unbilledLongAmount = sumOrders(repo.listUnbilledDailyOrders(admission.visitId(), today));
        BigDecimal depositBalance = repo.depositBalance(admission.id());
        BigDecimal shortfall = unpaidTotal.add(pendingAmount).add(unbilledLongAmount)
                .subtract(depositBalance).max(BigDecimal.ZERO);
        Bed bed = admission.bedId() == null ? null : repo.findBed(admission.bedId()).orElse(null);
        return new InpatientAlert(admission.id(), admission.admissionNo(), admission.patientNo(),
                admission.bedId(), bed == null ? "" : bed.bedNo(),
                unpaidTotal, pendingAmount, depositBalance, shortfall);
    }

    private static BigDecimal sumCharges(List<Charge> charges) {
        return charges.stream().map(Charge::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumOrders(List<VisitOrder> orders) {
        return orders.stream().map(VisitOrder::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "ADMISSION", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "ADMISSION", targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
