package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Handover;
import com.medassist.clinical.domain.ClinicalModel.HandoverBrief;
import com.medassist.clinical.domain.ClinicalModel.HandoverItem;
import com.medassist.clinical.domain.ClinicalModel.HandoverTask;
import com.medassist.clinical.domain.ClinicalModel.HandoverTaskEntry;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.HandoverBriefView;
import com.medassist.clinical.web.ClinicalDtos.HandoverItemReq;
import com.medassist.clinical.web.ClinicalDtos.HandoverItemView;
import com.medassist.clinical.web.ClinicalDtos.HandoverSaveReq;
import com.medassist.clinical.web.ClinicalDtos.HandoverStatusSummary;
import com.medassist.clinical.web.ClinicalDtos.HandoverTaskReq;
import com.medassist.clinical.web.ClinicalDtos.HandoverTaskView;
import com.medassist.clinical.web.ClinicalDtos.HandoverView;
import com.medassist.clinical.web.ClinicalDtos.WardHandoverStatus;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 病区交班报告（班次交接）。
 *
 * <p>为什么值得独立成模块：交班是护理质量的核心环节，但纸质/口头交接既无法证明
 * "交接了什么"，也无法回答"接班时到底看到了哪些风险"。本模块只做三件事：
 * <ol>
 *   <li>把当前在院患者与风险项生成一张**可核对的交班清单**（机器码与出院预检同源）；</li>
 *   <li>允许护士逐人写交班备注、整班写交班说明；</li>
 *   <li>签字即冻结，保留签字当时患者、床位与风险项的快照，日后回看不会被现在的事实改写。</li>
 * </ol>
 *
 * <p>刻意不做：不改床位、不改医嘱、不改费用——交班是记录与交接，不是执行动作。
 */
@Service
public class HandoverService {

    /** 今日计划出院（交班清单专用提示项；出院预检本身不产生该码）。 */
    public static final String CODE_PLANNED_TODAY = "PLANNED_DISCHARGE_TODAY";

    /** 状态汇总里表示“该班次尚未创建交班报告”。 */
    public static final String STATUS_MISSING = "MISSING";

    /** 历史列表默认与上限：护士站回看通常只看最近一两天的班次。 */
    private static final int HISTORY_DEFAULT = 10;
    private static final int HISTORY_MAX = 50;

    private final ClinicalRepository repo;
    private final ObjectMapper objectMapper;

