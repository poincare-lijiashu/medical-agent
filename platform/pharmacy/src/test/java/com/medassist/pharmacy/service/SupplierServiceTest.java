package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.Supplier;
import com.medassist.pharmacy.repo.PurchaseRepository;
import com.medassist.pharmacy.web.PurchaseDtos.CreateSupplierReq;
import com.medassist.pharmacy.web.PurchaseDtos.SupplierView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商台账：编码唯一、启停幂等、版本号冲突提示。
 */
class SupplierServiceTest {

    private PurchaseRepository repo;
    private SupplierService service;

    private final UUID id = UUID.randomUUID();
    private final Supplier supplier = new Supplier(id, "SUP-01", "国药控股", null, null,
            "LIC-001", LocalDate.now().plusDays(30), Supplier.ACTIVE, null, 2L, Instant.now());

    @BeforeEach
    void setUp() {
        repo = mock(PurchaseRepository.class);
        service = new SupplierService(repo, JsonMapper.builder().build());
    }

    @Test
    @DisplayName("新建：编码已存在 → 409（预检查，避免前端看到数据库唯一键报错）")
    void createRejectsDuplicateCode() {
        when(repo.findSupplierByCode("SUP-01")).thenReturn(Optional.of(supplier));

        assertThatThrownBy(() -> service.create(new CreateSupplierReq("SUP-01", "国药控股", null,
                null, "LIC-001", LocalDate.now().plusDays(30), null), "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).insertSupplier(any());
    }

    @Test
    @DisplayName("新建：默认 ACTIVE，证照有效期原样落库")
    void createStoresSupplier() {
        when(repo.findSupplierByCode("SUP-02")).thenReturn(Optional.empty());

        SupplierView view = service.create(new CreateSupplierReq("SUP-02", "上药控股", null, null,
                "LIC-002", LocalDate.now().plusDays(100), null), "pharm01");

        assertThat(view.supplier().status()).isEqualTo(Supplier.ACTIVE);
        assertThat(view.licenseExpired()).isFalse();
        verify(repo).insertSupplier(any());
    }

    @Test
    @DisplayName("启停：已是目标状态时直接返回，不涨版本号（双击不报错）")
    void updateStatusIsIdempotent() {
        when(repo.findSupplier(id)).thenReturn(Optional.of(supplier));

        SupplierView view = service.updateStatus(id, Supplier.ACTIVE, 2L, "pharm01");

        assertThat(view.supplier().version()).isEqualTo(2L);
        verify(repo, never()).updateSupplierStatus(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("启停：版本号过期 → 409，提示刷新后重试")
    void updateStatusRejectsStaleVersion() {
        when(repo.findSupplier(id)).thenReturn(Optional.of(supplier));
        when(repo.updateSupplierStatus(id, Supplier.SUSPENDED, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.updateStatus(id, Supplier.SUSPENDED, 1L, "pharm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("列表：证照过期由服务端标记，前端不再自行比较日期")
    void listMarksExpiredLicense() {
        Supplier expired = new Supplier(UUID.randomUUID(), "SUP-03", "某药业", null, null,
                "LIC-003", LocalDate.now().minusDays(1), Supplier.ACTIVE, null, 0L, Instant.now());
        when(repo.listSuppliers(null, null, 100)).thenReturn(java.util.List.of(supplier, expired));

        var views = service.list(null, null, null);

        assertThat(views).hasSize(2);
        assertThat(views.get(0).licenseExpired()).isFalse();
        assertThat(views.get(1).licenseExpired()).isTrue();
    }
}
