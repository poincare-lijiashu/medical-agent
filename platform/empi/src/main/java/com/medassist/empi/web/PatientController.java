package com.medassist.empi.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.empi.service.PatientService;
import com.medassist.empi.web.dto.CreatePatientReq;
import com.medassist.empi.web.dto.CrosswalkReq;
import com.medassist.empi.web.dto.MergePatientReq;
import com.medassist.empi.web.dto.PatientResp;
import com.medassist.empi.web.dto.UpdatePatientReq;
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
 * 患者主索引 API。
 *
 * <p>actor 与权限来源：网关注入的 {@code X-Actor} / {@code X-Permissions}（由网关按 JWT 重写，
 * 客户端不可伪造）。
 *
 * <p>P1 授权矩阵：
 * <ul>
 *   <li>查询档案需要 {@code empi:patient:read}；</li>
 *   <li>{@code reveal=true} 额外需要 {@code empi:patient:read_phi}（证件号/手机号明文）——
 *       仅"有权限看患者"不等于"有权限看敏感标识"，两者必须分离；</li>
 *   <li>建档 / 修改 / 合并 / 跨系统标识登记分别对应 create / update / merge / update。</li>
 * </ul>
 */
@RestController
@RequestMapping("/patients")
public class PatientController {

    private static final String READ = "empi:patient:read";
    private static final String READ_PHI = "empi:patient:read_phi";
    private static final String CREATE = "empi:patient:create";
    private static final String UPDATE = "empi:patient:update";
    private static final String MERGE = "empi:patient:merge";

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<PatientResp>> search(
            @RequestParam(required = false) String patientNo,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.search(patientNo, name, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PatientResp> detail(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean reveal,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, READ);
        if (reveal) {
            Permissions.require(permissions, READ_PHI);
        }
        return ApiResponse.ok(service.detail(id, reveal, actor));
    }

    @PostMapping
    public ApiResponse<PatientResp> create(
            @Valid @RequestBody CreatePatientReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, CREATE);
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<PatientResp> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePatientReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, UPDATE);
        return ApiResponse.ok(service.update(id, req, actor));
    }

    @PostMapping("/merge")
    public ApiResponse<PatientResp> merge(
            @Valid @RequestBody MergePatientReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MERGE);
        return ApiResponse.ok(service.merge(req, actor));
    }

    @PostMapping("/{id}/crosswalk")
    public ApiResponse<Void> addCrosswalk(
            @PathVariable UUID id,
            @Valid @RequestBody CrosswalkReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, UPDATE);
        service.addCrosswalk(id, req.systemCode(), req.externalId(), actor);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/crosswalk")
    public ApiResponse<List<PatientResp.CrosswalkItem>> crosswalk(
            @PathVariable UUID id,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.crosswalk(id));
    }
}
