package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.HookTokens;
import com.medassist.release.domain.ReleaseIntegrationModel.CiEvent;
import com.medassist.release.domain.ReleaseIntegrationModel.CiSource;
import com.medassist.release.domain.ReleaseModel.Deployment;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.CiIntegrationRepository;
import com.medassist.release.repo.CiIntegrationRepository.StoredSource;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos.DeployReq;
import com.medassist.release.web.ReleaseDtos.VersionReq;
import com.medassist.release.web.ReleaseIntegrationDtos.CiEventReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CI/CD 接入：幂等、构建只登记草稿、部署不绕过门禁。
 */
class CiIngestServiceTest {

    private static final String SOURCE = "gh-actions";
    private static final String TOKEN = "ci_test_token";
    private static final String COMPONENT = "platform-clinical";
    private static final UUID VERSION_ID = UUID.randomUUID();

    private CiIntegrationRepository repo;
    private ReleaseRepository releaseRepo;
    private ReleaseCatalogService catalog;
    private DeploymentService deploymentService;
    private CiIngestService service;
    private CiSource source;

    @BeforeEach
    void setUp() {
        repo = mock(CiIntegrationRepository.class);
        releaseRepo = mock(ReleaseRepository.class);
        catalog = mock(ReleaseCatalogService.class);
        deploymentService = mock(DeploymentService.class);
        service = new CiIngestService(repo, releaseRepo, catalog, deploymentService);
        source = new CiSource(UUID.randomUUID(), SOURCE, "GitHub Actions", "GITHUB", "ci_test",
                List.of(), "ACTIVE", null, "admin", 0, Instant.now(), Instant.now());
        when(repo.findSource(SOURCE))
                .thenReturn(Optional.of(new StoredSource(source, HookTokens.hash(TOKEN))));
        when(repo.findEvent(eq(SOURCE), anyString())).thenReturn(Optional.empty());
        when(repo.tryInsertEvent(any())).thenReturn(true);
    }

    @Test
    @DisplayName("DEPLOY：记录部署，actor 固定为 ci:<source>")
    void deployRecordsDeployment() {
        when(releaseRepo.findVersionByKey(COMPONENT, "1.1.0")).thenReturn(Optional.of(version()));
        when(deploymentService.record(any(), anyString(), any(), any())).thenReturn(deployment());

        Map<String, Object> summary = service.ingest(SOURCE, "Bearer " + TOKEN, "evt-1",
                deployReq("DEPLOY", "PROD", "SUCCESS"), null, null);

        assertThat(summary.get("duplicate")).isEqualTo(false);
        ArgumentCaptor<DeployReq> req = ArgumentCaptor.forClass(DeployReq.class);
        verify(deploymentService).record(req.capture(), eq("ci:" + SOURCE), any(), any());
        assertThat(req.getValue().componentCode()).isEqualTo(COMPONENT);
        assertThat(req.getValue().environment()).isEqualTo("PROD");
        assertThat(req.getValue().startedAt()).isNotNull();
        verify(repo).finishEvent(eq(SOURCE), eq("evt-1"), eq("PROCESSED"), any(), any());
    }

    @Test
    @DisplayName("幂等：已处理事件再次投递直接返回首次结果，不重复落部署")
    void duplicateEventReturnsFirstResult() {
        CiEvent processed = new CiEvent(1, Instant.now(), SOURCE, "evt-1", "DEPLOY", COMPONENT,
                "1.1.0", "PROD", "DEPLOY", "SUCCESS", UUID.randomUUID(), "PROCESSED", "ok",
                Map.of());
        when(repo.findEvent(SOURCE, "evt-1")).thenReturn(Optional.of(processed));

        Map<String, Object> summary = service.ingest(SOURCE, "Bearer " + TOKEN, "evt-1",
                deployReq("DEPLOY", "PROD", "SUCCESS"), null, null);

        assertThat(summary.get("duplicate")).isEqualTo(true);
        verify(deploymentService, never()).record(any(), any(), any(), any());
        verify(repo, never()).tryInsertEvent(any());
    }

    @Test
    @DisplayName("BUILD 成功：版本缺失时自动登记草稿，发布仍需人工")
    void buildRegistersDraftVersion() {
        when(releaseRepo.findVersionByKey(COMPONENT, "1.2.0")).thenReturn(Optional.empty());
        when(catalog.registerVersion(any(), anyString(), any(), any())).thenReturn(version());

        CiEventReq req = buildReq("1.2.0", "SUCCESS");
        Map<String, Object> summary = service.ingest(SOURCE, "Bearer " + TOKEN, "evt-2", req,
                null, null);

        assertThat(summary.get("status")).isEqualTo("processed");
        ArgumentCaptor<VersionReq> versionReq = ArgumentCaptor.forClass(VersionReq.class);
        verify(catalog).registerVersion(versionReq.capture(), eq("ci:" + SOURCE), any(), any());
        assertThat(versionReq.getValue().componentCode()).isEqualTo(COMPONENT);
        assertThat(versionReq.getValue().version()).isEqualTo("1.2.0");
    }

