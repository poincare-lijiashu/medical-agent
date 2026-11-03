package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.material.config.MaterialProperties;
import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialDtos.ReceiveBatchReq;
import com.medassist.material.web.MaterialDtos.StockWarning;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 耗材批次库存（效期维度）。
 *
 * <p>与药品库存同口径：同耗材同批号唯一、过期不得入库、预警按业务日计算。
 * 差异点：一般耗材允许没有效期，高值/植入类必须有——这由 {@link Consumable#expiryRequired()} 决定。
 */
@Service
public class ConsumableStockService {

    private final MaterialRepository repo;
    private final MaterialProperties props;
    private final ObjectMapper objectMapper;

    public ConsumableStockService(MaterialRepository repo, MaterialProperties props,
                                  ObjectMapper objectMapper) {
        this.repo = repo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConsumableBatch receive(UUID consumableId, ReceiveBatchReq req, String actor) {
        Consumable consumable = repo.findConsumable(consumableId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_NOT_FOUND));
        if (!consumable.enabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "耗材已停用，不能入库：" + consumable.name());
        }
        LocalDate today = BusinessTime.today();
        if (consumable.expiryRequired() && req.expireDate() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "高值/植入类耗材必须录入有效期：" + consumable.name());
        }
        if (req.expireDate() != null && req.expireDate().isBefore(today)) {
            throw new BusinessException(ErrorCode.CONSUMABLE_BATCH_EXPIRED,
                    "不能入库已过期耗材（效期 " + req.expireDate() + "，业务日 " + today + "）");
        }
        String batchNo = req.batchNo().trim();
        UUID batchId = UUID.randomUUID();
        BigDecimal unitPrice = req.unitPrice() == null ? consumable.price() : req.unitPrice();
        try {
            repo.insertBatch(batchId, consumableId, batchNo, req.expireDate(), req.quantity(),
                    blankToNull(req.supplier()), unitPrice);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该耗材已存在批号 " + batchNo + "，请核对批号或做补录");
        }
        ConsumableBatch batch = repo.findBatch(batchId).orElseThrow();
        audit(actor, "CONSUMABLE_RECEIVE", consumableId.toString(), detail()
                .put("consumableCode", consumable.code())
                .put("batchNo", batch.batchNo())
                .put("expireDate", batch.expireDate() == null ? null : batch.expireDate().toString())
                .put("quantity", batch.quantity())
                .put("unitPrice", batch.unitPrice().toPlainString())
                .put("totalStock", repo.totalStock(consumableId))
                .build());
        return batch;
    }

    public List<ConsumableBatch> batches(UUID consumableId) {
        repo.findConsumable(consumableId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_NOT_FOUND));
        return repo.listBatchesByConsumable(consumableId);
    }

    /** 近效期与已过期批次预警（只列有效期的余量批次）。 */
    public List<StockWarning> warnings() {
        LocalDate today = BusinessTime.today();
        LocalDate threshold = today.plusDays(props.nearExpiryDaysOrDefault());
        return repo.listExpiringBefore(threshold).stream()
                .map(b -> new StockWarning(b.consumableCode(), b.consumableName(), b.batchNo(),
                        b.expireDate(), b.quantity(), b.expiredOn(today)))
                .toList();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private Detail detail() {
        return new Detail();
    }

    private final class Detail {
        private final Map<String, Object> map = new LinkedHashMap<>();

        Detail put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        String build() {
            try {
                return objectMapper.writeValueAsString(map);
            } catch (JacksonException e) {
                return "{\"_detail_serialize_failed\":true}";
            }
        }
    }

    private void audit(String actor, String action, String targetId, String detailJson) {
        repo.logChange(actor, action, "CONSUMABLE_STOCK", targetId, detailJson);
    }
}
