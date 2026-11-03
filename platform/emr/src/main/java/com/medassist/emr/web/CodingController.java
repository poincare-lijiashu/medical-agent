package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.DataScope;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.CodingQueueItem;
import com.medassist.emr.service.HomepageCodingService;
import com.medassist.emr.web.EmrDtos.CodingSummary;
import com.medassist.emr.web.EmrDtos.CodingView;
import com.medassist.emr.web.EmrDtos.SaveCodingReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 病案编码工作台 API。
 *
 * <p>权限分权：
 * <ul>
 *   <li>{@code emr:coding:read}——查看待编码队列与编码详情；</li>
 *   <li>{@code emr:coding:write}——保存编码草稿；</li>
 *   <li>{@code emr:coding:submit}——提交编码（回写首页诊断/手术，之后才可归档）。</li>
 * </ul>
 * 患者号按 {@code phi:read} 掩码；姓名在 SQL 内掩码，列表不下发完整 PHI。
 */
@RestController
@RequestMapping("/emr/coding")
public class CodingController {

    private static final String READ = "emr:coding:read";
    private static final String WRITE = "emr:coding:write";
    private static final String SUBMIT = "emr:coding:submit";

    private final HomepageCodingService service;

    public CodingController(HomepageCodingService service) {
        this.service = service;
    }

    /** 待编码队列：已提交且编码未完成的病案首页。 */
    @GetMapping("/queue")
    public ApiResponse<List<CodingQueueItem>> queue(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.queue(keyword, limit, scope));
    }

    /** 编码看板：待编码/已编码 + ICD 字典规模。 */
    @GetMapping("/summary")
    public ApiResponse<CodingSummary> summary(
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/{homepageId}")
    public ApiResponse<CodingView> detail(
            @PathVariable UUID homepageId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            DataScope scope) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.detail(homepageId, scope));
    }

    /** 保存编码草稿：首次保存 version=0，后续必须与当前版本一致。 */
    @PutMapping("/{homepageId}")
    public ApiResponse<CodingView> save(
            @PathVariable UUID homepageId,
            @Valid @RequestBody SaveCodingReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, WRITE);
        return ApiResponse.ok(service.save(homepageId, req, actor, scope));
    }

    /** 提交编码：主诊断必须唯一；成功后回写首页诊断/手术，首页方可归档。 */
    @PostMapping("/{homepageId}/submit")
    public ApiResponse<CodingView> submit(
            @PathVariable UUID homepageId,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor,
            DataScope scope) {
        Permissions.require(permissions, SUBMIT);
        return ApiResponse.ok(service.submit(homepageId, actor, scope));
    }
}
