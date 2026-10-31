package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务间住院信息查询（仅内网直连，不经过网关）。
 *
 * <p>病历域需要"入院时间"判断书写时限（入院记录 24h / 首程 8h），
 * 但跨域不能直查 clinical 的库，因此提供最小字段的内部接口：
 * <ul>
 *   <li>网关对 {@code /api/v2/*&#47;internal/**} 直接 404，外部不可达；</li>
 *   <li>要求共享令牌 {@code X-Internal-Token}；令牌未配置时拒绝所有请求（fail-closed）；</li>
 *   <li>只返回 id/admissionNo/visitId/status/admittedAt/dischargedAt，不返回患者身份信息。</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal/admissions")
public class InternalAdmissionController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ClinicalRepository repo;
    private final String token;

    public InternalAdmissionController(ClinicalRepository repo,
                                       @Value("${clinical.internal-api-token:${clinical.internal-token:}}")
                                       String token) {
        this.repo = repo;
        this.token = token == null ? "" : token;
    }

    /** 按就诊号取最新一次住院（含已出院，便于补录病历）。 */
    @GetMapping("/by-visit/{visitId}")
    public Map<String, Object> byVisit(@PathVariable UUID visitId,
                                       @RequestHeader(value = "X-Internal-Token", required = false)
                                       String provided) {
        requireToken(provided);
        Admission admission = repo.findLatestAdmissionByVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该就诊号没有住院登记"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", admission.id());
        body.put("admissionNo", admission.admissionNo());
        body.put("visitId", admission.visitId());
        body.put("status", admission.status());
        body.put("admittedAt", admission.admittedAt() == null
                ? null : ISO.format(admission.admittedAt()));
        body.put("dischargedAt", admission.dischargedAt() == null
                ? null : ISO.format(admission.dischargedAt()));
        return body;
    }

    private void requireToken(String provided) {
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.UPSTREAM_UNAVAILABLE,
                    "内部接口未启用（缺少 CLINICAL_INTERNAL_TOKEN）");
        }
        if (provided == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口令牌无效");
        }
    }
}
