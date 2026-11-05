package com.medassist.clinical.service;

import com.medassist.clinical.client.PatientClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Charge;
import com.medassist.clinical.domain.ClinicalModel.Deposit;
import com.medassist.clinical.domain.ClinicalModel.Dept;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.time.BusinessTime;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.AdmitReq;
import com.medassist.clinical.web.ClinicalDtos.AssignBedReq;
import com.medassist.clinical.web.ClinicalDtos.CreateBedsReq;
import com.medassist.clinical.web.ClinicalDtos.CreateWardReq;
import com.medassist.clinical.web.ClinicalDtos.DepositView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 住院 ADT（入院登记 / 分床 / 转床 / 出院）。
 *
 * <p>为什么住院复用 clinical 的 visit + order + charge，而不是另起一套账：
 * <ul>
 *   <li>住院医嘱与住院费用必须同源同事务，否则出院结算时一定对不上；</li>
 *   <li>一张床同时只能有一个在院患者（部分唯一索引 + 床位行锁双保险）；</li>
 *   <li>同一患者同时只能有一次在院登记（部分唯一索引）；</li>
 *   <li>出院前必须结清（charge.status = PAID），避免"人走了账还挂着"。</li>
 * </ul>
 */
@Service
public class InpatientService {

    private final ClinicalRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public InpatientService(ClinicalRepository repo, PatientClient patientClient,
                            ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 病区 / 床位

    /** 启用病区列表（只读）。 */
    public List<Ward> listWards() {
        return listWards(java.util.Set.of());
    }

    /** 启用病区列表，按院区收敛（G46；空集合表示不限院区）。 */
    public List<Ward> listWards(java.util.Set<UUID> campusIds) {
        return repo.listWards(campusCsv(campusIds));
    }

    @Transactional
    public Ward createWard(CreateWardReq req, String actor) {
        return createWard(req, actor, com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID);
    }

    /** 新建病区并固化院区（G46）：病区不跨院区，入院与床位都以病区院区为准。 */
    @Transactional
    public Ward createWard(CreateWardReq req, String actor, UUID campusId) {
        Dept dept = repo.findDept(req.deptId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "科室不存在"));
        if (!dept.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "科室已停用");
        }
        Ward ward = new Ward(UUID.randomUUID(), req.code().trim(), req.name().trim(), dept.id(), true);
        try {
            repo.insertWard(ward, campusId);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "病区编码已存在：" + req.code());
        }
        audit(actor, "WARD_CREATE", "WARD", ward.id().toString(),
                Map.of("code", ward.code(), "name", ward.name(), "deptCode", dept.deptCode(),
                        "campusId", campusId.toString()));
        return ward;
    }

    @Transactional
    public List<Bed> createBeds(UUID wardId, CreateBedsReq req, String actor) {
        requireWard(wardId);
        List<Bed> created = new ArrayList<>();
        for (String raw : req.bedNos()) {
            String bedNo = raw.trim();
            UUID id = UUID.randomUUID();
            try {
                repo.insertBed(id, wardId, bedNo);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "床位号已存在：" + bedNo);
            }
            created.add(new Bed(id, wardId, bedNo, Bed.FREE, 0L));
        }
        audit(actor, "BED_CREATE", "WARD", wardId.toString(),
                Map.of("count", created.size(), "bedNos", created.stream().map(Bed::bedNo).toList()));
        return created;
    }

    public List<Bed> listBeds(UUID wardId) {
        requireWard(wardId);
        return repo.listBeds(wardId);
    }

    // ---------------------------------------------------------------- 入院

    @Transactional
    public AdmissionView admit(AdmitReq req, String actor) {
        Ward ward = requireWard(req.wardId());
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());

        repo.findActiveAdmissionByPatient(patient.id()).ifPresent(a -> {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该患者已在院（住院号 " + a.admissionNo() + "），不能重复入院");
        });

        Bed bed = null;
        if (req.bedId() != null) {
            bed = lockFreeBed(req.bedId(), ward.id());
            if (!repo.occupyBed(bed.id(), bed.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "床位已被占用，请刷新后重试");
            }
        }

        // 住院就诊：状态直接 IN_TREATMENT（入院即可开医嘱），复用门诊的就诊/医嘱/收费模型
        // G46：就诊院区随病区固化，保证住院患者与病区在同一院区
        UUID campusId = repo.findWardCampus(ward.id())
                .orElse(com.medassist.common.security.DataScope.DEFAULT_CAMPUS_ID);
        Visit visit = new Visit(UUID.randomUUID(), repo.nextVisitNo(), patient.id(), patient.patientNo(),
                ward.deptId(), req.attendingDoctor().trim(), null, "INPATIENT",
                Visit.IN_TREATMENT, BusinessTime.today(), 0L);
        try {
            repo.insertVisit(visit, campusId);
        } catch (DataIntegrityViolationException e) {
            // 唯一索引兜底：同日同科室已有在途门诊就诊（住院重复入院由 admission 唯一索引拦截）
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该患者当日在该科室已有在途门诊就诊，请先结束门诊或核对是否重复入院");
        }

        // 住院收费单：入院即建（金额 0），后续医嘱增量累加，出院前必须结清
        Charge charge = new Charge(UUID.randomUUID(), repo.nextChargeNo(), visit.id(),
                BigDecimal.ZERO, Charge.UNPAID, null, null, 0L);
        repo.insertCharge(charge);

        Admission admission = new Admission(UUID.randomUUID(), repo.nextAdmissionNo(), visit.id(),
                patient.id(), patient.patientNo(), ward.id(), bed == null ? null : bed.id(),
                req.attendingDoctor().trim(),
                bed == null ? Admission.ADMITTED : Admission.IN_HOSPITAL,
                null, null, 0L);
        try {
            repo.insertAdmission(admission);
        } catch (DataIntegrityViolationException e) {
            // 并发入院时由 ux_admission_patient_active / ux_admission_bed_active 兜底，
            // 必须转成 409 而不是 500（预检与插入之间仍有窗口）
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该患者已在院或该床位已被占用，请刷新后重试");
        }

        audit(actor, "ADMIT", "ADMISSION", admission.id().toString(), Map.of(
                "admissionNo", admission.admissionNo(), "visitNo", visit.visitNo(),
                // P1-S4：审计明细不留明文患者号
                "patientNo", com.medassist.common.security.Masks.patientNo(patient.patientNo()),
                "wardCode", ward.code(),
                "bedNo", bed == null ? "" : bed.bedNo(),
                "chargeNo", charge.chargeNo()));
        return toView(repo.findAdmission(admission.id()).orElseThrow());
    }

    // ---------------------------------------------------------------- 分床 / 转床

    @Transactional
    public AdmissionView assignBed(UUID admissionId, AssignBedReq req, String actor) {
        Admission a = repo.lockAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        if (!a.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该住院登记已出院或已取消");
        }
        if (req.bedId().equals(a.bedId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "目标床位与当前床位相同");
        }
        Bed target = lockFreeBed(req.bedId(), a.wardId());
        if (!repo.occupyBed(target.id(), target.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "床位已被占用，请刷新后重试");
        }
        if (!repo.updateAdmissionBed(a.id(), target.id(), a.version())) {
            // 事务回滚会同时撤销上面那次占床
            throw new BusinessException(ErrorCode.CONFLICT, "住院登记已被他人修改，请刷新后重试");
        }

        UUID fromBedId = a.bedId();
        if (fromBedId != null) {
            Bed from = repo.lockBed(fromBedId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "原床位不存在"));
            if (!repo.freeBed(from.id(), from.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "原床位状态异常，请刷新后重试");
            }
        }
        String reason = req.reason() == null || req.reason().isBlank()
                ? (fromBedId == null ? "入院分床" : "转床") : req.reason().trim();
        repo.logBedTransfer(a.id(), fromBedId, target.id(), reason, actor);
        audit(actor, fromBedId == null ? "BED_ASSIGN" : "BED_TRANSFER", "ADMISSION", a.id().toString(),
                Map.of("admissionNo", a.admissionNo(), "fromBedId", String.valueOf(fromBedId),
                        "toBedId", target.id().toString(), "bedNo", target.bedNo(), "reason", reason));
        return toView(repo.findAdmission(a.id()).orElseThrow());
    }

    // ---------------------------------------------------------------- 出院

    /**
     * 出院。
     *
     * <p>并发要点：先锁 visit（与 settle / dailyBill 串行），再锁 admission、床位。
     * 否则"出院检查通过 → 护理站同时跑当日按日计费 → 出院成功"会留下人已出院、
     * 账上却挂着一张有金额的未结单。
     *
     * <p>出院条件：无有金额的未结单 + 无未计费临时医嘱 + 当日有效长嘱已计费
     * （REFUSED / OMITTED 的长嘱不计入缺口）。
     */
    @Transactional
    public AdmissionView discharge(UUID admissionId, String actor) {
        Admission snapshot = repo.findAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        // 统一锁顺序：visit → admission → bed
        repo.lockVisit(snapshot.visitId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));

        Admission a = repo.lockAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        if (!Admission.IN_HOSPITAL.equals(a.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "只有在院（IN_HOSPITAL）状态可以出院，当前：" + a.status());
        }
        // 中途结算后可能已有多张收费单，出院只要求"没有有金额的未结单 + 没有未计费医嘱"
        if (repo.hasUnsettledCharge(a.visitId())) {
            throw new BusinessException(ErrorCode.CHARGE_STATUS_INVALID,
                    "出院前必须结清费用（存在未结算收费单）");
        }
        int unbilled = repo.countUnbilledTempOrders(a.visitId());
        if (unbilled > 0) {
            throw new BusinessException(ErrorCode.CHARGE_STATUS_INVALID,
                    "还有 " + unbilled + " 条临时医嘱未计费，请先结算再办理出院");
        }
        LocalDate today = BusinessTime.today();
        // 必须校验"入院以来全部有效日"，不能只看当天：某日漏跑按日计费时当天可能为 0，
        // 出院后就诊结束又无法补计 → 永久漏收。
        LocalDate firstDay = a.admittedAt() == null ? today
                : a.admittedAt().atZone(BusinessTime.zone()).toLocalDate();
        if (firstDay.isAfter(today)) {
            firstDay = today;
        }
        int unbilledDaily = repo.countUnbilledDailyOrders(a.visitId(), firstDay, today);
        if (unbilledDaily > 0) {
            throw new BusinessException(ErrorCode.CHARGE_STATUS_INVALID,
                    "还有 " + unbilledDaily + " 条长期医嘱（" + firstDay + " 至 " + today
                            + "）未按日计费，请先补计再办理出院");
        }
        if (!repo.updateAdmissionStatus(a.id(), Admission.IN_HOSPITAL, Admission.DISCHARGED, a.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "住院登记已被他人修改，请刷新后重试");
        }
        if (a.bedId() != null) {
            Bed bed = repo.lockBed(a.bedId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "床位不存在"));
            if (!repo.freeBed(bed.id(), bed.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "床位状态异常（可能已被释放），出院未完成");
            }
        }
        Visit visit = repo.findVisit(a.visitId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        if (!repo.updateVisitStatus(visit.id(), Visit.IN_TREATMENT, Visit.FINISHED, visit.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "就诊状态已变化，出院未完成");
        }

        Charge latest = repo.findLatestCharge(a.visitId()).orElse(null);
        audit(actor, "DISCHARGE", "ADMISSION", a.id().toString(), Map.of(
                "admissionNo", a.admissionNo(), "visitNo", visit.visitNo(),
                "chargeNo", latest == null ? "" : latest.chargeNo(),
                "totalAmount", latest == null ? BigDecimal.ZERO : latest.totalAmount()));
        return toView(repo.findAdmission(a.id()).orElseThrow());
    }

    // ---------------------------------------------------------------- 预交金

    /** 缴纳预交金（正数入账）。 */
    @Transactional
    public DepositView deposit(UUID admissionId, BigDecimal amount, String method,
                               String remark, String actor) {
        Admission a = requireActiveAdmission(admissionId);
        BigDecimal amt = requirePositive(amount);
        repo.insertDeposit(UUID.randomUUID(), a.id(), amt, Deposit.DEPOSIT,
                method.trim(), actor, blankToNull(remark));
        audit(actor, "DEPOSIT", "ADMISSION", a.id().toString(), Map.of(
                "admissionNo", a.admissionNo(), "amount", amt, "method", method.trim(),
                "balance", repo.depositBalance(a.id())));
        return balance(admissionId);
    }

    /** 退还预交金（负数入账，不得超过当前余额）。 */
    @Transactional
    public DepositView refund(UUID admissionId, BigDecimal amount, String method,
                              String remark, String actor) {
        Admission a = requireActiveAdmission(admissionId);
        // G51：退款金额必须是正数。原实现只做余额比较，负数金额会通过
        // `balance.compareTo(amt) < 0`（负数恒小于余额），随后 `amt.negate()`
        // 变成正向入账——"退款 -100"实际给患者加了 100 元预交金。
        BigDecimal amt = requirePositive(amount);
        BigDecimal balance = repo.depositBalance(a.id());
        if (balance.compareTo(amt) < 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "退还金额超过预交金余额（当前余额 " + balance + "）");
        }
        repo.insertDeposit(UUID.randomUUID(), a.id(), amt.negate(), Deposit.REFUND,
                method.trim(), actor, blankToNull(remark));
        audit(actor, "DEPOSIT_REFUND", "ADMISSION", a.id().toString(), Map.of(
                "admissionNo", a.admissionNo(), "amount", amt, "method", method.trim(),
                "balance", repo.depositBalance(a.id())));
        return balance(admissionId);
    }

    public DepositView balance(UUID admissionId) {
        Admission a = repo.findAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        return new DepositView(repo.depositBalance(a.id()), repo.listDeposits(a.id()));
    }

    /**
     * 预交金金额校验（G51）：必须是正数，统一保留 2 位。
     *
     * <p>放在服务层而不是只靠 DTO 的 {@code @DecimalMin}：服务会被内部接口、
     * 定时任务、补偿流程直接复用，HTTP 边界的校验在那里不存在。
     */
    private static BigDecimal requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "预交金金额必须大于 0");
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * 锁定在院登记：统一锁顺序 visit → admission。
     *
     * <p>预交金的写入（存入/退还）与结算抵扣必须复用同一把 admission 行锁，
     * 否则并发下"读余额 → 校验 → 插负流水"会互相覆盖，把余额做成负数。
     */
    private Admission requireActiveAdmission(UUID admissionId) {
        Admission snapshot = repo.findAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        repo.lockVisit(snapshot.visitId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_NOT_FOUND));
        Admission a = repo.lockAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在"));
        if (!a.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该住院登记已出院或已取消，不能操作预交金");
        }
        return a;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    public AdmissionView detail(UUID admissionId) {
        return toView(repo.findAdmission(admissionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "住院登记不存在")));
    }

    public List<AdmissionView> listByWard(UUID wardId, String status) {
        requireWard(wardId);
        String s = (status == null || status.isBlank()) ? Admission.IN_HOSPITAL : status.trim().toUpperCase();
        return repo.listAdmissions(wardId, s).stream().map(this::toView).toList();
    }

    // ---------------------------------------------------------------- 内部

    /** 院区集合转 SQL 过滤串；空集合（全院角色）返回空串表示不限院区（G71 统一走 CampusFilters）。 */
    private static String campusCsv(java.util.Set<UUID> campusIds) {
        return com.medassist.common.security.CampusFilters.csv(campusIds);
    }

    private Ward requireWard(UUID wardId) {
        Ward ward = repo.findWard(wardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "病区不存在"));
        if (!ward.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "病区已停用");
        }
        return ward;
    }

    /** 锁床位并校验：存在、属于目标病区、状态 FREE。 */
    private Bed lockFreeBed(UUID bedId, UUID wardId) {
        Bed bed = repo.lockBed(bedId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "床位不存在"));
        if (!bed.wardId().equals(wardId)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "床位不属于该病区");
        }
        if (!bed.free()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "床位 " + bed.bedNo() + " 当前状态为 " + bed.status() + "，不可占用");
        }
        return bed;
    }

    private AdmissionView toView(Admission a) {
        Visit visit = repo.findVisit(a.visitId()).orElse(null);
        Ward ward = repo.findWard(a.wardId()).orElse(null);
        Bed bed = a.bedId() == null ? null : repo.findBed(a.bedId()).orElse(null);
        return new AdmissionView(a.id(), a.admissionNo(), a.visitId(),
                visit == null ? "" : visit.visitNo(), a.patientId(), a.patientNo(),
                a.wardId(), ward == null ? "" : ward.name(),
                a.bedId(), bed == null ? "" : bed.bedNo(),
                a.attendingDoctor(), a.status(), a.admittedAt(), a.dischargedAt());
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
