package com.medassist.emr.web;

import com.medassist.common.api.ApiResponse;
import com.medassist.common.security.AuthHeaders;
import com.medassist.common.security.Permissions;
import com.medassist.emr.domain.EmrModel.IcdDiagnosis;
import com.medassist.emr.domain.EmrModel.IcdProcedure;
import com.medassist.emr.service.IcdDictionaryService;
import com.medassist.emr.web.EmrDtos.IcdDiagnosisReq;
import com.medassist.emr.web.EmrDtos.IcdProcedureReq;
import com.medassist.emr.web.EmrDtos.IcdToggleReq;
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

/**
 * ICD 字典 API（ICD-10 诊断 / ICD-9-CM-3 手术操作）。
 *
 * <p>权限：{@code emr:icd:read} 查询；{@code emr:icd:manage} 新增与启停。
 * 停用而不删除：历史病案需要保留当时的编码含义。
 */
@RestController
@RequestMapping("/emr/icd")
public class IcdController {

    private static final String READ = "emr:icd:read";
    private static final String MANAGE = "emr:icd:manage";

    private final IcdDictionaryService service;

    public IcdController(IcdDictionaryService service) {
        this.service = service;
    }

    @GetMapping("/diagnoses")
    public ApiResponse<List<IcdDiagnosis>> listDiagnoses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listDiagnoses(keyword, enabled, limit));
    }

    @PostMapping("/diagnoses")
    public ApiResponse<IcdDiagnosis> createDiagnosis(
            @Valid @RequestBody IcdDiagnosisReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createDiagnosis(req, actor));
    }

    @PutMapping("/diagnoses/{code}/enabled")
    public ApiResponse<IcdDiagnosis> toggleDiagnosis(
            @PathVariable String code,
            @Valid @RequestBody IcdToggleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.toggleDiagnosis(code, req.enabled(), req.version(), actor));
    }

    @GetMapping("/procedures")
    public ApiResponse<List<IcdProcedure>> listProcedures(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions) {
        Permissions.require(permissions, READ);
        return ApiResponse.ok(service.listProcedures(keyword, enabled, limit));
    }

    @PostMapping("/procedures")
    public ApiResponse<IcdProcedure> createProcedure(
            @Valid @RequestBody IcdProcedureReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.createProcedure(req, actor));
    }

    @PutMapping("/procedures/{code}/enabled")
    public ApiResponse<IcdProcedure> toggleProcedure(
            @PathVariable String code,
            @Valid @RequestBody IcdToggleReq req,
            @RequestHeader(value = AuthHeaders.PERMISSIONS, required = false) String permissions,
            @RequestHeader(value = AuthHeaders.ACTOR, defaultValue = "system") String actor) {
        Permissions.require(permissions, MANAGE);
        return ApiResponse.ok(service.toggleProcedure(code, req.enabled(), req.version(), actor));
    }
}
