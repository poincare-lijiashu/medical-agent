package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.PharmacyDtos.CreateDrugReq;
import com.medassist.pharmacy.web.PharmacyDtos.DrugView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 药品字典：编码唯一、调价版本保护、停用后不可开方（后者由开方侧校验）。
 */
class DrugCatalogServiceTest {

    private PharmacyRepository repo;
    private DrugCatalogService service;

    private final UUID drugId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        service = new DrugCatalogService(repo, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("新建药品：写入并按分保存价格与医保类别")
    void createStoresDrug() {
        when(repo.findDrugByCode("DRUG-AMOX-025")).thenReturn(Optional.empty());

        DrugView view = service.create(new CreateDrugReq("DRUG-AMOX-025", "阿莫西林胶囊", "阿莫仙",
                "0.25g*24粒", "CAPSULE", "盒", "粒", 24, new BigDecimal("12.5"),
                "XA01AA0401010101010", "A", 1, false, null, null, null), "pharm01");

        assertThat(view.drug().price()).isEqualByComparingTo("12.50");
        assertThat(view.drug().insuranceCategory()).isEqualTo(Drug.INSURANCE_A);
        assertThat(view.drug().antibioticLevel()).isEqualTo(1);
        verify(repo).insertDrug(any(Drug.class));
        verify(repo).logChange(eq("pharm01"), eq("DRUG_CREATE"), eq("DRUG"),
                anyString(), anyString());
    }

    @Test
    @DisplayName("新建药品：默认医保类别为 NONE、抗菌分级 0")
    void createDefaults() {
        when(repo.findDrugByCode("DRUG-NS-500")).thenReturn(Optional.empty());

        DrugView view = service.create(new CreateDrugReq("DRUG-NS-500", "0.9%氯化钠注射液", null,
                "500ml", "INJECTION", "瓶", "瓶", 1, new BigDecimal("3.20"),
                null, null, null, null, null, null, null), "pharm01");

        assertThat(view.drug().insuranceCategory()).isEqualTo(Drug.INSURANCE_NONE);
        assertThat(view.drug().antibioticLevel()).isZero();
        assertThat(view.drug().narcotic()).isFalse();
    }

    @Test
    @DisplayName("新建药品：编码重复 → 409")
    void createRejectsDuplicateCode() {
        when(repo.findDrugByCode("DRUG-AMOX-025")).thenReturn(Optional.of(drug()));

        assertThatThrownBy(() -> service.create(new CreateDrugReq("DRUG-AMOX-025", "阿莫西林胶囊",
                null, "0.25g*24粒", "CAPSULE", "盒", null, null, new BigDecimal("12.50"),
                null, null, null, null, null, null, null), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).insertDrug(any());
    }

    @Test
    @DisplayName("调价：版本冲突 → 409，且不写审计")
    void adjustPriceVersionConflict() {
        when(repo.findDrug(drugId)).thenReturn(Optional.of(drug()));
        when(repo.updateDrugPrice(eq(drugId), any(), eq(3L))).thenReturn(false);

        assertThatThrownBy(() -> service.adjustPrice(drugId, new BigDecimal("13.00"), 3L, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).logChange(anyString(), eq("DRUG_PRICE"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("调价：成功时审计记录调价前后金额")
    void adjustPriceAuditsBeforeAfter() {
        when(repo.findDrug(drugId)).thenReturn(Optional.of(drug()));
        when(repo.updateDrugPrice(eq(drugId), eq(new BigDecimal("13.00")), eq(0L))).thenReturn(true);

        service.adjustPrice(drugId, new BigDecimal("13.00"), 0L, "pharm01");

        verify(repo).logChange(eq("pharm01"), eq("DRUG_PRICE"), eq("DRUG"),
                eq(drugId.toString()), anyString());
    }

    private Drug drug() {
        return new Drug(drugId, "DRUG-AMOX-025", "阿莫西林胶囊", "阿莫仙", "0.25g*24粒",
                "CAPSULE", "盒", "粒", 24, new BigDecimal("12.50"), null, "A", 1, false, true, 0L, null, false, false);
    }
}
