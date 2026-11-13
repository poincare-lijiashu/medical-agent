package com.medassist.masterdata.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.masterdata.domain.MasterDataModel.MetricDefinition;
import com.medassist.masterdata.domain.MasterDataModel.MetricLineage;
import com.medassist.masterdata.domain.MasterDataModel.MetricVersion;
import com.medassist.masterdata.repo.MasterDataRepository;
import com.medassist.masterdata.web.MetricDtos.MetricUpsertReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 指标字典治理规则（§293 / 计划书 W4.5）。
 *
 * <p>锁住四条底线：
 * 1. 口径不完整不能发布；
 * 2. 发布必须至少有一个上游源表/接口，并把源表登记为血缘；
 * 3. 已发布指标不能直接改，必须修订；
 * 4. 每次动作都落版本快照（发布快照带校验和）。
 */
class MetricDictionaryServiceTest {

    private MasterDataRepository repo;
    private MetricDictionaryService service;
    private final AtomicReference<MetricDefinition> head = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(MasterDataRepository.class);
        service = new MetricDictionaryService(repo, new ObjectMapper());
        when(repo.findMetric(anyString())).thenAnswer(inv -> {
            MetricDefinition m = head.get();
            return m != null && m.code().equals(inv.getArgument(0)) ? Optional.of(m)
                    : Optional.empty();
        });
        when(repo.insertMetric(any())).thenAnswer(inv -> {
            head.set(inv.getArgument(0));
            return true;
        });
        when(repo.countMetricLineage(anyString(), anyString())).thenReturn(0);
    }

    @Test
    @DisplayName("口径定义过短不能创建")
    void rejectsIncompleteDefinition() {
        MetricUpsertReq req = new MetricUpsertReq("TOO_SHORT_DEF", "指标名称", "FINANCE",
                "太短", "sum(charge.total_amount)", "SUM", "元", "DAY", "CAMPUS",
                "clinical_svc", List.of("charge"), "财务科", "收费处", null);

        assertThatThrownBy(() -> service.create(req, "mdm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).insertMetric(any());
    }

    @Test
    @DisplayName("没有上游源表/接口不能发布")
    void publishRequiresUpstream() {
        head.set(metric("NO_SOURCE", MetricDefinition.DRAFT, 2, null, List.of()));
        when(repo.publishMetric(anyString(), anyLong(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.publish("NO_SOURCE", 2, "mdm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(repo, never()).publishMetric(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("发布：落 PUBLISH 版本快照，并把源表登记为 UPSTREAM 血缘")
    void publishSnapshotsAndRegistersSources() {
        head.set(metric("OUTPATIENT_PAID_AMOUNT", MetricDefinition.DRAFT, 2, null,
                List.of("charge")));
        when(repo.publishMetric(anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            MetricDefinition current = head.get();
            head.set(new MetricDefinition(current.code(), current.name(), current.domain(),
                    current.definition(), current.formula(), current.aggregation(), current.unit(),
                    current.timeWindow(), current.granularity(), current.sourceSchema(),
                    current.sourceTables(), current.ownerDept(), current.ownerRole(),
                    MetricDefinition.PUBLISHED, current.version(), current.version(),
                    Instant.now(), "mdm01", current.createdBy(), "mdm01",
                    current.createdAt(), Instant.now()));
            return true;
        });
        when(repo.listMetricVersions(anyString(), anyInt()))
                .thenReturn(List.of());
        when(repo.listMetricLineage(anyString())).thenReturn(List.of());

        service.publish("OUTPATIENT_PAID_AMOUNT", 2, "mdm01");

        ArgumentCaptor<MetricVersion> version = ArgumentCaptor.forClass(MetricVersion.class);
        verify(repo).insertMetricVersion(version.capture());
        assertThat(version.getValue().changeType()).isEqualTo(MetricVersion.PUBLISH);
        assertThat(version.getValue().checksum()).hasSize(64);
        assertThat(version.getValue().snapshot()).containsEntry("status", MetricDefinition.PUBLISHED);

        ArgumentCaptor<MetricLineage> lineage = ArgumentCaptor.forClass(MetricLineage.class);
        verify(repo).insertMetricLineage(lineage.capture());
        assertThat(lineage.getValue().direction()).isEqualTo(MetricLineage.UPSTREAM);
        assertThat(lineage.getValue().refCode()).isEqualTo("clinical_svc.charge");
    }

    @Test
    @DisplayName("已发布指标不能直接修改（必须 revise）")
    void publishedCannotBeEdited() {
        head.set(metric("LIVE_METRIC", MetricDefinition.PUBLISHED, 3, 3L, List.of("visit")));

        assertThatThrownBy(() -> service.update("LIVE_METRIC", req("LIVE_METRIC"), "mdm01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(repo, never()).updateMetric(any());
    }

    @Test
    @DisplayName("修订：head 进入下一版本 DRAFT，published_version 保持不动")
    void reviseKeepsPublishedVersion() {
        head.set(metric("LIVE_METRIC", MetricDefinition.PUBLISHED, 3, 3L, List.of("visit")));
        when(repo.reviseMetric(anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            MetricDefinition current = head.get();
            head.set(new MetricDefinition(current.code(), current.name(), current.domain(),
                    current.definition(), current.formula(), current.aggregation(), current.unit(),
                    current.timeWindow(), current.granularity(), current.sourceSchema(),
                    current.sourceTables(), current.ownerDept(), current.ownerRole(),
                    MetricDefinition.DRAFT, current.version() + 1, current.publishedVersion(),
                    current.publishedAt(), current.publishedBy(), current.createdBy(), "mdm01",
                    current.createdAt(), Instant.now()));
            return true;
        });
        when(repo.listMetricVersions(anyString(), anyInt())).thenReturn(List.of());
        when(repo.listMetricLineage(anyString())).thenReturn(List.of());

        var detail = service.revise("LIVE_METRIC", 3, "mdm01");

        assertThat(detail.metric().status()).isEqualTo(MetricDefinition.DRAFT);
        assertThat(detail.metric().version()).isEqualTo(4);
        assertThat(detail.metric().publishedVersion()).isEqualTo(3);
        assertThat(detail.metric().revisionInProgress()).isTrue();
        verify(repo).insertMetricVersion(any());
    }

    @Test
    @DisplayName("影响分析返回上下游数量与修订状态")
    void impactSummarisesLineage() {
        head.set(metric("LIVE_METRIC", MetricDefinition.DRAFT, 4, 3L, List.of("visit")));
        when(repo.countMetricLineage("LIVE_METRIC", MetricLineage.UPSTREAM)).thenReturn(2);
        when(repo.countMetricLineage("LIVE_METRIC", MetricLineage.DOWNSTREAM)).thenReturn(5);
        when(repo.countMetricVersions("LIVE_METRIC")).thenReturn(4);

        var impact = service.impact("LIVE_METRIC");

        assertThat(impact.upstreamCount()).isEqualTo(2);
        assertThat(impact.downstreamCount()).isEqualTo(5);
        assertThat(impact.versionCount()).isEqualTo(4);
        assertThat(impact.revisionInProgress()).isTrue();
    }

    // ---------------------------------------------------------------- fixtures

    private static MetricUpsertReq req(String code) {
        return new MetricUpsertReq(code, "指标名称", "FINANCE",
                "统计期内已结算收费单金额合计，按收费时间归属", "sum(charge.total_amount)",
                "SUM", "元", "DAY", "CAMPUS", "clinical_svc", List.of("charge"),
                "财务科", "收费处", null);
    }

    private static MetricDefinition metric(String code, String status, long version,
                                           Long publishedVersion, List<String> sources) {
        return new MetricDefinition(code, "指标名称", "FINANCE",
                "统计期内已结算收费单金额合计，按收费时间归属", "sum(charge.total_amount)",
                "SUM", "元", "DAY", "CAMPUS", "clinical_svc", sources, "财务科", "收费处",
                status, version, publishedVersion,
                publishedVersion == null ? null : Instant.now(),
                publishedVersion == null ? null : "mdm01", "system", "system",
                Instant.now(), Instant.now());
    }
}
