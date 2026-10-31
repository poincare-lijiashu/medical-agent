package com.medassist.insurance.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.insurance.service.InsuranceService;
import com.medassist.insurance.web.InsuranceDtos.GenerateBillReq;
import com.medassist.insurance.web.InsuranceDtos.ItemReq;
import com.medassist.insurance.web.InsuranceDtos.MemberReq;
import com.medassist.insurance.web.InsuranceDtos.VoidReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 医保接口权限矩阵（直接驱动 Controller，不启动 Spring 上下文）。
 *
 * <p>锁住授权边界：目录维护与参保登记需要 manage；结算生成需要 write；
 * 确认/作废需要独立的 confirm（V16 职责分离，窗口岗不得自行确认）。
 */
class InsuranceControllerPermissionTest {

    private InsuranceService service;

    private CatalogController catalogController;
    private MemberController memberController;
    private SettlementController settlementController;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(InsuranceService.class);
        catalogController = new CatalogController(service);
        memberController = new MemberController(service);
        settlementController = new SettlementController(service);
    }

    @Test
    @DisplayName("目录维护：只有 insurance:catalog:manage 能建目录项")
    void catalogCreateRequiresManage() {
        var req = new ItemReq("I-1", "示例项目", "SERVICE", "A",
                BigDecimal.ZERO, null, true, null);

        assertThatThrownBy(() -> catalogController.create(req, "insurance:settlement:read", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).createItem(any(), anyString());
    }

    @Test
    @DisplayName("参保登记：需要 insurance:member:manage")
    void memberRegisterRequiresManage() {
        var req = new MemberReq(id, "EMPLOYEE", "LOCAL", null,
                BigDecimal.ZERO, new BigDecimal("0.8"), new BigDecimal("300000"), null, null, null);

        assertThatThrownBy(() -> memberController.register(req, "insurance:settlement:read", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).registerMember(any(), anyString());
    }

    @Test
    @DisplayName("结算生成：只读权限不能出清单")
    void generateRequiresWrite() {
        var req = new GenerateBillReq(id);

        assertThatThrownBy(() -> settlementController.generate(req,
                "insurance:settlement:read", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).generate(any(), anyString());
    }

    @Test
    @DisplayName("结算确认：只有 confirm 权限可确认（write 不够）")
    void confirmRequiresConfirmPermission() {
        var voidReq = new VoidReq("test");

        assertThatThrownBy(() -> settlementController.confirm(id,
                "insurance:settlement:read,insurance:settlement:write", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> settlementController.voidBill(id, voidReq,
                "insurance:settlement:write", "cashier01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).confirm(any(), anyString());
        verify(service, never()).voidBill(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("结算确认：持有 confirm 权限放行")
    void confirmAllowedWithConfirmPermission() {
        settlementController.confirm(id, "insurance:settlement:confirm", "yb01");

        verify(service).confirm(id, "yb01");
    }

    @Test
    @DisplayName("结算查询：只读权限放行")
    void detailAllowedForReadPermission() {
        settlementController.detail(id, "insurance:settlement:read");

        verify(service).detail(id);
    }

    @Test
    @DisplayName("权限头缺失：提示重新登录（403）")
    void emptyPermissionsHeader() {
        assertThatThrownBy(() -> settlementController.detail(id, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
