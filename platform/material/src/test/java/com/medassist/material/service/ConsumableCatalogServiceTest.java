package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.material.config.MaterialProperties;
import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialDtos.CreateConsumableReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 耗材目录准入：注册证、编码唯一、CAS 调价与启停幂等。
 */
class ConsumableCatalogServiceTest {

    private MaterialRepository repo;
    private ConsumableCatalogService service;

    private final UUID id = UUID.randomUUID();
    private final Consumable implant = new Consumable(id, "CONS-STENT-001", "冠状动脉支架系统",
            "3.0×18mm", "套", Consumable.IMPLANT, new BigDecimal("4800.00"), "某介入器械公司",
            "国械注准20263130001", true, 2L, Instant.now());

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        service = new ConsumableCatalogService(repo, new MaterialProperties(90),
                JsonMapper.builder().build());
    }

    @Test
    @DisplayName("建档：高值/植入类缺注册证号 → 422")
    void createRejectsMissingRegistration() {
        assertThatThrownBy(() -> service.create(new CreateConsumableReq("CONS-X", "支架", "3.0mm",
                "套", Consumable.IMPLANT, new BigDecimal("100.00"), null, null), "mat01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertConsumable(any());
    }

    @Test
    @DisplayName("建档：编码已存在 → 409")
    void createRejectsDuplicateCode() {
        when(repo.findConsumableByCode("CONS-STENT-001")).thenReturn(Optional.of(implant));

        assertThatThrownBy(() -> service.create(new CreateConsumableReq("CONS-STENT-001", "支架",
                "3.0mm", "套", Consumable.IMPLANT, new BigDecimal("100.00"), null,
                "国械注准20263130001"), "mat01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("建档：一般耗材允许无注册证")
    void createAllowsGeneralWithoutRegistration() {
        when(repo.findConsumableByCode("CONS-SYR-5ML")).thenReturn(Optional.empty());

        var view = service.create(new CreateConsumableReq("CONS-SYR-5ML", "一次性注射器", "5ml",
                "支", Consumable.GENERAL, new BigDecimal("0.80"), null, null), "mat01");

        assertThat(view.consumable().category()).isEqualTo(Consumable.GENERAL);
        verify(repo).insertConsumable(any());
    }

    @Test
    @DisplayName("调价：版本号过期 → 409")
    void adjustPriceRejectsStaleVersion() {
        when(repo.findConsumable(id)).thenReturn(Optional.of(implant));
        when(repo.updatePrice(id, new BigDecimal("5000.00"), 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.adjustPrice(id, new BigDecimal("5000.00"), 1L, "mat01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("启停：已是目标状态时直接返回，不发 UPDATE（双击不报错）")
    void toggleIsIdempotent() {
        when(repo.findConsumable(id)).thenReturn(Optional.of(implant));

        service.toggle(id, true, 2L, "mat01");

        verify(repo, never()).setEnabled(any(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("看板：过期与近效期分开计数，且当日出库/追溯按业务日归集")
    void summarySeparatesExpiredAndNearExpiry() {
        var expired = new com.medassist.material.domain.MaterialModel.ConsumableBatch(
                UUID.randomUUID(), id, implant.code(), implant.name(), "B-EXP",
                LocalDate.now().minusDays(2), 5, null, BigDecimal.ONE, 0L);
        var near = new com.medassist.material.domain.MaterialModel.ConsumableBatch(
                UUID.randomUUID(), id, implant.code(), implant.name(), "B-NEAR",
                LocalDate.now().plusDays(10), 5, null, BigDecimal.ONE, 0L);
        when(repo.listExpiringBefore(any(LocalDate.class))).thenReturn(java.util.List.of(expired, near));
        when(repo.countConsumables(true)).thenReturn(2L);
        when(repo.totalStockAll()).thenReturn(10);
        when(repo.stockValue()).thenReturn(new BigDecimal("100.00"));
        when(repo.countIssuesSince(any())).thenReturn(3L);
        when(repo.countTracesSince(any())).thenReturn(4L);

        var summary = service.summary();

        assertThat(summary.expiredBatches()).isEqualTo(1);
        assertThat(summary.nearExpiryBatches()).isEqualTo(1);
        assertThat(summary.consumableCount()).isEqualTo(2);
        assertThat(summary.issuesToday()).isEqualTo(3);
        assertThat(summary.tracesToday()).isEqualTo(4);
    }
}
