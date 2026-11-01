package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.emr.client.ClinicalClient;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.CreateHomepageReq;
import com.medassist.emr.web.EmrDtos.HomepageView;
import com.medassist.emr.web.EmrDtos.HomepageProcedureReq;
import com.medassist.emr.web.EmrDtos.UpdateHomepageReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 病案首页：生成（临床快照）、补录、提交、归档与打印。
 *
 * <p>核心不变量：
 * <ol>
 *   <li>一次住院只有一份首页（数据库唯一约束兜底），只能对**已出院**的住院就诊生成；</li>
 *   <li>诊断、费用汇总、出院时间是生成时的临床域快照，首页侧不可修改——要改必须回临床域
 *       修正后重新生成，避免两套账；</li>
 *   <li>提交前强校验（离院方式、医师签名、唯一主诊断、费用合计与明细一致）；</li>
 *   <li>归档与提交必须由不同的人完成（四眼原则），归档是终态。</li>
 * </ol>
 */
@Service
public class HomepageService {

    /** 收费项目类别（与临床 charge_item.category 一致），首页按此固定顺序展示。 */
    static final List<String> FEE_CATEGORIES = List.of(
            "REGISTRATION", "CONSULTATION", "DRUG", "TREATMENT", "EXAM", "MATERIAL");

