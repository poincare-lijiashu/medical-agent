package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.domain.PharmacyModel.Supplier;
import com.medassist.pharmacy.repo.PurchaseRepository;
import com.medassist.pharmacy.web.PurchaseDtos.CreateSupplierReq;
import com.medassist.pharmacy.web.PurchaseDtos.SupplierView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 供应商台账（GSP 首营企业与证照有效期）。
 *
 * <p>为什么证照有效期必须是必填且参与采购判断：
 * 供应商《药品经营许可证》过期后仍能开单，是飞检最常见的缺陷项；
 * 这里把"证照校验"放到建单入口，而不是靠药师记得到期日。
 */
@Service
public class SupplierService {

    private final PurchaseRepository repo;
    private final ObjectMapper objectMapper;

    public SupplierService(PurchaseRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SupplierView create(CreateSupplierReq req, String actor) {
        String code = req.code().trim();
        if (repo.findSupplierByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "供应商编码已存在：" + code);
        }
        Supplier supplier = new Supplier(UUID.randomUUID(), code, req.name().trim(),
                blankToNull(req.contact()), blankToNull(req.phone()), req.licenseNo().trim(),
                req.licenseExpireDate(), Supplier.ACTIVE, blankToNull(req.remark()), 0L,
                Instant.now());
        try {
            repo.insertSupplier(supplier);
        } catch (DataIntegrityViolationException e) {
            // 并发建同一编码：唯一键兜底（预检查无法覆盖两个请求同时通过的情况）
            throw new BusinessException(ErrorCode.CONFLICT, "供应商编码已存在：" + code);
        }
        audit(actor, "SUPPLIER_CREATE", supplier.id().toString(), Map.of(
                "code", supplier.code(), "name", supplier.name(),
                "licenseNo", supplier.licenseNo(),
                "licenseExpireDate", supplier.licenseExpireDate().toString()));
        return SupplierView.of(supplier, BusinessTime.today());
    }

    public List<SupplierView> list(String keyword, String status, Integer limit) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        LocalDate today = BusinessTime.today();
        return repo.listSuppliers(keyword, status, size).stream()
                .map(s -> SupplierView.of(s, today))
                .toList();
    }

    @Transactional
    public SupplierView updateStatus(UUID id, String status, long version, String actor) {
        Supplier supplier = repo.findSupplier(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND));
        // 幂等：已是目标状态直接返回，双击/重试不应该报"数据已被修改"
        if (status.equals(supplier.status())) {
            return SupplierView.of(supplier, BusinessTime.today());
        }
        if (!repo.updateSupplierStatus(id, status, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "供应商状态已被他人修改，请刷新后重试");
        }
        Supplier updated = repo.findSupplier(id).orElseThrow();
        audit(actor, "SUPPLIER_STATUS", id.toString(), Map.of(
                "from", supplier.status(), "to", status, "fromVersion", supplier.version(),
                "toVersion", updated.version()));
        return SupplierView.of(updated, BusinessTime.today());
    }

    public Supplier requireActiveLicensed(UUID id, LocalDate today) {
        Supplier supplier = repo.findSupplier(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUPPLIER_NOT_FOUND));
        if (!supplier.active()) {
            throw new BusinessException(ErrorCode.SUPPLIER_INACTIVE,
                    "供应商 " + supplier.name() + " 已停用，不能新开采购单");
        }
        if (!supplier.licensedOn(today)) {
            throw new BusinessException(ErrorCode.SUPPLIER_LICENSE_EXPIRED,
                    "供应商 " + supplier.name() + " 的药品经营许可证已于 "
                            + supplier.licenseExpireDate() + " 到期");
        }
        return supplier;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private void audit(String actor, String action, String targetId, Map<String, Object> detail) {
        try {
            repo.logChange(actor, action, "SUPPLIER", targetId,
                    objectMapper.writeValueAsString(new LinkedHashMap<>(detail)));
        } catch (JacksonException e) {
            repo.logChange(actor, action, "SUPPLIER", targetId,
                    "{\"_detail_serialize_failed\":true}");
        }
    }
}
