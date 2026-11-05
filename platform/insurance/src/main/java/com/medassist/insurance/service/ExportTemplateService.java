package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.ExportMessageTemplate;
import com.medassist.insurance.domain.InsuranceModel.TemplateField;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.CreateExportTemplateReq;
import com.medassist.insurance.web.InsuranceDtos.ExportTemplateView;
import com.medassist.insurance.web.InsuranceDtos.TemplateFieldReq;
import com.medassist.insurance.web.InsuranceDtos.UpdateExportTemplateReq;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 省局报盘报文模板（G85）。
 *
 * <p>职责边界：
 * <ul>
 *   <li>模板只描述"报盘元素 ← 院内字段"的映射与必填口径，不承载业务计算；</li>
 *   <li>字段来源必须是导出层真实能取到的列（{@link #SUPPORTED_SOURCES}），
 *       否则发布后必然在导出时缺值，因此在保存阶段就拒绝；</li>
 *   <li>全局最多一个 PUBLISHED（数据库部分唯一索引兜底），导出时无需再选模板；</li>
 *   <li>仅 DRAFT 可修改；发布/停用均用 rowVersion 做 CAS，避免两个窗口互相覆盖。</li>
 * </ul>
 */
@Service
public class ExportTemplateService {

    /** 导出层可提供的院内字段（与结算清单快照一致）。 */
    public static final Set<String> SUPPORTED_SOURCES = Set.of(
            "billNo", "visitId", "patientNo", "totalAmount", "eligibleAmount", "deductible",
            "insuranceAmount", "selfPayAmount", "diagnosisCount", "primaryDiagnosisCode",
            "primaryDiagnosisName", "confirmedBy", "confirmedAt");

    /** 省局结算清单的核心必填字段：模板必须声明为 required，否则报盘无法被受理。 */
    private static final Set<String> CORE_REQUIRED_SOURCES = Set.of(
            "billNo", "patientNo", "totalAmount", "insuranceAmount");

    private static final int MAX_FIELDS = 60;
    private static final String ELEMENT_PATTERN = "[A-Za-z][A-Za-z0-9_.-]{0,31}";

    private final InsuranceRepository repo;
    private final ObjectMapper objectMapper;

    public ExportTemplateService(InsuranceRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 查询

    public List<ExportTemplateView> list(Integer limit) {
        int size = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
        return repo.listExportTemplates(size).stream().map(this::view).toList();
    }

    public ExportTemplateView detail(UUID id) {
        return view(find(id));
    }

    // ---------------------------------------------------------------- 写操作

    @Transactional
    public ExportTemplateView create(CreateExportTemplateReq req, String actor) {
        String code = req.code().trim();
        repo.findExportTemplateByCode(code).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_DUPLICATE,
                    "报文模板编码已存在：" + code);
        });
        List<TemplateField> fields = normalize(req.fields());
        validateFields(fields);
        ExportMessageTemplate template = new ExportMessageTemplate(UUID.randomUUID(), code,
                req.name().trim(), 0, ExportMessageTemplate.DRAFT, toJson(fields), actor, actor,
                null, null, 0L, Instant.now(), Instant.now());
        try {
            repo.insertExportTemplate(template);
        } catch (DataIntegrityViolationException e) {
            // 并发创建同编码：唯一索引兜底，晚到者 409
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_DUPLICATE,
                    "报文模板编码已存在：" + code);
        }
        audit(actor, "EXPORT_TEMPLATE_CREATE", template, null);
        return detail(template.id());
    }

    @Transactional
    public ExportTemplateView update(UUID id, UpdateExportTemplateReq req, String actor) {
        ExportMessageTemplate current = find(id);
        if (!ExportMessageTemplate.DRAFT.equals(current.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_STATUS_INVALID,
                    "仅草稿状态可修改，当前状态：" + current.status());
        }
        List<TemplateField> fields = normalize(req.fields());
        validateFields(fields);
        if (!repo.updateExportTemplate(id, req.name().trim(), toJson(fields), actor,
                req.rowVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "报文模板已被他人修改，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", current.code());
        detail.put("name", req.name().trim());
        detail.put("fieldCount", fields.size());
        audit(actor, "EXPORT_TEMPLATE_UPDATE", current, detail);
        return detail(id);
    }

    /**
     * 发布草稿：发布后成为全局唯一生效模板（version+1）。
     *
     * <p>发布前**重新校验一次库里的字段**：防止绕过接口直接改库导致脏模板生效。
     */
    @Transactional
    public ExportTemplateView publish(UUID id, String actor) {
        ExportMessageTemplate current = find(id);
        if (!ExportMessageTemplate.DRAFT.equals(current.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_STATUS_INVALID,
                    "仅草稿状态可发布，当前状态：" + current.status());
        }
        validateFields(parseFields(current.fields()));
        try {
            if (!repo.publishExportTemplate(id, actor, current.rowVersion())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "报文模板已被他人修改，请刷新后重试");
            }
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_PUBLISH_CONFLICT);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", current.code());
        detail.put("version", current.version() + 1);
        audit(actor, "EXPORT_TEMPLATE_PUBLISH", current, detail);
        return detail(id);
    }

    /**
     * 停用生效模板（PUBLISHED → DISABLED）。
     *
     * <p>停用后导出回落内置 BUILTIN 布局；批次仍会快照模板来源，便于事后核对。
     */
    @Transactional
    public ExportTemplateView disable(UUID id, String actor) {
        ExportMessageTemplate current = find(id);
        if (!ExportMessageTemplate.PUBLISHED.equals(current.status())) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_STATUS_INVALID,
                    "仅生效中的模板可停用，当前状态：" + current.status());
        }
        if (!repo.disableExportTemplate(id, actor, current.rowVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "报文模板已被他人更新，请刷新后重试");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", current.code());
        detail.put("version", current.version());
        audit(actor, "EXPORT_TEMPLATE_DISABLE", current, detail);
        return detail(id);
    }

    // ---------------------------------------------------------------- 导出侧复用

    /** 解析库中字段 JSON；损坏视为配置无效，不能静默使用半个模板。 */
    public List<TemplateField> parseFields(String json) {
        return parseFields(objectMapper, json);
    }

    /** 供导出侧复用的静态解析（不引入服务间依赖）。 */
    public static List<TemplateField> parseFields(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                    "报文模板字段配置为空");
        }
        try {
            TemplateField[] fields = mapper.readValue(json, TemplateField[].class);
            return fields == null ? List.of() : List.of(fields);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                    "报文模板字段配置损坏，请重新保存模板");
        }
    }

    /**
     * 校验字段映射：元素名合法且唯一、来源必须可导出、省局核心必填项必须声明。
     *
     * <p>这些规则放在保存/发布/导出三个入口，保证库里任何 PUBLISHED 模板都能真正出报文。
     */
    public static void validateFields(List<TemplateField> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                    "报文模板至少需要一个字段映射");
        }
        if (fields.size() > MAX_FIELDS) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                    "单个模板最多支持 " + MAX_FIELDS + " 个字段");
        }
        Set<String> elements = new HashSet<>();
        Set<String> requiredSources = new HashSet<>();
        for (TemplateField field : fields) {
            String element = field == null ? null : trim(field.element());
            String source = field == null ? null : trim(field.source());
            if (element == null || element.isEmpty()) {
                throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                        "报文字段名不能为空");
            }
            if (!element.matches(ELEMENT_PATTERN)) {
                throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                        "报文字段名不合法：" + element + "（字母开头，仅限字母/数字/._-）");
            }
            if (!elements.add(element.toLowerCase(Locale.ROOT))) {
                throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                        "报文字段名重复：" + element);
            }
            if (source == null || !SUPPORTED_SOURCES.contains(source)) {
                throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                        "报文字段 " + element + " 的来源不受支持：" + source);
            }
            if (field.isRequired()) {
                requiredSources.add(source);
            }
        }
        for (String core : CORE_REQUIRED_SOURCES) {
            if (!requiredSources.contains(core)) {
                throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                        "省局核心字段必须声明为必填：" + core);
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    private ExportMessageTemplate find(UUID id) {
        return repo.findExportTemplate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSURANCE_TEMPLATE_NOT_FOUND));
    }

    private ExportTemplateView view(ExportMessageTemplate template) {
        return new ExportTemplateView(template.id(), template.code(), template.name(),
                template.version(), template.status(), parseFields(template.fields()),
                template.createdBy(), template.updatedBy(), template.publishedBy(),
                template.publishedAt(), template.rowVersion(), template.createdAt(),
                template.updatedAt());
    }

    private static List<TemplateField> normalize(List<TemplateFieldReq> requests) {
        List<TemplateField> fields = new ArrayList<>(requests.size());
        for (TemplateFieldReq req : requests) {
            if (req == null) {
                throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                        "报文字段映射不能为空");
            }
            fields.add(new TemplateField(trim(req.element()), trim(req.source()),
                    Boolean.TRUE.equals(req.required()), trim(req.label())));
        }
        return fields;
    }

    private String toJson(List<TemplateField> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.INSURANCE_TEMPLATE_INVALID,
                    "报文模板字段无法序列化");
        }
    }

    private void audit(String actor, String action, ExportMessageTemplate template,
                       Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("code", template.code());
        detail.put("version", template.version());
        detail.put("status", template.status());
        if (extra != null) {
            detail.putAll(extra);
        }
        try {
            repo.logChange(actor, action, "EXPORT_TEMPLATE", template.id().toString(),
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "EXPORT_TEMPLATE", template.id().toString(),
                    "{\"_detail_serialize_failed\":true}");
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
