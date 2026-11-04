package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.client.PacsDicomGateway;
import com.medassist.tech.domain.TechModel.PacsAccessLog;
import com.medassist.tech.domain.TechModel.PacsSeries;
import com.medassist.tech.domain.TechModel.PacsStudy;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.PacsDtos.SeriesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
 * DICOMweb 调阅编排：未配置 PACS、未知检查、UID 校验、序列缓存回退与调阅审计。
 */
class PacsDicomServiceTest {

    private static final String STUDY_UID = "1.2.840.113619.2.55.3.20261103.9";
    private static final String SERIES_UID = "1.2.840.113619.2.55.3.20261103.9.1";
    private static final String SOP_UID = "1.2.840.113619.2.55.3.20261103.9.1.1";
    private static final String ACCESSION = "AP20261103000009";

    private TechRepository repo;
    private PacsDicomGateway gateway;
    private PacsDicomService service;

    private final UUID studyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(TechRepository.class);
        gateway = mock(PacsDicomGateway.class);
        service = new PacsDicomService(repo, gateway);
        when(repo.findPacsStudyByUid(STUDY_UID)).thenReturn(Optional.of(study()));
        when(gateway.configured()).thenReturn(true);
    }

    private PacsStudy study() {
        return new PacsStudy(studyId, UUID.randomUUID(), STUDY_UID, ACCESSION, "CT", "胸部",
                PacsStudy.COMPLETED, 2, 240, null, Instant.now().minusSeconds(300),
                Instant.now(), 2L, Instant.now());
    }

    private PacsSeries cachedSeries() {
        return new PacsSeries(UUID.randomUUID(), studyId, SERIES_UID, 2, "CT", "CHEST",
                "胸部平扫", 120, Instant.now());
    }

    private void assertCode(Runnable call, ErrorCode code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(code);
    }

    @Test
    @DisplayName("序列：未配置 PACS → 46407，不访问远端")
    void seriesRequiresConfiguredPacs() {
        when(gateway.configured()).thenReturn(false);

        assertCode(() -> service.listSeries(STUDY_UID, "doc01"), ErrorCode.TECH_PACS_NOT_CONFIGURED);
        verify(gateway, never()).listSeries(anyString());
    }

    @Test
    @DisplayName("序列：未知检查 → 46406，避免成为任意 DICOMweb 代理")
    void seriesRequiresKnownStudy() {
        when(repo.findPacsStudyByUid("1.2.3.4")).thenReturn(Optional.empty());

        assertCode(() -> service.listSeries("1.2.3.4", "doc01"), ErrorCode.TECH_PACS_STUDY_NOT_FOUND);
        verify(gateway, never()).listSeries(anyString());
    }

    @Test
    @DisplayName("序列：实时成功 → 原地更新缓存、写 VIEW_SERIES 审计、返回 LIVE")
    void seriesSyncsCacheAndAudits() {
        PacsSeries cached = cachedSeries();
        when(gateway.listSeries(STUDY_UID)).thenReturn(List.of(
                new PacsDicomGateway.SeriesPayload(SERIES_UID, 2, "CT", "CHEST", "胸部平扫", 120)));
        when(repo.listPacsSeries(studyId)).thenReturn(List.of(cached));

        SeriesResponse response = service.listSeries(STUDY_UID, "doc01");

        assertThat(response.source()).isEqualTo("LIVE");
        assertThat(response.notice()).isNull();
        assertThat(response.series()).hasSize(1);
        // 同一 UID 复用原主键，避免每次同步都换一批序列 ID
        org.mockito.ArgumentCaptor<PacsSeries> captor =
                org.mockito.ArgumentCaptor.forClass(PacsSeries.class);
        verify(repo).insertPacsSeries(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(cached.id());
        verify(repo, never()).deletePacsSeriesById(any());
        verify(repo).insertPacsAccess(any(PacsAccessLog.class));
    }

    @Test
    @DisplayName("序列：PACS 已删除的序列从缓存移除")
    void seriesRemovesStaleCacheEntries() {
        PacsSeries stale = new PacsSeries(UUID.randomUUID(), studyId, STUDY_UID + ".99", 9,
                "CT", "CHEST", "旧序列", 10, Instant.now());
        when(gateway.listSeries(STUDY_UID)).thenReturn(List.of(
                new PacsDicomGateway.SeriesPayload(SERIES_UID, 1, "CT", "CHEST", "胸部平扫", 120)));
        when(repo.listPacsSeries(studyId)).thenReturn(List.of(cachedSeries(), stale));

        service.listSeries(STUDY_UID, "doc01");

        verify(repo).deletePacsSeriesById(stale.id());
    }

    @Test
    @DisplayName("序列：远端故障且有缓存 → 回退 CACHED 并提示，审计标注 cached")
    void seriesFallsBackToCache() {
        when(gateway.listSeries(STUDY_UID))
                .thenThrow(new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED));
        when(repo.listPacsSeries(studyId)).thenReturn(List.of(cachedSeries()));

        SeriesResponse response = service.listSeries(STUDY_UID, "doc01");

        assertThat(response.source()).isEqualTo("CACHED");
        assertThat(response.notice()).contains("暂不可用");
        assertThat(response.series()).hasSize(1);
        verify(repo, never()).deletePacsSeriesById(any());
        verify(repo).insertPacsAccess(any(PacsAccessLog.class));
    }

    @Test
    @DisplayName("序列：远端故障且无缓存 → 原样抛 503")
    void seriesPropagatesWhenNoCache() {
        when(gateway.listSeries(STUDY_UID))
                .thenThrow(new BusinessException(ErrorCode.TECH_PACS_UPSTREAM_FAILED));
        when(repo.listPacsSeries(studyId)).thenReturn(List.of());

        assertCode(() -> service.listSeries(STUDY_UID, "doc01"), ErrorCode.TECH_PACS_UPSTREAM_FAILED);
        verify(repo, never()).insertPacsAccess(any());
    }

    @Test
    @DisplayName("序列：缺少调阅人 → 422（不能无主调阅）")
    void seriesRequiresActor() {
        assertCode(() -> service.listSeries(STUDY_UID, " "), ErrorCode.VALIDATION_FAILED);
        verify(gateway, never()).listSeries(anyString());
    }

    @Test
    @DisplayName("影像：非法 UID → 422，不触达 PACS")
    void instanceRejectsInvalidUid() {
        assertCode(() -> service.fetchInstance(STUDY_UID, "../etc/passwd", SOP_UID, "doc01"),
                ErrorCode.VALIDATION_FAILED);
        verify(gateway, never()).fetchInstance(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("影像：未配置 PACS → 46407")
    void instanceRequiresConfiguredPacs() {
        when(gateway.configured()).thenReturn(false);

        assertCode(() -> service.fetchInstance(STUDY_UID, SERIES_UID, SOP_UID, "doc01"),
                ErrorCode.TECH_PACS_NOT_CONFIGURED);
        verify(gateway, never()).fetchInstance(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("影像：成功取流并写 FETCH_IMAGE 审计")
    void instanceFetchesAndAudits() {
        byte[] dicom = new byte[]{1, 2, 3, 4};
        when(gateway.fetchInstance(STUDY_UID, SERIES_UID, SOP_UID)).thenReturn(dicom);

        PacsDicomService.InstancePayload payload =
                service.fetchInstance(STUDY_UID, SERIES_UID, SOP_UID, "doc01");

        assertThat(payload.bytes()).isEqualTo(dicom);
        assertThat(payload.contentType()).isEqualTo("application/dicom");
        verify(repo).insertPacsAccess(any(PacsAccessLog.class));
    }

    @Test
    @DisplayName("影像：检查未登记 → 404，不触达 PACS")
    void instanceRequiresKnownStudy() {
        when(repo.findPacsStudyByUid(STUDY_UID)).thenReturn(Optional.empty());

        assertCode(() -> service.fetchInstance(STUDY_UID, SERIES_UID, SOP_UID, "doc01"),
                ErrorCode.TECH_PACS_STUDY_NOT_FOUND);
        verify(gateway, never()).fetchInstance(anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- 影像分页（G31）

    @Test
    @DisplayName("影像分页：序列不在当前检查缓存 → 404，不触达 PACS")
    void instancesRequireCachedSeries() {
        when(repo.findPacsSeries(studyId, SERIES_UID)).thenReturn(Optional.empty());

        assertCode(() -> service.listInstances(STUDY_UID, SERIES_UID, 1, 24, "doc01"),
                ErrorCode.TECH_PACS_REMOTE_NOT_FOUND);
        verify(gateway, never()).listInstances(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("影像分页：按页计算 offset，total 取序列元数据并写审计")
    void instancesPageAndAudit() {
        when(repo.findPacsSeries(studyId, SERIES_UID)).thenReturn(Optional.of(cachedSeries()));
        when(gateway.listInstances(STUDY_UID, SERIES_UID, 10, 10)).thenReturn(List.of(
                new PacsDicomGateway.InstanceMeta(SOP_UID + ".11", 11, "1.2.840.10008.5.1.4.1.1.2"),
                new PacsDicomGateway.InstanceMeta(SOP_UID + ".12", 12, null)));

        var page = service.listInstances(STUDY_UID, SERIES_UID, 2, 10, "doc01");

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(10);
        assertThat(page.total()).isEqualTo(120);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).instanceNumber()).isEqualTo(11);
        verify(repo).insertPacsAccess(any(PacsAccessLog.class));
    }

    @Test
    @DisplayName("影像分页：size 上限 200，缺省 24")
    void instancesCapSize() {
        when(repo.findPacsSeries(studyId, SERIES_UID)).thenReturn(Optional.of(cachedSeries()));
        when(gateway.listInstances(STUDY_UID, SERIES_UID, 200, 0)).thenReturn(List.of());

        var page = service.listInstances(STUDY_UID, SERIES_UID, null, 9999, "doc01");

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(200);
        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("影像分页：缺少调阅人 → 422")
    void instancesRequireActor() {
        assertCode(() -> service.listInstances(STUDY_UID, SERIES_UID, 1, 24, " "),
                ErrorCode.VALIDATION_FAILED);
        verify(gateway, never()).listInstances(anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("预览图：成功返回 image/jpeg 并写 FETCH_IMAGE 审计")
    void renderedFetchesAndAudits() {
        when(gateway.fetchRendered(STUDY_UID, SERIES_UID, SOP_UID))
                .thenReturn(new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2});

        PacsDicomService.InstancePayload payload =
                service.fetchRendered(STUDY_UID, SERIES_UID, SOP_UID, "doc01");

        assertThat(payload.contentType()).isEqualTo("image/jpeg");
        assertThat(payload.bytes()).hasSize(4);
        verify(repo).insertPacsAccess(any(PacsAccessLog.class));
    }

    @Test
    @DisplayName("预览图：远端不支持 rendered → 404")
    void renderedRemoteNotSupported() {
        when(gateway.fetchRendered(STUDY_UID, SERIES_UID, SOP_UID))
                .thenThrow(new BusinessException(ErrorCode.TECH_PACS_REMOTE_NOT_FOUND));

        assertCode(() -> service.fetchRendered(STUDY_UID, SERIES_UID, SOP_UID, "doc01"),
                ErrorCode.TECH_PACS_REMOTE_NOT_FOUND);
        verify(repo, never()).insertPacsAccess(any(PacsAccessLog.class));
    }

    // ---------------------------------------------------------------- 查看器深链（G31）

    @Test
    @DisplayName("查看器：配置 OHIF 根地址时拼 Study/Series 深链")
    void viewerUsesConfiguredBase() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "viewerBaseUrl",
                "http://ohif.local/viewer");

        var link = service.viewerLink(STUDY_UID, SERIES_UID);

        assertThat(link.configured()).isTrue();
        assertThat(link.viewerUrl()).startsWith("http://ohif.local/viewer?StudyInstanceUIDs=");
        assertThat(link.viewerUrl()).contains("&SeriesInstanceUIDs=");
        assertThat(link.seriesInstanceUid()).isEqualTo(SERIES_UID);
    }

    @Test
    @DisplayName("查看器：未配置 OHIF 时回退检查登记回传的 viewerUrl")
    void viewerFallsBackToStudyUrl() {
        PacsStudy withViewer = new PacsStudy(studyId, UUID.randomUUID(), STUDY_UID, ACCESSION,
                "CT", "胸部", PacsStudy.COMPLETED, 2, 240,
                "http://vendor/viewer?study=1.2.3", Instant.now(), Instant.now(), 2L, Instant.now());
        when(repo.findPacsStudyByUid(STUDY_UID)).thenReturn(Optional.of(withViewer));

        var link = service.viewerLink(STUDY_UID, null);

        assertThat(link.configured()).isTrue();
        assertThat(link.viewerUrl()).isEqualTo("http://vendor/viewer?study=1.2.3");
        assertThat(link.seriesInstanceUid()).isNull();
    }

    @Test
    @DisplayName("查看器：都未配置时 configured=false，不编造链接")
    void viewerNotConfigured() {
        var link = service.viewerLink(STUDY_UID, null);

        assertThat(link.configured()).isFalse();
        assertThat(link.viewerUrl()).isNull();
    }
}
