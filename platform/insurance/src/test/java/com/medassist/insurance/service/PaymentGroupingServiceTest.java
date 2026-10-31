package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.domain.InsuranceModel.GroupSimulation;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroup;
import com.medassist.insurance.domain.InsuranceModel.PaymentGroupRule;
import com.medassist.insurance.domain.InsuranceModel.PaymentScheme;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.InsuranceDtos.CreateSchemeReq;
import com.medassist.insurance.web.InsuranceDtos.GroupImportResult;
import com.medassist.insurance.web.InsuranceDtos.GroupReq;
import com.medassist.insurance.web.InsuranceDtos.RuleImportResult;
import com.medassist.insurance.web.InsuranceDtos.RuleReq;
import com.medassist.insurance.web.InsuranceDtos.SimulateReq;
import com.medassist.insurance.web.InsuranceDtos.UpdateSchemeReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DRG/DIP 分组：方案 CAS、导入幂等、规则优先级与模拟预分组口径。
 */
class PaymentGroupingServiceTest {

    private InsuranceRepository repo;
    private PaymentGroupingService service;

    private final UUID visitId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(InsuranceRepository.class);
        service = new PaymentGroupingService(repo, JsonMapper.builder().build());
    }

    private PaymentScheme scheme(boolean enabled) {
        return new PaymentScheme("DRG-2026", "2026 年 DRG", PaymentScheme.DRG,
                new BigDecimal("100.0000"), LocalDate.of(2026, 1, 1), enabled, 0L,
                "yb01", Instant.now());
    }

    private PaymentGroup group(String code, String weight, boolean enabled) {
        return new PaymentGroup("DRG-2026", code, "分组 " + code,
                new BigDecimal(weight), enabled, 0L, Instant.now());
    }

    private PaymentGroupRule rule(int priority, String type, String diag, String proc,
                                  String groupCode) {
        return new PaymentGroupRule(UUID.randomUUID(), "DRG-2026", priority, type, diag, proc,
                groupCode, true, Instant.now());
    }

    private SimulateReq simulateReq(String... diagnoses) {
        return new SimulateReq(visitId, "DRG-2026", List.of(diagnoses), List.of());
    }

    // ---------------------------------------------------------------- 方案

    @Test
    @DisplayName("新建方案：编码已存在则 409")
    void createSchemeDuplicateRejected() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));

        assertThatThrownBy(() -> service.createScheme(new CreateSchemeReq("DRG-2026", "n",
                "DRG", new BigDecimal("100"), LocalDate.now(), true), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).insertScheme(any());
    }

    @Test
    @DisplayName("修改方案：版本过期 409，且不写审计")
    void updateSchemeCasConflict() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.updateScheme("DRG-2026", "新名称", new BigDecimal("120.0000"),
                LocalDate.now(), true, 3L, "yb01")).thenReturn(false);

        assertThatThrownBy(() -> service.updateScheme("DRG-2026",
                new UpdateSchemeReq("新名称", new BigDecimal("120"), LocalDate.now(), true, 3L),
                "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).logChange(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 导入

    @Test
    @DisplayName("导入分组：内容未变不写库（幂等），仅统计 unchanged")
    void importGroupsSkipsUnchanged() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.findGroup("DRG-2026", "GB1")).thenReturn(Optional.of(group("GB1", "1.5000", true)));

        GroupImportResult result = service.importGroups("DRG-2026",
                List.of(new GroupReq("GB1", "分组 GB1", new BigDecimal("1.5"), true)), "yb01");

        assertThat(result.created()).isZero();
        assertThat(result.updated()).isZero();
        assertThat(result.unchanged()).isEqualTo(1);
        verify(repo, never()).upsertGroup(any());
        verify(repo).logChange(eq("yb01"), eq("GROUP_IMPORT"), eq("PAYMENT_GROUPING"),
                eq("DRG-2026"), anyString());
    }

    @Test
    @DisplayName("导入分组：请求内编码重复 422")
    void importGroupsRejectsDuplicateCodes() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));

        assertThatThrownBy(() -> service.importGroups("DRG-2026", List.of(
                new GroupReq("GB1", "A", BigDecimal.ONE, true),
                new GroupReq("GB1", "B", BigDecimal.ONE, true)), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).upsertGroup(any());
    }

    @Test
    @DisplayName("导入规则：优先级重复 422")
    void importRulesRejectsDuplicatePriority() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));

        assertThatThrownBy(() -> service.importRules("DRG-2026", List.of(
                new RuleReq(1, "DIAGNOSIS", "J18", null, "GB1", true),
                new RuleReq(1, "DIAGNOSIS", "J19", null, "GB1", true)), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("导入规则：分组不存在/停用 422")
    void importRulesRejectsMissingGroup() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.findGroup("DRG-2026", "GB9")).thenReturn(Optional.of(group("GB9", "1.0", false)));

        assertThatThrownBy(() -> service.importRules("DRG-2026",
                List.of(new RuleReq(1, "DIAGNOSIS", "J18", null, "GB9", true)), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("导入规则：DIAGNOSIS 带手术前缀属于配置错误 422")
    void importRulesRejectsShapeMismatch() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));

        assertThatThrownBy(() -> service.importRules("DRG-2026",
                List.of(new RuleReq(1, "DIAGNOSIS", "J18", "31.1", "GB1", true)), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------------------------------------------------------------- 模拟

    @Test
    @DisplayName("模拟：命中诊断规则，支付标准 = 权重 × 费率（2 位 HALF_UP）")
    void simulateMatchesDiagnosisRule() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.listRules("DRG-2026", true))
                .thenReturn(List.of(rule(1, "DIAGNOSIS", "J18", null, "GB1")));
        when(repo.findGroup("DRG-2026", "GB1"))
                .thenReturn(Optional.of(group("GB1", "1.2345", true)));

        GroupSimulation result = service.simulate(simulateReq("j18.900"), "yb01");

        assertThat(result.matched()).isTrue();
        assertThat(result.groupCode()).isEqualTo("GB1");
        assertThat(result.paymentStandard()).isEqualByComparingTo("123.45");
        assertThat(result.inputHash()).hasSize(64);
        verify(repo).insertSimulation(any());
        verify(repo).logChange(eq("yb01"), eq("GROUP_SIMULATE"), eq("PAYMENT_GROUPING"),
                eq(visitId.toString()), anyString());
    }

    @Test
    @DisplayName("模拟：优先级数字小的先命中")
    void simulateUsesLowestPriorityNumber() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.listRules("DRG-2026", true)).thenReturn(List.of(
                rule(1, "DIAGNOSIS", "J18", null, "GB1"),
                rule(2, "DIAGNOSIS", "J", null, "GB2")));
        when(repo.findGroup("DRG-2026", "GB1"))
                .thenReturn(Optional.of(group("GB1", "1.0", true)));
        when(repo.findGroup("DRG-2026", "GB2"))
                .thenReturn(Optional.of(group("GB2", "2.0", true)));

        GroupSimulation result = service.simulate(simulateReq("J18.900"), "yb01");

        assertThat(result.groupCode()).isEqualTo("GB1");
        assertThat(result.paymentStandard()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("模拟：DIAG_AND_PROC 必须同时满足")
    void simulateCombinedRuleNeedsBoth() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.listRules("DRG-2026", true))
                .thenReturn(List.of(rule(1, "DIAG_AND_PROC", "I50", "93.90", "GB3")));
        when(repo.findGroup("DRG-2026", "GB3"))
                .thenReturn(Optional.of(group("GB3", "3.0", true)));

        GroupSimulation withoutProc = service.simulate(
                new SimulateReq(visitId, "DRG-2026", List.of("I50.900"), List.of()), "yb01");
        GroupSimulation withProc = service.simulate(new SimulateReq(visitId, "DRG-2026",
                List.of("I50.900"), List.of("93.90")), "yb01");

        assertThat(withoutProc.matched()).isFalse();
        assertThat(withoutProc.reason()).contains("未命中");
        assertThat(withProc.matched()).isTrue();
        assertThat(withProc.groupCode()).isEqualTo("GB3");
    }

    @Test
    @DisplayName("模拟：未命中任何规则也落库并给出原因")
    void simulateUnmatchedStillPersists() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.listRules("DRG-2026", true))
                .thenReturn(List.of(rule(1, "DIAGNOSIS", "J18", null, "GB1")));

        GroupSimulation result = service.simulate(simulateReq("K35.900"), "yb01");

        assertThat(result.matched()).isFalse();
        assertThat(result.groupCode()).isNull();
        assertThat(result.paymentStandard()).isNull();
        assertThat(result.reason()).contains("未命中");
        verify(repo).insertSimulation(any());
    }

    @Test
    @DisplayName("模拟：命中的分组已停用 → 不回退到低优先级，显式给出原因")
    void simulateDisabledGroupDoesNotFallThrough() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.listRules("DRG-2026", true)).thenReturn(List.of(
                rule(1, "DIAGNOSIS", "J18", null, "GB1"),
                rule(2, "DIAGNOSIS", "J", null, "GB2")));
        when(repo.findGroup("DRG-2026", "GB1"))
                .thenReturn(Optional.of(group("GB1", "1.0", false)));
        when(repo.findGroup("DRG-2026", "GB2"))
                .thenReturn(Optional.of(group("GB2", "2.0", true)));

        GroupSimulation result = service.simulate(simulateReq("J18.900"), "yb01");

        assertThat(result.matched()).isFalse();
        assertThat(result.groupCode()).isNull();
        assertThat(result.reason()).contains("已停用").contains("GB1");
    }

    @Test
    @DisplayName("模拟：输入顺序/大小写不影响 input_hash")
    void simulateInputHashIsCanonical() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(true)));
        when(repo.listRules("DRG-2026", true)).thenReturn(List.of());

        GroupSimulation first = service.simulate(new SimulateReq(visitId, "DRG-2026",
                List.of("j18.900", "E11.900"), List.of("93.90")), "yb01");
        GroupSimulation second = service.simulate(new SimulateReq(visitId, "DRG-2026",
                List.of("E11.900 ", "J18.900"), List.of("93.90")), "yb01");

        assertThat(first.inputHash()).isEqualTo(second.inputHash());
    }

    @Test
    @DisplayName("模拟：方案停用 409")
    void simulateRequiresEnabledScheme() {
        when(repo.findScheme("DRG-2026")).thenReturn(Optional.of(scheme(false)));

        assertThatThrownBy(() -> service.simulate(simulateReq("J18.900"), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_SCHEME_INVALID);
    }

    @Test
    @DisplayName("查询最近模拟：无记录 404")
    void latestSimulationMissing() {
        when(repo.findLatestSimulation(visitId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.latestSimulation(visitId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_GROUP_NOT_FOUND);
    }
}
