package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.config.EmrProperties;
import com.medassist.emr.domain.EmrModel.Homepage;
import com.medassist.emr.repo.EmrRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务间病案首页查询（仅内网直连，不经过网关）。
 *
 * <p>用途：医保 DRG/DIP 模拟分组需要"病案首页手术操作"，而手术操作目前只在首页录入。
 * 安全边界与其它内部接口一致：网关对 {@code /api/v2/*&#47;internal/**} 直接 404，
 * 且要求共享令牌（令牌未配置时拒绝所有请求）。
 */
@RestController
@RequestMapping("/internal/homepages")
public class InternalHomepageController {

    private final EmrRepository repo;
    private final ObjectMapper objectMapper;
    private final String token;

    public InternalHomepageController(EmrRepository repo, ObjectMapper objectMapper,
                                      EmrProperties props) {
        this.repo = repo;
        this.objectMapper = objectMapper;
        this.token = props.internal() == null || props.internal().token() == null
                ? "" : props.internal().token();
    }

    /**
     * 按就诊号取首页手术操作。
     *
     * <p>未生成首页时返回 200 + {@code found=false}：这是"还没有首页"的正常状态，
     * 不是错误，调用方据此按"无手术"处理（而不是把它当成上游故障）。
     */
    @GetMapping("/by-visit/{visitId}/procedures")
    public Map<String, Object> procedures(@PathVariable UUID visitId,
                                          @RequestHeader(value = "X-Internal-Token", required = false)
                                          String provided) {
        requireToken(provided);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitId", visitId);
        Homepage homepage = repo.findHomepageByVisit(visitId).orElse(null);
        body.put("found", homepage != null);
        body.put("status", homepage == null ? null : homepage.status());
        body.put("homepageNo", homepage == null ? null : homepage.homepageNo());
        body.put("procedures", homepage == null ? List.of() : parseProcedures(homepage.procedures()));
        return body;
    }

    private List<Map<String, Object>> parseProcedures(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JacksonException e) {
            return List.of();
        }
    }

    /** 统一泛化文案：不区分"未启用/令牌错误/令牌缺失"。 */
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
