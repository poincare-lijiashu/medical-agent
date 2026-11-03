package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.IcdDiagnosis;
import com.medassist.emr.domain.EmrModel.IcdProcedure;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.IcdDiagnosisReq;
import com.medassist.emr.web.EmrDtos.IcdProcedureReq;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ICD 字典维护（ICD-10 诊断 / ICD-9-CM-3 手术操作）。
 *
 * <p>字典是病案编码的唯一编码真源：编码时只接受字典内且启用的编码，名称由服务端回填，
 * 避免"客户端传什么名称就存什么名称"导致同一编码多种写法、DRG 入组口径漂移。
 *
 * <p>停用不删除：历史病案仍要能解释当时的编码含义，因此只提供启停（带版本号 CAS）。
 */
@Service
public class IcdDictionaryService {

    private final EmrRepository repo;
    private final ObjectMapper objectMapper;

    public IcdDictionaryService(EmrRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public List<IcdDiagnosis> listDiagnoses(String keyword, Boolean enabled, Integer limit) {
        return repo.listIcdDiagnoses(keyword, enabled, clamp(limit));
    }

    public List<IcdProcedure> listProcedures(String keyword, Boolean enabled, Integer limit) {
        return repo.listIcdProcedures(keyword, enabled, clamp(limit));
    }

    @Transactional
    public IcdDiagnosis createDiagnosis(IcdDiagnosisReq req, String actor) {
        String code = req.code().trim();
        if (repo.findIcdDiagnosis(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "ICD 编码已存在：" + code);
        }
        IcdDiagnosis item = new IcdDiagnosis(code, req.name().trim(), blankToNull(req.chapter()),
                blankToNull(req.category()), true, 0L);
        try {
            repo.insertIcdDiagnosis(item);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "ICD 编码已存在：" + code);
        }
        audit(actor, "ICD_DIAGNOSIS_CREATE", "icd_diagnosis", code, detail()
                .put("name", item.name()).put("chapter", item.chapter()).build());
        return item;
    }

    @Transactional
    public IcdProcedure createProcedure(IcdProcedureReq req, String actor) {
        String code = req.code().trim();
        if (repo.findIcdProcedure(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "手术操作编码已存在：" + code);
        }
        IcdProcedure item = new IcdProcedure(code, req.name().trim(),
                blankToNull(req.category()), true, 0L);
        try {
            repo.insertIcdProcedure(item);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "手术操作编码已存在：" + code);
        }
        audit(actor, "ICD_PROCEDURE_CREATE", "icd_procedure", code, detail()
                .put("name", item.name()).put("category", item.category()).build());
        return item;
    }

    @Transactional
    public IcdDiagnosis toggleDiagnosis(String code, boolean enabled, long version, String actor) {
        IcdDiagnosis item = repo.findIcdDiagnosis(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.ICD_ITEM_NOT_FOUND));
        // 幂等：已是目标状态直接返回，双击不报"已被他人修改"
        if (item.enabled() == enabled) {
            return item;
        }
        if (!repo.setIcdDiagnosisEnabled(code, enabled, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该编码已被他人修改，请刷新后重试");
        }
        audit(actor, "ICD_DIAGNOSIS_ENABLED", "icd_diagnosis", code,
                detail().put("enabled", enabled).put("fromVersion", version).build());
        return repo.findIcdDiagnosis(code).orElseThrow();
    }

    @Transactional
    public IcdProcedure toggleProcedure(String code, boolean enabled, long version, String actor) {
        IcdProcedure item = repo.findIcdProcedure(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.ICD_ITEM_NOT_FOUND));
        if (item.enabled() == enabled) {
            return item;
        }
        if (!repo.setIcdProcedureEnabled(code, enabled, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "该编码已被他人修改，请刷新后重试");
        }
        audit(actor, "ICD_PROCEDURE_ENABLED", "icd_procedure", code,
                detail().put("enabled", enabled).put("fromVersion", version).build());
        return repo.findIcdProcedure(code).orElseThrow();
    }

    private int clamp(Integer limit) {
        return limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Detail detail() {
        return new Detail();
    }

    private final class Detail {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Detail put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        String build() {
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JacksonException e) {
                return "{\"_detail_serialize_failed\":true}";
            }
        }
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       String detailJson) {
        repo.logChange(actor, action, targetType, targetId, detailJson);
    }
}
