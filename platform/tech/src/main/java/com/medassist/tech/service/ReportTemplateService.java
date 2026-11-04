package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.domain.TechModel.ReportTemplate;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.DeviceDtos.CreateReportTemplateReq;
import com.medassist.tech.web.DeviceDtos.FieldSpec;
import com.medassist.tech.web.DeviceDtos.TemplateView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 结构化报告模板（G35）。
 *
 * <p>模板发布后不可修改，只能停用后新建版本；报告书写时只能选用 PUBLISHED 模板，
 * 必填字段缺失会被 {@link ReportService} 拒绝。
 */
@Service
public class ReportTemplateService {

    private static final Set<String> MODALITIES =
            Set.of("CT", "MR", "US", "DR", "CR", "LIS", "ECG", "OTHER");
    private static final Set<String> STATUSES =
            Set.of(ReportTemplate.DRAFT, ReportTemplate.PUBLISHED, ReportTemplate.DISABLED);

    private final TechRepository repo;
    private final ObjectMapper objectMapper;

    public ReportTemplateService(TechRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TemplateView create(CreateReportTemplateReq req, String actor) {
        String operator = requireActor(actor);
        String code = req.code().trim().toUpperCase();
        if (repo.findReportTemplateByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_DUPLICATE);
        }
        validateFields(req.fields());
        String schema;
        try {
            schema = objectMapper.writeValueAsString(Map.of("fields", req.fields()));
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_INVALID, "模板字段序列化失败");
        }
        ReportTemplate template = new ReportTemplate(UUID.randomUUID(), code, req.name().trim(),
                req.modality(), schema, ReportTemplate.DRAFT, operator, Instant.now(), null, null,
                0L);
        repo.insertReportTemplate(template);
        audit(operator, "TEMPLATE_CREATE", template.id().toString(), Map.of(
                "code", code, "modality", template.modality(), "fields", req.fields().size()));
        return new TemplateView(repo.findReportTemplate(template.id()).orElse(template));
    }

    public List<ReportTemplate> list(String status, String modality, Integer limit) {
        String normalizedStatus = normalize(status, STATUSES, true);
        String normalizedModality = normalize(modality, MODALITIES, true);
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        return repo.listReportTemplates(normalizedStatus, normalizedModality, size);
    }

    public TemplateView detail(UUID id) {
        return new TemplateView(repo.findReportTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_NOT_FOUND)));
    }

    /** 报告编辑器只允许选择已发布模板。 */
    public List<ReportTemplate> published(String modality) {
        String normalizedModality = normalize(modality, MODALITIES, true);
        return repo.listReportTemplates(ReportTemplate.PUBLISHED, normalizedModality, 200);
    }

    @Transactional
    public TemplateView publish(UUID id, String actor) {
        String operator = requireActor(actor);
        ReportTemplate template = repo.lockReportTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_NOT_FOUND));
        if (!ReportTemplate.DRAFT.equals(template.status())) {
            throw new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_STATUS_INVALID,
                    "只有草稿模板可以发布，当前：" + template.status());
        }
        if (!repo.publishReportTemplate(id, operator, template.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板状态已变化，请刷新后重试");
        }
        audit(operator, "TEMPLATE_PUBLISH", id.toString(), Map.of("code", template.code()));
        return detail(id);
    }

    @Transactional
    public TemplateView disable(UUID id, String actor) {
        String operator = requireActor(actor);
        ReportTemplate template = repo.lockReportTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_NOT_FOUND));
        if (!ReportTemplate.PUBLISHED.equals(template.status())) {
            throw new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_STATUS_INVALID,
                    "只有已发布模板可以停用，当前：" + template.status());
        }
        if (!repo.disableReportTemplate(id, template.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板状态已变化，请刷新后重试");
        }
        audit(operator, "TEMPLATE_DISABLE", id.toString(), Map.of("code", template.code()));
        return detail(id);
    }

    /** 字段定义校验：key 唯一、SELECT 必须有选项、options 只能用于 SELECT。 */
    static void validateFields(List<FieldSpec> fields) {
        Set<String> keys = new HashSet<>();
        for (FieldSpec field : fields) {
            String key = field.key() == null ? "" : field.key().trim();
            if (key.isEmpty() || !keys.add(key)) {
                throw new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_INVALID,
                        "字段 key 不能为空且必须唯一：" + key);
            }
            boolean select = "SELECT".equals(field.type());
            if (select && (field.options() == null || field.options().isEmpty())) {
                throw new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_INVALID,
                        "下拉字段 " + key + " 必须提供可选项");
            }
            if (!select && field.options() != null && !field.options().isEmpty()) {
                throw new BusinessException(ErrorCode.TECH_REPORT_TEMPLATE_INVALID,
                        "只有 SELECT 字段可以配置可选项：" + key);
            }
        }
    }

    private static String normalize(String value, Set<String> allowed, boolean allowNull) {
        if (value == null || value.isBlank()) {
            if (allowNull) {
                return null;
            }
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "参数不能为空");
        }
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的取值：" + value);
        }
        return normalized;
    }

    private static String requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "缺少操作人，不能维护模板");
        }
        return actor.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "REPORT_TEMPLATE", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "REPORT_TEMPLATE", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
