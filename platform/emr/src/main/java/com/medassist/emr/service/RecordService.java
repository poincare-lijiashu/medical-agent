package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.client.PatientClient;
import com.medassist.emr.domain.EmrModel.Amendment;
import com.medassist.emr.domain.EmrModel.MedicalRecord;
import com.medassist.emr.domain.EmrModel.QcResult;
import com.medassist.emr.domain.EmrModel.QcRule;
import com.medassist.emr.domain.EmrModel.RecordSignature;
import com.medassist.emr.domain.EmrModel.Template;
import com.medassist.emr.repo.EmrRepository;
import com.medassist.emr.web.EmrDtos.AmendReq;
import com.medassist.emr.web.EmrDtos.CreateRecordReq;
import com.medassist.emr.web.EmrDtos.HashVerify;
import com.medassist.emr.web.EmrDtos.QcReport;
import com.medassist.emr.web.EmrDtos.RecordView;
import com.medassist.emr.web.EmrDtos.SaveContentReq;
import com.medassist.emr.web.EmrDtos.SubmitResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 病历书写、质控、签名与归档。
 *
 * <p>核心不变量：
 * <ol>
 *   <li>只有 DRAFT 可改正文；SUBMITTED 只能签名；SIGNED 只能归档或修订；ARCHIVED 终态；</li>
 *   <li>提交前必须过质控，FATAL 项未通过直接 409（不落库，响应里返回明细）；</li>
 *   <li>签名时对 contentText 计算 SHA-256 并落库，事后可用 verify 接口校验是否被改；</li>
 *   <li>修订保留旧版本到 record_amendment，revision+1 并清空签名（必须重新签）。</li>
 * </ol>
 */
@Service
public class RecordService {

    private final EmrRepository repo;
    private final PatientClient patientClient;
    private final ObjectMapper objectMapper;

    public RecordService(EmrRepository repo, PatientClient patientClient, ObjectMapper objectMapper) {
        this.repo = repo;
        this.patientClient = patientClient;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------- 书写

    @Transactional
    public RecordView create(CreateRecordReq req, String actor) {
        Template template = repo.findTemplateByCode(req.templateCode().trim())
                .filter(Template::enabled)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_TEMPLATE_NOT_FOUND,
                        "模板不存在或已停用：" + req.templateCode()));
        PatientClient.PatientRef patient = patientClient.requireActive(req.patientId());
        Map<String, String> content = req.content() == null ? Map.of() : req.content();
        String contentText = render(template.schemaJson(), content);

