package com.medassist.empi.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.api.PageResult;
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
 * <p>actor 取网关注入的 {@code X-Actor}（P0-4 接入 JWT 后由网关覆盖，客户端不可伪造）；
 * {@code reveal=true} 需 read_phi 权限——P0 暂以审计留痕替代，鉴权在 P0-4 补齐。
 */
@RestController
@RequestMapping("/patients")
public class PatientController {

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<PatientResp>> search(
            @RequestParam(required = false) String patientNo,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.search(patientNo, name, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PatientResp> detail(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean reveal,
            @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.detail(id, reveal, actor));
    }

    @PostMapping
    public ApiResponse<PatientResp> create(
            @Valid @RequestBody CreatePatientReq req,
            @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.create(req, actor));
    }

    @PutMapping("/{id}")
    public ApiResponse<PatientResp> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePatientReq req,
            @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.update(id, req, actor));
    }

    @PostMapping("/merge")
    public ApiResponse<PatientResp> merge(
            @Valid @RequestBody MergePatientReq req,
            @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        return ApiResponse.ok(service.merge(req, actor));
    }

    @PostMapping("/{id}/crosswalk")
    public ApiResponse<Void> addCrosswalk(
            @PathVariable UUID id,
            @Valid @RequestBody CrosswalkReq req,
            @RequestHeader(value = "X-Actor", defaultValue = "system") String actor) {
        service.addCrosswalk(id, req.systemCode(), req.externalId(), actor);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{id}/crosswalk")
    public ApiResponse<List<PatientResp.CrosswalkItem>> crosswalk(@PathVariable UUID id) {
        return ApiResponse.ok(service.crosswalk(id));
    }
}
