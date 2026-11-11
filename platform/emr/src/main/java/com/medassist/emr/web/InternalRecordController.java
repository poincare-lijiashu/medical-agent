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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务间病历归档核查（仅内网直连，不经过网关）。
 *
 * <p>用途：住院处/医师在出院前需要知道"这次住院的病历写完了没有"。
 * 只回**计数与状态**：病历标题、作者、正文都属于临床内容，出院体检不需要，
 * 一律不出域。
 *
 * <p>安全边界：网关对 {@code /api/v2/*&#47;internal/**} 直接 404，并要求共享令牌
 * （未配置令牌时拒绝所有请求），与首页内部接口一致。
 */
@RestController
@RequestMapping("/internal/records")
public class InternalRecordController {

    /** 单次批量上限：超过一个病区的规模就不是"预检"了，限制可防止被当成扫描接口。 */
    private static final int MAX_VISITS = 200;

    private final EmrRepository repo;
    private final String token;

    public InternalRecordController(EmrRepository repo, EmrProperties props) {
        this.repo = repo;
        this.token = props.internal() == null || props.internal().token() == null
                ? "" : props.internal().token();
    }

    /**
     * 按就诊号统计归档情况。
     *
     * <p>无病历、首页未生成都属于正常状态（返回 0 / {@code homepageFound=false}），
     * 由调用方解释；只有令牌不对才是拒绝。
     */
    @GetMapping("/by-visit/{visitId}/archive-summary")
    public Map<String, Object> archiveSummary(
            @PathVariable UUID visitId,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        InternalToken.require(token, provided);
        EmrRepository.RecordArchiveCounts counts = repo.archiveCounts(visitId);
        Homepage homepage = repo.findHomepageByVisit(visitId).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("visitId", visitId);
        body.put("total", counts.total());
        body.put("archived", counts.archived());
        body.put("draft", counts.draft());
        body.put("homepageFound", homepage != null);
        body.put("homepageStatus", homepage == null ? null : homepage.status());
        body.put("homepageArchived", homepage != null && Homepage.ARCHIVED.equals(homepage.status()));
        return body;
    }

    /**
     * 批量归档摘要（病区出院预检）：一次返回请求里**每个就诊**的结果。
     *
     * <p>没有病历的就诊也要回一条（total=0）：调用方据此区分"查过、没有病历"
     * 与"没查"——两者在界面上是完全不同的结论。
     */
    @GetMapping("/archive-summaries")
    public Map<String, Object> archiveSummaries(
            @RequestParam("visitIds") List<UUID> visitIds,
            @RequestHeader(value = "X-Internal-Token", required = false) String provided) {
        InternalToken.require(token, provided);
        if (visitIds == null || visitIds.isEmpty()) {
            return Map.of("items", List.of());
        }
        if (visitIds.size() > MAX_VISITS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "一次最多核查 " + MAX_VISITS + " 次就诊");
        }
        List<UUID> distinct = visitIds.stream().distinct().toList();
        Map<UUID, EmrRepository.RecordArchiveCounts> counts = repo.archiveCountsByVisits(distinct);
        Map<UUID, Homepage> homepages = repo.findHomepagesByVisits(distinct);

        List<Map<String, Object>> items = new ArrayList<>(distinct.size());
        for (UUID visitId : distinct) {
            EmrRepository.RecordArchiveCounts c = counts.getOrDefault(visitId,
                    new EmrRepository.RecordArchiveCounts(0, 0, 0));
            Homepage homepage = homepages.get(visitId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("visitId", visitId);
            item.put("total", c.total());
            item.put("archived", c.archived());
            item.put("draft", c.draft());
            item.put("homepageFound", homepage != null);
            item.put("homepageStatus", homepage == null ? null : homepage.status());
            item.put("homepageArchived",
                    homepage != null && Homepage.ARCHIVED.equals(homepage.status()));
            items.add(item);
        }
        return Map.of("items", items);
    }
}
