package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.config.PharmacyProperties;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.DrugBatch;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PharmacyDtos.ReceiveStockReq;
import com.medassist.pharmacy.web.PharmacyDtos.StockWarning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 库存规则：拒收过期药品、同批号唯一、近效期预警口径。
 */
class StockServiceTest {

    private PharmacyRepository repo;
    private StockService service;

    private final UUID drugId = UUID.randomUUID();
    private final Drug drug = new Drug(drugId, "DRUG-AMOX-025", "阿莫西林胶囊", null, "0.25g*24粒",
            "CAPSULE", "盒", "粒", 24, new BigDecimal("12.50"), null, "A", 1, false, true, 0L);

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        PharmacyProperties props = new PharmacyProperties(
                new PharmacyProperties.Empi("http://127.0.0.1:8082", "token", Duration.ofSeconds(2)),
                new PharmacyProperties.Clinical("http://127.0.0.1:8083", "token", Duration.ofSeconds(3)),
                90);
        service = new StockService(repo, props, JsonMapper.builder().build());
        when(repo.findDrug(drugId)).thenReturn(Optional.of(drug));
    }

    @Test
    @DisplayName("入库：正常批次写入并留审计")
    void receiveStoresBatch() {
        LocalDate expire = LocalDate.now().plusDays(365);
        when(repo.findBatch(any())).thenAnswer(inv -> Optional.of(new DrugBatch(
                inv.getArgument(0), drugId, drug.drugCode(), drug.name(), "B-2026", expire,
                100, "国药", 0L)));

        DrugBatch batch = service.receive(drugId, new ReceiveStockReq("B-2026", expire, 100, "国药"), "pharm01");

        assertThat(batch.batchNo()).isEqualTo("B-2026");
        assertThat(batch.quantity()).isEqualTo(100);
        verify(repo).insertBatch(any(), eq(drugId), eq("B-2026"), eq(expire), eq(100), eq("国药"));
        verify(repo).logChange(eq("pharm01"), eq("STOCK_RECEIVE"), eq("DRUG"),
                eq(drugId.toString()), anyString());
    }

    @Test
    @DisplayName("入库：已过期药品拒收（不允许把过期药入账）")
    void receiveRejectsExpired() {
        assertThatThrownBy(() -> service.receive(drugId,
                new ReceiveStockReq("B-OLD", LocalDate.now().minusDays(1), 10, null), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.DRUG_BATCH_EXPIRED);
        verify(repo, never()).insertBatch(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("入库：同药品同批号重复 → 409")
    void receiveRejectsDuplicateBatchNo() {
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("ux_drug_batch"))
                .when(repo).insertBatch(any(), any(), anyString(), any(), anyInt(), any());

        assertThatThrownBy(() -> service.receive(drugId,
                new ReceiveStockReq("B-DUP", LocalDate.now().plusDays(30), 5, null), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("批号");
    }

    @Test
    @DisplayName("入库：停用药品拒收")
    void receiveRejectsDisabledDrug() {
        Drug disabled = new Drug(drugId, drug.drugCode(), drug.name(), null, drug.spec(),
                drug.dosageForm(), drug.unit(), null, null, drug.price(), null, "A", 1, false, false, 0L);
        when(repo.findDrug(drugId)).thenReturn(Optional.of(disabled));

        assertThatThrownBy(() -> service.receive(drugId,
                new ReceiveStockReq("B-1", LocalDate.now().plusDays(30), 5, null), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
    }

    @Test
    @DisplayName("库存预警：区分已过期与近效期")
    void warningsMarkExpired() {
        DrugBatch expired = new DrugBatch(UUID.randomUUID(), drugId, drug.drugCode(), drug.name(),
                "B-EXP", LocalDate.now().minusDays(3), 8, null, 0L);
        DrugBatch near = new DrugBatch(UUID.randomUUID(), drugId, drug.drugCode(), drug.name(),
                "B-NEAR", LocalDate.now().plusDays(20), 12, null, 0L);
        when(repo.listExpiringBefore(any(LocalDate.class))).thenReturn(List.of(expired, near));

        List<StockWarning> warnings = service.warnings();

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0).expired()).isTrue();
        assertThat(warnings.get(1).expired()).isFalse();
    }
}
