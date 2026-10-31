package com.medassist.insurance.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.GroupSimulation;
import com.medassist.insurance.domain.InsuranceModel.PaymentScheme;
import com.medassist.insurance.service.PaymentGroupingService;
import com.medassist.insurance.web.InsuranceDtos.CreateSchemeReq;
import com.medassist.insurance.web.InsuranceDtos.ImportGroupsReq;
import com.medassist.insurance.web.InsuranceDtos.GroupReq;
import com.medassist.insurance.web.InsuranceDtos.SimulateReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DRG/DIP 分组权限：配置归医保办（grouping:manage），模拟测算对只读开放。
 */
class GroupingControllerPermissionTest {

    private PaymentGroupingService service;
    private GroupingController controller;

    private final UUID visitId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(PaymentGroupingService.class);
        controller = new GroupingController(service);
    }

    private PaymentScheme scheme() {
        return new PaymentScheme("DRG-2026", "2026 DRG", "DRG", new BigDecimal("100.0000"),
                LocalDate.of(2026, 1, 1), true, 0L, "yb01", Instant.now());
    }

    @Test
    @DisplayName("方案配置：只读权限不能建方案")
    void createSchemeRequiresManage() {
        var req = new CreateSchemeReq("DRG-2026", "2026 DRG", "DRG",
                new BigDecimal("100"), LocalDate.now(), true);

        assertThatThrownBy(() -> controller.createScheme(req, "insurance:settlement:read", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).createScheme(any(), anyString());
    }

    @Test
    @DisplayName("方案配置：grouping:manage 放行")
    void createSchemeAllowedForManage() {
        var req = new CreateSchemeReq("DRG-2026", "2026 DRG", "DRG",
                new BigDecimal("100"), LocalDate.now(), true);
        when(service.createScheme(any(), anyString())).thenReturn(scheme());

        var resp = controller.createScheme(req, "insurance:grouping:manage", "yb01");

        assertThat(resp.data().code()).isEqualTo("DRG-2026");
        verify(service).createScheme(req, "yb01");
    }

    @Test
    @DisplayName("分组导入：只读权限 403")
    void importGroupsRequiresManage() {
        var req = new ImportGroupsReq(List.of(
                new GroupReq("GB1", "分组", new BigDecimal("1.2"), true)));

        assertThatThrownBy(() -> controller.importGroups("DRG-2026", req,
                "insurance:settlement:read", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).importGroups(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("模拟测算：只读权限放行并透传操作人")
    void simulateAllowedForRead() {
        var req = new SimulateReq(visitId, "DRG-2026", List.of("J18.900"), List.of());
        when(service.simulate(any(), anyString())).thenReturn(new GroupSimulation(UUID.randomUUID(),
                visitId, "DRG-2026", "GB1", true, "命中", BigDecimal.ONE,
                new BigDecimal("100.0000"), new BigDecimal("100.00"), "[]", "[]", "hash",
                "doctor01", Instant.now()));

        var resp = controller.simulate(req, "insurance:settlement:read", "doctor01");

        assertThat(resp.data().matched()).isTrue();
        verify(service).simulate(req, "doctor01");
    }

    @Test
    @DisplayName("方案修改与规则导入：只读权限 403（补齐负例）")
    void updateSchemeAndImportRulesRequireManage() {
        var update = new InsuranceDtos.UpdateSchemeReq("n", new BigDecimal("1"),
                LocalDate.now(), true, 0L);
        var rules = new InsuranceDtos.ImportRulesReq(List.of(
                new InsuranceDtos.RuleReq(1, "DIAGNOSIS", "J18", null, "GB1", true)));

        assertThatThrownBy(() -> controller.updateScheme("DRG-2026", update,
                "insurance:settlement:read", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.importRules("DRG-2026", rules,
                "insurance:settlement:read", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).updateScheme(anyString(), any(), anyString());
        verify(service, never()).importRules(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("模拟测算：无权限头 403")
    void simulateRequiresPermission() {
        var req = new SimulateReq(visitId, "DRG-2026", List.of("J18.900"), List.of());

        assertThatThrownBy(() -> controller.simulate(req, null, "anonymous"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
