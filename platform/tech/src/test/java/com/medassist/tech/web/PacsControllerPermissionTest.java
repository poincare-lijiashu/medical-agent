package com.medassist.tech.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.tech.service.PacsDicomService;
import com.medassist.tech.service.PacsDimseService;
import com.medassist.tech.service.PacsReadingService;
import com.medassist.tech.service.PacsService;
import com.medassist.tech.service.TechAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 影像接口级权限矩阵：阅片动作、DICOM 调阅与调阅审计各自独立授权。
 *
 * <p>为什么必须单独测：PACS 相关权限串很像（tech:pacs:read / tech:pacs:write /
 * tech:pacs:audit），写错一个字母业务单测仍全绿，只有真正打到 Controller 才暴露。
 */
class PacsControllerPermissionTest {

    private PacsService service;
    private PacsReadingService readingService;
    private PacsDicomService dicomService;
    private PacsDimseService dimseService;
    private PacsController controller;

    private static final String UID = "1.2.840.113619.2.55.3.20261103.7";

    @BeforeEach
    void setUp() {
        service = mock(PacsService.class);
        readingService = mock(PacsReadingService.class);
        dicomService = mock(PacsDicomService.class);
        dimseService = mock(PacsDimseService.class);
        controller = new PacsController(service, readingService, dicomService, dimseService,
                mock(TechAccessGuard.class));
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("阅片队列：tech:pacs:read 放行，写权限不能代替读")
    void queueRequiresRead() {
        assertForbidden(() -> controller.readingQueue(null, null, null, 10,
                "tech:pacs:write", "doc01", DataScope.all("doc01", true)));

        controller.readingQueue(null, "MINE", null, 10, "tech:pacs:read", "doc01",
                DataScope.all("doc01", true));
        verify(readingService).queue(null, "MINE", null, 10, "doc01", Set.of());
    }

    @Test
    @DisplayName("DIMSE：C-ECHO/C-FIND 需要 tech:pacs:read，C-MOVE/C-STORE 需要 tech:pacs:transfer")
    void dimseRequiresTransferForMutation() {
        assertForbidden(() -> controller.dimseEcho("tech:pacs:write", "tech01"));
        assertForbidden(() -> controller.dimseMove(
                new PacsDimseDtos.MoveReq(UID, "MEDAGENT"), "tech:pacs:read", "tech01"));

        controller.dimseEcho("tech:pacs:read", "tech01");
        verify(dimseService).echo("tech01");

        controller.dimseMove(new PacsDimseDtos.MoveReq(UID, "MEDAGENT"),
                "tech:pacs:transfer", "tech01");
        verify(dimseService).move(UID, "MEDAGENT", "tech01");
    }

    @Test
    @DisplayName("领取阅片：必须 tech:report:write，普通影像读权限不能领取")
    void claimRequiresReportWrite() {
        assertForbidden(() -> controller.claim(UID, "tech:pacs:read", "doc01",
                DataScope.all("doc01", true)));
        verify(readingService, never()).claim(any(), any());

        controller.claim(UID, "tech:report:write", "doc01", DataScope.all("doc01", true));
        verify(readingService).claim(UID, "doc01");
    }

    @Test
    @DisplayName("释放/完成阅片：必须 tech:report:write")
    void releaseAndCompleteRequireReportWrite() {
        assertForbidden(() -> controller.release(UID, null, "tech:pacs:write", "doc01",
                DataScope.all("doc01", true)));
        verify(readingService, never()).release(any(), any(), any());

        controller.release(UID, null, "tech:report:write", "doc01", DataScope.all("doc01", true));
        verify(readingService).release(UID, null, "doc01");

        assertForbidden(() -> controller.complete(UID, null, "tech:pacs:write", "doc01",
                DataScope.all("doc01", true)));
        controller.complete(UID, null, "tech:report:write", "doc01", DataScope.all("doc01", true));
        verify(readingService).complete(UID, null, "doc01");
    }

    @Test
    @DisplayName("序列/影像调阅：tech:pacs:read 放行")
    void seriesAndInstanceRequireRead() {
        assertForbidden(() -> controller.series(UID, "tech:pacs:audit", "doc01",
                DataScope.all("doc01", true)));
        controller.series(UID, "tech:pacs:read", "doc01", DataScope.all("doc01", true));
        verify(dicomService).listSeries(UID, "doc01");

        assertForbidden(() -> controller.instance(UID, "1.2.3", "1.2.3.4", "tech:pacs:write",
                "doc01", DataScope.all("doc01", true)));
        verify(dicomService, never()).fetchInstance(any(), any(), any(), any());
    }

    @Test
    @DisplayName("调阅审计：必须 tech:pacs:audit，影像读权限不能看他人调阅轨迹")
    void accessLogRequiresAudit() {
        assertForbidden(() -> controller.accessLog(UID, 10, "tech:pacs:read",
                DataScope.all("doc01", true)));
        verify(readingService, never()).accessLog(any(), any());

        controller.accessLog(UID, 10, "tech:pacs:audit", DataScope.all("doc01", true));
        verify(readingService).accessLog(UID, 10);
    }

    @Test
    @DisplayName("权限头为空：403 且不触达服务层")
    void emptyPermissionsForbidden() {
        assertForbidden(() -> controller.readingQueue(null, null, null, 10, null, "doc01",
                DataScope.all("doc01", true)));
        verify(readingService, never()).queue(any(), any(), any(), any(), any(), any());
    }
}
