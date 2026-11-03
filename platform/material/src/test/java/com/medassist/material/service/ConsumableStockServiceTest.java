package com.medassist.material.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.material.config.MaterialProperties;
import com.medassist.material.domain.MaterialModel.Consumable;
import com.medassist.material.domain.MaterialModel.ConsumableBatch;
import com.medassist.material.repo.MaterialRepository;
import com.medassist.material.web.MaterialDtos.ReceiveBatchReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 耗材入库：高值/植入必须有有效期、过期拒收、同批号唯一。
 */
class ConsumableStockServiceTest {

    private MaterialRepository repo;
    private ConsumableStockService service;

    private final UUID id = UUID.randomUUID();
    private final Consumable implant = new Consumable(id, "CONS-STENT-001", "冠状动脉支架系统",
            "3.0×18mm", "套", Consumable.IMPLANT, new BigDecimal("4800.00"), "某介入器械公司",
            "国械注准20263130001", true, 0L, Instant.now());

    @BeforeEach
    void setUp() {
        repo = mock(MaterialRepository.class);
        service = new ConsumableStockService(repo, new MaterialProperties(90),
                JsonMapper.builder().build());
        when(repo.findConsumable(id)).thenReturn(Optional.of(implant));
    }

    @Test
    @DisplayName("入库：正常批次写入并留审计")
    void receiveStoresBatch() {
        LocalDate expire = LocalDate.now().plusDays(365);
        when(repo.findBatch(any())).thenAnswer(inv -> Optional.of(new ConsumableBatch(
                inv.getArgument(0), id, implant.code(), implant.name(), "B-1", expire, 5, "某供应商",
                new BigDecimal("4800.00"), 0L)));

        ConsumableBatch batch = service.receive(id,
                new ReceiveBatchReq("B-1", expire, 5, "某供应商", null), "mat01");

        assertThat(batch.batchNo()).isEqualTo("B-1");
        verify(repo).insertBatch(any(), eq(id), eq("B-1"), eq(expire), eq(5), eq("某供应商"),
                eq(new BigDecimal("4800.00")));
        verify(repo).logChange(eq("mat01"), eq("CONSUMABLE_RECEIVE"), eq("CONSUMABLE_STOCK"),
                eq(id.toString()), anyString());
    }

    @Test
    @DisplayName("入库：植入类缺有效期 → 422（无菌/植入器械硬性要求）")
    void receiveRejectsMissingExpiryForImplant() {
        assertThatThrownBy(() -> service.receive(id,
                new ReceiveBatchReq("B-1", null, 5, null, null), "mat01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertBatch(any(), any(), anyString(), any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("入库：已过期批次拒收")
    void receiveRejectsExpiredBatch() {
        assertThatThrownBy(() -> service.receive(id,
                new ReceiveBatchReq("B-OLD", LocalDate.now().minusDays(1), 5, null, null), "mat01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONSUMABLE_BATCH_EXPIRED);
    }

    @Test
    @DisplayName("入库：同耗材同批号重复 → 409")
    void receiveRejectsDuplicateBatchNo() {
        doThrow(new DataIntegrityViolationException("ux_consumable_batch"))
                .when(repo).insertBatch(any(), any(), anyString(), any(), anyInt(), any(), any());

        assertThatThrownBy(() -> service.receive(id,
                new ReceiveBatchReq("B-DUP", LocalDate.now().plusDays(30), 5, null, null), "mat01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("批号");
    }

    @Test
    @DisplayName("入库：停用耗材拒收")
    void receiveRejectsDisabled() {
        Consumable disabled = new Consumable(id, implant.code(), implant.name(), implant.spec(),
                implant.unit(), implant.category(), implant.price(), null, implant.registrationNo(),
                false, 1L, Instant.now());
        when(repo.findConsumable(id)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.receive(id,
                new ReceiveBatchReq("B-1", LocalDate.now().plusDays(30), 5, null, null), "mat01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
    }
}
