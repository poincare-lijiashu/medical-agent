package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.Template;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.CreateTemplateReq;
import com.medassist.emr.web.EmrDtos.TemplateView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 病历模板。
 *
 * <p>模板结构在创建时一次性校验为合法 JSON 数组，避免坏模板导致书写端渲染崩溃。
 */
@Service
public class TemplateService {

    private final EmrRepository repo;
    private final ObjectMapper objectMapper;

    public TemplateService(EmrRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TemplateView create(CreateTemplateReq req, String actor) {
        requireValidSchema(req.schemaJson());
        if (repo.findTemplateByCode(req.templateCode().trim()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板编码已存在：" + req.templateCode());
        }
        Template template = new Template(UUID.randomUUID(), req.templateCode().trim(),
                req.name().trim(), req.docType(),
                req.scope() == null || req.scope().isBlank() ? "INPATIENT" : req.scope(),
                req.schemaJson(), true, 0L);
        try {
            repo.insertTemplate(template);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板编码冲突，请刷新后重试");
        }
        audit(actor, "TEMPLATE_CREATE", "TEMPLATE", template.id().toString(), Map.of(
                "templateCode", template.templateCode(), "docType", template.docType()));
        return new TemplateView(repo.findTemplateByCode(template.templateCode()).orElseThrow());
    }

    public List<Template> list(String docType, Boolean enabled) {
        String normalized = (docType == null || docType.isBlank()) ? null : docType.trim().toUpperCase();
        return repo.listTemplates(normalized, enabled);
    }

    public TemplateView detail(String templateCode) {
        return new TemplateView(repo.findTemplateByCode(templateCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "模板不存在：" + templateCode)));
    }

    @Transactional
    public TemplateView toggle(UUID id, boolean enabled, long version, String actor) {
        if (!repo.setTemplateEnabled(id, enabled, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板已被他人修改，请刷新后重试");
        }
        audit(actor, enabled ? "TEMPLATE_ENABLE" : "TEMPLATE_DISABLE", "TEMPLATE", id.toString(),
                Map.of("enabled", enabled));
        return new TemplateView(repo.listTemplates(null, null).stream()
                .filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "模板不存在")));
    }

    /** 校验模板 schema 是合法 JSON 数组，且每个元素含 key/label。 */
    private void requireValidSchema(String schemaJson) {
        List<Object> fields;
        try {
            fields = objectMapper.readValue(schemaJson, List.class);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "模板字段定义不是合法 JSON 数组");
        }
        for (Object f : fields) {
            if (!(f instanceof Map<?, ?> map) || map.get("key") == null || map.get("label") == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "模板字段必须包含 key 与 label：" + f);
            }
        }
    }

    private void audit(String actor, String action, String targetType, String targetId,
                       Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetType, targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetType, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