        MedicalRecord record = new MedicalRecord(UUID.randomUUID(), repo.nextRecordNo(),
                patient.id(), patient.patientNo(), req.visitId(), blankToNull(req.visitNo()),
                req.deptId(), template.docType(), template.templateCode(),
                req.title() == null || req.title().isBlank()
                        ? template.name() + " " + patient.name() : req.title().trim(),
                toJson(content), contentText, actor, MedicalRecord.DRAFT, 1,
                null, null, null, null, 0L, null);
        repo.insertRecord(record);
        audit(actor, "RECORD_CREATE", "RECORD", record.id().toString(), Map.of(
                "recordNo", record.recordNo(), "patientNo", patient.patientNo(),
                "docType", record.docType(), "templateCode", record.templateCode()));
        return detail(record.id());
    }

    /** 保存草稿（仅 DRAFT；必须带版本号防并发覆盖）。 */
    @Transactional
    public RecordView save(UUID id, SaveContentReq req, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!MedicalRecord.DRAFT.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有草稿可以修改正文；已提交/已签名病历请走修订，当前：" + record.status());
        }
        Template template = repo.findTemplateByCode(record.templateCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_TEMPLATE_NOT_FOUND));
        String contentText = render(template.schemaJson(), req.content());
        if (!repo.updateDraftContent(id, toJson(req.content()), contentText, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历已被他人修改，请刷新后重试");
        }
        audit(actor, "RECORD_SAVE", "RECORD", id.toString(), Map.of("recordNo", record.recordNo()));
        return detail(id);
    }

    // ---------------------------------------------------------------- 提交（质控）

    @Transactional
    public SubmitResult submit(UUID id, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!MedicalRecord.DRAFT.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有草稿可以提交，当前：" + record.status());
        }
        Map<String, String> content = fromJson(record.content());
        QcReport qc = evaluate(record, content);
        if (qc.fatalCount() > 0) {
            String detail = qc.results().stream()
                    .filter(r -> !r.passed() && QcRule.FATAL.equals(r.severity()))
                    .limit(3)
                    .map(QcResult::message)
                    .reduce((a, b) -> a + "；" + b).orElse("");
            throw new BusinessException(ErrorCode.EMR_QC_BLOCKED, "质控未通过：" + detail);
        }
        for (QcResult result : qc.results()) {
            repo.insertQcResult(result);
        }
        if (!repo.updateStatus(id, MedicalRecord.DRAFT, MedicalRecord.SUBMITTED, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
        }
        audit(actor, "RECORD_SUBMIT", "RECORD", id.toString(), Map.of(
                "recordNo", record.recordNo(), "fatal", qc.fatalCount(),
                "major", qc.majorCount(), "minor", qc.minorCount()));
        return new SubmitResult(detail(id), qc);
    }

    /** 提交前预览质控结果（不落库）。 */
    public QcReport previewQc(UUID id) {
        MedicalRecord record = repo.findRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        return evaluate(record, fromJson(record.content()));
    }

    // ---------------------------------------------------------------- 签名 / 归档

    @Transactional
    public RecordView sign(UUID id, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!MedicalRecord.SUBMITTED.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有已提交的病历可以签名，当前：" + record.status());
        }
        String hash = sha256(record.contentText());
        if (!repo.signRecord(id, actor, hash, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
        }
        repo.insertSignature(new RecordSignature(UUID.randomUUID(), id, record.revision(),
                actor, RecordSignature.AUTHOR, null, hash));
        audit(actor, "RECORD_SIGN", "RECORD", id.toString(), Map.of(
                "recordNo", record.recordNo(), "revision", record.revision(), "hash", hash));
        return detail(id);
    }

    @Transactional
    public RecordView archive(UUID id, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (!MedicalRecord.SIGNED.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有已签名病历可以归档，当前：" + record.status());
        }
        if (!repo.archiveRecord(id, record.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
        }
        audit(actor, "RECORD_ARCHIVE", "RECORD", id.toString(), Map.of(
                "recordNo", record.recordNo(), "revision", record.revision()));
        return detail(id);
    }

    // ---------------------------------------------------------------- 修订

    /**
     * 修订：签名后修改必须留痕。
     *
     * <p>旧版本写入 record_amendment，正文覆盖为 revision+1，状态回到 SUBMITTED 并清空签名——
     * 修改过的病历必须重新签名，且归档后不允许修订（需走病案修改流程，当前未实现）。
     */
    @Transactional
    public RecordView amend(UUID id, AmendReq req, String actor) {
        MedicalRecord record = repo.lockRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        if (record.archived()) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "已归档病历不允许修订，请走病案修改流程");
        }
        if (!MedicalRecord.SIGNED.equals(record.status())) {
            throw new BusinessException(ErrorCode.EMR_RECORD_STATUS_INVALID,
                    "只有已签名病历需要修订；草稿请直接保存，当前：" + record.status());
        }
        Template template = repo.findTemplateByCode(record.templateCode())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_TEMPLATE_NOT_FOUND));
        String newText = render(template.schemaJson(), req.content());

        // 先留旧版本（含旧哈希），再覆盖正文
        repo.insertAmendment(new Amendment(UUID.randomUUID(), id, record.revision(),
                record.content(), record.contentText(),
                record.contentHash() == null ? sha256(record.contentText()) : record.contentHash(),
                record.signedBy() == null ? record.author() : record.signedBy(), req.reason().trim(), null));

        if (!repo.applyAmendment(id, toJson(req.content()), newText, record.version(), record.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "病历状态已变化，请刷新后重试");
        }
        audit(actor, "RECORD_AMEND", "RECORD", id.toString(), Map.of(
                "recordNo", record.recordNo(), "fromRevision", record.revision(),
                "reason", req.reason().trim()));
        return detail(id);
    }

    // ---------------------------------------------------------------- 查询

    public RecordView detail(UUID id) {
        MedicalRecord record = repo.findRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        return new RecordView(record, repo.listSignatures(id), repo.listAmendments(id),
                repo.listQcResults(id, record.revision()));
    }

    public List<MedicalRecord> list(String status, UUID patientId, UUID visitId, Integer limit) {
        String normalized = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        int size = limit == null || limit <= 0 ? 50 : Math.min(limit, 200);
        return repo.listRecords(normalized, patientId, visitId, size);
    }

    /** 校验内容哈希：重新计算并与签名时保存的哈希比对。 */
    public HashVerify verify(UUID id) {
        MedicalRecord record = repo.findRecord(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_RECORD_NOT_FOUND));
        String recomputed = sha256(record.contentText());
        String stored = record.contentHash();
        boolean valid = stored != null && stored.equals(recomputed);
        return new HashVerify(valid, stored, recomputed);
    }

    // ---------------------------------------------------------------- 质控引擎

    /**
     * 内置三类规则，避免引入表达式引擎（表达式求值是可注入面，且难以审计）。
     *
     * <p>SIGNATURE_REQUIRED 在提交阶段不适用（此时还没有签名），留待归档前校验。
     */
    private QcReport evaluate(MedicalRecord record, Map<String, String> content) {
        List<QcRule> rules = repo.listQcRules(record.docType());
        List<QcResult> results = new ArrayList<>();
        int fatal = 0;
        int major = 0;
        int minor = 0;
        for (QcRule rule : rules) {
            Boolean passed = switch (rule.ruleType()) {
                case QcRule.REQUIRED_FIELD -> notBlank(content.get(rule.fieldKey()));
                case QcRule.MIN_LENGTH -> {
                    String v = content.get(rule.fieldKey());
                    int min = rule.threshold() == null ? 1 : rule.threshold();
                    yield v != null && v.trim().length() >= min;
                }
                default -> null;
            };
            if (passed == null) {
                continue;
            }
            if (!passed) {
                if (QcRule.FATAL.equals(rule.severity())) {
                    fatal++;
                } else if (QcRule.MAJOR.equals(rule.severity())) {
                    major++;
                } else {
                    minor++;
                }
            }
            results.add(new QcResult(UUID.randomUUID(), record.id(), record.revision(),
                    rule.ruleCode(), rule.severity(), passed, rule.message()));
        }
        return new QcReport(fatal == 0, fatal, major, minor, results);
    }

    // ---------------------------------------------------------------- 渲染 / 哈希

    /**
     * 渲染确定性文本：按模板字段顺序输出"标签：值"，模板外的字段按 key 排序追加。
     *
     * <p>空值也输出（保持行数稳定），保证"同样内容 → 同样哈希"。
     */
    String render(String schemaJson, Map<String, String> content) {
        StringBuilder sb = new StringBuilder();
        Set<String> used = new HashSet<>();
        List<Map<?, ?>> fields = parseSchema(schemaJson);
        for (Map<?, ?> field : fields) {
            Object keyObj = field.get("key");
            if (keyObj == null) {
                continue;
            }
            String key = String.valueOf(keyObj);
            String label = field.get("label") == null ? key : String.valueOf(field.get("label"));
            String value = content.getOrDefault(key, "");
            sb.append(label).append("：").append(value == null ? "" : value).append('\n');
            used.add(key);
        }
        content.entrySet().stream()
                .filter(e -> !used.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append("：")
                        .append(e.getValue() == null ? "" : e.getValue()).append('\n'));
        return sb.toString();
    }

    private List<Map<?, ?>> parseSchema(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(schemaJson, List.class);
        } catch (JacksonException e) {
            // 模板损坏时退化为"仅按 key 排序渲染"，不能因为渲染失败挡住病历书写
            return List.of();
        }
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Map<String, String> content) {
        try {
            return objectMapper.writeValueAsString(content == null ? Map.of() : content);
        } catch (JacksonException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "病历内容无法序列化");
        }
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
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
