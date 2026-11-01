package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.AnesthesiaRecord;
import com.medassist.clinical.domain.ClinicalModel.AnesthesiaVital;
import com.medassist.clinical.domain.ClinicalModel.IntraopNursing;
import com.medassist.clinical.domain.ClinicalModel.SurgeryCase;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.TheatreDtos.AnesthesiaReq;
import com.medassist.clinical.web.TheatreDtos.AnesthesiaView;
import com.medassist.clinical.web.TheatreDtos.NursingReq;
import com.medassist.clinical.web.TheatreDtos.VitalReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 术中记录：麻醉记录单 + 术中生命体征 + 术中护理。
 *
 * <p>三条业务底线：
 * <ol>
 *   <li>只有进行中/已完成的手术能写术中记录（还没开始的手术谈不上麻醉/护理记录）；</li>
 *   <li>签署是终态：签署后连生命体征都不能再追加（数据库触发器兜底，应用层只是提前失败）；</li>
 *   <li>签署必须有内容：麻醉记录必须有起止时间与至少一条生命体征；护理记录必须有洗手与巡回护士。</li>
 * </ol>
 */
@Service
public class IntraopService {

    private final ClinicalRepository repo;

    public IntraopService(ClinicalRepository repo) {
        this.repo = repo;
    }

    // ---------------------------------------------------------------- 麻醉记录

    public AnesthesiaView getAnesthesia(UUID caseId) {
        AnesthesiaRecord record = repo.findAnesthesiaByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚无麻醉记录"));
        return new AnesthesiaView(record, repo.listVitals(record.id()));
    }

