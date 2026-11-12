package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.config.ReleaseProperties;
import com.medassist.release.domain.ReleaseModel.ChangeWindow;
import com.medassist.release.domain.ReleaseModel.Deployment;
import com.medassist.release.domain.ReleaseModel.Patch;
import com.medassist.release.domain.ReleaseModel.Version;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.web.ReleaseDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部署：生产检查单、冻结窗口、HOTFIX 例外与回滚目标。
 */
class DeploymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");
    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final UUID DEPLOYMENT_ID = UUID.randomUUID();

    private ReleaseRepository repo;
    private ReleaseCatalogService catalog;
    private DeploymentService service;

    @BeforeEach
    void setUp() {
        repo = mock(ReleaseRepository.class);
        catalog = mock(ReleaseCatalogService.class);
        when(catalog.requireVersion(VERSION_ID)).thenReturn(new Version(VERSION_ID,
                "platform-clinical", "1.0.0", "abc", "sha256:x", null, "RELEASED", false,
                "changelog", NOW, "release01", 0, NOW, NOW));
        service = new DeploymentService(repo, catalog, new ReleaseProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("生产部署：检查单缺项直接拒绝")
    void checklistRequired() {
        assertThatThrownBy(() -> service.record(req("DEPLOY", Map.of("backup", true)),
                "deploy01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_CHECKLIST_INCOMPLETE);
    }

    @Test
    @DisplayName("生产部署：冻结窗口内 DEPLOY 被拒绝")
    void freezeBlocksDeploy() {
        when(repo.activeFreezes(any(), eq("platform-clinical"))).thenReturn(List.of(
                new ChangeWindow(UUID.randomUUID(), "freeze", "FREEZE", NOW.minusSeconds(60),
                        NOW.plusSeconds(60), List.of(), "year end", "ACTIVE", "release01", 0)));

        assertThatThrownBy(() -> service.record(req("DEPLOY", fullChecklist()),
                "deploy01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_FREEZE_ACTIVE);
    }

    @Test
    @DisplayName("生产 HOTFIX：有已发布补丁时允许穿透冻结窗口")
    void hotfixAllowedWithPatch() {
        when(repo.activeFreezes(any(), eq("platform-clinical"))).thenReturn(List.of(
                new ChangeWindow(UUID.randomUUID(), "freeze", "FREEZE", NOW.minusSeconds(60),
                        NOW.plusSeconds(60), List.of(), "year end", "ACTIVE", "release01", 0)));
        when(repo.findReleasedPatch("platform-clinical", VERSION_ID)).thenReturn(Optional.of(
                new Patch(UUID.randomUUID(), "PAT2026111200001", "hotfix", "CRITICAL",
                        "platform-clinical", List.of("0.9.0"), VERSION_ID, "advisory", null,
                        "RELEASED", "release01", "manager01", NOW.minusSeconds(120),
                        NOW.minusSeconds(60), 2, NOW.minusSeconds(300), NOW.minusSeconds(60))));

        Deployment deployment = service.record(req("HOTFIX", fullChecklist()),
                "deploy01", null, null);

        assertThat(deployment.action()).isEqualTo("HOTFIX");
        verify(repo).insertDeployment(any());
    }

    @Test
    @DisplayName("回滚：没有上一次成功部署时拒绝")
    void rollbackWithoutTarget() {
        when(repo.findDeployment(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment(DEPLOYMENT_ID)));
        when(repo.previousSuccessfulDeployment("platform-clinical", "PROD", "tenant-001",
                DEPLOYMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(DEPLOYMENT_ID, "rollback",
                "deploy01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.REL_ROLLBACK_NO_TARGET);
    }

    @Test
    @DisplayName("回滚：生成 ROLLBACK 记录并指回原部署")
    void rollbackCreatesRecord() {
        UUID previousId = UUID.randomUUID();
        Deployment previous = new Deployment(previousId, "platform-clinical", VERSION_ID, "PROD",
                "tenant-001", null, "DEPLOY", "SUCCESS", Map.of(), null, null, "deploy01",
                NOW.minusSeconds(3600), NOW.minusSeconds(3500), NOW.minusSeconds(3600));
        when(repo.findDeployment(DEPLOYMENT_ID)).thenReturn(Optional.of(deployment(DEPLOYMENT_ID)));
        when(repo.previousSuccessfulDeployment("platform-clinical", "PROD", "tenant-001",
                DEPLOYMENT_ID)).thenReturn(Optional.of(previous));

        service.rollback(DEPLOYMENT_ID, "bad release", "deploy01", null, null);

        ArgumentCaptor<Deployment> captor = ArgumentCaptor.forClass(Deployment.class);
        verify(repo).insertDeployment(captor.capture());
        Deployment saved = captor.getValue();
        assertThat(saved.action()).isEqualTo("ROLLBACK");
        assertThat(saved.rollbackOf()).isEqualTo(DEPLOYMENT_ID);
        assertThat(saved.versionId()).isEqualTo(VERSION_ID);
        // 回滚记录必须继承原部署的 Cell：否则台账里会出现"回滚到别家"的歧义
        assertThat(saved.tenantCode()).isEqualTo("tenant-001");
    }

    @Test
    @DisplayName("部署：Cell 编码落台账，空白归一为平台级")
    void tenantRecorded() {
        Deployment deployment = service.record(
                new ReleaseDtos.DeployReq("platform-clinical", VERSION_ID, "PROD",
                        " tenant-002 ", "PILOT", "DEPLOY", "SUCCESS", fullChecklist(),
                        null, "pilot", NOW, NOW.plusSeconds(60)),
                "deploy01", null, null);
        assertThat(deployment.tenantCode()).isEqualTo("tenant-002");
        assertThat(deployment.batchLabel()).isEqualTo("PILOT");

        Deployment platform = service.record(
                new ReleaseDtos.DeployReq("platform-clinical", VERSION_ID, "PROD",
                        "  ", "", "DEPLOY", "SUCCESS", fullChecklist(),
                        null, "platform", NOW, NOW.plusSeconds(60)),
                "deploy01", null, null);
        assertThat(platform.tenantCode()).isNull();
        assertThat(platform.batchLabel()).isNull();
    }

    private ReleaseDtos.DeployReq req(String action, Map<String, Object> checklist) {
        return new ReleaseDtos.DeployReq("platform-clinical", VERSION_ID, "PROD",
                "tenant-001", null, action, "SUCCESS", checklist, null,
                "release 1.0.0", NOW, NOW.plusSeconds(60));
    }

    private static Map<String, Object> fullChecklist() {
        return Map.of("backup", true, "rollback_plan", true, "smoke_test", true,
                "oncall_confirmed", true, "change_ticket", true);
    }

    private static Deployment deployment(UUID id) {
        return new Deployment(id, "platform-clinical", VERSION_ID, "PROD", "tenant-001", null,
                "DEPLOY", "SUCCESS", fullChecklist(), null, null, "deploy01",
                NOW.minusSeconds(60), NOW.minusSeconds(30), NOW.minusSeconds(60));
    }
}
