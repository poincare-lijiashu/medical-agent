package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.DischargeMedication;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.VisitOrder;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.DischargeCheckItem;
import com.medassist.clinical.web.ClinicalDtos.DischargeCheckView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 出院前一致性体检（只读）。
 *
 * <p>设计原则：
 * <ul>
 *   <li><strong>与门禁同源</strong>：阻断项必须与 {@link InpatientService#discharge} 的校验一一对应，
 *       否则界面结论与真实结果会打架；</li>
 *   <li><strong>只读</strong>：不写业务表、不改状态、不触发计费，只落一条查看审计；</li>
 *   <li><strong>不装懂</strong>：本服务核查不到的（病历归档）显式返回 UNKNOWN，
 *       宁可显示"未核查"，也不给一个假的"通过"。</li>
 * </ul>
 */
@Service
public class DischargeCheckService {

    public static final String RESULT_PASS = "PASS";
    public static final String RESULT_FAIL = "FAIL";
    public static final String RESULT_UNKNOWN = "UNKNOWN";

    public static final String SEVERITY_BLOCKER = "BLOCKER";
    public static final String SEVERITY_WARNING = "WARNING";
    public static final String SEVERITY_INFO = "INFO";

    public static final String CODE_ADMISSION_STATUS = "ADMISSION_STATUS";
    public static final String CODE_UNSETTLED_CHARGE = "UNSETTLED_CHARGE";
    public static final String CODE_UNBILLED_TEMP_ORDER = "UNBILLED_TEMP_ORDER";
    public static final String CODE_UNBILLED_DAILY_ORDER = "UNBILLED_DAILY_ORDER";
    public static final String CODE_UNSTOPPED_LONG_ORDER = "UNSTOPPED_LONG_ORDER";
    public static final String CODE_DISCHARGE_MED_PENDING = "DISCHARGE_MED_PENDING";
    public static final String CODE_EMR_ARCHIVE = "EMR_ARCHIVE";

    /** 长嘱预览最多列出的条数：再多界面也放不下，条数本身已经给出。 */
    private static final int ORDER_PREVIEW_LIMIT = 5;

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public DischargeCheckService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public DischargeCheckView check(UUID admissionId, String actor) {
        Admission admission = repo.findAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        Visit visit = repo.findVisit(admission.visitId()).orElse(null);
        LocalDate today = BusinessTime.today();

        List<DischargeCheckItem> items = new ArrayList<>();
        items.add(admissionStatusItem(admission));
        items.add(unsettledChargeItem(admission.visitId()));
        items.add(unbilledTempOrderItem(admission.visitId()));
        items.add(unbilledDailyOrderItem(admission, today));
        items.add(unstoppedLongOrderItem(admission.visitId()));
        items.add(dischargeMedicationItem(admission.id()));
        items.add(emrArchiveItem());

        int blockers = count(items, SEVERITY_BLOCKER, RESULT_FAIL);
        int warnings = count(items, SEVERITY_WARNING, RESULT_FAIL);
        int unchecked = (int) items.stream().filter(i -> RESULT_UNKNOWN.equals(i.result())).count();

        DischargeCheckView view = new DischargeCheckView(admission.id(), admission.admissionNo(),
                admission.visitId(), visit == null ? "" : visit.visitNo(), admission.patientNo(),
                admission.status(), blockers == 0, blockers, warnings, unchecked,
                Instant.now(), List.copyOf(items));
        audit(actor, admission.id().toString(), view);
        return view;
    }

    // ---------------------------------------------------------------- 条目

    private DischargeCheckItem admissionStatusItem(Admission admission) {
        boolean inHospital = Admission.IN_HOSPITAL.equals(admission.status());
        return new DischargeCheckItem(CODE_ADMISSION_STATUS, "ADMISSION", SEVERITY_BLOCKER,
                inHospital ? RESULT_PASS : RESULT_FAIL, "住院状态",
                inHospital ? "住院登记为在院状态"
                        : "当前状态为 " + admission.status() + "，只有在院（IN_HOSPITAL）可以办理出院",
                inHospital ? null : "请刷新住院列表确认当前状态，已出院的患者不能重复办理",
                null, null);
    }

    private DischargeCheckItem unsettledChargeItem(UUID visitId) {
        List<Charge> unpaid = repo.listCharges(visitId).stream()
                .filter(c -> Charge.UNPAID.equals(c.status()) && c.totalAmount().signum() > 0)
                .toList();
        if (unpaid.isEmpty()) {
            return new DischargeCheckItem(CODE_UNSETTLED_CHARGE, "BILLING", SEVERITY_BLOCKER,
                    RESULT_PASS, "未结算费用", "无有金额的未结算收费单", null, 0, BigDecimal.ZERO);
        }
        BigDecimal total = sumCharges(unpaid);
        return new DischargeCheckItem(CODE_UNSETTLED_CHARGE, "BILLING", SEVERITY_BLOCKER,
                RESULT_FAIL, "未结算费用",
                "存在 " + unpaid.size() + " 张未结算收费单，合计 " + yuan(total) + " 元",
                "在收费/结算台结清后再办理出院", unpaid.size(), total);
    }

    private DischargeCheckItem unbilledTempOrderItem(UUID visitId) {
        List<VisitOrder> pending = repo.listOrders(visitId, VisitOrder.PLACED).stream()
                .filter(o -> !o.longTerm())
                .toList();
        if (pending.isEmpty()) {
            return new DischargeCheckItem(CODE_UNBILLED_TEMP_ORDER, "ORDER", SEVERITY_BLOCKER,
                    RESULT_PASS, "临时医嘱计费", "无未计费的临时医嘱", null, 0, BigDecimal.ZERO);
        }
        BigDecimal total = sumOrders(pending);
        return new DischargeCheckItem(CODE_UNBILLED_TEMP_ORDER, "ORDER", SEVERITY_BLOCKER,
                RESULT_FAIL, "临时医嘱计费",
                "还有 " + pending.size() + " 条临时医嘱未计费，合计 " + yuan(total) + " 元",
                "在医嘱工作台完成计费（不需要的医嘱请先撤销）", pending.size(), total);
    }

    private DischargeCheckItem unbilledDailyOrderItem(Admission admission, LocalDate today) {
        LocalDate firstDay = firstBillableDay(admission, today);
        int unbilled = repo.countUnbilledDailyOrders(admission.visitId(), firstDay, today);
        if (unbilled == 0) {
            return new DischargeCheckItem(CODE_UNBILLED_DAILY_ORDER, "BILLING", SEVERITY_BLOCKER,
                    RESULT_PASS, "长期医嘱按日计费", "长期医嘱已按日计费至 " + today, null, 0, null);
        }
        return new DischargeCheckItem(CODE_UNBILLED_DAILY_ORDER, "BILLING", SEVERITY_BLOCKER,
                RESULT_FAIL, "长期医嘱按日计费",
                "有 " + unbilled + " 条长期医嘱在 " + firstDay + " 至 " + today + " 存在未按日计费",
                "补跑按日计费（先核对护理执行记录）后再办理出院", unbilled, null);
    }

    private DischargeCheckItem unstoppedLongOrderItem(UUID visitId) {
        List<VisitOrder> unstopped = repo.listUnstoppedLongOrders(visitId);
        if (unstopped.isEmpty()) {
            return new DischargeCheckItem(CODE_UNSTOPPED_LONG_ORDER, "ORDER", SEVERITY_WARNING,
                    RESULT_PASS, "长期医嘱停止", "长期医嘱均已停止", null, 0, null);
        }
        String preview = unstopped.stream().limit(ORDER_PREVIEW_LIMIT)
                .map(VisitOrder::orderNo)
                .reduce((a, b) -> a + "、" + b).orElse("");
        String more = unstopped.size() > ORDER_PREVIEW_LIMIT ? " 等" : "";
        return new DischargeCheckItem(CODE_UNSTOPPED_LONG_ORDER, "ORDER", SEVERITY_WARNING,
                RESULT_FAIL, "长期医嘱停止",
                "仍有 " + unstopped.size() + " 条长期医嘱未停止（" + preview + more + "）",
                "出院时系统会按出院日自动停止；如需更早的停嘱时间，请先在医嘱工作台停嘱",
                unstopped.size(), null);
    }

    private DischargeCheckItem dischargeMedicationItem(UUID admissionId) {
        DischargeMedication med = repo.findActiveDischargeMedication(admissionId).orElse(null);
        if (med == null) {
            return new DischargeCheckItem(CODE_DISCHARGE_MED_PENDING, "PHARMACY", SEVERITY_WARNING,
                    RESULT_PASS, "出院带药", "无待处理的出院带药", null, 0, null);
        }
        boolean draft = DischargeMedication.DRAFT.equals(med.status());
        String detail = draft
                ? "出院带药单 " + med.medicationNo() + " 尚未提交"
                : "出院带药单 " + med.medicationNo() + " 已提交但未发药，金额 "
                        + yuan(med.totalAmount()) + " 元";
        String next = draft ? "在出院带药页提交或取消该单" : "在药房完成发药/结算后再办理出院";
        return new DischargeCheckItem(CODE_DISCHARGE_MED_PENDING, "PHARMACY", SEVERITY_WARNING,
                RESULT_FAIL, "出院带药", detail, next, 1, med.totalAmount());
    }

    /**
     * 病历归档：clinical 未接入 emr，固定返回 UNKNOWN。
     *
     * <p>为什么不隐藏这一条：出院时最容易漏的就是病历归档，界面至少要让医护知道
     * "这一项本系统没有替你查"，而不是让它从清单里消失、被默认当作通过。
     */
    private DischargeCheckItem emrArchiveItem() {
        return new DischargeCheckItem(CODE_EMR_ARCHIVE, "EMR", SEVERITY_INFO, RESULT_UNKNOWN,
                "病历归档", "临床核心服务未接入电子病历，未核查病历与病案首页是否已归档",
                "请在电子病历系统确认病历已归档", null, null);
    }

    // ---------------------------------------------------------------- 内部

    /** 计费起始日与出院门禁一致：入院日（未来入院按今天封顶）。 */
    private static LocalDate firstBillableDay(Admission admission, LocalDate today) {
        if (admission.admittedAt() == null) {
            return today;
        }
        LocalDate first = admission.admittedAt().atZone(BusinessTime.zone()).toLocalDate();
        return first.isAfter(today) ? today : first;
    }

    private static int count(List<DischargeCheckItem> items, String severity, String result) {
        return (int) items.stream()
                .filter(i -> severity.equals(i.severity()) && result.equals(i.result()))
                .count();
    }

    private static BigDecimal sumCharges(List<Charge> charges) {
        return charges.stream().map(Charge::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumOrders(List<VisitOrder> orders) {
        return orders.stream().map(VisitOrder::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String yuan(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void audit(String actor, String targetId, DischargeCheckView view) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ready", view.ready());
        detail.put("blockerCount", view.blockerCount());
        detail.put("warningCount", view.warningCount());
        detail.put("uncheckedCount", view.uncheckedCount());
        try {
            repo.logChange(actor, "DISCHARGE_CHECK", "ADMISSION", targetId,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, "DISCHARGE_CHECK", "ADMISSION", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
