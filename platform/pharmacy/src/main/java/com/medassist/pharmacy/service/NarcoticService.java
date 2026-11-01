package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticPrescriber;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticRegister;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.NarcoticDtos.AmpouleReturnReq;
import com.medassist.pharmacy.web.NarcoticDtos.GrantPrescriberReq;
import com.medassist.pharmacy.web.NarcoticDtos.RevokePrescriberReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 麻醉药品"五专"管理：处方权名单、专册登记、空安瓿回收。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>专权</b>：只有 {@code narcotic_prescriber} 名单内且 {@code enabled} 的医师可以开麻醉药品；
 *       这是人事资格，不是权限点——有开方权限不等于有麻醉药品处方权；</li>
 *   <li><b>专册</b>：每次发药明细自动登记一条（患者、药品、批号、处方医师、发药人、核对人），
 *       事实字段不可修改（触发器兜底）；</li>
 *   <li><b>空安瓿</b>：登记状态 {@code PENDING_RETURN → RETURNED}，回收数量必须等于发药数量；
 *       同一患者同一药品存在未回收登记时，拒绝再次开具。</li>
 * </ol>
 *
 * <p>为什么"未回收就拒绝再开"要做成硬门槛：空安瓿是防止麻醉药品流弊的唯一实物凭证，
 * 只在制度里写"应及时回收"等于没有约束。
 */
@Service
public class NarcoticService {

    /** 专册查询区间上限（与临床报表口径一致，防止一次拉全年）。 */
    private static final int MAX_RANGE_DAYS = 92;

    private final PharmacyRepository repo;
    private final ObjectMapper objectMapper;

    public NarcoticService(PharmacyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 处方权

    public List<NarcoticPrescriber> prescribers(Boolean enabled, Integer limit) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        return repo.listNarcoticPrescribers(enabled, size);
    }

    /** 授予（或重新启用）处方权；重复授予是幂等的。 */
    @Transactional
    public NarcoticPrescriber grant(GrantPrescriberReq req, String actor) {
        String prescriber = req.prescriber().trim();
        NarcoticPrescriber record = new NarcoticPrescriber(UUID.randomUUID(), prescriber,
                blankToNull(req.licenseNo()), actor, Instant.now(), null, null, null, true, 0L);
        repo.upsertNarcoticPrescriber(record);
        NarcoticPrescriber saved = repo.findNarcoticPrescriber(prescriber)
                .orElseThrow(() -> new BusinessException(ErrorCode.NARCOTIC_PRESCRIBER_NOT_FOUND));
        audit(actor, "NARCOTIC_PRESCRIBER_GRANT", prescriber,
                Map.of("licenseNo", saved.licenseNo() == null ? "" : saved.licenseNo(),
                        "version", saved.version()));
        return saved;
    }

    /** 撤销处方权：必须写原因，撤销后该医师不能再开麻醉药品。 */
    @Transactional
    public NarcoticPrescriber revoke(String prescriber, RevokePrescriberReq req, String actor) {
        String who = prescriber == null ? "" : prescriber.trim();
        NarcoticPrescriber existing = repo.findNarcoticPrescriber(who)
                .orElseThrow(() -> new BusinessException(ErrorCode.NARCOTIC_PRESCRIBER_NOT_FOUND));
        if (!existing.enabled()) {
            throw new BusinessException(ErrorCode.NARCOTIC_PRESCRIBER_STATE_INVALID,
                    "该医师的麻醉药品处方权已撤销，无需重复操作");
        }
        String reason = req.reason().trim();
        if (!repo.revokeNarcoticPrescriber(who, reason, actor, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "处方权记录已被他人修改，请刷新后重试");
        }
        NarcoticPrescriber saved = repo.findNarcoticPrescriber(who).orElseThrow();
        audit(actor, "NARCOTIC_PRESCRIBER_REVOKE", who, Map.of("reason", reason));
        return saved;
    }

    /**
     * 开方前的处方权校验（由 {@link PrescriptionService} 调用）。
     *
     * @throws BusinessException 422 {@code NARCOTIC_PRESCRIBER_REQUIRED}
     */
    public void requireLicensed(String prescriber) {
        String who = prescriber == null ? "" : prescriber.trim();
        boolean licensed = repo.findNarcoticPrescriber(who)
                .map(NarcoticPrescriber::enabled)
                .orElse(false);
        if (!licensed) {
            throw new BusinessException(ErrorCode.NARCOTIC_PRESCRIBER_REQUIRED,
                    "医师 " + who + " 未取得麻醉药品处方权，不能开具麻醉药品");
        }
    }

    /** 同一患者同一麻醉药品存在未回收空安瓿时拒绝再开。 */
    public void requireAmpouleReturned(UUID patientId, Drug drug) {
        if (repo.countOutstandingNarcotic(patientId, drug.id()) > 0) {
            throw new BusinessException(ErrorCode.NARCOTIC_AMPOULE_OUTSTANDING,
                    "患者上次「" + drug.name() + "」的空安瓿尚未回收，请先完成回收登记后再开方");
        }
    }

    // ---------------------------------------------------------------- 专册

    /**
     * 专册查询（默认最近 7 天）。
     *
     * @param status {@code PENDING_RETURN} 只看待回收（药房每日核对用）
     */
    public List<NarcoticRegister> registers(LocalDate from, LocalDate to, UUID patientId,
                                            String status, Integer limit) {
        LocalDate start = from == null ? BusinessTime.today().minusDays(6) : from;
        LocalDate end = to == null ? BusinessTime.today() : to;
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "统计区间不能超过 " + MAX_RANGE_DAYS + " 天");
        }
        String normalized = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalized != null && !NarcoticRegister.PENDING_RETURN.equals(normalized)
                && !NarcoticRegister.RETURNED.equals(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "专册状态只能是 PENDING_RETURN / RETURNED");
        }
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        ZoneId zone = BusinessTime.zone();
        return repo.listNarcoticRegisters(start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant(), patientId, normalized, size);
    }

    /** 空安瓿回收登记：数量必须等于发药数量，回收后同一药品才能再次开具。 */
    @Transactional
    public NarcoticRegister returnAmpoule(UUID registerId, AmpouleReturnReq req, String actor) {
        NarcoticRegister record = repo.lockNarcoticRegister(registerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NARCOTIC_REGISTER_NOT_FOUND));
        if (NarcoticRegister.RETURNED.equals(record.status())) {
            throw new BusinessException(ErrorCode.NARCOTIC_AMPOULE_INVALID, "该笔空安瓿已回收，请勿重复登记");
        }
        if (req.quantity() != record.outstanding()) {
            throw new BusinessException(ErrorCode.NARCOTIC_AMPOULE_INVALID,
                    "空安瓿回收数量必须等于发药数量（应回收 " + record.outstanding() + "，实际 "
                            + req.quantity() + "）");
        }
        String receiver = req.receiver().trim();
        if (!repo.returnNarcoticAmpoule(registerId, req.quantity(), receiver, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "专册登记已被他人修改，请刷新后重试");
        }
        NarcoticRegister saved = repo.findNarcoticRegister(registerId).orElseThrow();
        audit(actor, "NARCOTIC_AMPOULE_RETURN", record.prescriptionNo(), Map.of(
                "registerId", registerId.toString(), "drugCode", record.drugCode(),
                "quantity", req.quantity(), "receiver", receiver));
        return saved;
    }

    // ---------------------------------------------------------------- 内部

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "NARCOTIC", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "NARCOTIC", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
