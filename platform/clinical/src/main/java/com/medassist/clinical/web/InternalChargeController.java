package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ChargeLine;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务间费用明细查询（仅内网直连，不经过网关）。
 *
 * <p>医保域生成结算清单时读取收费明细；本接口只读、只返回最少字段，
 * 仍要求共享令牌，与内部住院信息接口同一套安全边界。
 */
@RestController
@RequestMapping("/internal/visits")
public class InternalChargeController {

    private final ClinicalRepository repo;
    private final String token;

    public InternalChargeController(ClinicalRepository repo,
                                    @Value("${clinical.internal-api-token:${clinical.internal-token:}}")
                                    String token) {
        this.repo = repo;
        this.token = token == null ? "" : token;
    }

    @GetMapping("/{visitId}/charges")
    public Map<String, Object> charges(@PathVariable UUID visitId,
                                       @RequestHeader(value = "X-Internal-Token", required = false)
                                       String provided) {
        requireToken(provided);
        Visit visit = repo.findVisit(visitId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "就诊记录不存在"));
        List<ChargeLine> lines = repo.listChargeLines(visitId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitId", visit.id());
        body.put("patientId", visit.patientId());
        body.put("patientNo", visit.patientNo());
        body.put("lines", lines);
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
