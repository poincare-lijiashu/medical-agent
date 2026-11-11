package com.medassist.clinical.web;

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

    /** 记录一次打印（前端在打印窗口成功打开后上报；失败由前端静默忽略）。 */
    @PostMapping
    public ApiResponse<PrintEvent> record(
            @Valid @RequestBody RecordPrintReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, RECORD);
        return ApiResponse.ok(service.record(req.docType(), req.docNo(), req.targetType(),
                req.targetId(), req.patientNo(), actor, campusOf(scope)));
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

    /** 院区归属：只有唯一院区的账号才落 campus_id，跨院区角色写空（与列表收敛口径一致）。 */
    private static UUID campusOf(DataScope scope) {
        Set<UUID> campuses = scope.campusFilter();
        return campuses != null && campuses.size() == 1 ? campuses.iterator().next() : null;
    }

    /** 打印上报请求：字段长度上限与库表一致，超长在入口即拒绝。 */
    public record RecordPrintReq(
            @Size(max = 32) String docType,
            @Size(max = 64) String docNo,
            @Size(max = 32) String targetType,
            UUID targetId,
            @Size(max = 32) String patientNo) {
    }
}
