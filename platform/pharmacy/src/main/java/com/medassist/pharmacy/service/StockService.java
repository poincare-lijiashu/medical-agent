package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.pharmacy.config.PharmacyProperties;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PharmacyDtos.ReceiveStockReq;
import com.medassist.pharmacy.web.PharmacyDtos.StockWarning;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 药品库存（批次 + 效期）。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li>不接受过期药品入库（入库时校验效期 ≥ 今天）；</li>
 *   <li>同一药品同一批号只允许一条库存记录（数据库唯一键），重复入库必须按"补录数量"处理；</li>
 *   <li>库存扣减只走 {@code quantity >= ?} 的原子更新，绝不做"先查后减"。</li>
 * </ol>
 */
@Service
public class StockService {

    private final PharmacyRepository repo;
    private final PharmacyProperties props;
    private final ObjectMapper objectMapper;

    public StockService(PharmacyRepository repo, PharmacyProperties props, ObjectMapper objectMapper) {
        this.repo = repo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DrugBatch receive(UUID drugId, ReceiveStockReq req, String actor) {
        Drug drug = repo.findDrug(drugId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DRUG_NOT_FOUND));
        if (!drug.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "药品已停用，不能入库：" + drug.name());
        }
        LocalDate today = BusinessTime.today();
        if (req.expireDate().isBefore(today)) {
            throw new BusinessException(ErrorCode.DRUG_BATCH_EXPIRED,
                    "不能入库已过期药品（效期 " + req.expireDate() + "，业务日 " + today + "）");
        }
        UUID batchId = UUID.randomUUID();
        try {
            repo.insertBatch(batchId, drugId, req.batchNo().trim(), req.expireDate(),
                    req.quantity(), blankToNull(req.supplier()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该药品已存在批号 " + req.batchNo() + "，请改用补录或核对批号");
        }
        DrugBatch batch = repo.findBatch(batchId).orElseThrow();
        audit(actor, "STOCK_RECEIVE", "DRUG", drugId.toString(), Map.of(
                "drugCode", drug.drugCode(), "batchNo", batch.batchNo(),
                "expireDate", batch.expireDate().toString(), "quantity", batch.quantity(),
                "totalStock", repo.totalStock(drugId)));
        return batch;
    }

    public List<DrugBatch> batches(UUID drugId) {
        repo.findDrug(drugId).orElseThrow(() -> new BusinessException(ErrorCode.DRUG_NOT_FOUND));
        return repo.listBatchesByDrug(drugId);
    }

    /**
     * 近效期与已过期批次预警（只列有余量的批次）。
     *
     * <p>已过期批次仍需人工处理（退货/销毁），所以一并列出并用 {@code expired} 区分。
     */
    public List<StockWarning> warnings() {
        LocalDate today = BusinessTime.today();
        LocalDate threshold = today.plusDays(props.nearExpiryDaysOrDefault());
        return repo.listExpiringBefore(threshold).stream()
                .map(b -> new StockWarning(b.drugCode(), b.drugName(), b.batchNo(),
                        b.expireDate(), b.quantity(), b.expiredOn(today)))
                .toList();
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
