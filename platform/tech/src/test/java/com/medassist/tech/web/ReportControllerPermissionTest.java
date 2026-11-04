package com.medassist.tech.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.service.ReportService;
import com.medassist.tech.service.TechAccessGuard;
import com.medassist.tech.web.TechDtos.AckCriticalReq;
import com.medassist.tech.web.TechDtos.CloseCriticalReq;
import com.medassist.tech.web.TechDtos.ReviewReportReq;
import com.medassist.tech.web.TechDtos.WriteReportReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 医技报告与危急值的接口级权限矩阵（不依赖 Spring 上下文）。
 *
 * <p>为什么要单测：报告签发、危急值确认/关闭是高危动作，权限串写错（例如把
 * {@code tech:critical:acknowledge} 写成 {@code tech:read}）业务单测仍然全绿，
 * 只有真正打到 Controller 才会暴露越权。
 */
class ReportControllerPermissionTest {

    private ReportService service;
    private ReportController controller;
    private final DataScope scope = DataScope.all("tester", true);

    private final UUID applicationId = UUID.randomUUID();
    private final UUID reportId = UUID.randomUUID();
    private final UUID criticalId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(ReportService.class);
        controller = new ReportController(service, mock(TechAccessGuard.class));
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("报告详情：只有 tech:read 可读，报告写权限不能代替读权限")
    void detailRequiresRead() {
        assertForbidden(() -> controller.detail(reportId, "tech:report:write", scope));

        controller.detail(reportId, "tech:read", scope);
        verify(service).detail(reportId);
    }

    @Test
    @DisplayName("书写报告：需要 tech:report:write，审方权限不能越权书写")
    void writeRequiresWrite() {
        WriteReportReq req = new WriteReportReq("所见", "结论", List.of(), null, null);
        assertForbidden(() -> controller.write(applicationId, req, "tech:report:review",
                "tech01", scope));
        verify(service, never()).write(any(), any(), anyString());

        controller.write(applicationId, req, "tech:report:write", "tech01", scope);
        verify(service).write(applicationId, req, "tech01");
    }

    @Test
    @DisplayName("审核签发：需要 tech:report:review，书写权限不能自行签发")
    void reviewRequiresReview() {
        ReviewReportReq req = new ReviewReportReq(true, null);
        assertForbidden(() -> controller.review(reportId, req, "tech:report:write",
                "chief01", scope));
        verify(service, never()).review(any(), anyBoolean(), any(), anyString());

        controller.review(reportId, req, "tech:report:review", "chief01", scope);
        verify(service).review(reportId, true, null, "chief01");
    }

    @Test
    @DisplayName("危急值列表：tech:read 放行")
    void criticalsRequiresRead() {
        assertForbidden(() -> controller.criticals(null, null, 10, "tech:report:write", scope));

        controller.criticals(null, null, 10, "tech:read", scope);
        verify(service).criticals(null, null, Set.of(), 10);
    }

    @Test
    @DisplayName("危急值确认/关闭：必须 tech:critical:acknowledge，普通读权限不行")
    void criticalActionsRequireAck() {
        assertForbidden(() -> controller.acknowledge(criticalId, new AckCriticalReq("已通知"),
                "tech:read", "nurse01", scope));
        verify(service, never()).acknowledge(any(), any(), anyString());

        controller.acknowledge(criticalId, new AckCriticalReq("已通知"),
                "tech:critical:acknowledge", "nurse01", scope);
        verify(service).acknowledge(criticalId, "已通知", "nurse01");

        assertForbidden(() -> controller.close(criticalId, new CloseCriticalReq("闭环"),
                "tech:read", "nurse01", scope));
        controller.close(criticalId, new CloseCriticalReq("闭环"),
                "tech:critical:acknowledge", "nurse01", scope);
        verify(service).closeCritical(criticalId, "闭环", "nurse01");
    }

    @Test
    @DisplayName("权限头为空：403（提示重新登录），且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.criticals(null, null, 10, null, scope));
        assertThat(service).isNotNull();
        verify(service, never()).criticals(any(), any(), any(), eq(10));
    }
}
