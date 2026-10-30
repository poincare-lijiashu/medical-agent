package com.medassist.empi.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.api.PageResult;
import com.medassist.common.exception.BusinessException;
import com.medassist.empi.crypto.FieldCipher;
import com.medassist.empi.domain.Patient;
import com.medassist.empi.repo.PatientRepository;
import com.medassist.empi.web.dto.CreatePatientReq;
import com.medassist.empi.web.dto.MergePatientReq;
import com.medassist.empi.web.dto.PatientResp;
import com.medassist.empi.web.dto.UpdatePatientReq;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 患者主索引业务规则。
 *
 * <p>生产级约束：
 * <ul>
 *   <li>证件号查重（(证型, 哈希) 唯一），命中返回 {@link ErrorCode#PATIENT_DUPLICATE} 并带出已有档案号；</li>
 *   <li>敏感字段加密落库；对外默认脱敏，{@code reveal=true} 才返回明文且强制审计；</li>
 *   <li>合并：双档案必须有效、源 != 目标；合并后源标记 MERGED 并迁移跨系统标识、留合并痕迹；</li>
 *   <li>所有写操作走乐观锁；全部变更写 empi 域审计。</li>
 * </ul>
 */
@Service
public class PatientService {

    private final PatientRepository repo;
    private final FieldCipher cipher;
    private final ObjectMapper objectMapper;

    public PatientService(PatientRepository repo, FieldCipher cipher, ObjectMapper objectMapper) {
        this.repo = repo;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PatientResp create(CreatePatientReq req, String actor) {
        String idType = normalizeIdType(req.idType(), req.idNo());
        String idNoHash = cipher.hash(req.idNo());

        if (idNoHash != null) {
            repo.findActiveByIdNoHash(idType, idNoHash).ifPresent(existing -> {
                throw new BusinessException(ErrorCode.PATIENT_DUPLICATE,
                        "该证件号已存在档案：" + existing.patientNo());
            });
        }

        UUID id = UUID.randomUUID();
        Patient p = new Patient(
                id,
                repo.nextPatientNo(),
                req.name().trim(),
                req.gender() == null ? "UNKNOWN" : req.gender(),
                req.birthDate(),
                idType,
                cipher.encrypt(req.idNo()),
                idNoHash,
                cipher.encrypt(req.phone()),
                cipher.hash(req.phone()),
                cipher.encrypt(req.address()),
                blankToNull(req.bloodType()),
                blankToNull(req.allergy()),
                Patient.STATUS_ACTIVE,
                null,
                0L, null, null);
        repo.insert(p);
        audit(actor, "PATIENT_CREATE", id.toString(), Map.of(
                "patientNo", p.patientNo(), "name", p.name()));
        // 建档响应同样默认脱敏（最小暴露原则）：调用方需要明文必须显式 reveal 并留审计
        return detail(id, false, actor);
    }

    /** 详情；reveal=true 返回明文并强制审计（P0-4 接入权限后改为必须持有 read_phi 权限）。 */
    public PatientResp detail(UUID id, boolean reveal, String actor) {
        Patient p = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATIENT_NOT_FOUND));
        if (reveal) {
            audit(actor, "PATIENT_VIEW_PHI", id.toString(), Map.of("patientNo", p.patientNo()));
        }
        return toResp(p, reveal);
    }

    public PageResult<PatientResp> search(String patientNo, String nameKeyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), PageResult.MAX_PAGE_SIZE);
        List<PatientResp> items = repo.search(blankToNull(patientNo), blankToNull(nameKeyword),
                        safeSize, (safePage - 1) * safeSize)
                .stream().map(p -> toResp(p, false)).toList();
        long total = repo.count(blankToNull(patientNo), blankToNull(nameKeyword));
        return PageResult.of(safePage, safeSize, total, items);
    }

    @Transactional
    public PatientResp update(UUID id, UpdatePatientReq req, String actor) {
        Patient current = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATIENT_NOT_FOUND));
        if (!current.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该档案已合并或已归档，不能修改");
        }

        String idType = current.idType();
        String idNoEnc = current.idNoEnc();
        String idNoHash = current.idNoHash();
        // 证件号变更：重新查重（排除自身）
        if (req.idNo() != null && !req.idNo().isBlank()) {
            String newType = normalizeIdType(req.idType(), req.idNo());
            String newHash = cipher.hash(req.idNo());
            repo.findActiveByIdNoHash(newType, newHash).ifPresent(other -> {
                if (!other.id().equals(id)) {
                    throw new BusinessException(ErrorCode.PATIENT_DUPLICATE,
                            "该证件号已存在其他档案：" + other.patientNo());
                }
            });
            idType = newType;
            idNoEnc = cipher.encrypt(req.idNo());
            idNoHash = newHash;
        }

        boolean ok = repo.update(id, req.name().trim(),
                req.gender() == null ? current.gender() : req.gender(),
                req.birthDate(), idType, idNoEnc, idNoHash,
                cipher.encrypt(req.phone()), cipher.hash(req.phone()),
                cipher.encrypt(req.address()),
                blankToNull(req.bloodType()), blankToNull(req.allergy()),
                req.version());
        if (!ok) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        audit(actor, "PATIENT_UPDATE", id.toString(), Map.of(
                "patientNo", current.patientNo(), "fromVersion", current.version()));
        return detail(id, false, actor);
    }

    @Transactional
    public PatientResp merge(MergePatientReq req, String actor) {
        if (req.fromId().equals(req.toId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "源档案与目标档案不能相同");
        }
        Patient from = repo.findById(req.fromId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PATIENT_NOT_FOUND, "源档案不存在"));
        Patient to = repo.findById(req.toId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PATIENT_NOT_FOUND, "目标档案不存在"));
        if (!from.active()) {
            throw new BusinessException(ErrorCode.PATIENT_MERGE_CONFLICT, "源档案不是有效状态，无法合并");
        }
        if (!to.active()) {
            throw new BusinessException(ErrorCode.PATIENT_MERGE_CONFLICT, "目标档案不是有效状态，无法合并");
        }

        if (!repo.markMerged(from.id(), to.id(), from.version())) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        int moved = repo.moveCrosswalk(from.id(), to.id());
        repo.logMerge(from.id(), to.id(), actor, req.reason().trim(), moved);
        audit(actor, "PATIENT_MERGE", from.id().toString(), Map.of(
                "fromPatientNo", from.patientNo(),
                "toPatientNo", to.patientNo(),
                "movedCrosswalk", moved,
                "reason", req.reason().trim()));
        return detail(to.id(), false, actor);
    }

    @Transactional
    public void addCrosswalk(UUID id, String systemCode, String externalId, String actor) {
        Patient p = repo.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PATIENT_NOT_FOUND));
        if (!p.active()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该档案已合并或已归档，不能登记外部标识");
        }
        repo.addCrosswalk(id, systemCode, externalId);
        audit(actor, "PATIENT_CROSSWALK_ADD", id.toString(), Map.of(
                "systemCode", systemCode, "externalId", externalId));
    }

    public List<PatientResp.CrosswalkItem> crosswalk(UUID id) {
        repo.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.PATIENT_NOT_FOUND));
        return repo.listCrosswalk(id).stream()
                .map(a -> new PatientResp.CrosswalkItem(a[0], a[1]))
                .toList();
    }

    // ---------------------------------------------------------------- 内部方法

    private String normalizeIdType(String idType, String idNo) {
        if (idNo == null || idNo.isBlank()) {
            return "NONE";
        }
        if (idType == null || idType.isBlank() || "NONE".equals(idType)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "填写证件号时必须指定证件类型");
        }
        return idType;
    }

    private PatientResp toResp(Patient p, boolean reveal) {
        String idNo = cipher.decrypt(p.idNoEnc());
        String phone = cipher.decrypt(p.phoneEnc());
        String address = cipher.decrypt(p.addressEnc());
        List<PatientResp.CrosswalkItem> cw = repo.listCrosswalk(p.id()).stream()
                .map(a -> new PatientResp.CrosswalkItem(a[0], a[1]))
                .toList();
        return new PatientResp(
                p.id(), p.patientNo(), p.name(), p.gender(), p.birthDate(), p.idType(),
                reveal ? idNo : maskIdNo(idNo),
                reveal ? phone : maskPhone(phone),
                reveal ? address : maskAddress(address),
                p.bloodType(), p.allergy(), p.status(), p.mergedInto(),
                p.version(), p.createdAt(), !reveal, cw);
    }

    /** 证件号脱敏：保留前 4 与后 4，中间固定 4 个星号。 */
    private String maskIdNo(String v) {
        if (v == null || v.isBlank()) {
            return v;
        }
        if (v.length() <= 8) {
            return "****";
        }
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    /** 手机号脱敏：前 3 后 4。 */
    private String maskPhone(String v) {
        if (v == null || v.isBlank()) {
            return v;
        }
        if (v.length() <= 7) {
            return "****";
        }
        return v.substring(0, 3) + "****" + v.substring(v.length() - 4);
    }

    /** 地址脱敏：保留前 6 个字符。 */
    private String maskAddress(String v) {
        if (v == null || v.isBlank()) {
            return v;
        }
        return v.length() <= 6 ? "******" : v.substring(0, 6) + "******";
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, targetId, "{\"_detail_serialize_failed\":true}");
        }
    }
}
