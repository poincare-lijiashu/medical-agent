package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PharmacyDtos.CreateDrugReq;
import com.medassist.pharmacy.web.PharmacyDtos.DrugView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 药品字典。
 *
 * <p>为什么价格调整必须带版本号：调价会影响新处方的金额，而历史处方保存的是价格快照。
 * 如果两个窗口同时调价（一个改 12.50→13.00，一个改 12.50→11.80），无版本号会互相覆盖且无人知晓。
 */
@Service
public class DrugCatalogService {

    private final PharmacyRepository repo;
    private final ObjectMapper objectMapper;

    public DrugCatalogService(PharmacyRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DrugView create(CreateDrugReq req, String actor) {
        if (repo.findDrugByCode(req.drugCode().trim()).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "药品编码已存在：" + req.drugCode());
        }
        Drug drug = new Drug(UUID.randomUUID(), req.drugCode().trim(), req.name().trim(),
                blankToNull(req.tradeName()), req.spec().trim(), req.dosageForm(), req.unit().trim(),
                blankToNull(req.packageUnit()), req.packageSize(),
                req.price().setScale(2, RoundingMode.HALF_UP),
                blankToNull(req.insuranceCode()),
                req.insuranceCategory() == null || req.insuranceCategory().isBlank()
                        ? Drug.INSURANCE_NONE : req.insuranceCategory().trim(),
                req.antibioticLevel() == null ? 0 : req.antibioticLevel(),
                Boolean.TRUE.equals(req.narcotic()), true, 0L,
                req.maxDailyDose() == null ? null : req.maxDailyDose().setScale(3, RoundingMode.HALF_UP),
                Boolean.TRUE.equals(req.pediatricContraindicated()),
                Boolean.TRUE.equals(req.elderlyCaution()));
        try {
            repo.insertDrug(drug);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "药品编码或医保编码冲突，请核对后重试");
        }
        audit(actor, "DRUG_CREATE", "DRUG", drug.id().toString(), Map.of(
                "drugCode", drug.drugCode(), "name", drug.name(), "price", drug.price(),
                "insuranceCategory", drug.insuranceCategory(),
                "maxDailyDose", drug.maxDailyDose() == null ? "" : drug.maxDailyDose(),
                "pediatricContraindicated", drug.pediatricContraindicated(),
                "elderlyCaution", drug.elderlyCaution()));
        return new DrugView(drug, 0);
    }

    public List<DrugView> list(String keyword, Boolean enabled, Integer limit) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 500);
        return repo.listDrugs(keyword, enabled, size).stream()
                .map(d -> new DrugView(d, repo.totalStock(d.id())))
                .toList();
    }

    public DrugView detail(UUID id) {
        Drug drug = requireDrug(id);
        return new DrugView(drug, repo.totalStock(id));
    }

    /** 调价（CAS）：历史处方是快照，不受影响。 */
    @Transactional
    public DrugView adjustPrice(UUID id, BigDecimal price, long version, String actor) {
        Drug before = requireDrug(id);
        BigDecimal normalized = price.setScale(2, RoundingMode.HALF_UP);
        if (!repo.updateDrugPrice(id, normalized, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "药品信息已被他人修改，请刷新后重试");
        }
        Drug after = repo.findDrug(id).orElseThrow();
        audit(actor, "DRUG_PRICE", "DRUG", id.toString(), Map.of(
                "drugCode", before.drugCode(), "before", before.price(), "after", normalized));
        return new DrugView(after, repo.totalStock(id));
    }

    /** 启停用（CAS）：停用后不可开方，但已开处方仍可发药。 */
    @Transactional
    public DrugView toggle(UUID id, boolean enabled, long version, String actor) {
        Drug before = requireDrug(id);
        if (!repo.setDrugEnabled(id, enabled, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "药品信息已被他人修改，请刷新后重试");
        }
        audit(actor, enabled ? "DRUG_ENABLE" : "DRUG_DISABLE", "DRUG", id.toString(),
                Map.of("drugCode", before.drugCode()));
        return new DrugView(repo.findDrug(id).orElseThrow(), repo.totalStock(id));
    }

    // ---------------------------------------------------------------- 内部

    private Drug requireDrug(UUID id) {
        return repo.findDrug(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.DRUG_NOT_FOUND));
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
