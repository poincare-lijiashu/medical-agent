package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.service.RecordService;
import com.medassist.emr.web.EmrDtos.CreateRecordReq;
import com.medassist.emr.web.EmrDtos.ReviewReq;
import com.medassist.emr.web.EmrDtos.SignReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 病历接口权限矩阵（直接驱动 Controller，不启动 Spring 上下文）。
 *
 * <p>锁住 V13/V14 的授权边界：书写/提交/签名/归档/修订归医师；
 * 病案修改审批是独立权限点（emr:record:approve），医师不得自审；
 * 只读权限不得触发任何写操作。
 */
class EmrControllerPermissionTest {

    private RecordService service;
    private RecordController controller;

    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = mock(RecordService.class);
        controller = new RecordController(service);
    }

    @Test
    @DisplayName("书写：只读权限不能建病历")
    void createRequiresCreatePermission() {
        var req = new CreateRecordReq(id, null, null, null, "TPL-ADMISSION", null, Map.of());

        assertThatThrownBy(() -> controller.create(req, "emr:record:read", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).create(any(), anyString());
    }

    @Test
    @DisplayName("签名：只读权限不能签名")
    void signRequiresSignPermission() {
        var req = new SignReq("AUTHOR");

        assertThatThrownBy(() -> controller.sign(id, req, "emr:record:read", "nurse01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).sign(any(), any(), anyString());
    }

    @Test
    @DisplayName("归档：修订权限不等于归档权限")
    void archiveRequiresArchivePermission() {
        assertThatThrownBy(() -> controller.archive(id, "emr:record:amend", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).archive(any(), anyString());
    }

    @Test
    @DisplayName("病案修改审批：医师只有修订权，不能审批（需 emr:record:approve）")
    void approveRequiresApprovePermission() {
        var req = new ReviewReq("同意");

        assertThatThrownBy(() -> controller.approveChange(id, id, req,
                "emr:record:amend", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).approveChange(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("病案修改审批：质控审批权限放行")
    void approveAllowedForQcPermission() {
        var req = new ReviewReq("同意");

        controller.approveChange(id, id, req, "emr:record:approve", "qc01");

        verify(service).approveChange(id, id, req, "qc01");
    }

    @Test
    @DisplayName("只读查询：emr:record:read 放行")
    void detailAllowedForReadPermission() {
        controller.detail(id, "emr:record:read");

        verify(service).detail(id);
    }

    @Test
    @DisplayName("权限头缺失：提示重新登录（403）")
    void emptyPermissionsHeader() {
        assertThatThrownBy(() -> controller.detail(id, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
