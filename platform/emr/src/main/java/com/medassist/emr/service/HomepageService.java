package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.common.time.BusinessTime;
import com.medassist.emr.client.ClinicalClient;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.domain.EmrModel.HomepageSummary;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

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
    public HomepageView create(CreateHomepageReq req, String actor, DataScope scope) {
        ClinicalClient.HomepageSource src = clinicalClient.homepageSource(req.visitId());
        // 科室归属只能从临床域拿到，因此可见性校验放在取回数据源之后、任何落库之前
        if (!scope.canSeeDepartment(src.deptId())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID, "未找到该就诊");
        }
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
        // 死亡病例也必须能生成首页（DECEASED 不是 MERGED，只排除已合并档案）
        PatientClient.PatientRef patient = patientClient.requireExists(src.patientId());
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
                normalizeSex(patient.patientSex()), patient.birthDate(),
                ageAt(patient.birthDate(), src.admittedAt()),
                blankToNull(src.visitNo()), src.deptId(), blankToNull(src.deptName()),
                src.wardId(), blankToNull(src.wardName()), blankToNull(src.bedNo()),
                src.admittedAt(), src.dischargedAt(), stayDays(src.admittedAt(), src.dischargedAt()),
                blankToNull(src.attendingDoctor()), null, null, null, null, "NONE",
                null, null, null, null, null, 0, 0, false, false, null, null, null,
                toJson(diagnoses), surgeryProceduresJson(src.procedures()), toJson(feeSummary),
                total, Homepage.DRAFT,
                null, null, null, null, null, 0L, null);
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

    // ---------------------------------------------------------------- 重新生成快照

    /**
     * 重新生成首页快照（仅草稿）。
     *
     * <p>为什么需要：诊断与费用是"生成时快照"，但出院后仍可能发生退费/补录诊断。
     * 没有这个出口，归档件的金额会与临床账实永久不符且只能改库（第六轮 MF-05）。
     * 重算只在草稿态允许，差异写入审计；提交/归档后由数据库触发器兜底拒绝。
     */
    @Transactional
    public HomepageView recalculate(UUID id, String actor) {
        // 先做跨服务读取，再取行锁：HTTP 调用放在 FOR UPDATE 之后会把首页行锁持到下游超时，
        // 同一首页的提交/归档/再次重算会全部排队（多智能体评审 D1）。
        Homepage snapshotBefore = repo.findHomepage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        // 先做一次无锁状态判断：已提交/归档的首页根本不该触发跨服务调用
        if (!Homepage.DRAFT.equals(snapshotBefore.status())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "只有草稿状态可以重新生成快照，当前：" + snapshotBefore.status());
        }
        ClinicalClient.HomepageSource src = clinicalClient.homepageSource(snapshotBefore.visitId());
        if (!"DISCHARGED".equalsIgnoreCase(src.admissionStatus())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID,
                    "住院状态为 " + src.admissionStatus() + "，不能重新生成病案首页");
        }
        if (src.admittedAt() == null || src.dischargedAt() == null) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_SOURCE_INVALID,
                    "住院起止时间不完整，无法重新生成病案首页");
        }
        Snapshot snapshot = snapshot(src);
        int stayDays = stayDays(src.admittedAt(), src.dischargedAt());

        Homepage current = repo.lockHomepage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.DRAFT.equals(current.status())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "只有草稿状态可以重新生成快照，当前：" + current.status());
        }
        if (!repo.recalculateHomepage(id, current.version(), snapshot.diagnosesJson(),
                snapshot.proceduresJson(), snapshot.feeSummaryJson(), snapshot.total(),
                src.admittedAt(), src.dischargedAt(), stayDays)) {
            throw new BusinessException(ErrorCode.CONFLICT, "病案首页已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("homepageNo", current.homepageNo());
        detail.put("totalBefore", current.totalAmount() == null
                ? "0.00" : current.totalAmount().toPlainString());
        detail.put("totalAfter", snapshot.total().toPlainString());
        detail.put("diagnosisCount", snapshot.diagnosisCount());
        detail.put("stayDays", stayDays);
        audit(actor, "HOMEPAGE_RECALCULATE", "HOMEPAGE", id.toString(), detail);
        return detail(id);
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
                current.patientSex(), current.birthDate(), current.admitAge(),
                current.visitNo(), current.deptId(), deptName, current.wardId(), wardName,
                blankToNull(req.bedNo()), current.admittedAt(), current.dischargedAt(),
                current.stayDays(), attending,
                upper(req.admissionWay()), upper(req.dischargeMode()),
                req.deathTime(), blankToNull(req.deathCause()),
                upper(req.readmitPlan()) == null ? "NONE" : upper(req.readmitPlan()),
                upper(req.maritalStatus()), blankToNull(req.occupation()), blankToNull(req.nation()),
                upper(req.bloodType()), upper(req.rhFactor()), rescueCount, rescueSuccess,
                Boolean.TRUE.equals(req.criticalCase()), Boolean.TRUE.equals(req.hospitalInfection()),
                blankToNull(req.chiefPhysician()), blankToNull(req.residentPhysician()),
                blankToNull(req.coder()),
                current.diagnoses(), proceduresJson(req.procedures()),
                current.feeSummary(), current.totalAmount(), current.status(),
                current.submittedBy(), current.submittedById(), current.submittedAt(),
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
    public HomepageView submit(UUID id, String actor, UUID actorId) {
        requireRealActor(actor, "提交");
        if (actorId == null) {
            // 没有账号主键就无法做四眼校验，宁可拒绝也不能让"提交人=system/空"混过去
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "缺少操作者身份（X-User-Id），不能提交病案首页");
        }
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
        // 提交前费用/诊断指纹校验：出院后退费或补录诊断会让快照与临床账实不符，
        // 一旦提交/归档就再也改不了（第六轮 MF-05）。不一致时要求先重新生成。
        ClinicalClient.HomepageSource src = clinicalClient.homepageSource(h.visitId());
        Snapshot fresh = snapshot(src);
        if (!snapshotMatches(h, fresh)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "临床诊断或费用已变化（首页合计 "
                            + (h.totalAmount() == null ? "0.00" : h.totalAmount().toPlainString())
                            + " / 当前 " + fresh.total().toPlainString()
                            + "），请先重新生成病案首页再提交");
        }
        if (!repo.submitHomepage(id, actor, actorId, h.version())) {
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
    public HomepageView archive(UUID id, String actor, UUID actorId) {
        requireRealActor(actor, "归档");
        Homepage h = repo.lockHomepage(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_HOMEPAGE_NOT_FOUND));
        if (!Homepage.SUBMITTED.equals(h.status())) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "只有已提交的病案首页可以归档，当前：" + h.status());
        }
        // 四眼原则按**账号主键**判定（第六轮 MF-13）：username 可重建/共享，不能作为"不同人"的依据
        if (h.submittedById() == null) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "该首页缺少提交人身份记录（历史数据），无法校验四眼归档，请重新提交后再归档");
        }
        if (actorId == null) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "缺少操作者身份（X-User-Id），不能归档病案首页");
        }
        if (h.submittedById().equals(actorId)) {
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

    /**
     * 详情查看（带审计）：返回整份 PHI，必须留痕（第六轮 MF-12）。
     *
     * <p>审计只记首页号与操作者，不记患者姓名/诊断等 PHI。
     */
    public HomepageView viewDetail(UUID id, String actor) {
        HomepageView view = detail(id);
        audit(actor, "HOMEPAGE_VIEW", "HOMEPAGE", id.toString(),
                Map.of("homepageNo", view.homepage().homepageNo(),
                        "status", view.homepage().status()));
        return view;
    }

    /**
     * 列表（摘要口径 + 查询审计）。
     *
     * <p>只返回掩码姓名与金额合计，不返回诊断/费用明细；审计只记条数与筛选条件。
     */
    public List<HomepageSummary> list(String status, UUID patientId, UUID deptId, Integer limit,
                                      String actor) {
        String normalized = blankToNull(status) == null ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        List<HomepageSummary> rows = repo.listHomepageSummaries(normalized, patientId, deptId, size);
        audit(actor, "HOMEPAGE_QUERY", "HOMEPAGE", "-", Map.of(
                "count", rows.size(), "status", String.valueOf(normalized),
                "patientFilter", patientId != null, "deptFilter", deptId != null, "limit", size));
        return rows;
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

    /**
     * 写操作必须带真实操作者。
     *
     * <p>{@code X-Actor} 缺省值是 {@code system}，直连服务（绕过网关）时最容易出现；
     * 一旦落库，四眼归档就变成"提交人=system、归档人=本人"的假合规（MF-13）。
     */
    private static void requireRealActor(String actor, String action) {
        if (actor == null || actor.isBlank() || "system".equalsIgnoreCase(actor)) {
            throw new BusinessException(ErrorCode.EMR_HOMEPAGE_STATUS_INVALID,
                    "缺少操作者身份（X-Actor），不能" + action + "病案首页");
        }
    }

    /** 由临床域数据构建的首页快照（生成与重新生成共用同一套口径）。 */
    private record Snapshot(String diagnosesJson, String proceduresJson, String feeSummaryJson,
                            BigDecimal total, int diagnosisCount) {
    }

    private Snapshot snapshot(ClinicalClient.HomepageSource src) {
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
        return new Snapshot(toJson(diagnoses), surgeryProceduresJson(src.procedures()),
                toJson(feeSummary), total, diagnoses.size());
    }

    /**
     * 已完成手术 → 首页手术操作 JSON（第六轮之后：手术操作不再手工录入）。
     *
     * <p>病案室仍可在草稿态补充切口等级/麻醉方式，提交后只读。
     */
    private String surgeryProceduresJson(List<ClinicalClient.Procedure> procedures) {
        if (procedures == null || procedures.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> rows = new ArrayList<>(procedures.size());
        for (ClinicalClient.Procedure p : procedures) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", p.code());
            row.put("name", p.name());
            row.put("date", p.date());
            row.put("surgeon", p.surgeon());
            row.put("anesthesia", p.anesthesia());
            row.put("incisionLevel", p.incisionLevel());
            rows.add(row);
        }
        return toJson(rows);
    }

    /**
     * 首页已有快照是否仍与临床一致（提交前的指纹校验）。
     *
     * <p>比较口径不看 JSON 文本，而是规范化后的"费用类别金额 + 诊断编码/主诊断标记"，
     * 避免键序与金额格式造成的误判。
     */
    private boolean snapshotMatches(Homepage h, Snapshot fresh) {
        if (h.totalAmount() == null
                || h.totalAmount().setScale(2, RoundingMode.HALF_UP).compareTo(fresh.total()) != 0) {
            return false;
        }
        if (!canonicalFees(h.feeSummary()).equals(canonicalFees(fresh.feeSummaryJson()))) {
            return false;
        }
        if (!proceduresCoverClinical(h.procedures(), fresh.proceduresJson())) {
            return false;
        }
        return canonicalDiagnoses(h.diagnoses()).equals(canonicalDiagnoses(fresh.diagnosesJson()));
    }

    /**
     * 手术操作指纹：临床已完成的手术必须全部出现在首页快照中（按编码计数量）。
     *
     * <p>为什么不做全等比较：病案室在草稿态可以补录外院手术、补充切口/麻醉信息，
     * 但**不能丢掉**临床已记录的手术——否则 DRG 权重失真。出院后又完成一台手术时，
     * 首页必须重新生成才能提交。
     */
    private boolean proceduresCoverClinical(String homepageJson, String clinicalJson) {
        Map<String, Long> clinical = countProceduresByCode(clinicalJson);
        if (clinical.isEmpty()) {
            return true;
        }
        Map<String, Long> homepage = countProceduresByCode(homepageJson);
        return clinical.entrySet().stream()
                .allMatch(e -> homepage.getOrDefault(e.getKey(), 0L) >= e.getValue());
    }

    private Map<String, Long> countProceduresByCode(String json) {
        return readJsonList(json).stream()
                .collect(Collectors.groupingBy(p -> str(p.get("code")), Collectors.counting()));
    }

    private Map<String, BigDecimal> canonicalFees(String json) {
        return new TreeMap<>(normalizeFees(readJsonMap(json)));
    }

    private List<String> canonicalDiagnoses(String json) {
        return readJsonList(json).stream()
                .map(d -> str(d.get("code")) + "|" + (Boolean.TRUE.equals(d.get("primary")) ? "1" : "0"))
                .sorted()
                .toList();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 提交前的完整性校验项；返回空表示可提交。 */
    private List<String> completenessIssues(Homepage h) {
        List<String> missing = new ArrayList<>();
        if (h.dischargedAt() == null) {
            missing.add("出院时间");
        }
        if (h.dischargeMode() == null) {
            missing.add("离院方式");
        }
        // 死亡病例必须写死亡时间（数据库在提交态也有 CHECK 兜底）
        if ("DEATH".equals(h.dischargeMode()) && h.deathTime() == null) {
            missing.add("死亡时间");
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

    /** 性别口径归一：只接受 MALE/FEMALE/UNKNOWN，其他历史值按 UNKNOWN 处理（HQMS 必填项）。 */
    static String normalizeSex(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNKNOWN";
        }
        return switch (raw.trim().toUpperCase()) {
            case "MALE", "FEMALE" -> raw.trim().toUpperCase();
            case "M", "1" -> "MALE";
            case "F", "2" -> "FEMALE";
            default -> "UNKNOWN";
        };
    }

    /** 入院年龄（岁）：出生日期为空时返回 null（历史数据），负数按 0 处理。 */
    static Integer ageAt(LocalDate birthDate, Instant admittedAt) {
        if (birthDate == null || admittedAt == null) {
            return null;
        }
        LocalDate in = LocalDate.ofInstant(admittedAt, BusinessTime.zone());
        long years = ChronoUnit.YEARS.between(birthDate, in);
        return (int) Math.max(0, Math.min(years, 150));
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
