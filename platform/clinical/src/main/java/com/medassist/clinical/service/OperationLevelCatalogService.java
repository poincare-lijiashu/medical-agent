package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.OperationCatalog;
import com.medassist.clinical.domain.ClinicalModel.OperationLevel;
import com.medassist.clinical.domain.ClinicalModel.OperationLevelRow;
import com.medassist.clinical.repo.ClinicalRepository;
import com.medassist.clinical.web.SurgeryLevelDtos.UpsertOperationLevelReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 手术分级目录：术种 → 默认分级（G81）。
 *
 * <p>目录是**全院默认值**，不是审批结论：
 * <ol>
 *   <li>计划手术时按术种带出：1~2 级直接免审批，3~4 级自动进入待审批；</li>
 *   <li>术者仍可在开台前按实际情况调整，调整后按 V18 的审批规则重新走流程；</li>
 *   <li>只有字典里存在且启用的编码可以配置——否则计划手术时永远匹配不上，等于埋了一条静默失效的配置；</li>
 *   <li>修改与删除都带版本号 CAS，避免两个人同时改同一术种时后写覆盖前写。</li>
 * </ol>
 */
@Service
public class OperationLevelCatalogService {

    private static final int LIST_LIMIT = 100;
    private static final int MIN_LEVEL = 1;
    private static final int MAX_LEVEL = 4;

    private final ClinicalRepository repo;

    public OperationLevelCatalogService(ClinicalRepository repo) {
        this.repo = repo;
    }

    /** 目录列表（编码/名称模糊匹配，{@code limit} 在 SQL 层生效）。 */
    public List<OperationLevelRow> list(String keyword, int limit) {
        int size = limit <= 0 ? LIST_LIMIT : Math.min(limit, LIST_LIMIT);
        return repo.listOperationLevels(keyword, size);
    }

    /** 新增或修改目录项；修改必须带 version。 */
    @Transactional
    public OperationLevel upsert(String code, UpsertOperationLevelReq req, String actor) {
        String operationCode = code == null ? "" : code.trim();
        if (operationCode.isEmpty()) {
            throw new BusinessException(ErrorCode.OPERATION_LEVEL_INVALID, "手术编码不能为空");
        }
        int level = req.level() == null ? 0 : req.level();
        if (level < MIN_LEVEL || level > MAX_LEVEL) {
            throw new BusinessException(ErrorCode.OPERATION_LEVEL_INVALID);
        }
        OperationCatalog catalog = repo.findOperation(operationCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPERATION_LEVEL_INVALID,
                        "手术编码不存在：" + operationCode));
        if (!catalog.enabled()) {
            throw new BusinessException(ErrorCode.OPERATION_LEVEL_INVALID,
                    "手术编码已停用，不能配置默认分级：" + operationCode);
        }
        String operator = requireActor(actor);
        String note = blankToNull(req.note());
        OperationLevel existing = repo.findOperationLevel(operationCode).orElse(null);
        if (existing == null) {
            OperationLevel created = new OperationLevel(operationCode, level, note, operator,
                    0L, Instant.now(), Instant.now());
            try {
                repo.insertOperationLevel(created);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(ErrorCode.OPERATION_LEVEL_CONFLICT,
                        "该目录项已被他人创建，请刷新后重试");
            }
            audit(operator, "OPERATION_LEVEL_SET", operationCode, "create level=" + level);
            return repo.findOperationLevel(operationCode).orElse(created);
        }
        if (req.version() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "修改默认分级必须携带 version（乐观锁）");
        }
        if (!repo.updateOperationLevel(operationCode, level, note, operator, req.version())) {
            throw new BusinessException(ErrorCode.OPERATION_LEVEL_CONFLICT);
        }
        audit(operator, "OPERATION_LEVEL_SET", operationCode,
                "update " + existing.level() + "->" + level);
        return repo.findOperationLevel(operationCode).orElseThrow();
    }

    /** 删除目录项：之后计划同类手术不再自动带出分级（历史记录不受影响）。 */
    @Transactional
    public void delete(String code, long version, String actor) {
        String operationCode = code == null ? "" : code.trim();
        OperationLevel existing = repo.findOperationLevel(operationCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.OPERATION_LEVEL_NOT_FOUND));
        if (!repo.deleteOperationLevel(operationCode, version)) {
            throw new BusinessException(ErrorCode.OPERATION_LEVEL_CONFLICT);
        }
        audit(requireActor(actor), "OPERATION_LEVEL_DELETE", operationCode,
                "delete level=" + existing.level());
    }

    private void audit(String actor, String action, String code, String detail) {
        repo.logChange(actor, action, "OPERATION_LEVEL", code,
                "{\"detail\":\"" + escape(detail) + "\"}");
    }

    private static String requireActor(String actor) {
        return actor == null || actor.isBlank() ? "system" : actor.trim();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
