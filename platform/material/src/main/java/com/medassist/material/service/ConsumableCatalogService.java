package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.time.BusinessTime;
import com.medassist.material.config.MaterialProperties;
import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialDtos.ConsumableView;
import com.medassist.material.web.MaterialDtos.CreateConsumableReq;
import com.medassist.material.web.MaterialDtos.MaterialSummary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 耗材目录（物资科主数据）。
 *
 * <p>三条准入规则直接对应飞检项：
 * <ol>
 *   <li>高值/植入类必须登记医疗器械注册证号，否则不允许建档；</li>
 *   <li>编码唯一（并发由数据库唯一键兜底）；</li>
 *   <li>调价与启停都带版本号，避免并发覆盖。</li>
 * </ol>
 */
@Service
public class ConsumableCatalogService {

    private static final List<String> CATEGORIES = List.of(Consumable.GENERAL,
            Consumable.HIGH_VALUE, Consumable.IMPLANT);

    private final MaterialRepository repo;
    private final MaterialProperties props;
    private final ObjectMapper objectMapper;

    public ConsumableCatalogService(MaterialRepository repo, MaterialProperties props,
                                    ObjectMapper objectMapper) {
        this.repo = repo;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ConsumableView create(CreateConsumableReq req, String actor) {
        String code = req.code().trim();
        if (!CATEGORIES.contains(req.category())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "耗材类别不合法");
        }
        if (repo.findConsumableByCode(code).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "耗材编码已存在：" + code);
        }
        boolean traceRequired = !Consumable.GENERAL.equals(req.category());
        String registrationNo = blankToNull(req.registrationNo());
        if (traceRequired && registrationNo == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "高值/植入类耗材必须登记医疗器械注册证号");
        }
        Consumable consumable = new Consumable(UUID.randomUUID(), code, req.name().trim(),
                req.spec().trim(), req.unit().trim(), req.category(), req.price(),
                blankToNull(req.manufacturer()), registrationNo, true, 0L, Instant.now());
        try {
            repo.insertConsumable(consumable);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "耗材编码已存在：" + code);
        }
        audit(actor, "CONSUMABLE_CREATE", consumable.id().toString(), detail()
                .put("code", consumable.code())
                .put("name", consumable.name())
                .put("category", consumable.category())
                .put("price", consumable.price().toPlainString())
                .put("registrationNo", registrationNo)
                .build());
        return new ConsumableView(consumable, 0);
    }

    public List<ConsumableView> list(String keyword, String category, Boolean enabled,
                                     Integer limit) {
        int size = limit == null || limit <= 0 ? 100 : Math.min(limit, 200);
        List<Consumable> rows = repo.listConsumables(keyword, category, enabled, size);
        Map<UUID, Integer> stocks = repo.totalStocks(rows.stream().map(Consumable::id).toList());
        return rows.stream()
                .map(c -> new ConsumableView(c, stocks.getOrDefault(c.id(), 0)))
                .toList();
    }

    public ConsumableView detail(UUID id) {
        Consumable consumable = repo.findConsumable(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_NOT_FOUND));
        return new ConsumableView(consumable, repo.totalStock(id));
    }

    @Transactional
    public ConsumableView adjustPrice(UUID id, BigDecimal price, long version, String actor) {
        Consumable consumable = repo.findConsumable(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_NOT_FOUND));
        if (!repo.updatePrice(id, price, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "耗材价格已被他人修改，请刷新后重试");
        }
        Consumable updated = repo.findConsumable(id).orElseThrow();
        audit(actor, "CONSUMABLE_PRICE", id.toString(), detail()
                .put("from", consumable.price().toPlainString())
                .put("to", price.toPlainString())
                .put("fromVersion", consumable.version())
                .put("toVersion", updated.version())
                .build());
        return new ConsumableView(updated, repo.totalStock(id));
    }

    @Transactional
    public ConsumableView toggle(UUID id, boolean enabled, long version, String actor) {
        Consumable consumable = repo.findConsumable(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSUMABLE_NOT_FOUND));
        // 幂等：已是目标状态直接返回，双击不报"已被他人修改"
        if (consumable.enabled() == enabled) {
            return new ConsumableView(consumable, repo.totalStock(id));
        }
        if (!repo.setEnabled(id, enabled, version)) {
            throw new BusinessException(ErrorCode.CONFLICT, "耗材状态已被他人修改，请刷新后重试");
        }
        Consumable updated = repo.findConsumable(id).orElseThrow();
        audit(actor, "CONSUMABLE_ENABLED", id.toString(), detail()
                .put("enabled", enabled)
                .put("fromVersion", consumable.version())
                .put("toVersion", updated.version())
                .build());
        return new ConsumableView(updated, repo.totalStock(id));
    }

    /** 看板：目录数、库存件数/金额、近效期与过期批次、当日出库与追溯量。 */
    public MaterialSummary summary() {
        LocalDate today = BusinessTime.today();
        LocalDate threshold = today.plusDays(props.nearExpiryDaysOrDefault());
        var expiring = repo.listExpiringBefore(threshold);
        long expired = expiring.stream().filter(b -> b.expiredOn(today)).count();
        long near = expiring.size() - expired;
        Instant dayStart = today.atStartOfDay(BusinessTime.zone()).toInstant();
        return new MaterialSummary(repo.countConsumables(true), repo.totalStockAll(),
                repo.stockValue(), near, expired, repo.countIssuesSince(dayStart),
                repo.countTracesSince(dayStart));
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
        repo.logChange(actor, action, "CONSUMABLE", targetId, detailJson);
    }
}
