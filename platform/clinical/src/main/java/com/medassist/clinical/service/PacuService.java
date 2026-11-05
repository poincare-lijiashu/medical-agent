package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.PacuBoardRow;
import com.medassist.clinical.domain.ClinicalModel.PacuObservation;
import com.medassist.clinical.domain.ClinicalModel.PacuStay;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.PacuDtos.AdmitReq;
import com.medassist.clinical.web.PacuDtos.DischargeReq;
import com.medassist.clinical.web.PacuDtos.ObservationReq;
import com.medassist.clinical.web.PacuDtos.PacuView;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.CampusFilters;
import com.medassist.common.security.DataScope;
import com.medassist.common.time.BusinessTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 麻醉复苏（PACU）闭环。
 *
 * <p>四条业务底线（与麻醉记录/术中护理同口径）：
 * <ol>
 *   <li>只有<b>已完成</b>的手术能入复苏室——手术没结束谈不上复苏；</li>
 *   <li>入室、观察、出室、签名是按顺序的：入室后才可记录，出室后不可再追加观察；</li>
 *   <li>出室必须有依据：最近一次 Aldrete ≥ 9 分，否则拒绝（不存在"绕过评分直接出室"）；</li>
 *   <li>签名必须由另一名医护人员完成（四眼复核），签名后整份记录冻结（数据库触发器兜底）。</li>
 * </ol>
 *
 * <p>院区与科室在入室时从就诊固化，看板查询据此在 SQL 层收敛（不做事后内存过滤）。
 */
@Service
public class PacuService {

    private static final int LIST_LIMIT = 100;
    private static final Set<String> STATUSES = Set.of(PacuStay.IN_PACU, PacuStay.OUT);
    private static final Set<String> DESTINATIONS = Set.of(PacuStay.DEST_WARD, PacuStay.DEST_ICU,
            PacuStay.DEST_DISCHARGE);

    private final ClinicalRepository repo;

    public PacuService(ClinicalRepository repo) {
        this.repo = repo;
    }

    // ---------------------------------------------------------------- 查询

