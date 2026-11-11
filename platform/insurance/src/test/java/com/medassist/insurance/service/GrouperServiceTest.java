package com.medassist.insurance.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.client.ClinicalClient;
import com.medassist.insurance.client.EmrClient;
import com.medassist.insurance.config.GrouperProperties;
import com.medassist.insurance.domain.InsuranceModel.GrouperAdrgRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperCodeCount;
import com.medassist.insurance.domain.InsuranceModel.GrouperDrgDef;
import com.medassist.insurance.domain.InsuranceModel.GrouperEngineStat;
import com.medassist.insurance.domain.InsuranceModel.GrouperMdcRule;
import com.medassist.insurance.domain.InsuranceModel.GrouperReasonCount;
import com.medassist.insurance.domain.InsuranceModel.GrouperResult;
import com.medassist.insurance.domain.InsuranceModel.GrouperRuleSet;
import com.medassist.insurance.domain.InsuranceModel.PaymentScheme;
import com.medassist.insurance.grouper.GrouperInput;
import com.medassist.insurance.grouper.RemoteGrouperClient;
import com.medassist.insurance.repo.InsuranceRepository;
import com.medassist.insurance.web.GrouperDtos.AdrgRuleReq;
import com.medassist.insurance.web.GrouperDtos.DrgDefReq;
import com.medassist.insurance.web.GrouperDtos.ImportRuleSetReq;
import com.medassist.insurance.web.GrouperDtos.MdcRuleReq;
import com.medassist.insurance.web.GrouperDtos.OfficialReq;
import com.medassist.insurance.web.GrouperDtos.PreviewReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * 分组服务：规则包导入校验、本地预分组、远端失败语义、正式结果差异分析。
 */
class GrouperServiceTest {

