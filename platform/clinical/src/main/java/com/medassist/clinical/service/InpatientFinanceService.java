package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.ClinicalDtos.InpatientFinanceView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 住院费用看板与中途欠费预警。
 *
 * <p>口径（必须在所有报表里一致）：
 * <ul>
 *   <li>已计 = 该就诊全部收费单（含中途已结单）金额之和；</li>
 *   <li>已结 = status=PAID 的收费单金额之和；</li>
 *   <li>未结 = status=UNPAID 的收费单金额之和（单张，由部分唯一索引保证）；</li>
 *   <li>待计 = 尚未计费的临时医嘱金额（长嘱走按日计费，不在此列）；</li>
 *   <li>缺口 = max(0, 未结 + 待计 − 预交金余额)，大于 0 即触发欠费预警。</li>
 * </ul>
 */
@Service
public class InpatientFinanceService {

    private final ClinicalRepository repo;

    public InpatientFinanceService(ClinicalRepository repo) {
        this.repo = repo;
    }

    public InpatientFinanceView finance(UUID admissionId) {
        Admission admission = repo.findAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        return build(admission, LocalDate.now());
    }

    /** 欠费预警：只返回缺口大于 0 的在院患者，按缺口倒序（缺口大的先处理）。 */
    public List<InpatientAlert> alerts(UUID wardId) {
        if (wardId != null) {
            repo.findWard(wardId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "病区不存在"));
        }
        return repo.listActiveAdmissions(wardId).stream()
                .map(this::buildAlert)
                .filter(a -> a.shortfall().signum() > 0)
                .sorted(Comparator.comparing(InpatientAlert::shortfall).reversed())
                .toList();
    }

    // ---------------------------------------------------------------- 内部

    private InpatientFinanceView build(Admission admission, LocalDate date) {
        Visit visit = repo.findVisit(admission.visitId()).orElse(null);
        Ward ward = repo.findWard(admission.wardId()).orElse(null);
        Bed bed = admission.bedId() == null ? null : repo.findBed(admission.bedId()).orElse(null);

        List<Charge> charges = repo.listCharges(admission.visitId());
        BigDecimal billedTotal = sumCharges(charges);
        BigDecimal settledTotal = sumCharges(charges.stream()
                .filter(c -> Charge.PAID.equals(c.status())).toList());
        BigDecimal unpaidTotal = sumCharges(charges.stream()
                .filter(c -> Charge.UNPAID.equals(c.status())).toList());

        List<VisitOrder> pending = repo.listOrders(admission.visitId(), VisitOrder.PLACED).stream()
                .filter(o -> !o.longTerm())
                .toList();
        BigDecimal pendingAmount = sumOrders(pending);

        List<VisitOrder> longOrders = repo.listBillableLongOrders(admission.visitId(), date).stream()
                .filter(o -> o.billableOn(date))
                .toList();
        BigDecimal dailyLongAmount = sumOrders(longOrders);

        BigDecimal depositBalance = repo.depositBalance(admission.id());
        BigDecimal shortfall = unpaidTotal.add(pendingAmount).subtract(depositBalance).max(BigDecimal.ZERO);

        return new InpatientFinanceView(admission.id(), admission.admissionNo(),
                admission.visitId(), visit == null ? "" : visit.visitNo(), admission.patientNo(),
                admission.wardId(), ward == null ? "" : ward.name(),
                admission.bedId(), bed == null ? "" : bed.bedNo(), admission.status(),
                admission.admittedAt(),
                billedTotal, settledTotal, unpaidTotal, depositBalance,
                pendingAmount, pending.size(),
                dailyLongAmount, longOrders.size(),
                repo.sumDailyBilled(admission.visitId(), date),
                shortfall, shortfall.signum() > 0);
    }

    private InpatientAlert buildAlert(Admission admission) {
        List<Charge> charges = repo.listCharges(admission.visitId());
        BigDecimal unpaidTotal = sumCharges(charges.stream()
                .filter(c -> Charge.UNPAID.equals(c.status())).toList());
        BigDecimal pendingAmount = sumOrders(repo.listOrders(admission.visitId(), VisitOrder.PLACED).stream()
                .filter(o -> !o.longTerm())
                .toList());
        BigDecimal depositBalance = repo.depositBalance(admission.id());
        BigDecimal shortfall = unpaidTotal.add(pendingAmount).subtract(depositBalance).max(BigDecimal.ZERO);
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
}
