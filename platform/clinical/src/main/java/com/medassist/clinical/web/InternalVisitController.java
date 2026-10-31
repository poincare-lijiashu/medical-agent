package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.Admission;
import com.medassist.clinical.domain.ClinicalModel.Visit;
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
import java.util.Optional;
import java.util.UUID;

/**
 * 服务间就诊画像查询（仅内网直连，不经过网关）。
 *
 * <p>病历域创建病历时需要两件事：① 该就诊属于该患者（防止病历挂到别人的就诊上）；
 * ② 住院患者的入院时间（书写时限质控）。两者都从临床域取，避免跨 schema 直查。
 *
 * <p>安全边界与其它内部接口一致：网关对 {@code /api/v2/*&#47;internal/**} 直接 404，
 * 且要求共享令牌（跳过令牌一律拒绝）。
 */
@RestController
@RequestMapping("/internal/visits")
public class InternalVisitController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final ClinicalRepository repo;
    private final String token;

    public InternalVisitController(ClinicalRepository repo,
                                   @Value("${clinical.internal-api-token:${clinical.internal-token:}}")
                                   String token) {
        this.repo = repo;
        this.token = token == null ? "" : token;
    }

    /** 就诊画像：归属患者、就诊类型与日期、住院起止时间（门诊为空）。 */
    @GetMapping("/{visitId}")
    public Map<String, Object> visit(@PathVariable UUID visitId,
                                     @RequestHeader(value = "X-Internal-Token", required = false)
                                     String provided) {
        requireToken(provided);
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "就诊记录不存在"));
        Optional<Admission> admission = repo.findLatestAdmissionByVisit(visitId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitId", visit.id());
        body.put("patientId", visit.patientId());
        body.put("visitType", visit.visitType());
        body.put("visitDate", visit.visitDate() == null ? null : visit.visitDate().toString());
        body.put("admittedAt", admission.map(Admission::admittedAt).map(ISO::format).orElse(null));
        body.put("dischargedAt", admission.map(Admission::dischargedAt)
                .map(ISO::format).orElse(null));
        return body;
    }

    private void requireToken(String provided) {
        if (token.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
        if (provided == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "内部接口不可用");
        }
    }
}