    public PacuView get(UUID caseId) {
        PacuStay stay = repo.findPacuStayByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PACU_STAY_NOT_FOUND));
        return new PacuView(stay, repo.listPacuObservations(stay.id()));
    }

    /**
     * 复苏看板。
     *
     * <p>{@code date} 是业务日期（G76），换算成东八区的半开区间后再下推 SQL；
     * 科室/院区同样在 SQL 层收敛，避免不可见行占掉 {@code limit} 额度。
     */
    public List<PacuBoardRow> board(String status, LocalDate date, int limit, DataScope scope) {
        String normalized = status == null || status.isBlank()
                ? null : status.trim().toUpperCase();
        if (normalized != null && !STATUSES.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "复苏状态只能是 IN_PACU / OUT");
        }
        int size = limit <= 0 ? LIST_LIMIT : Math.min(limit, LIST_LIMIT);
        Instant from = null;
        Instant to = null;
        if (date != null) {
            from = date.atStartOfDay(BusinessTime.zone()).toInstant();
            to = date.plusDays(1).atStartOfDay(BusinessTime.zone()).toInstant();
        }
        return repo.listPacuBoard(normalized, from, to, deptCsv(scope),
                CampusFilters.csv(scope.campusFilter()), size);
    }

    // ---------------------------------------------------------------- 入室

    @Transactional
    public PacuView admit(UUID caseId, AdmitReq req, String actor) {
        SurgeryCase surgery = repo.findSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SURGERY_CASE_NOT_FOUND));
        if (!SurgeryCase.COMPLETED.equals(surgery.status())) {
            throw new BusinessException(ErrorCode.PACU_CASE_NOT_ELIGIBLE,
                    "只有已完成的手术可以进入复苏室，当前：" + surgery.status());
        }
        UUID deptId = repo.findVisitDepartmentByCaseId(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SURGERY_CASE_NOT_FOUND));
        UUID campusId = repo.findVisitCampusByCaseId(caseId).orElse(DataScope.DEFAULT_CAMPUS_ID);
        Instant admittedAt = req.admittedAt() == null ? Instant.now() : req.admittedAt();
        if (surgery.endedAt() != null && admittedAt.isBefore(surgery.endedAt())) {
            throw new BusinessException(ErrorCode.PACU_OBSERVATION_TIME_INVALID,
                    "入复苏室时间不能早于手术结束时间");
        }
        PacuStay stay = new PacuStay(UUID.randomUUID(), caseId, blankToNull(req.bedNo()),
                deptId, campusId, admittedAt, null, null, PacuStay.IN_PACU, null, null,
                requireActor(actor), 0L, Instant.now());
        try {
            repo.insertPacuStay(stay);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.PACU_STAY_EXISTS);
        }
        audit(actor, "PACU_ADMIT", stay.id(), surgery.caseNo() + " bed=" + nullToEmpty(stay.bedNo()));
        return new PacuView(stay, List.of());
    }

    // ---------------------------------------------------------------- 观察

    @Transactional
    public PacuObservation addObservation(UUID caseId, ObservationReq req, String actor) {
        PacuStay stay = requireStay(caseId);
        requireInPacu(stay);
        if (req.recordedAt().isBefore(stay.admittedAt())) {
            throw new BusinessException(ErrorCode.PACU_OBSERVATION_TIME_INVALID,
                    "复苏观察时间不能早于入复苏室时间");
        }
        int total = req.activity() + req.respiration() + req.circulation()
                + req.consciousness() + req.oxygenation();
        PacuObservation observation = new PacuObservation(UUID.randomUUID(), stay.id(),
                req.recordedAt(), req.activity(), req.respiration(), req.circulation(),
                req.consciousness(), req.oxygenation(), total, req.heartRate(), req.sbp(),
                req.dbp(), req.spo2(), req.temperature(), blankToNull(req.note()), Instant.now());
        try {
            repo.insertPacuObservation(observation, requireActor(actor));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.PACU_OBSERVATION_DUPLICATED);
        }
        audit(actor, "PACU_OBSERVATION", stay.id(), "aldrete=" + total);
        return observation;
    }

    // ---------------------------------------------------------------- 出室 / 签名

    @Transactional
    public PacuView discharge(UUID caseId, DischargeReq req, String actor) {
        PacuStay stay = requireStay(caseId);
        requireInPacu(stay);
        String destination = req.destination() == null
                ? null : req.destination().trim().toUpperCase();
        if (destination == null || !DESTINATIONS.contains(destination)) {
            throw new BusinessException(ErrorCode.PACU_DESTINATION_INVALID);
        }
        Instant dischargedAt = req.dischargedAt() == null ? Instant.now() : req.dischargedAt();
        if (dischargedAt.isBefore(stay.admittedAt())) {
            throw new BusinessException(ErrorCode.PACU_OBSERVATION_TIME_INVALID,
                    "出复苏室时间不能早于入室时间");
        }
        PacuObservation latest = repo.latestPacuObservation(stay.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.PACU_DISCHARGE_NOT_READY,
                        "出室前至少要记录一条 Aldrete 评分"));
        if (!latest.meetsDischargeCriteria()) {
            throw new BusinessException(ErrorCode.PACU_DISCHARGE_NOT_READY,
                    "最近一次 Aldrete 评分 " + latest.total() + " 分，未达 "
                            + PacuObservation.DISCHARGE_THRESHOLD + " 分");
        }
        if (!repo.dischargePacu(stay.id(), dischargedAt, destination, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "复苏记录已被他人修改，请刷新后重试");
        }
        audit(actor, "PACU_DISCHARGE", stay.id(), "destination=" + destination);
        return get(caseId);
    }

    @Transactional
    public PacuView sign(UUID caseId, long version, String actor) {
        PacuStay stay = requireStay(caseId);
        if (stay.signed()) {
            throw new BusinessException(ErrorCode.PACU_STAY_STATUS_INVALID,
                    "复苏记录已签名，请勿重复签署");
        }
        if (!stay.out()) {
            throw new BusinessException(ErrorCode.PACU_STAY_STATUS_INVALID,
                    "患者出复苏室后才能签名");
        }
        String signer = requireActor(actor);
        if (signer.equals(stay.createdBy())) {
            throw new BusinessException(ErrorCode.PACU_SELF_SIGN);
        }
        if (!repo.signPacu(stay.id(), signer, version)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "复苏记录已被他人修改，请刷新后重试");
        }
        audit(actor, "PACU_SIGN", stay.id(), "signedBy=" + signer);
        return get(caseId);
    }

    // ---------------------------------------------------------------- 内部

    private PacuStay requireStay(UUID caseId) {
        return repo.findPacuStayByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PACU_STAY_NOT_FOUND));
    }

    /** 只有"在室内、未签名"的复苏记录可以继续书写。 */
    private void requireInPacu(PacuStay stay) {
        if (stay.signed()) {
            throw new BusinessException(ErrorCode.PACU_STAY_STATUS_INVALID,
                    "复苏记录已签名，不能再修改");
        }
        if (stay.out()) {
            throw new BusinessException(ErrorCode.PACU_STAY_STATUS_INVALID,
                    "患者已出复苏室，不能追加观察记录");
        }
    }

    /** 科室维度下推 SQL（空串 = 跨科室角色不限）；与院区一样在 LIMIT 之前生效。 */
    private static String deptCsv(DataScope scope) {
        if (scope.allDepartments()) {
            return "";
        }
        return scope.deptCodes().stream().sorted().map(UUID::toString)
                .collect(Collectors.joining(","));
    }

    private void audit(String actor, String action, UUID targetId, String detail) {
        repo.logChange(requireActor(actor), action, "PACU_STAY", targetId.toString(),
                "{\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String requireActor(String actor) {
        return actor == null || actor.isBlank() ? "system" : actor.trim();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
