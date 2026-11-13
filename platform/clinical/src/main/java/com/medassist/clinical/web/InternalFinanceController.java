package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.VisitCollection;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务间财务汇总查询（仅内网直连，不经过网关）。
 *
 * <p>医保域做三方对账时需要"临床到底实收了多少"。逐就诊调用
 * {@code /internal/visits/{id}/charges} 会把月结变成上万次 HTTP，
 * 因此提供**按期间一次汇总**的只读接口：只返回就诊主键与金额，不含姓名/证件号。
 *
 * <p>安全边界与内部收费接口一致：共享令牌、只读、失败明确返回（不做 fail-open），
 * 拿不到实收数据时对账必须失败，绝不能把"未知"当成"已收清"。
 */
@RestController
@RequestMapping("/internal/finance")
public class InternalFinanceController {

    /** 单次上限：超过说明期间粒度过粗，要求按月或缩小范围（与医保对账上限一致）。 */
    private static final int MAX_LIMIT = 5_000;

    private final ClinicalRepository repo;
    private final String token;

    public InternalFinanceController(ClinicalRepository repo,
                                     @Value("${clinical.internal-api-token:${clinical.internal-token:}}")
                                     String token) {
        this.repo = repo;
        this.token = token == null ? "" : token;
    }

    /** 期间内按就诊汇总的收费实收（业务日期归属，与医保清单口径一致）。 */
    @GetMapping("/visits/collections")
    public Map<String, Object> collections(
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam(value = "limit", defaultValue = "5000") int limit,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        requireToken(provided);
        LocalDate start = parseDate(from, "from");
        LocalDate end = parseDate(to, "to");
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束日期不能早于开始日期");
        }
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<VisitCollection> rows = repo.listVisitCollections(start, end,
                BusinessTime.zone().getId(), size + 1);
        boolean truncated = rows.size() > size;
        List<VisitCollection> items = truncated ? rows.subList(0, size) : rows;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", start.toString());
        body.put("to", end.toString());
        body.put("items", items);
        body.put("truncated", truncated);
        return body;
    }

    private static LocalDate parseDate(String raw, String field) {
        try {
            return LocalDate.parse(raw == null ? "" : raw.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, field + " 需为 yyyy-MM-dd");
        }
    }

    /** 统一泛化文案：不区分"未启用/令牌错误/令牌缺失"，避免外部探测内部接口状态。 */
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
