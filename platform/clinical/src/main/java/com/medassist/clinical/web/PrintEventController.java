package com.medassist.clinical.web;

import com.medassist.clinical.domain.PrintModel;
import com.medassist.clinical.domain.PrintModel.PrintAnomaly;
import com.medassist.clinical.domain.PrintModel.PrintEvent;
import com.medassist.clinical.domain.PrintModel.PrintEventPage;
import com.medassist.clinical.service.PrintEventService;
import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.common.security.CampusFilters;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 单据打印留痕 API（第 230 批）。
 *
 * <p>写：任何能打印的业务角色都可记录（{@code clinical:print:record}），
 * 记录动作本身不再叠加业务权限——能不能打印已由各自的打印入口判定，
 * 这里只负责"确实打了"留痕，避免重复鉴权把已出纸的动作变成 403。
 *
 * <p>读：质控/管理员（{@code clinical:print:read}），按院区收敛；
 * 用于补打核查（同一单号打印了几次、谁打的）。
 */
@RestController
@RequestMapping("/clinical/print-events")
public class PrintEventController {

    private static final String RECORD = "clinical:print:record";
    private static final String READ = "clinical:print:read";

    private final PrintEventService service;

    public PrintEventController(PrintEventService service) {
        this.service = service;
    }

    /** 记录一次打印（前端在打印窗口成功打开后上报；补打未填原因时返回 422 由前端补问）。 */
    @PostMapping
    public ApiResponse<PrintEvent> record(
            @Valid @RequestBody RecordPrintReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, RECORD);
        return ApiResponse.ok(service.record(req.docType(), req.docNo(), req.targetType(),
                req.targetId(), req.patientNo(), req.reason(), actor, campusOf(scope)));
    }

    /** 打印历史（分页）：条件全空返回最近流水；按院区收敛；支持日期区间核查。 */
    @GetMapping
    public ApiResponse<PrintEventPage> page(
            @RequestParam(required = false) String docType,
            @RequestParam(required = false) String docNo,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer offset,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.page(docType, docNo, targetType, targetId,
                CampusFilters.csv(scope.campusFilter()), from, to, offset, limit));
    }

    /** 补打异常（第 237 批）：同一单号在期间内打印次数 ≥ threshold，按次数倒序。 */
    @GetMapping("/anomalies")
    public ApiResponse<List<PrintAnomaly>> anomalies(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer threshold,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.anomalies(CampusFilters.csv(scope.campusFilter()),
                from, to, threshold, limit));
    }

    /**
     * 待核查统计（第 239 批）：全局待办角标只消费 `pending`。
     *
     * <p>与 `/anomalies` 同一套阈值与院区口径；`from/to` 为空时默认最近 30 天，
     * 避免 60 秒轮询每次都做全表聚合。
     */
    @GetMapping("/anomalies/summary")
    public ApiResponse<PrintModel.PrintAnomalySummary> anomalySummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer threshold,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.anomalySummary(CampusFilters.csv(scope.campusFilter()),
                from, to, threshold));
    }

    /**
     * 核查处置（第 239 批）：给一条补打异常签字。
     *
     * <p>权限复用 `clinical:print:read`：能看异常的人就是负责核查的人；
     * 单独再开一个 `print:review` 只会让管理员多配一次权限，却不增加任何约束。
     */
    @PostMapping("/anomalies/review")
    public ApiResponse<PrintModel.Review> review(
            @Valid @RequestBody ReviewReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.review(req.docType(), req.docNo(), req.verdict(),
                req.note(), actor));
    }

    /** 院区归属：只有唯一院区的账号才落 campus_id，跨院区角色写空（与列表收敛口径一致）。 */
    private static UUID campusOf(DataScope scope) {
        Set<UUID> campuses = scope.campusFilter();
        return campuses != null && campuses.size() == 1 ? campuses.iterator().next() : null;
    }

    /**
     * 打印上报请求：字段长度上限与库表一致，超长在入口即拒绝。
     *
     * <p>第 238 批追加 {@code reason}：第 2 次及以后打印必填，由服务端校验；
     * 理由不写成枚举——院内口径会变（新增"自助机重打"等），
     * 前端给预设选项，服务端只约束长度与"补打必须有值"。
     */
    public record RecordPrintReq(
            @Size(max = 32) String docType,
            @Size(max = 64) String docNo,
            @Size(max = 32) String targetType,
            UUID targetId,
            @Size(max = 32) String patientNo,
            @Size(max = 64) String reason) {
    }

    /** 核查处置请求（第 239 批）：结论 + 说明；说明是否必填由服务端按结论判定。 */
    public record ReviewReq(
            @Size(max = 32) String docType,
            @Size(max = 64) String docNo,
            @Size(max = 16) String verdict,
            @Size(max = 200) String note) {
    }
}
