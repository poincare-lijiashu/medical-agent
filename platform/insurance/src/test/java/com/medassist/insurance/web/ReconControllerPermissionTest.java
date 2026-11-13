package com.medassist.insurance.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.common.security.DataScope;
import com.medassist.insurance.service.SettlementReconService;
import com.medassist.insurance.web.InsuranceDtos.ReconComputeReq;
import com.medassist.insurance.web.InsuranceDtos.ReconResolveReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 对账权限（§291）：查询与生成/处理必须分离；窗口角色不应拿到 manage。
 */
class ReconControllerPermissionTest {

    private SettlementReconService service;
    private ReconController controller;

    @BeforeEach
    void setUp() {
        service = mock(SettlementReconService.class);
        controller = new ReconController(service);
    }

    @Test
    @DisplayName("生成批次：只读权限 403，manage 放行")
    void computeRequiresManage() {
        ReconComputeReq req = new ReconComputeReq("DAY", "2026-11-10", "2026-11-10");
        DataScope scope = DataScope.all("admin01", true);

        assertThatThrownBy(() -> controller.compute(req, "insurance:recon:read", "admin01", scope))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).compute(anyString(), anyString(), anyString(), anyString(), any());

        assertThatCode(() -> controller.compute(req, "insurance:recon:manage", "admin01", scope))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("处理差异：只读权限 403")
    void resolveRequiresManage() {
        assertThatThrownBy(() -> controller.resolve(UUID.randomUUID(),
                new ReconResolveReq("RESOLVED", "已与省局核对"), "insurance:recon:read", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("关闭批次：只读权限 403")
    void closeRequiresManage() {
        assertThatThrownBy(() -> controller.close(UUID.randomUUID(), "insurance:recon:read", "admin01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
