package com.medassist.clinical.web;

import com.medassist.clinical.service.TransfusionService;
import com.medassist.clinical.web.TransfusionDtos.BoardView;
import com.medassist.clinical.web.TransfusionDtos.CancelReq;
import com.medassist.clinical.web.TransfusionDtos.CompleteTransfusionReq;
import com.medassist.clinical.web.TransfusionDtos.CreateRequestReq;
import com.medassist.clinical.web.TransfusionDtos.CrossmatchReq;
import com.medassist.clinical.web.TransfusionDtos.ReactionReq;
import com.medassist.clinical.web.TransfusionDtos.RequestSummaryView;
import com.medassist.clinical.web.TransfusionDtos.StartTransfusionReq;
import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 输血接口权限矩阵：申请 / 配血 / 发血 / 输注执行四权分立。
 */
class TransfusionControllerPermissionTest {

    private TransfusionService service;
    private TransfusionController controller;

    private final UUID requestId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final DataScope fullScope = DataScope.all("admin01", true);

    @BeforeEach
    void setUp() {
        service = mock(TransfusionService.class);
        controller = new TransfusionController(service);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("申请查看：read 放行，并做 PHI 掩码")
    void requestsRequireRead() {
        when(service.listProducts()).thenReturn(List.of());
        assertForbidden(() -> controller.products("transfusion:request:write"));
        controller.products("transfusion:request:read");
        verify(service).listProducts();

        RequestSummaryView summary = new RequestSummaryView(requestId, "TR20261104000001",
                UUID.randomUUID(), "P001", "张三", "内科病区", "01", "去白红细胞",
                new BigDecimal("2"), "U", "A", "POSITIVE", "URGENT", "REQUESTED", "doctor01",
                Instant.now(), null, null);
        when(service.listRequests(null, null, null, 100, java.util.Set.of()))
                .thenReturn(List.of(summary));
        assertForbidden(() -> controller.requests(null, null, null, 100,
                "transfusion:crossmatch:manage", fullScope));
        var masked = controller.requests(null, null, null, 100, "transfusion:request:read",
                DataScope.all("nurse01", false)).data().get(0);
        assertThat(masked.patientNo()).isNotEqualTo("P001");
        assertThat(masked.patientName()).isNotEqualTo("张三");
    }

    @Test
    @DisplayName("申请开立/取消：request:write")
    void writePermissions() {
        CreateRequestReq create = new CreateRequestReq(null, "A001", UUID.randomUUID(),
                new BigDecimal("2"), "A", "POSITIVE", new BigDecimal("70"), 150, "URGENT",
                "重度贫血");
        assertForbidden(() -> controller.create(create, "transfusion:request:read",
                "doctor01", fullScope));
        verify(service, never()).createRequest(any(), any());
        controller.create(create, "transfusion:request:write", "doctor01", fullScope);
        verify(service).createRequest(create, "doctor01");

        CancelReq cancel = new CancelReq("患者拒绝");
        assertForbidden(() -> controller.cancel(requestId, cancel, "transfusion:execute",
                "doctor01", fullScope));
        controller.cancel(requestId, cancel, "transfusion:request:write", "doctor01", fullScope);
        verify(service).cancel(requestId, "患者拒绝", "doctor01");
    }

    @Test
    @DisplayName("配血/发血：各自独立权限")
    void crossmatchAndIssuePermissions() {
        CrossmatchReq crossmatch = new CrossmatchReq("BAG001", "A", "POSITIVE", "COMPATIBLE",
                "微柱凝胶法", LocalDate.now().plusDays(20), null);
        assertForbidden(() -> controller.crossmatch(requestId, crossmatch,
                "transfusion:issue:manage", "bloodbank01", fullScope));
        controller.crossmatch(requestId, crossmatch, "transfusion:crossmatch:manage",
                "bloodbank01", fullScope);
        verify(service).crossmatch(requestId, crossmatch, "bloodbank01");

        assertForbidden(() -> controller.issue(requestId, "transfusion:crossmatch:manage",
                "bloodbank01", fullScope));
        controller.issue(requestId, "transfusion:issue:manage", "bloodbank01", fullScope);
        verify(service).issue(requestId, "bloodbank01");
    }

    @Test
    @DisplayName("输注执行：开始/完成/不良反应都必须 transfusion:execute")
    void executePermissions() {
        StartTransfusionReq start = new StartTransfusionReq("nurse01", "nurse02",
                new BigDecimal("36.5"), 80, 120, 80);
        assertForbidden(() -> controller.start(requestId, start, "transfusion:request:read",
                "nurse01", fullScope));
        controller.start(requestId, start, "transfusion:execute", "nurse01", fullScope);
        verify(service).start(requestId, start, "nurse01");

        CompleteTransfusionReq complete = new CompleteTransfusionReq(new BigDecimal("200"),
                new BigDecimal("36.8"), 82, 118, 78);
        assertForbidden(() -> controller.complete(recordId, complete,
                "transfusion:issue:manage", "nurse01", fullScope));
        controller.complete(recordId, complete, "transfusion:execute", "nurse01", fullScope);
        verify(service).complete(recordId, complete, "nurse01");

        ReactionReq reaction = new ReactionReq("MILD", "皮疹", "停药观察");
        assertForbidden(() -> controller.reaction(recordId, reaction,
                "transfusion:request:write", "nurse01", fullScope));
        controller.reaction(recordId, reaction, "transfusion:execute", "nurse01", fullScope);
        verify(service).reportReaction(recordId, reaction, "nurse01");

        when(service.board()).thenReturn(new BoardView(1, 1, 1, 1, 2, 0, 3));
        assertForbidden(() -> controller.board("transfusion:execute"));
        assertThat(controller.board("transfusion:request:read").data().totalToday())
                .isEqualTo(3);
    }
}
