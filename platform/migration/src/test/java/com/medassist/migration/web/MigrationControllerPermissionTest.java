package com.medassist.migration.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.migration.service.MigrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 迁移权限矩阵：预检（写台账）与应用（写业务数据）必须是两个权限点。
 *
 * <p>这是 §270 的核心安全边界：能给实施工程师"导入"权限，但不能顺手给"回滚"，
 * 否则一次误操作可以由同一个人立即抹掉痕迹。
 */
class MigrationControllerPermissionTest {

    private MigrationService service;
    private MigrationController controller;

    @BeforeEach
    void setUp() {
        service = mock(MigrationService.class);
        controller = new MigrationController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("查询/模板/批次详情：必须 migration:read")
    void readPermissions() {
        when(service.listTargets()).thenReturn(List.of());
        when(service.template("EMPI_PATIENT")).thenReturn("patient_no\r\n");

        assertForbidden(() -> controller.targets("migration:write"));
        assertThat(controller.targets("migration:read").data()).isEmpty();

        assertForbidden(() -> controller.template("EMPI_PATIENT", "migration:apply"));
        ResponseEntity<byte[]> response = controller.template("EMPI_PATIENT", "migration:read");
        assertThat(response.getBody()).isEqualTo("patient_no\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .contains("template_EMPI_PATIENT.csv");
    }

    @Test
    @DisplayName("预检：必须 migration:write，apply 权限不能替代")
    void previewPermission() {
        var req = new com.medassist.migration.domain.MigrationRequests.PreviewReq(
                "EMPI_PATIENT", "HIS", null, null, List.of(), null);
        assertForbidden(() -> controller.preview(req, "migration:apply", "tester", null, null));
    }

    @Test
    @DisplayName("应用：必须 migration:apply，write 权限不能替代")
    void applyPermission() {
        assertForbidden(() -> controller.apply(java.util.UUID.randomUUID(),
                "migration:write", "tester", null, null));
    }

    @Test
    @DisplayName("回滚：必须 migration:rollback，实施工程师的 apply 权限不能替代")
    void rollbackPermission() {
        var req = new com.medassist.migration.domain.MigrationRequests.RollbackReq(
                List.of("MIG-1"), "数据有误");
        assertForbidden(() -> controller.rollback(req, "migration:apply", "tester", null, null));
    }

    @Test
    @DisplayName("审计：必须 migration:audit:read")
    void auditPermission() {
        assertForbidden(() -> controller.audit(null, "migration:read"));
    }
}