    @Test
    @DisplayName("BUILD 失败：不登记版本，只留事件")
    void failedBuildDoesNotRegister() {
        when(releaseRepo.findVersionByKey(COMPONENT, "1.3.0")).thenReturn(Optional.empty());

        service.ingest(SOURCE, "Bearer " + TOKEN, "evt-3", buildReq("1.3.0", "FAILED"),
                null, null);

        verify(catalog, never()).registerVersion(any(), any(), any(), any());
        verify(repo).finishEvent(eq(SOURCE), eq("evt-3"), eq("PROCESSED"), isNull(), any());
    }

    @Test
    @DisplayName("DEPLOY 版本不存在：422 并标记事件失败")
    void deployVersionMissingRejected() {
        when(releaseRepo.findVersionByKey(COMPONENT, "9.9.9")).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service.ingest(SOURCE, "Bearer " + TOKEN, "evt-4",
                                deployReq("DEPLOY", "STAGING", "SUCCESS"), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_VERSION_NOT_FOUND);

        verify(repo).finishEvent(eq(SOURCE), eq("evt-4"), eq("FAILED"), isNull(), any());
    }

    @Test
    @DisplayName("部署门禁：冻结窗口/检查单错误原样抛回流水线，并把事件标记失败")
    void deploymentGateErrorPropagates() {
        when(releaseRepo.findVersionByKey(COMPONENT, "1.1.0")).thenReturn(Optional.of(version()));
        when(deploymentService.record(any(), anyString(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.REL_FREEZE_ACTIVE));

        assertThatThrownBy(() -> service.ingest(SOURCE, "Bearer " + TOKEN, "evt-5",
                deployReq("DEPLOY", "PROD", "SUCCESS"), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_FREEZE_ACTIVE);

        verify(repo).finishEvent(eq(SOURCE), eq("evt-5"), eq("FAILED"), isNull(), any());
    }

    @Test
    @DisplayName("组件白名单：接入源不允许的组件直接 422")
    void allowedComponentsEnforced() {
        CiSource limited = new CiSource(source.id(), SOURCE, "GH", "GITHUB", "ci_test",
                List.of("platform-auth"), "ACTIVE", null, "admin", 0, Instant.now(), Instant.now());
        when(repo.findSource(SOURCE))
                .thenReturn(Optional.of(new StoredSource(limited, HookTokens.hash(TOKEN))));

        assertThatThrownBy(() -> service.ingest(SOURCE, "Bearer " + TOKEN, "evt-6",
                deployReq("DEPLOY", "STAGING", "SUCCESS"), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_CI_PAYLOAD_INVALID);
        verify(repo, never()).tryInsertEvent(any());
    }

    @Test
    @DisplayName("令牌错误：401，不处理事件")
    void badTokenRejected() {
        assertThatThrownBy(() -> service.ingest(SOURCE, "Bearer wrong", "evt-7",
                deployReq("DEPLOY", "STAGING", "SUCCESS"), null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_CI_TOKEN_INVALID);
        verify(repo, never()).tryInsertEvent(any());
    }

    // ------------------------------------------------------------------ 辅助

    private static Version version() {
        return new Version(VERSION_ID, COMPONENT, "1.1.0", "abc", "sha256:x", null,
                "RELEASED", false, "clinical release", Instant.now(), "admin", 1,
                Instant.now(), Instant.now());
    }

    private static Deployment deployment() {
        return new Deployment(UUID.randomUUID(), COMPONENT, VERSION_ID, "PROD", "DEPLOY",
                "SUCCESS", Map.of(), null, "ci", "ci:" + SOURCE, Instant.now(), null,
                Instant.now());
    }

    private static CiEventReq deployReq(String action, String environment, String result) {
        return new CiEventReq("DEPLOY", COMPONENT, "1.1.0", null, environment, action, result,
                Map.of("backup", true), null, "pipeline run 1", "2026-11-12T08:00:00Z",
                "2026-11-12T08:10:00Z", "github-actions", null, null, null, null, null, null);
    }

    private static CiEventReq buildReq(String version, String result) {
        return new CiEventReq("BUILD", COMPONENT, version, null, null, null, result,
                null, null, "pipeline run 2", "2026-11-12T08:00:00Z", null, "github-actions",
                "abc123", "sha256:x", null, false, "CI build for 1.2.0", null);
    }
}
