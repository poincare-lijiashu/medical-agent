package com.medassist.release.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.service.ChangeWindowService;
import com.medassist.release.service.CiIngestService;
import com.medassist.release.service.CompatibilityService;
import com.medassist.release.service.DeploymentService;
import com.medassist.release.service.PatchService;
import com.medassist.release.service.ReleaseCatalogService;
import com.medassist.release.service.ReleaseDashboardService;
import com.medassist.release.service.ReleaseTrainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 发布权限矩阵：编排、审批、部署必须分离。
 */
class ReleaseControllerPermissionTest {

    private ReleaseCatalogService catalogService;
    private ReleaseController controller;

    @BeforeEach
    void setUp() {
        catalogService = mock(ReleaseCatalogService.class);
        controller = new ReleaseController(catalogService,
                mock(ReleaseTrainService.class), mock(CompatibilityService.class),
                mock(PatchService.class), mock(ChangeWindowService.class),
                mock(DeploymentService.class), mock(ReleaseDashboardService.class),
                mock(CiIngestService.class), mock(ReleaseRepository.class));
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询：必须 release:read")
    void readPermission() {
        assertForbidden(() -> controller.versions(null, null, 1, 20, "release:write"));
    }

    @Test
    @DisplayName("编排：必须 release:write")
    void writePermission() {
        ReleaseDtos.ComponentReq req = new ReleaseDtos.ComponentReq(
                "platform-clinical", "临床核心", "SERVICE", "CLINICAL_ONCALL", "ACTIVE", 40);
        assertForbidden(() -> controller.upsertComponent(req, "release:read",
                "release01", null, null));
    }

    @Test
    @DisplayName("发布/冻结：必须 release:approve，编排权限不能替代")
    void approvePermission() {
        assertForbidden(() -> controller.publishVersion(UUID.randomUUID(), "release:write",
                "release01", null, null));
    }

    @Test
    @DisplayName("部署：必须 release:deploy，审批权限不能替代")
    void deployPermission() {
        ReleaseDtos.DeployReq req = new ReleaseDtos.DeployReq("platform-clinical",
                UUID.randomUUID(), "PROD", "DEPLOY", "SUCCESS",
                Map.of("backup", true), null, "reason", Instant.now(), Instant.now());
        assertForbidden(() -> controller.recordDeployment(req, "release:approve",
                "deploy01", null, null));
    }

    @Test
    @DisplayName("审计：必须 release:audit:read")
    void auditPermission() {
        assertForbidden(() -> controller.audit(null, "release:read"));
    }
}
