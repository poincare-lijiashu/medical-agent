package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.client.PacsDicomGateway;
import com.medassist.tech.domain.TechModel.PacsAccessLog;
import com.medassist.tech.domain.TechModel.PacsSeries;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.PacsDtos.SeriesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * DICOMweb 调阅编排：本地检查校验 → 远端查询 → 序列缓存 → 调阅审计。
 *
 * <p>安全要点（避免把本服务变成"任意 URL 代理"）：
 * <ul>
 *   <li>只允许调阅已在本地 {@code pacs_study} 登记的 StudyInstanceUID，未知 UID 直接 404；</li>
 *   <li>Series/SOP UID 必须是合法 DICOM UID（数字与点），防止路径穿越；</li>
 *   <li>PACS 未配置时明确返回 503，而不是返回空列表让前端误以为"没有影像"。</li>
 * </ul>
 *
 * <p>可用性：远端不可用时若已有缓存序列，返回缓存并标注 {@code CACHED}，
 * 让阅片台在 PACS 短暂故障时仍能确认"检查里有哪些序列"，但不返回像素（像素只能实时取）。
 */
@Service
public class PacsDicomService {

    private static final Logger log = LoggerFactory.getLogger(PacsDicomService.class);
    private static final Pattern UID = Pattern.compile("[0-9]+(\\.[0-9]+)+");

    private final TechRepository repo;
    private final PacsDicomGateway gateway;

    public PacsDicomService(TechRepository repo, PacsDicomGateway gateway) {
        this.repo = repo;
        this.gateway = gateway;
    }

    /** 查询检查的序列：优先 PACS 实时结果，失败时回退缓存。 */
    @Transactional
    public SeriesResponse listSeries(String studyInstanceUid, String actor) {
        PacsStudy study = requireStudy(studyInstanceUid);
        String operator = requireActor(actor);
        if (!gateway.configured()) {
            throw new BusinessException(ErrorCode.TECH_PACS_NOT_CONFIGURED);
        }
        List<PacsDicomGateway.SeriesPayload> remote;
        try {
            remote = gateway.listSeries(study.studyInstanceUid());
        } catch (BusinessException e) {
            List<PacsSeries> cached = repo.listPacsSeries(study.id());
            if (cached.isEmpty()) {
                throw e;
            }
            log.info("tech.pacs.series_cache_fallback study={} series={}",
                    study.studyInstanceUid(), cached.size());
            audit(study, operator, PacsAccessLog.VIEW_SERIES, null, null,
                    "{\"cached\":true,\"series\":" + cached.size() + "}");
            return new SeriesResponse(cached, "CACHED",
                    "影像服务暂不可用，展示最近一次同步的序列信息（影像需服务恢复后调阅）");
        }
        replaceCache(study, remote);
        audit(study, operator, PacsAccessLog.VIEW_SERIES, null, null,
                "{\"cached\":false,\"series\":" + remote.size() + "}");
        return new SeriesResponse(repo.listPacsSeries(study.id()), "LIVE", null);
    }

    /** 取单张影像（WADO-RS）；每次调阅都写审计。 */
    @Transactional
    public InstancePayload fetchInstance(String studyInstanceUid, String seriesInstanceUid,
                                         String sopInstanceUid, String actor) {
        PacsStudy study = requireStudy(studyInstanceUid);
        String operator = requireActor(actor);
        String series = requireUid(seriesInstanceUid, "SeriesInstanceUID");
        String sop = requireUid(sopInstanceUid, "SOPInstanceUID");
        if (!gateway.configured()) {
            throw new BusinessException(ErrorCode.TECH_PACS_NOT_CONFIGURED);
        }
        byte[] bytes = gateway.fetchInstance(study.studyInstanceUid(), series, sop);
        audit(study, operator, PacsAccessLog.FETCH_IMAGE, series, sop,
                "{\"bytes\":" + bytes.length + "}");
        return new InstancePayload(bytes, "application/dicom");
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 用 PACS 实时结果校准缓存：仍存在的序列按 UID 原地更新（保留主键与同步历史），
     * 已消失的序列按主键删除。避免每次同步都换一批 ID，导致前端引用与审计线索断裂。
     */
    private void replaceCache(PacsStudy study, List<PacsDicomGateway.SeriesPayload> remote) {
        Map<String, PacsSeries> existing = new HashMap<>();
        for (PacsSeries s : repo.listPacsSeries(study.id())) {
            existing.put(s.seriesInstanceUid(), s);
        }
        Set<String> remoteUids = new HashSet<>();
        Instant now = Instant.now();
        for (PacsDicomGateway.SeriesPayload s : remote) {
            remoteUids.add(s.seriesInstanceUid());
            PacsSeries previous = existing.get(s.seriesInstanceUid());
            repo.insertPacsSeries(new PacsSeries(
                    previous == null ? UUID.randomUUID() : previous.id(), study.id(),
                    s.seriesInstanceUid(), s.seriesNumber(), s.modality(), s.bodyPart(),
                    s.description(), s.instanceCount() == null ? 0 : s.instanceCount(), now));
        }
        for (PacsSeries stale : existing.values()) {
            if (!remoteUids.contains(stale.seriesInstanceUid())) {
                repo.deletePacsSeriesById(stale.id());
            }
        }
    }

    private void audit(PacsStudy study, String actor, String action, String seriesUid,
                       String sopUid, String detail) {
        repo.insertPacsAccess(new PacsAccessLog(UUID.randomUUID(), study.id(),
                study.studyInstanceUid(), study.accessionNumber(), actor, action,
                seriesUid, sopUid, detail, Instant.now()));
    }

    private PacsStudy requireStudy(String studyInstanceUid) {
        String uid = studyInstanceUid == null ? "" : studyInstanceUid.trim();
        return repo.findPacsStudyByUid(uid)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_PACS_STUDY_NOT_FOUND));
    }

    private static String requireUid(String value, String field) {
        String uid = value == null ? "" : value.trim();
        if (!UID.matcher(uid).matches() || uid.length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    field + " 不是合法的 DICOM UID（数字与点，长度不超过 64）");
        }
        return uid;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "缺少操作人，影像调阅必须记录调阅人");
        }
        return actor.trim();
    }

    /** 影像字节流 + 内容类型。 */
    public record InstancePayload(byte[] bytes, String contentType) {
    }
}
