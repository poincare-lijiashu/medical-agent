package com.medassist.empi.web;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.empi.service.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 患者明文明读的授权矩阵（P2-6）。
 *
 * <p>为什么单独测：{@code ActorGuardFilter} 只拦写方法，{@code reveal=true} 是读接口，
 * 若不在这里拦住"system 兜底"，审计里就会出现无主责的 PHI 访问记录。
 */
class PatientControllerPermissionTest {

    private static final String READ = "empi:patient:read";
    private static final String READ_PHI = "empi:patient:read_phi";
    private static final String BOTH = READ + "," + READ_PHI;

    private final UUID patientId = UUID.randomUUID();
    private PatientService service;
    private PatientController controller;

    @BeforeEach
    void setUp() {
        service = mock(PatientService.class);
        controller = new PatientController(service);
    }

    @Test
    @DisplayName("明文明读缺 X-Actor：422，且不触达服务层")
    void revealWithoutActorIsRejected() {
        assertThatThrownBy(() -> controller.detail(patientId, true, BOTH, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(service, never()).detail(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("明文明读把 actor 兜底成 system：同样拒绝（审计不能无主责）")
    void revealWithSystemActorIsRejected() {
        assertThatThrownBy(() -> controller.detail(patientId, true, BOTH, " system "))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verify(service, never()).detail(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("明文明读没有 read_phi 权限：403")
    void revealWithoutPhiPermissionIsForbidden() {
        assertThatThrownBy(() -> controller.detail(patientId, true, READ, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(service, never()).detail(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("有权限 + 真实操作人：透传 reveal 与 actor")
    void revealWithRealActorPasses() {
        when(service.detail(patientId, true, "doctor01")).thenReturn(null);

        controller.detail(patientId, true, BOTH, "doctor01");

        verify(service).detail(patientId, true, "doctor01");
    }

    @Test
    @DisplayName("脱敏读取不要求 X-Actor（只读脱敏字段不算敏感访问）")
    void maskedReadWithoutActorPasses() {
        when(service.detail(patientId, false, null)).thenReturn(null);

        controller.detail(patientId, false, READ, null);

        verify(service).detail(patientId, false, null);
    }

    @Test
    @DisplayName("完全无权限：403（不因 reveal=false 而放行）")
    void readWithoutPermissionIsForbidden() {
        assertThatThrownBy(() -> controller.detail(patientId, false, null, "doctor01"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verify(service, never()).detail(any(), any(Boolean.class), any());
    }
}