    @Transactional
    public AnesthesiaView saveAnesthesia(UUID caseId, AnesthesiaReq req, String actor) {
        SurgeryCase surgery = requireIntraopCase(caseId);
        AnesthesiaRecord existing = repo.findAnesthesiaByCase(caseId).orElse(null);
        if (existing != null && AnesthesiaRecord.SIGNED.equals(existing.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "麻醉记录已签署，不能再修改（如需更正请走病案修改审批）");
        }
        if (req.startedAt() != null && req.endedAt() != null
                && req.endedAt().isBefore(req.startedAt())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "麻醉结束时间不能早于开始时间");
        }
        if (existing == null) {
            AnesthesiaRecord created = new AnesthesiaRecord(UUID.randomUUID(), caseId,
                    upper(req.method()), upper(req.asaGrade()), blankToNull(req.anesthesiologist()),
                    upper(req.airway()), req.startedAt(), req.endedAt(), blankToNull(req.notes()),
                    AnesthesiaRecord.DRAFT, null, null, actor, 0L, Instant.now());
            try {
                repo.insertAnesthesia(created);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "该手术已有麻醉记录，请刷新后重试");
            }
            audit(actor, "ANESTHESIA_SAVE", "ANESTHESIA_RECORD", created.id(),
                    surgery.caseNo() + " create");
            return new AnesthesiaView(created, List.of());
        }
        if (req.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "更新麻醉记录必须携带 version（乐观锁）");
        }
        AnesthesiaRecord updated = new AnesthesiaRecord(existing.id(), caseId,
                upper(req.method()), upper(req.asaGrade()), blankToNull(req.anesthesiologist()),
                upper(req.airway()), req.startedAt(), req.endedAt(), blankToNull(req.notes()),
                existing.status(), existing.signedBy(), existing.signedAt(), existing.createdBy(),
                existing.version(), existing.createdAt());
        if (!repo.updateAnesthesiaDraft(updated, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "麻醉记录已被他人修改，请刷新后重试");
        }
        audit(actor, "ANESTHESIA_SAVE", "ANESTHESIA_RECORD", existing.id(),
                surgery.caseNo() + " update");
        return new AnesthesiaView(
                repo.findAnesthesiaByCase(caseId).orElseThrow(), repo.listVitals(existing.id()));
    }

    @Transactional
    public AnesthesiaView signAnesthesia(UUID caseId, long version, String actor) {
        SurgeryCase surgery = requireIntraopCase(caseId);
        AnesthesiaRecord record = repo.findAnesthesiaByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚无麻醉记录"));
        if (AnesthesiaRecord.SIGNED.equals(record.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "麻醉记录已签署，请勿重复签署");
        }
        if (record.startedAt() == null || record.endedAt() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "签署前必须填写麻醉开始与结束时间");
        }
        if (repo.countVitals(record.id()) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "签署前至少要记录一条术中生命体征");
        }
        if (!repo.signAnesthesia(record.id(), actor, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "麻醉记录已被他人修改，请刷新后重试");
        }
        audit(actor, "ANESTHESIA_SIGN", "ANESTHESIA_RECORD", record.id(), surgery.caseNo());
        return new AnesthesiaView(repo.findAnesthesiaByCase(caseId).orElseThrow(),
                repo.listVitals(record.id()));
    }

    /**
     * 追加一条术中生命体征。
     *
     * <p>同一时间点重复提交返回 409（唯一约束兜底），而不是静默覆盖——生命体征是原始记录。
     */
    @Transactional
    public AnesthesiaVital addVital(UUID caseId, VitalReq req, String actor) {
        requireIntraopCase(caseId);
        AnesthesiaRecord record = repo.findAnesthesiaByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚无麻醉记录"));
        if (AnesthesiaRecord.SIGNED.equals(record.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "麻醉记录已签署，不能追加生命体征");
        }
        if (req.recordedAt() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "必须填写记录时间");
        }
        if (record.startedAt() != null && req.recordedAt().isBefore(record.startedAt())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "记录时间不能早于麻醉开始时间");
        }
        if (req.heartRate() == null && req.sbp() == null && req.dbp() == null
                && req.spo2() == null && req.etco2() == null && req.temperature() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少填写一项生命体征");
        }
        AnesthesiaVital vital = new AnesthesiaVital(UUID.randomUUID(), record.id(),
                req.recordedAt(), req.heartRate(), req.sbp(), req.dbp(), req.spo2(),
                req.etco2(), req.temperature(), blankToNull(req.note()), Instant.now());
        try {
            repo.insertVital(vital, actor);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "该时间点已有生命体征记录，请调整时间");
        }
        audit(actor, "ANESTHESIA_VITAL_ADD", "ANESTHESIA_RECORD", record.id(),
                vital.recordedAt().toString());
        return vital;
    }

    public List<AnesthesiaVital> vitals(UUID caseId) {
        AnesthesiaRecord record = repo.findAnesthesiaByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚无麻醉记录"));
        return repo.listVitals(record.id());
    }

    // ---------------------------------------------------------------- 术中护理

    public IntraopNursing getNursing(UUID caseId) {
        return repo.findNursingByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该手术尚无术中护理记录"));
    }

    @Transactional
    public IntraopNursing saveNursing(UUID caseId, NursingReq req, String actor) {
        SurgeryCase surgery = requireIntraopCase(caseId);
        IntraopNursing existing = repo.findNursingByCase(caseId).orElse(null);
        if (existing != null && IntraopNursing.SIGNED.equals(existing.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "术中护理记录已签署，不能再修改（如需更正请走病案修改审批）");
        }
        int specimenCount = req.specimenCount() == null ? 0 : req.specimenCount();
        if (req.tourniquetStart() != null && req.tourniquetEnd() != null
                && req.tourniquetEnd().isBefore(req.tourniquetStart())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "止血带结束时间不能早于开始时间");
        }
        if (existing == null) {
            IntraopNursing created = new IntraopNursing(UUID.randomUUID(), caseId,
                    blankToNull(req.scrubNurse()), blankToNull(req.circulatingNurse()),
                    upper(req.patientPosition()), blankToNull(req.skinPrep()),
                    req.tourniquetStart(), req.tourniquetEnd(), specimenCount,
                    blankToNull(req.notes()), IntraopNursing.DRAFT, null, null, actor, 0L,
                    Instant.now());
            try {
                repo.insertNursing(created);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ErrorCode.CONFLICT, "该手术已有术中护理记录，请刷新后重试");
            }
            audit(actor, "INTRAOP_NURSING_SAVE", "INTRAOP_NURSING", created.id(),
                    surgery.caseNo() + " create");
            return created;
        }
        if (req.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "更新术中护理记录必须携带 version（乐观锁）");
        }
        IntraopNursing updated = new IntraopNursing(existing.id(), caseId,
                blankToNull(req.scrubNurse()), blankToNull(req.circulatingNurse()),
                upper(req.patientPosition()), blankToNull(req.skinPrep()),
                req.tourniquetStart(), req.tourniquetEnd(), specimenCount,
                blankToNull(req.notes()), existing.status(), existing.signedBy(),
                existing.signedAt(), existing.createdBy(), existing.version(), existing.createdAt());
        if (!repo.updateNursingDraft(updated, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "术中护理记录已被他人修改，请刷新后重试");
        }
        audit(actor, "INTRAOP_NURSING_SAVE", "INTRAOP_NURSING", existing.id(),
                surgery.caseNo() + " update");
        return repo.findNursingByCase(caseId).orElseThrow();
    }

    @Transactional
    public IntraopNursing signNursing(UUID caseId, long version, String actor) {
        SurgeryCase surgery = requireIntraopCase(caseId);
        IntraopNursing nursing = repo.findNursingByCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "该手术尚无术中护理记录"));
        if (IntraopNursing.SIGNED.equals(nursing.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "术中护理记录已签署，请勿重复签署");
        }
        if (isBlank(nursing.scrubNurse()) || isBlank(nursing.circulatingNurse())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "签署前必须填写洗手护士与巡回护士");
        }
        if (!repo.signNursing(nursing.id(), actor, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "术中护理记录已被他人修改，请刷新后重试");
        }
        audit(actor, "INTRAOP_NURSING_SIGN", "INTRAOP_NURSING", nursing.id(), surgery.caseNo());
        return repo.findNursingByCase(caseId).orElseThrow();
    }

    // ---------------------------------------------------------------- 内部工具

    /** 术中记录只允许在手术进行中/完成后书写。 */
    private SurgeryCase requireIntraopCase(UUID caseId) {
        SurgeryCase surgery = repo.findSurgeryCase(caseId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到该手术记录"));
        if (!SurgeryCase.IN_PROGRESS.equals(surgery.status())
                && !SurgeryCase.COMPLETED.equals(surgery.status())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "只有进行中或已完成的手术可以书写术中记录，当前：" + surgery.status());
        }
        return surgery;
    }

    private void audit(String actor, String action, String targetType, UUID targetId,
                       String detail) {
        repo.logChange(actor, action, targetType, targetId.toString(),
                "{\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
