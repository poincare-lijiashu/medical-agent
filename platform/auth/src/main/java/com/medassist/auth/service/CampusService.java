package com.medassist.auth.service;

import com.medassist.auth.domain.Campus;
import com.medassist.auth.repo.CampusRepository;
import com.medassist.auth.web.dto.CreateCampusReq;
import com.medassist.auth.web.dto.UpdateCampusReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 院区字典维护（G46）。
 *
 * <p>约束：
 * <ul>
 *   <li>编码一经创建不可修改——历史业务数据的 {@code campus_id} 与令牌声明都靠它对齐；</li>
 *   <li>不允许停用最后一个启用院区，否则新数据无处归属；</li>
 *   <li>所有写操作留审计（auth_svc.change_log，target_type=CAMPUS）。</li>
 * </ul>
 */
@Service
public class CampusService {

    private final CampusRepository repo;
    private final ObjectMapper objectMapper;

    public CampusService(CampusRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public List<Campus> list() {
        return repo.list();
    }

    @Transactional
    public Campus create(CreateCampusReq req, String actor) {
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        if (repo.existsByCode(code)) {
            throw new BusinessException(ErrorCode.CONFLICT, "院区编码已存在：" + code);
        }
        Campus campus = new Campus(UUID.randomUUID(), code, req.name().trim(),
                Campus.ACTIVE, req.sortNo() == null ? 100 : req.sortNo(), 0L);
        repo.insert(campus);
        audit(actor, "CAMPUS_CREATE", campus.id().toString(), Map.of(
                "code", campus.code(), "name", campus.name(), "sortNo", campus.sortNo()));
        return repo.findById(campus.id()).orElse(campus);
    }

    @Transactional
    public Campus update(UUID id, UpdateCampusReq req, String actor) {
        Campus current = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "院区不存在"));
        boolean disabling = Campus.DISABLED.equals(req.status()) && current.active();
        if (disabling && repo.countActive() <= 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "至少需要保留一个启用院区");
        }
        int sortNo = req.sortNo() == null ? current.sortNo() : req.sortNo();
        if (!repo.update(id, req.name().trim(), req.status(), sortNo, req.version())) {
            throw new BusinessException(ErrorCode.CONFLICT, "院区已被其他管理员修改，请刷新后重试");
        }
        audit(actor, "CAMPUS_UPDATE", id.toString(), Map.of(
                "code", current.code(), "name", req.name().trim(), "status", req.status(),
                "sortNo", sortNo));
        return repo.findById(id).orElseThrow();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(detail);
            repo.logChange(actor, action, targetId, objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