    public HandoverService(ClinicalRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    /** 读取某班次交班报告：已签字返回冻结快照；否则返回实时清单并叠加草稿备注。 */
    public HandoverView view(UUID wardId, LocalDate shiftDate, String shiftCode) {
        Ward ward = requireWard(wardId);
        String shift = normalizeShift(shiftCode);
        LocalDate date = shiftDate == null ? BusinessTime.today() : shiftDate;
        Optional<Handover> saved = repo.findHandover(wardId, date, shift);
        if (saved.isPresent() && !saved.get().editable()) {
            Handover h = saved.get();
            return new HandoverView(h.id(), wardId, ward.name(), h.shiftDate(), h.shiftCode(),
                    h.status(), h.note(), h.createdBy(), h.signedBy(), h.signedAt(), false,
                    toViews(repo.listHandoverItems(h.id())),
                    taskViews(repo.listHandoverTasks(h.id()), h.shiftDate(), h.shiftCode()),
                    carriedViews(repo.listOpenHandoverTasks(wardId, h.id())));
        }
        Map<UUID, String> notes = new HashMap<>();
        UUID handoverId = null;
        String note = null;
        String createdBy = null;
        if (saved.isPresent()) {
            handoverId = saved.get().id();
            note = saved.get().note();
            createdBy = saved.get().createdBy();
            for (HandoverItem item : repo.listHandoverItems(handoverId)) {
                notes.put(item.admissionId(), item.note());
            }
        }
        List<HandoverItemView> items = liveItems(wardId, date, notes);
        List<HandoverTaskView> tasks = handoverId == null ? List.of()
                : taskViews(repo.listHandoverTasks(handoverId), date, shift);
        List<HandoverTaskView> carried = carriedViews(
                repo.listOpenHandoverTasks(wardId, handoverId));
        return new HandoverView(handoverId, wardId, ward.name(), date, shift, Handover.DRAFT,
                note, createdBy, null, null, true, items, tasks, carried);
    }

    /**
     * 保存草稿 / 交班签字。
     *
     * @param sign true = 保存后立即签字冻结；已签字时任何保存都 409
     */
    @Transactional
    public HandoverView save(UUID wardId, HandoverSaveReq req, String actor, boolean sign) {
        Ward ward = requireWard(wardId);
        String shift = normalizeShift(req.shiftCode());
        LocalDate date = req.shiftDate();
        String note = trimToNull(req.note());
        Map<UUID, String> notes = noteMap(req.items());

        Handover handover = repo.findHandover(wardId, date, shift).orElse(null);
        if (handover == null) {
            Handover created = new Handover(UUID.randomUUID(), wardId, date, shift, note,
                    Handover.DRAFT, actor, null, null, Instant.now(), 0L);
            try {
                repo.insertHandover(created);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该班次交班报告已被他人创建，请刷新后重试");
            }
            handover = created;
        } else {
            Handover locked = repo.lockHandover(handover.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "交班报告不存在"));
            if (!locked.editable()) {
                throw new BusinessException(ErrorCode.CONFLICT, "该班次交班报告已签字，不能再修改");
            }
            if (!repo.updateHandoverDraft(locked.id(), note, locked.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "交班报告已被他人修改，请刷新后重试");
            }
            handover = repo.lockHandover(locked.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "交班报告不存在"));
        }

