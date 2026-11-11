package com.medassist.clinical.service;

import com.medassist.clinical.client.EmrArchiveClient;
import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Bed;
import com.medassist.clinical.domain.ClinicalModel.Ward;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.ClinicalDtos.WardDischargeReadiness;
import com.medassist.clinical.web.ClinicalDtos.WardDischargeReadinessView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 病区出院预检（只读批量）。
 *
 * <p>为什么值得独立于单患者体检：
 * <ul>
 *   <li>护士站要的是"全病区谁还走不了、卡在哪一项"，逐个点开 40 次不现实；</li>
 *   <li>批量必须走集合查询：40 人 × 5 条 SQL 会把连接池打满，
 *       这里全部按 {@code visitIds = ANY(...)} 一次取回，EMR 也只调一次；</li>
 *   <li>口径与单患者体检、出院门禁**完全一致**（阻断项码相同），
 *       不允许出现"预检说能走、点进去发现走不了"。</li>
 * </ul>
 *
 * <p>刻意不返回金额：病区列表面向所有有住院读权限的角色，
 * 费用金额要额外的 FINANCE_READ；数字留在单个患者页展示。
 */
@Service
public class WardDischargeCheckService {

    private final ClinicalRepository repo;
    private final EmrArchiveClient emrClient;
    private final ObjectMapper objectMapper;

    public WardDischargeCheckService(ClinicalRepository repo, EmrArchiveClient emrClient,
                                     ObjectMapper objectMapper) {
        this.repo = repo;
        this.emrClient = emrClient;
        this.objectMapper = objectMapper;
    }

    public WardDischargeReadinessView check(UUID wardId, String actor) {
        Ward ward = repo.findWard(wardId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "病区不存在"));
        List<Admission> admissions = repo.listActiveAdmissions(wardId);
        if (admissions.isEmpty()) {
            audit(actor, wardId, 0, 0, false);
            return new WardDischargeReadinessView(wardId, ward.name(), Instant.now(),
                    0, 0, 0, false, List.of());
        }

        List<UUID> visitIds = admissions.stream().map(Admission::visitId).distinct().toList();
        List<UUID> admissionIds = admissions.stream().map(Admission::id).toList();
        LocalDate today = BusinessTime.today();
        Map<UUID, String> bedNos = new LinkedHashMap<>();
        for (Bed bed : repo.listBeds(wardId)) {
            bedNos.put(bed.id(), bed.bedNo());
        }

        Map<UUID, Integer> unsettled = repo.countUnsettledChargesByVisits(visitIds);
        Map<UUID, Integer> unbilledTemp = repo.countUnbilledTempOrdersByVisits(visitIds);
        Map<UUID, Integer> unbilledDaily = repo.countUnbilledDailyOrdersByVisits(visitIds,
                earliestAdmissionDay(admissions, today), today);
        Map<UUID, Integer> unstoppedLong = repo.countUnstoppedLongOrdersByVisits(visitIds);
        Set<UUID> medPending = repo.listActiveDischargeMedicationAdmissions(admissionIds);
        Map<UUID, EmrArchiveClient.ArchiveSummary> emr = emrClient.batchArchiveSummary(visitIds);
        boolean emrChecked = !emr.isEmpty();

        List<WardDischargeReadiness> items = new ArrayList<>(admissions.size());
        for (Admission a : admissions) {
            List<String> blockers = new ArrayList<>(3);
            List<String> warnings = new ArrayList<>(3);
            if (!Admission.IN_HOSPITAL.equals(a.status())) {
                blockers.add(DischargeCheckService.CODE_ADMISSION_STATUS);
            }
            if (count(unsettled, a.visitId()) > 0) {
                blockers.add(DischargeCheckService.CODE_UNSETTLED_CHARGE);
            }
            if (count(unbilledTemp, a.visitId()) > 0) {
                blockers.add(DischargeCheckService.CODE_UNBILLED_TEMP_ORDER);
            }
            if (count(unbilledDaily, a.visitId()) > 0) {
                blockers.add(DischargeCheckService.CODE_UNBILLED_DAILY_ORDER);
            }
            if (count(unstoppedLong, a.visitId()) > 0) {
                warnings.add(DischargeCheckService.CODE_UNSTOPPED_LONG_ORDER);
            }
            if (medPending.contains(a.id())) {
                warnings.add(DischargeCheckService.CODE_DISCHARGE_MED_PENDING);
            }
            EmrArchiveClient.ArchiveSummary summary = emr.get(a.visitId());
            if (summary != null && (summary.total() == 0 || summary.unarchived() > 0)) {
                warnings.add(DischargeCheckService.CODE_EMR_ARCHIVE);
            }
            items.add(new WardDischargeReadiness(a.id(), a.admissionNo(), a.patientNo(),
                    a.bedId(), a.bedId() == null ? null : bedNos.get(a.bedId()), a.status(),
                    blockers.isEmpty(), blockers.size(), warnings.size(),
                    List.copyOf(blockers), List.copyOf(warnings)));
        }
        // 走不了的排前面；同组按床号，未分床排最后
        items.sort(Comparator.comparing(WardDischargeReadiness::ready)
                .thenComparing(i -> i.bedNo() == null ? "\uffff" : i.bedNo()));

        int ready = (int) items.stream().filter(WardDischargeReadiness::ready).count();
        audit(actor, wardId, items.size(), items.size() - ready, emrChecked);
        return new WardDischargeReadinessView(wardId, ward.name(), Instant.now(),
                items.size(), ready, items.size() - ready, emrChecked, List.copyOf(items));
    }

    // ---------------------------------------------------------------- 内部

    /** 病区内最早入院日：统一区间对每个就诊都成立（早于医嘱起效日不会 JOIN 出缺口）。 */
    private static LocalDate earliestAdmissionDay(List<Admission> admissions, LocalDate today) {
        LocalDate earliest = null;
        for (Admission a : admissions) {
            if (a.admittedAt() == null) {
                continue;
            }
            LocalDate day = a.admittedAt().atZone(BusinessTime.zone()).toLocalDate();
            if (earliest == null || day.isBefore(earliest)) {
                earliest = day;
            }
        }
        if (earliest == null || earliest.isAfter(today)) {
            return today;
        }
        return earliest;
    }

    private static int count(Map<UUID, Integer> counts, UUID visitId) {
        Integer n = counts.get(visitId);
        return n == null ? 0 : n;
    }

    private void audit(String actor, UUID wardId, int total, int blocked, boolean emrChecked) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("total", total);
        detail.put("blocked", blocked);
        detail.put("emrChecked", emrChecked);
        try {
            repo.logChange(actor, "WARD_DISCHARGE_CHECK", "WARD", wardId.toString(),
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, "WARD_DISCHARGE_CHECK", "WARD", wardId.toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