    private final EmrRepository repo;
    private final ClinicalClient clinicalClient;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public HomepageService(EmrRepository repo, ClinicalClient clinicalClient,
                           PatientClient patientClient, ObjectMapper objectMapper) {
        this.repo = repo;
        this.clinicalClient = clinicalClient;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 生成

    @Transactional
    public HomepageView create(CreateHomepageReq req, String actor) {
        ClinicalClient.HomepageSource src = clinicalClient.homepageSource(req.visitId());
        if (!"INPATIENT".equalsIgnoreCase(src.visitType()) || src.admissionId() == null) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID,
                    "该就诊不是住院就诊，不能生成病案首页");
        }
        if (!"DISCHARGED".equalsIgnoreCase(src.admissionStatus())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID,
                    "患者尚未办理出院（住院状态：" + src.admissionStatus() + "），不能生成病案首页");
        }
        if (src.admittedAt() == null || src.dischargedAt() == null) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID,
                    "住院起止时间不完整，无法计算住院天数");
        }
        PatientClient.PatientRef patient = patientClient.requireActive(src.patientId());
        if (repo.findHomepageByVisit(req.visitId()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该就诊已存在病案首页，请直接查询或重新生成前先核对");
        }

        Map<String, BigDecimal> feeSummary = normalizeFees(src.feeSummary());
        BigDecimal total = feeSummary.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> diagnoses = new ArrayList<>();
        for (ClinicalClient.Diagnosis d : src.diagnoses()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", d.code());
            row.put("name", d.name());
            row.put("primary", d.primary());
            diagnoses.add(row);
        }

        Homepage homepage = new Homepage(UUID.randomUUID(), repo.nextHomepageNo(),
                src.visitId(), src.admissionId(), src.patientId(), src.patientNo(), patient.name(),
                blankToNull(src.visitNo()), src.deptId(), blankToNull(src.deptName()),
                src.wardId(), blankToNull(src.wardName()), blankToNull(src.bedNo()),
                src.admittedAt(), src.dischargedAt(), stayDays(src.admittedAt(), src.dischargedAt()),
                blankToNull(src.attendingDoctor()), null, null, "NONE", null, null, null,
                null, null, 0, 0, false, false, null, null, null,
                toJson(diagnoses), "[]", toJson(feeSummary), total, Homepage.DRAFT,
                null, null, null, null, 0L, null);
        repo.insertHomepage(homepage);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("homepageNo", homepage.homepageNo());
        detail.put("visitNo", homepage.visitNo());
        detail.put("totalAmount", total.toPlainString());
        detail.put("diagnosisCount", diagnoses.size());
        detail.put("stayDays", homepage.stayDays());
        audit(actor, "HOMEPAGE_CREATE", "HOMEPAGE", homepage.id().toString(), detail);
        return detail(homepage.id());
    }

    // ---------------------------------------------------------------- 补录（草稿）

    /**
     * 保存首页专有信息（PUT 语义：提交完整可编辑字段，未传的字段按清空/默认值处理）。
     *
     * <p>诊断、费用、出院时间不在此接口范围内——它们来自临床域，首页只做展示与归档。
     */
    @Transactional
    public HomepageView update(UUID id, UpdateHomepageReq req, String actor) {
        Homepage current = repo.lockHomepage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!current.editable()) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "只有草稿状态可以修改，当前：" + current.status());
        }
        int rescueCount = req.rescueCount() == null ? 0 : req.rescueCount();
        int rescueSuccess = req.rescueSuccessCount() == null ? 0 : req.rescueSuccessCount();
        if (rescueSuccess > rescueCount) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "抢救成功次数不能大于抢救次数（成功 " + rescueSuccess + " / 共 " + rescueCount + "）");
        }
        String deptName = blankToNull(req.deptName()) == null ? current.deptName() : req.deptName().trim();
        String wardName = blankToNull(req.wardName()) == null ? current.wardName() : req.wardName().trim();
        String attending = blankToNull(req.attendingDoctor()) == null
                ? current.attendingDoctor() : req.attendingDoctor().trim();
        Homepage updated = new Homepage(current.id(), current.homepageNo(), current.visitId(),
                current.admissionId(), current.patientId(), current.patientNo(), current.patientName(),
                current.visitNo(), current.deptId(), deptName, current.wardId(), wardName,
                blankToNull(req.bedNo()), current.admittedAt(), current.dischargedAt(),
                current.stayDays(), attending,
                upper(req.admissionWay()), upper(req.dischargeMode()),
                upper(req.readmitPlan()) == null ? "NONE" : upper(req.readmitPlan()),
                upper(req.maritalStatus()), blankToNull(req.occupation()), blankToNull(req.nation()),
                upper(req.bloodType()), upper(req.rhFactor()), rescueCount, rescueSuccess,
                Boolean.TRUE.equals(req.criticalCase()), Boolean.TRUE.equals(req.hospitalInfection()),
                blankToNull(req.chiefPhysician()), blankToNull(req.residentPhysician()),
                blankToNull(req.coder()),
                current.diagnoses(), proceduresJson(req.procedures()),
                current.feeSummary(), current.totalAmount(), current.status(),
                current.submittedBy(), current.submittedAt(),
                current.archivedBy(), current.archivedAt(), current.version(), current.createdAt());

        if (!repo.updateHomepageDraft(id, updated, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病案首页已被他人修改，请刷新后重试");
        }
        audit(actor, "HOMEPAGE_UPDATE", "HOMEPAGE", id.toString(), Map.of(
                "homepageNo", current.homepageNo(),
                "dischargeMode", String.valueOf(updated.dischargeMode()),
                "procedureCount", req.procedures() == null ? 0 : req.procedures().size()));
        return detail(id);
    }

    // ---------------------------------------------------------------- 提交 / 归档

    @Transactional
    public HomepageView submit(UUID id, String actor) {
        Homepage h = repo.lockHomepage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.DRAFT.equals(h.status())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "只有草稿可以提交，当前：" + h.status());
        }
        List<String> missing = completenessIssues(h);
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_INCOMPLETE,
                    "病案首页必填项未完成：" + String.join("、", missing));
        }
        if (!repo.submitHomepage(id, actor, h.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病案首页已被他人修改，请刷新后重试");
        }
        audit(actor, "HOMEPAGE_SUBMIT", "HOMEPAGE", id.toString(), Map.of(
                "homepageNo", h.homepageNo(), "totalAmount", h.totalAmount().toPlainString()));
        return detail(id);
    }

    /**
     * 归档：提交人与归档人必须不同（四眼原则），归档后不可再改。
     */
    @Transactional
    public HomepageView archive(UUID id, String actor) {
        Homepage h = repo.lockHomepage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.SUBMITTED.equals(h.status())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "只有已提交的病案首页可以归档，当前：" + h.status());
        }
        if (h.submittedBy() != null && h.submittedBy().equals(actor)) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "提交人不得归档自己的病案首页，请由病案质控人员归档");
        }
        if (!repo.archiveHomepage(id, actor, h.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病案首页已被他人修改，请刷新后重试");
        }
        audit(actor, "HOMEPAGE_ARCHIVE", "HOMEPAGE", id.toString(), Map.of(
                "homepageNo", h.homepageNo(), "submittedBy", String.valueOf(h.submittedBy())));
        return detail(id);
    }

    // ---------------------------------------------------------------- 查询 / 打印

    public HomepageView detail(UUID id) {
        Homepage h = repo.findHomepage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        return new HomepageView(h, readJsonList(h.diagnoses()), readJsonList(h.procedures()),
                readJsonMap(h.feeSummary()));
    }

    public List<Homepage> list(String status, UUID patientId, UUID deptId, Integer limit) {
        String normalized = blankToNull(status) == null ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listHomepages(normalized, patientId, deptId, size);
    }

    /** 打印视图（HTML，浏览器打印 → 另存为 PDF）；未归档带水印，并留痕。 */
    public String printHtml(UUID id, String actor) {
        Homepage h = repo.findHomepage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        String html = HomepagePrintRenderer.render(h, readJsonList(h.diagnoses()),
                readJsonList(h.procedures()), readJsonMap(h.feeSummary()));
        audit(actor, "HOMEPAGE_PRINT", "HOMEPAGE", id.toString(), Map.of(
                "homepageNo", h.homepageNo(), "status", h.status()));
        return html;
    }

    // ---------------------------------------------------------------- 内部工具

    /** 提交前的完整性校验项；返回空表示可提交。 */
    private List<String> completenessIssues(Homepage h) {
        List<String> missing = new ArrayList<>();
        if (h.dischargedAt() == null) {
            missing.add("出院时间");
        }
        if (h.dischargeMode() == null) {
            missing.add("离院方式");
        }
        if (blankToNull(h.chiefPhysician()) == null) {
            missing.add("主任医师签名");
        }
        if (blankToNull(h.residentPhysician()) == null) {
            missing.add("住院医师签名");
        }
        List<Map<String, Object>> diagnoses = readJsonList(h.diagnoses());
        long primary = diagnoses.stream()
                .filter(d -> Boolean.TRUE.equals(d.get("primary"))).count();
        if (diagnoses.isEmpty()) {
            missing.add("出院诊断");
        } else if (primary != 1) {
            missing.add("唯一主诊断");
        }
        BigDecimal sum = readJsonMap(h.feeSummary()).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        if (sum.compareTo(h.totalAmount().setScale(2, RoundingMode.HALF_UP)) != 0) {
            missing.add("费用合计与明细一致");
        }
        return missing;
    }

    /** 费用汇总补齐固定类别并统一两位小数（临床未产生的类别补 0）。 */
    private Map<String, BigDecimal> normalizeFees(Map<String, BigDecimal> raw) {
        Map<String, BigDecimal> summary = new LinkedHashMap<>();
        for (String category : FEE_CATEGORIES) {
            summary.put(category, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        if (raw != null) {
            raw.forEach((category, amount) -> summary.put(category,
                    (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP)));
        }
        return summary;
    }

    /** 住院天数：出院日期 − 入院日期，当天入出院按 1 天计。 */
    static int stayDays(Instant admittedAt, Instant dischargedAt) {
        LocalDate in = LocalDate.ofInstant(admittedAt, BusinessTime.zone());
        LocalDate out = LocalDate.ofInstant(dischargedAt, BusinessTime.zone());
        long days = ChronoUnit.DAYS.between(in, out);
        return (int) Math.max(1, days);
    }

    private String proceduresJson(List<HomepageProcedureReq> procedures) {
        if (procedures == null || procedures.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (HomepageProcedureReq p : procedures) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", p.code());
            row.put("name", p.name());
            row.put("date", p.date() == null ? null : p.date().toString());
            row.put("surgeon", blankToNull(p.surgeon()));
            row.put("anesthesia", blankToNull(p.anesthesia()));
            row.put("incisionLevel", blankToNull(p.incisionLevel()));
            rows.add(row);
        }
        return toJson(rows);
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private Map<String, BigDecimal> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, BigDecimal> result = new LinkedHashMap<>();
            raw.forEach((k, v) -> result.put(k, v == null ? BigDecimal.ZERO
                    : new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP)));
            return result;
        } catch (JacksonException | NumberFormatException e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "病案首页内容无法序列化");
        }
    }

    private static String upper(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase();
    }

    private static String blankToNull(String s) {
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
