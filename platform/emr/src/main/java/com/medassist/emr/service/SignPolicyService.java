package com.medassist.emr.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.SignPolicy;
import com.medassist.emr.repo.EmrRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 归档签名策略管理（N-04）。
 *
 * <p>策略决定"某类文书归档前必须签到哪一级"，是电子病历分级评审的硬指标配置：
 * <ul>
 *   <li>修改必须携带当前 version（CAS），避免两个病案室窗口并发保存时"最后写赢"，
 *       把出院小结从 3 级静默降为 1 级；</li>
 *   <li>每次变更写 {@code SIGN_POLICY_UPSERT} 审计，detail 记 old→new 对照；</li>
 *   <li>文书类型限定为模板 CHECK 允许的内置集合，避免产生永不被引用的孤儿策略。</li>
 * </ul>
 */
@Service
public class SignPolicyService {

    /** 与 md_template.doc_type 的 CHECK 约束保持一致；新增文书类型需先扩展模板与策略。 */
    private static final Set<String> DOC_TYPES = Set.of(
            "ADMISSION_NOTE", "FIRST_COURSE", "PROGRESS_NOTE", "DISCHARGE_SUMMARY",
            "OPERATION_NOTE", "OUTPATIENT_NOTE", "CONSULT_NOTE");

    /** 允许的文书类型（供启动守卫在报错信息里列出可选值）。 */
    public static Set<String> docTypes() {
        return DOC_TYPES;
    }

    private final EmrRepository repo;
    private final ObjectMapper objectMapper;

    public SignPolicyService(EmrRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public List<SignPolicy> list() {
        return repo.listSignPolicies();
    }

    /**
     * 新增或修改策略。
     *
     * @param version 已存在的策略必须传入当前版本号；不存在时传 null 走创建
     */
    @Transactional
    public SignPolicy upsert(String docType, int requiredLevel, Long version, String actor) {
        String type = normalize(docType);
        if (requiredLevel < 1 || requiredLevel > 3) {
            throw new BusinessException(ErrorCode.EMR_SIGN_POLICY_INVALID,
                    "签名级别必须为 1（书写医师）/ 2（主治）/ 3（主任）");
        }
        SignPolicy before = repo.findSignPolicy(type).orElse(null);
        if (before == null) {
            if (version != null) {
                throw new BusinessException(ErrorCode.EMR_SIGN_POLICY_INVALID,
                        "「" + type + "」尚无签名策略，创建时不要携带版本号");
            }
            if (!repo.insertSignPolicy(type, requiredLevel, actor)) {
                // 唯一键冲突：另一个窗口已并发创建
                throw new BusinessException(ErrorCode.CONFLICT,
                        "签名策略已被他人创建，请刷新后重试");
            }
            audit(actor, type, null, requiredLevel);
        } else {
            if (version == null) {
                throw new BusinessException(ErrorCode.EMR_SIGN_POLICY_INVALID,
                        "修改签名策略必须携带当前版本号（version=" + before.version() + "）");
            }
            if (!repo.updateSignPolicy(type, requiredLevel, version, actor)) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "签名策略已被他人修改，请刷新后重试");
            }
            audit(actor, type, before.requiredLevel(), requiredLevel);
        }
        return repo.findSignPolicy(type)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMR_SIGN_POLICY_INVALID,
                        "签名策略保存后读取失败：" + type));
    }

    private String normalize(String docType) {
        String type = docType == null ? "" : docType.trim().toUpperCase(Locale.ROOT);
        if (!DOC_TYPES.contains(type)) {
            // 回显截断：docType 来自路径变量，长度不受限，不截断会让响应/日志被超长串撑爆
            String shown = type.length() > 64 ? type.substring(0, 64) + "…" : type;
            throw new BusinessException(ErrorCode.EMR_SIGN_POLICY_INVALID,
                    "文书类型不合法：" + shown + "（可选值 " + String.join("/", DOC_TYPES) + "）");
        }
        return type;
    }

    private void audit(String actor, String docType, Integer beforeLevel, int afterLevel) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("docType", docType);
        detail.put("before", beforeLevel);
        detail.put("after", afterLevel);
        detail.put("mode", beforeLevel == null ? "CREATE" : "UPDATE");
        try {
            repo.logChange(actor, "SIGN_POLICY_UPSERT", "SIGN_POLICY", docType,
                    objectMapper.writeValueAsString(detail));
        } catch (JacksonException e) {
            repo.logChange(actor, "SIGN_POLICY_UPSERT", "SIGN_POLICY", docType,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
