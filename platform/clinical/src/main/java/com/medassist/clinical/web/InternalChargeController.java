package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.ChargeLine;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
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
        // 业务日期：医保按"费用发生/出院日期"校验参保与年度封顶，不能按请求时刻的今天
        body.put("serviceDate", serviceDate(visit));
        body.put("lines", lines);
        return body;
    }

    /**
     * 就诊业务日期：住院取出院日期（未出院取入院日期），门诊/急诊取挂号日期。
     *
     * <p>跨年补结算时，用"今天"校验参保会把去年有效的参保判成失效，因此必须回到就诊本身。
     */
    private LocalDate serviceDate(Visit visit) {
        return repo.findLatestAdmissionByVisit(visit.id())
                .map(a -> LocalDate.ofInstant(
                        a.dischargedAt() == null ? a.admittedAt() : a.dischargedAt(),
                        BusinessTime.zone()))
                .orElseGet(visit::visitDate);
    }

    /**
     * 统一泛化文案：不区分"未启用/令牌错误/令牌缺失"，
     * 避免外部探测出内部接口的启用状态与配置项名称。
     */
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