        // 患者、床位、风险项一律由服务端按当前在院数据生成；只接受客户端提交的逐人备注
        List<LiveItem> items = snapshot(wardId, date, notes);
        repo.deleteHandoverItems(handover.id());
        for (LiveItem item : items) {
            repo.insertHandoverItem(new HandoverItem(UUID.randomUUID(), handover.id(),
                    item.admissionId(), item.bedNo(), item.patientNo(),
                    String.join(",", item.flags()), item.note()));
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shiftDate", date.toString());
        detail.put("shiftCode", shift);
        detail.put("patients", items.size());
        detail.put("notedPatients", items.stream().filter(i -> i.note() != null).count());
        detail.put("tasks", replaceOpenTasks(handover.id(), items, req.tasks(), actor));

        if (sign) {
            Handover current = repo.lockHandover(handover.id())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "交班报告不存在"));
            if (!repo.signHandover(current.id(), actor, current.version())) {
                throw new BusinessException(ErrorCode.CONFLICT, "交班报告已被他人签字，请刷新后重试");
            }
            audit(actor, "HANDOVER_SIGN", current.id(), detail);
        } else {
            audit(actor, "HANDOVER_SAVE", handover.id(), detail);
        }
        return view(wardId, date, shift);
    }

    /**
     * 闭环一条交班事项（接班者确认办完）。
     *
     * <p>只允许 OPEN → DONE；重复点击返回 409，不覆盖第一次的闭环人与时间。
     * 只能闭环本病区事项（SQL 里 JOIN handover 限制 ward_id）。
     */
    @Transactional
    public void closeTask(UUID wardId, UUID taskId, String note, String actor) {
        requireWard(wardId);
        HandoverTask task = repo.findHandoverTaskInWard(wardId, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "交班事项不存在"));
        if (!task.open()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该交班事项已闭环，请刷新");
        }
        String closeNote = trimToNull(note);
        if (closeNote != null && closeNote.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "闭环说明最多 500 字");
        }
        if (!repo.closeHandoverTask(taskId, actor, closeNote)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该交班事项已被他人闭环，请刷新");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("content", task.content());
        detail.put("patientNo", task.patientNo());
        detail.put("closeNote", closeNote);
        audit(actor, "HANDOVER_TASK_CLOSE", task.handoverId(), detail);
    }

    /**
     * 某病区最近若干个班次（交班历史）。
     *
     * <p>只回摘要：打开某一班次时才拉逐患者明细，避免“看一眼历史”就把几百条明细搬到前端。
     * 与 {@link #view} 一致要求病区存在且启用，防止停用病区的历史被继续查阅。
     *
     * @param limit 缺省 10，最大 50（负数/0 按 1 处理）
     */
    public List<HandoverBriefView> history(UUID wardId, Integer limit) {
        requireWard(wardId);
        int n = limit == null ? HISTORY_DEFAULT : Math.max(1, Math.min(limit, HISTORY_MAX));
        return repo.listHandovers(wardId, n).stream()
                .map(HandoverService::toBriefView)
                .toList();
    }

    /**
     * 某日某班次的交班状态汇总（驾驶舱 / 待办角标，只读）。
     *
     * <p>分母口径：当前可见病区中“有在院患者”或“该班次已有交班记录”的病区。
     * 空病区若还没写交班，不计入分母——否则全院空病区会让角标永远是未完成。
     */
    public HandoverStatusSummary status(List<Ward> wards, LocalDate shiftDate, String shiftCode) {
        LocalDate date = shiftDate == null ? BusinessTime.today() : shiftDate;
        String shift = normalizeShift(shiftCode);
        if (wards == null || wards.isEmpty()) {
            return new HandoverStatusSummary(date, shift, 0, 0, 0, List.of());
        }
        List<UUID> wardIds = wards.stream().map(Ward::id).toList();
        Set<UUID> active = repo.listWardsWithActiveAdmissions(wardIds);
        Map<UUID, HandoverBrief> saved = new HashMap<>();
        for (HandoverBrief brief : repo.listHandoversByWards(wardIds, date, shift)) {
            saved.put(brief.wardId(), brief);
        }

        List<WardHandoverStatus> rows = new ArrayList<>(wards.size());
        int signed = 0;
        for (Ward ward : wards) {
            HandoverBrief brief = saved.get(ward.id());
            if (brief == null && !active.contains(ward.id())) {
                continue;
            }
            boolean isSigned = brief != null && Handover.SIGNED.equals(brief.status());
            if (isSigned) {
                signed++;
            }
            rows.add(new WardHandoverStatus(ward.id(), ward.name(),
                    brief == null ? STATUS_MISSING : brief.status(),
                    brief == null ? null : brief.signedBy(),
                    brief == null ? null : brief.signedAt(),
                    brief == null ? 0 : brief.patientCount()));
        }
        // 未交班/草稿排前面（要处理的事优先），同组按病区名
        rows.sort(Comparator.comparing((WardHandoverStatus s) -> Handover.SIGNED.equals(s.status()))
                .thenComparing(WardHandoverStatus::wardName));
        return new HandoverStatusSummary(date, shift, rows.size(), signed,
                rows.size() - signed, List.copyOf(rows));
    }

    // ---------------------------------------------------------------- 内部

    private static HandoverBriefView toBriefView(HandoverBrief b) {
        return new HandoverBriefView(b.id(), b.shiftDate(), b.shiftCode(), b.status(),
                b.createdBy(), b.signedBy(), b.signedAt(), b.patientCount(), b.notedCount());
    }

    /**
     * 用本次提交的事项替换本报告的 OPEN 事项（已闭环的保留）。
     *
     * <p>为什么只替换 OPEN：接班者可能已经闭环了上一版草稿里的某条事项，
     * 全量删除会让“已办完”的证据消失；保留 DONE 才符合交接责任链。
     *
     * @return 本次登记的事项数（写审计用）
     */
    private int replaceOpenTasks(UUID handoverId, List<LiveItem> items,
                                 List<HandoverTaskReq> requested, String actor) {
        Map<UUID, LiveItem> byAdmission = new HashMap<>();
        for (LiveItem item : items) {
            byAdmission.put(item.admissionId(), item);
        }
        // 先校验并构造，再删旧数据：非法请求不该先清空草稿事项（虽然事务会回滚，
        // 但少一次无效写入，日志与锁都更干净）
        List<HandoverTask> tasks = new ArrayList<>();
        if (requested != null) {
            for (HandoverTaskReq req : requested) {
                if (req == null) {
                    continue;
                }
                String content = trimToNull(req.content());
                if (content == null) {
                    continue;
                }
                if (content.length() > 300) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "交班事项最多 300 字");
                }
                UUID admissionId = req.admissionId();
                LiveItem item = admissionId == null ? null : byAdmission.get(admissionId);
                if (admissionId != null && item == null) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                            "交班事项关联的患者不在本病区在院清单中");
                }
                tasks.add(new HandoverTask(UUID.randomUUID(), handoverId, admissionId,
                        item == null ? null : item.bedNo(),
                        item == null ? null : item.patientNo(),
                        content, HandoverTask.OPEN, actor, null, null, null));
            }
        }
        repo.deleteOpenHandoverTasks(handoverId);
        for (HandoverTask task : tasks) {
            repo.insertHandoverTask(task);
        }
        return tasks.size();
    }

    private static List<HandoverTaskView> taskViews(List<HandoverTask> tasks,
                                                    LocalDate date, String shift) {
        return tasks.stream().map(t -> toTaskView(t, date, shift)).toList();
    }

    private static List<HandoverTaskView> carriedViews(List<HandoverTaskEntry> entries) {
        return entries.stream()
                .map(e -> toTaskView(e.task(), e.shiftDate(), e.shiftCode()))
                .toList();
    }

    private static HandoverTaskView toTaskView(HandoverTask t, LocalDate date, String shift) {
        return new HandoverTaskView(t.id(), t.admissionId(), t.bedNo(), t.patientNo(),
                t.content(), t.status(), t.createdBy(), date, shift,
                t.closedBy(), t.closedAt(), t.closeNote());
    }

    private List<HandoverItemView> liveItems(UUID wardId, LocalDate today,
                                             Map<UUID, String> notes) {
        return snapshot(wardId, today, notes).stream()
                .map(i -> new HandoverItemView(i.admissionId(), i.bedNo(), i.patientNo(),
                        i.flags(), i.note()))
                .toList();
    }

    /**
     * 当前在院患者快照 + 风险项。
     *
     * <p>风险口径与出院预检一致（同机器码），另加“今日计划出院”：
     * 交班最常问的两句就是“谁今天要走”“谁还走不了”。
     */
    private List<LiveItem> snapshot(UUID wardId, LocalDate today, Map<UUID, String> notes) {
        List<Admission> admissions = repo.listActiveAdmissions(wardId);
        if (admissions.isEmpty()) {
            return List.of();
        }
        List<UUID> visitIds = admissions.stream().map(Admission::visitId).distinct().toList();
        List<UUID> admissionIds = admissions.stream().map(Admission::id).toList();
        Map<UUID, String> bedNos = new LinkedHashMap<>();
        for (Bed bed : repo.listBeds(wardId)) {
            bedNos.put(bed.id(), bed.bedNo());
        }

        Map<UUID, Integer> unsettled = repo.countUnsettledChargesByVisits(visitIds);
        Map<UUID, Integer> unbilledTemp = repo.countUnbilledTempOrdersByVisits(visitIds);
        Map<UUID, Integer> unbilledDaily = repo.countUnbilledDailyOrdersByVisits(visitIds,
                WardDischargeCheckService.earliestAdmissionDay(admissions, today), today);
        Map<UUID, Integer> unstoppedLong = repo.countUnstoppedLongOrdersByVisits(visitIds);
        Set<UUID> medPending = repo.listActiveDischargeMedicationAdmissions(admissionIds);

        List<LiveItem> items = new ArrayList<>(admissions.size());
        for (Admission a : admissions) {
            List<String> flags = new ArrayList<>(6);
            boolean inHospital = Admission.IN_HOSPITAL.equals(a.status());
            if (!inHospital) {
                flags.add(DischargeCheckService.CODE_ADMISSION_STATUS);
            }
            if (count(unsettled, a.visitId()) > 0) {
                flags.add(DischargeCheckService.CODE_UNSETTLED_CHARGE);
            }
            if (count(unbilledTemp, a.visitId()) > 0) {
                flags.add(DischargeCheckService.CODE_UNBILLED_TEMP_ORDER);
            }
            if (count(unbilledDaily, a.visitId()) > 0) {
                flags.add(DischargeCheckService.CODE_UNBILLED_DAILY_ORDER);
            }
            if (count(unstoppedLong, a.visitId()) > 0) {
                flags.add(DischargeCheckService.CODE_UNSTOPPED_LONG_ORDER);
            }
            if (medPending.contains(a.id())) {
                flags.add(DischargeCheckService.CODE_DISCHARGE_MED_PENDING);
            }
            LocalDate planned = a.plannedDischargeDate();
            if (planned != null && inHospital) {
                if (planned.isBefore(today)) {
                    flags.add(WardDischargeCheckService.CODE_OVERDUE_DISCHARGE);
                } else if (planned.isEqual(today)) {
                    flags.add(CODE_PLANNED_TODAY);
                }
            }
            String bedNo = a.bedId() == null ? null : bedNos.get(a.bedId());
            items.add(new LiveItem(a.id(), bedNo, a.patientNo(), List.copyOf(flags),
                    notes.get(a.id())));
        }
        items.sort((x, y) -> {
            String bx = x.bedNo() == null ? "\uffff" : x.bedNo();
            String by = y.bedNo() == null ? "\uffff" : y.bedNo();
            return bx.compareTo(by);
        });
        return items;
    }

    private static Map<UUID, String> noteMap(List<HandoverItemReq> items) {
        Map<UUID, String> map = new HashMap<>();
        if (items == null) {
            return map;
        }
        for (HandoverItemReq item : items) {
            if (item != null && item.admissionId() != null) {
                map.put(item.admissionId(), trimToNull(item.note()));
            }
        }
        return map;
    }

    private static List<HandoverItemView> toViews(List<HandoverItem> items) {
        return items.stream()
                .map(i -> new HandoverItemView(i.admissionId(), i.bedNo(), i.patientNo(),
                        splitFlags(i.flags()), i.note()))
                .toList();
    }

    private static List<String> splitFlags(String flags) {
        if (flags == null || flags.isBlank()) {
            return List.of();
        }
        return List.of(flags.split(","));
    }

    private Ward requireWard(UUID wardId) {
        Ward ward = repo.findWard(wardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "病区不存在"));
        if (!ward.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "病区已停用");
        }
        return ward;
    }

    /** 班次只允许 DAY / EVENING / NIGHT；空值按当前时间推断。 */
    private static String normalizeShift(String shiftCode) {
        if (shiftCode == null || shiftCode.isBlank()) {
            int hour = java.time.ZonedDateTime.now(BusinessTime.zone()).getHour();
            if (hour >= 8 && hour < 16) {
                return Handover.DAY;
            }
            if (hour >= 16) {
                return Handover.EVENING;
            }
            return Handover.NIGHT;
        }
        String shift = shiftCode.trim().toUpperCase();
        if (!Handover.DAY.equals(shift) && !Handover.EVENING.equals(shift)
                && !Handover.NIGHT.equals(shift)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "班次只能是 DAY / EVENING / NIGHT");
        }
        return shift;
    }

    private static int count(Map<UUID, Integer> counts, UUID visitId) {
        Integer n = counts.get(visitId);
        return n == null ? 0 : n;
    }

    private static String trimToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void audit(String actor, String action, UUID handoverId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "HANDOVER", handoverId.toString(),
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "HANDOVER", handoverId.toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }

    /** 交班清单一行（内部）。 */
    private record LiveItem(UUID admissionId, String bedNo, String patientNo,
                            List<String> flags, String note) {
    }
}
