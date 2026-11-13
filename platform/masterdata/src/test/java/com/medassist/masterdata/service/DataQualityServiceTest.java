package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.domain.DataQualityModel.Dashboard;
import com.medassist.masterdata.domain.DataQualityModel.Issue;
import com.medassist.masterdata.domain.DataQualityModel.Rule;
import com.medassist.masterdata.domain.DataQualityModel.Run;
import com.medassist.masterdata.repo.DataQualityRepository;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.web.DataQualityDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据质量：SQL 白名单、比较符、失败开单与重复失败累加（§299）。
 */
class DataQualityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T05:00:00Z");
    private static final UUID RULE_ID = UUID.randomUUID();

    private DataQualityRepository repo;
    private DataQualityService service;

    @BeforeEach
    void setUp() {
        repo = mock(DataQualityRepository.class);
        MasterDataRepository masterRepo = mock(MasterDataRepository.class);
        service = new DataQualityService(repo, masterRepo, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ SQL 白名单

    @Test
    @DisplayName("探针：拒绝 UPDATE/DDL 与多语句")
    void sqlGuardRejectsWrites() {
        for (String sql : new String[]{
                "UPDATE mdm_svc.md_item SET name = 'x'",
                "SELECT 1; DROP TABLE mdm_svc.md_item",
                "SELECT * FROM pg_catalog.pg_tables",
                "DELETE FROM mdm_svc.md_item",
                "SELECT 1 -- comment"}) {
            assertThatThrownBy(() -> DataQualityService.assertSafeSql(sql))
                    .as(sql)
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.MDM_DQ_SQL_UNSAFE);
        }
    }

    @Test
    @DisplayName("探针：允许 SELECT 与 WITH")
    void sqlGuardAllowsReadOnly() {
        DataQualityService.assertSafeSql("SELECT count(*) FROM mdm_svc.md_item");
        DataQualityService.assertSafeSql(
                "WITH x AS (SELECT 1 AS n) SELECT count(*) FROM x");
    }

    // ------------------------------------------------------------------ 执行

    @Test
    @DisplayName("执行：观测值满足阈值记 PASSED，不开问题")
    void runPassed() {
        stubRule("LTE", 5, null);
        when(repo.probe(any())).thenReturn(BigDecimal.valueOf(3));
        when(repo.nextRunNo()).thenReturn("DQR2026111300001");

        Run run = service.runRule(RULE_ID, "steward01", null, null);

        assertThat(run.status()).isEqualTo("PASSED");
        assertThat(run.passed()).isTrue();
        verify(repo).insertRun(any());
        verify(repo, never()).insertIssue(any());
    }

    @Test
    @DisplayName("执行：超阈值记 FAILED 并自动开问题单")
    void runFailedOpensIssue() {
        stubRule("LTE", 5, null);
        when(repo.probe(any())).thenReturn(BigDecimal.valueOf(10));
        when(repo.nextRunNo()).thenReturn("DQR2026111300002");
        when(repo.nextIssueNo()).thenReturn("DQI202611130001");
        when(repo.findOpenIssue(RULE_ID)).thenReturn(Optional.empty());

        service.runRule(RULE_ID, "steward01", null, null);

        ArgumentCaptor<Issue> captor = ArgumentCaptor.forClass(Issue.class);
        verify(repo).insertIssue(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("OPEN");
        assertThat(captor.getValue().severity()).isEqualTo("HIGH");
        assertThat(captor.getValue().occurrenceCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("执行：同一规则重复失败累加次数，不重复开单")
    void runFailedTouchesExistingIssue() {
        stubRule("LTE", 5, null);
        when(repo.probe(any())).thenReturn(BigDecimal.valueOf(12));
        when(repo.nextRunNo()).thenReturn("DQR2026111300003");
        Issue open = new Issue(UUID.randomUUID(), "DQI202611120001", RULE_ID, "DQ-METRIC",
                "指标未发布", "HIGH", "OPEN", "steward01", UUID.randomUUID(), UUID.randomUUID(),
                3, BigDecimal.TEN, null, null, null, null, null, null, null, 4, NOW, NOW);
        when(repo.findOpenIssue(RULE_ID)).thenReturn(Optional.of(open));
        when(repo.updateIssue(eq(open.id()), eq(4L), any())).thenReturn(true);

        service.runRule(RULE_ID, "steward01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repo).updateIssue(eq(open.id()), eq(4L), captor.capture());
        assertThat(captor.getValue().get("occurrence_count")).isEqualTo(4);
        verify(repo, never()).insertIssue(any());
    }

    @Test
    @DisplayName("执行：探针异常记 ERROR，不开业务问题")
    void runError() {
        stubRule("LTE", 5, null);
        when(repo.probe(any())).thenThrow(new IllegalStateException("relation does not exist"));
        when(repo.nextRunNo()).thenReturn("DQR2026111300004");

        Run run = service.runRule(RULE_ID, "steward01", null, null);

        assertThat(run.status()).isEqualTo("ERROR");
        assertThat(run.errorMessage()).contains("relation");
        verify(repo, never()).insertIssue(any());
    }

    @Test
    @DisplayName("执行：BETWEEN 在区间内才通过")
    void runBetween() {
        stubRule("BETWEEN", 2, 8);
        when(repo.probe(any())).thenReturn(BigDecimal.valueOf(5));
        when(repo.nextRunNo()).thenReturn("DQR2026111300005");

        Run run = service.runRule(RULE_ID, "steward01", null, null);

        assertThat(run.status()).isEqualTo("PASSED");
    }

    // ------------------------------------------------------------------ 问题闭环

    @Test
    @DisplayName("问题：解决必须写解决方案")
    void resolveRequiresResolution() {
        when(repo.findIssue(any())).thenReturn(Optional.of(issue("OPEN")));

        assertThatThrownBy(() -> service.resolve(UUID.randomUUID(),
                new DataQualityDtos.IssueResolveReq(" "), "steward01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MDM_DQ_ISSUE_RESOLUTION_REQUIRED);
    }

    @Test
    @DisplayName("问题：已解决/已豁免不能再确认")
    void acknowledgeInvalidStatus() {
        when(repo.findIssue(any())).thenReturn(Optional.of(issue("RESOLVED")));

        assertThatThrownBy(() -> service.acknowledge(UUID.randomUUID(), null,
                "steward01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MDM_DQ_ISSUE_STATUS_INVALID);
    }

    // ------------------------------------------------------------------ 看板

    @Test
    @DisplayName("看板：通过率按近 24 小时执行记录计算")
    void dashboardPassRate() {
        when(repo.countActiveRules()).thenReturn(4L);
        when(repo.countRunsSince(any(), isNull())).thenReturn(10L);
        when(repo.countRunsSince(any(), eq("PASSED"))).thenReturn(8L);
        when(repo.countRunsSince(any(), eq("FAILED"))).thenReturn(1L);
        when(repo.countRunsSince(any(), eq("ERROR"))).thenReturn(1L);
        when(repo.countOpenIssues()).thenReturn(2L);
        when(repo.countOpenBlockers()).thenReturn(1L);
        when(repo.topFailures(any(), anyInt())).thenReturn(java.util.List.of());

        Dashboard dashboard = service.dashboard();

        assertThat(dashboard.runs24h()).isEqualTo(10);
        assertThat(dashboard.passRate24h()).isEqualTo(80.0);
        assertThat(dashboard.openIssues()).isEqualTo(2);
        assertThat(dashboard.blockers()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 构造

    private void stubRule(String comparator, int threshold, Integer threshold2) {
        Rule rule = new Rule(RULE_ID, "DQ-METRIC", "指标未发布占比", "METRIC", "COMPLETENESS",
                "SELECT 0", comparator, BigDecimal.valueOf(threshold),
                threshold2 == null ? null : BigDecimal.valueOf(threshold2), "PERCENT", "HIGH",
                "steward01", "ACTIVE", 0, "system", NOW, NOW);
        when(repo.findRule(RULE_ID)).thenReturn(Optional.of(rule));
    }

    private static Issue issue(String status) {
        return new Issue(UUID.randomUUID(), "DQI202611130001", RULE_ID, "DQ-METRIC",
                "指标未发布占比", "HIGH", status, "steward01", UUID.randomUUID(),
                UUID.randomUUID(), 1, BigDecimal.TEN, null, null, null, null, null, null,
                null, 0, NOW, NOW);
    }
}
