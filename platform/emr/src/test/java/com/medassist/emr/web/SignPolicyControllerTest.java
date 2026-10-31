package com.medassist.emr.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.emr.domain.EmrModel.SignPolicy;
import com.medassist.emr.service.SignPolicyService;
import com.medassist.emr.web.EmrDtos.SignPolicyReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签名策略接口权限矩阵（N-04）：只有病案质控/管理员可改，医师只能读。
 */
class SignPolicyControllerTest {

    private SignPolicyService service;
    private SignPolicyController controller;

    @BeforeEach
    void setUp() {
        service = mock(SignPolicyService.class);
        controller = new SignPolicyController(service);
    }

    @Test
    @DisplayName("配置：医师只有病历读写权，不能改签名策略")
    void upsertRejectedForDoctorPermission() {
        var req = new SignPolicyReq(1, 0L);

        assertThatThrownBy(() -> controller.upsert("PROGRESS_NOTE", req,
                "emr:record:read,emr:record:amend", "doctor01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(service, never()).upsert(anyString(), anyInt(), anyLong(), anyString());
    }

    @Test
    @DisplayName("配置：病案质控放行并透传操作人")
    void upsertAllowedForQcPermission() {
        var req = new SignPolicyReq(2, 3L);
        when(service.upsert("PROGRESS_NOTE", 2, 3L, "qc01"))
                .thenReturn(new SignPolicy("PROGRESS_NOTE", 2, 4L, "qc01", Instant.now()));

        var resp = controller.upsert("PROGRESS_NOTE", req, "emr:policy:manage", "qc01");

        assertThat(resp.data().requiredLevel()).isEqualTo(2);
        verify(service).upsert("PROGRESS_NOTE", 2, 3L, "qc01");
    }

    @Test
    @DisplayName("查询：只读权限可读取全部策略")
    void listAllowedForReadPermission() {
        when(service.list()).thenReturn(List.of(
                new SignPolicy("DISCHARGE_SUMMARY", 3, 0L, null, Instant.now())));

        var resp = controller.list("emr:record:read");

        assertThat(resp.data()).hasSize(1);
    }

    @Test
    @DisplayName("查询：无权限头返回 403")
    void listRequiresPermission() {
        assertThatThrownBy(() -> controller.list(null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