    private InsuranceRepository repo;
    private ClinicalClient clinicalClient;
    private EmrClient emrClient;
    private RemoteGrouperClient remoteClient;
    private GrouperService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID visitId = UUID.randomUUID();
    private final UUID setId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(InsuranceRepository.class);
        clinicalClient = mock(ClinicalClient.class);
        emrClient = mock(EmrClient.class);
        remoteClient = mock(RemoteGrouperClient.class);
        service = service("LOCAL", false);
    }

    private GrouperService service(String engine, boolean fallback) {
        return new GrouperService(repo, clinicalClient, emrClient, remoteClient,
                new GrouperProperties(engine, "http://127.0.0.1:9/grouper", "", Duration.ofSeconds(1),
                        fallback), objectMapper);
    }

    private GrouperRuleSet activeSet() {
        return new GrouperRuleSet(setId, "TEST-DRG", "1.0", "测试规则包", "DRG",
                LocalDate.of(2026, 1, 1), "LOCAL", "checksum", GrouperRuleSet.STATUS_ACTIVE,
                "yb01", Instant.now(), "yb01", Instant.now());
    }

    private void stubBundle() {
        when(repo.listMdcRules(setId)).thenReturn(List.of(
                new GrouperMdcRule(UUID.randomUUID(), setId, 1, "MDCB", "呼吸系统",
                        "J00", "J99", true)));
        when(repo.listAdrgRules(setId)).thenReturn(List.of(
                new GrouperAdrgRule(UUID.randomUUID(), setId, 1, "ADRG-B1", "肺手术", "MDCB",
                        "PROCEDURE", null, "32", true)));
        when(repo.listDrgDefs(setId)).thenReturn(List.of(
                new GrouperDrgDef(setId, "DRG-B1A", "肺手术无并发症", "ADRG-B1", "NONE",
                        new BigDecimal("1.5"))));
        when(repo.listCcRules(setId)).thenReturn(List.of());
        when(repo.listCcExclusions(setId)).thenReturn(List.of());
        when(repo.listDipCatalog(setId)).thenReturn(List.of());
    }

    private ImportRuleSetReq importReq(boolean activate, List<AdrgRuleReq> adrg,
                                       List<DrgDefReq> defs) {
        return new ImportRuleSetReq("TEST-DRG", "1.0", "测试规则包", "DRG",
                LocalDate.of(2026, 1, 1), "LOCAL", activate,
                List.of(new MdcRuleReq(1, "MDCB", "呼吸系统", "J00", "J99", true)),
                adrg, defs, List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("导入：整包落库，返回各层规则计数")
    void importSuccess() {
        var adrg = List.of(new AdrgRuleReq(1, "ADRG-B1", "肺手术", "MDCB", "PROCEDURE",
                null, "32", true));
        var defs = List.of(new DrgDefReq("DRG-B1A", "无并发症", "ADRG-B1", "NONE",
                new BigDecimal("1.5")));

        var result = service.importRuleSet(importReq(false, adrg, defs), "yb01");

        assertThat(result.status()).isEqualTo(GrouperRuleSet.STATUS_DRAFT);
        assertThat(result.mdcRules()).isEqualTo(1);
        assertThat(result.adrgRules()).isEqualTo(1);
        assertThat(result.drgDefs()).isEqualTo(1);
        verify(repo).insertRuleSet(any());
        verify(repo).insertAdrgRules(any());
        verify(repo).insertDrgDefs(any());
        verify(repo, never()).activateRuleSet(any(), anyString());
    }

    @Test
    @DisplayName("导入：ADRG 引用不存在的 MDC 整包拒绝")
    void importRejectsUnknownMdc() {
        var adrg = List.of(new AdrgRuleReq(1, "ADRG-X", "错误", "MDCX", "PROCEDURE",
                null, "32", true));

        assertThatThrownBy(() -> service.importRuleSet(importReq(false, adrg, List.of()), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_GROUPER_RULE_INVALID);
        verify(repo, never()).insertRuleSet(any());
    }

    @Test
    @DisplayName("导入：激活 DRG 规则包但缺少细分组，拒绝激活")
    void importRequiresDrgDefsWhenActivating() {
        var adrg = List.of(new AdrgRuleReq(1, "ADRG-B1", "肺手术", "MDCB", "PROCEDURE",
                null, "32", true));

        assertThatThrownBy(() -> service.importRuleSet(importReq(true, adrg, List.of()), "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_GROUPER_RULE_INVALID);
    }

    @Test
    @DisplayName("导入：同 code 同版本重复导入 409")
    void importDuplicateVersion() {
        when(repo.findRuleSet("TEST-DRG", "1.0")).thenReturn(Optional.of(activeSet()));

        assertThatThrownBy(() -> service.importRuleSet(importReq(false, List.of(), List.of()),
                "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("预分组：本地引擎落库（含规则包版本与轨迹）")
    void previewLocalPersistsResult() {
        when(repo.findActiveRuleSet("DRG", null)).thenReturn(Optional.of(activeSet()));
        stubBundle();
        var req = new PreviewReq(visitId, "DRG", null, null, null,
                List.of("J44.900"), List.of("32.2100"), null, null);

        GrouperResult result = service.preview(req, "doctor01");

        assertThat(result.engine()).isEqualTo(GrouperResult.ENGINE_LOCAL);
        assertThat(result.ruleSetVersion()).isEqualTo("1.0");
        assertThat(result.drgCode()).isEqualTo("DRG-B1A");
        assertThat(result.inputHash()).isNotBlank();
        assertThat(result.trace()).contains("ADRG-B1");
        verify(repo).insertGrouperResult(result);
    }

    @Test
    @DisplayName("预分组：没有生效规则包时明确失败")
    void previewWithoutActiveRuleSet() {
        when(repo.findActiveRuleSet("DRG", null)).thenReturn(Optional.empty());
        var req = new PreviewReq(visitId, "DRG", null, null, "J44.900",
                List.of(), List.of(), null, null);

        assertThatThrownBy(() -> service.preview(req, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_GROUPER_RULE_SET_NOT_FOUND);
    }

    @Test
    @DisplayName("远端失败：未开启回退时抛出，不悄悄改本地口径")
    void remoteFailureWithoutFallback() {
        GrouperService remote = service("REMOTE", false);
        when(repo.findActiveRuleSet("DRG", null)).thenReturn(Optional.of(activeSet()));
        when(remoteClient.group(any(), any())).thenThrow(new BusinessException(
                ErrorCode.INSURANCE_GROUPER_UNAVAILABLE, "down"));
        var req = new PreviewReq(visitId, "DRG", null, null, "J44.900",
                List.of(), List.of("32.2100"), null, null);

        assertThatThrownBy(() -> remote.preview(req, "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_GROUPER_UNAVAILABLE);
        verify(repo, never()).insertGrouperResult(any());
    }

    @Test
    @DisplayName("远端失败：显式开启回退时走本地，结果标记 LOCAL")
    void remoteFailureWithFallback() {
        GrouperService remote = service("REMOTE", true);
        when(repo.findActiveRuleSet("DRG", null)).thenReturn(Optional.of(activeSet()));
        stubBundle();
        when(remoteClient.group(any(), any())).thenThrow(new BusinessException(
                ErrorCode.INSURANCE_GROUPER_UNAVAILABLE, "down"));
        var req = new PreviewReq(visitId, "DRG", null, null, "J44.900",
                List.of(), List.of("32.2100"), null, null);

        GrouperResult result = remote.preview(req, "doctor01");

        assertThat(result.engine()).isEqualTo(GrouperResult.ENGINE_LOCAL);
        assertThat(result.drgCode()).isEqualTo("DRG-B1A");
    }

    @Test
    @DisplayName("差异分析：字段不一致时逐项列出（DRG/权重/支付标准）")
    void varianceListsDifferences() {
        GrouperResult pre = result(GrouperResult.ENGINE_LOCAL, "DRG-B1A", "1.5000", "150.00");
        GrouperResult official = result(GrouperResult.ENGINE_OFFICIAL, "DRG-B1C", "3.0000",
                "300.00");
        when(repo.findLatestGrouperResult(visitId, GrouperResult.ENGINE_LOCAL))
                .thenReturn(Optional.of(pre));
        when(repo.findLatestGrouperResult(visitId, GrouperResult.ENGINE_OFFICIAL))
                .thenReturn(Optional.of(official));

        var view = service.variance(visitId, "qc01");

        assertThat(view.consistent()).isFalse();
        assertThat(view.differences()).anyMatch(item -> item.startsWith("drgCode"))
                .anyMatch(item -> item.startsWith("weight"))
                .anyMatch(item -> item.startsWith("paymentStandard"));
    }

    @Test
    @DisplayName("正式结果登记：既无 DRG 也无 DIP 编码时拒绝")
    void officialRequiresCode() {
        var req = new OfficialReq(visitId, "DRG", null, null, null, null, null, null, null,
                null, null, null, null, "J44.900", List.of(), List.of());

        assertThatThrownBy(() -> service.recordOfficial(req, "yb01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INSURANCE_GROUPER_RULE_INVALID);
    }

    @Test
    @DisplayName("报表：days/top 收敛到上限，返回三类统计")
    void summaryClampsWindow() {
        when(repo.grouperEngineStats(any(), any())).thenReturn(List.of(
                new GrouperEngineStat("LOCAL", "DRG", 10, 9, 8)));
        when(repo.grouperTopGroups(any(), any(), anyInt())).thenReturn(List.of(
                new GrouperCodeCount("DRG", "DRG-B1A", 5)));
        when(repo.grouperTopReasons(any(), any(), anyInt())).thenReturn(List.of(
                new GrouperReasonCount("未命中任何入组规则", 1)));

        var view = service.summary(9999, 999, "yb01");

        assertThat(view.engines()).hasSize(1);
        assertThat(view.topGroups().get(0).code()).isEqualTo("DRG-B1A");
        assertThat(view.topReasons()).hasSize(1);
        verify(repo).grouperTopGroups(any(), any(), eq(50));
    }

    private GrouperResult result(String engine, String drgCode, String weight, String payment) {
        return new GrouperResult(UUID.randomUUID(), visitId, setId, "TEST-DRG", "1.0", engine,
                PaymentScheme.DRG, "MDCB", "ADRG-B1", drgCode, null, "NONE",
                new BigDecimal(weight), null, new BigDecimal(payment), true, "命中", "[]",
                "J44.900", "[]", "[\"32.2100\"]", "hash", "yb01", Instant.now());
    }
}
