package com.medassist.tech.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.tech.pacs.PacsDimseGateway;
import com.medassist.tech.repo.TechRepository;
import com.medassist.tech.web.PacsDimseDtos.FindStudiesReq;
import com.medassist.tech.web.PacsDimseDtos.MoveView;
import com.medassist.tech.web.PacsDimseDtos.StudyView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DIMSE 编排层单测：权限之外的业务规则（白名单、脱敏、状态判定、审计）。
 *
 * <p>网络协议本身由 dcm4che 负责；这里验证"我们自己的规则"不会因为配置/状态
 * 组合而放开（尤其是 C-MOVE 目的地白名单与 C-FIND 的 PHI 掩码）。
 */
class PacsDimseServiceTest {

    private static final String STUDY_UID = "1.2.840.113619.2.55.3.20261111.1";

    private PacsDimseGateway gateway;
    private TechRepository repo;
    private PacsDimseService service;

    @BeforeEach
    void setUp() {
        gateway = mock(PacsDimseGateway.class);
        repo = mock(TechRepository.class);
        service = new PacsDimseService(gateway, repo, new ObjectMapper(),
                new SimpleMeterRegistry());
        when(gateway.enabled()).thenReturn(true);
        when(gateway.remoteAe()).thenReturn("MEDAGENT");
        when(gateway.remoteHost()).thenReturn("127.0.0.1");
        when(gateway.maxStoreBytes()).thenReturn(1024L * 1024);
    }

    @Test
    @DisplayName("C-ECHO：成功写 OK 审计并返回远端 AE")
    void echoSuccess() {
        when(gateway.echo()).thenReturn(new PacsDimseGateway.EchoResult(
                "MEDAGENT", "127.0.0.1", 4242, 0, 12));

        var result = service.echo("tech01");

        assertThat(result.remoteAe()).isEqualTo("MEDAGENT");
        assertThat(result.statusCode()).isZero();
        verify(repo).insertPacsDimse(eq("tech01"), eq("ECHO"), eq("MEDAGENT"),
                eq("127.0.0.1"), eq(null), eq(null), eq("OK"), eq(0), anyInt(), anyString());
    }

    @Test
    @DisplayName("未启用时明确 503，而不是尝试连接或返回空结果")
    void echoDisabled() {
        when(gateway.enabled()).thenReturn(false);

        assertThatThrownBy(() -> service.echo("tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_PACS_NOT_CONFIGURED);
        verify(gateway, never()).echo();
    }

    @Test
    @DisplayName("C-MOVE：目的地不在白名单时拒绝，且不调用远端")
    void moveRejectsUnknownDestination() {
        when(gateway.allowsDestination("EVIL")).thenReturn(false);

        assertThatThrownBy(() -> service.move(STUDY_UID, "EVIL", "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(gateway, never()).moveStudy(any(), any());
    }

    @Test
    @DisplayName("C-MOVE：非 0 状态判失败、写 FAILED 审计并抛 503")
    void moveFailureStatus() {
        when(gateway.allowsDestination("MEDAGENT")).thenReturn(true);
        when(gateway.moveStudy(STUDY_UID, "MEDAGENT"))
                .thenReturn(new PacsDimseGateway.MoveResult(0xB000, 1, 2, 0));

        assertThatThrownBy(() -> service.move(STUDY_UID, "MEDAGENT", "tech01"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.TECH_PACS_UPSTREAM_FAILED);
        verify(repo).insertPacsDimse(eq("tech01"), eq("MOVE"), eq("MEDAGENT"),
                eq("127.0.0.1"), eq(1), eq(3), eq("FAILED"), eq(0xB000), anyInt(), anyString());
    }

    @Test
    @DisplayName("C-MOVE：成功返回子操作计数")
    void moveSuccess() {
        when(gateway.allowsDestination("MEDAGENT")).thenReturn(true);
        when(gateway.moveStudy(STUDY_UID, "MEDAGENT"))
                .thenReturn(new PacsDimseGateway.MoveResult(0, 2, 0, 0));

        MoveView view = service.move(STUDY_UID, "MEDAGENT", "tech01");

        assertThat(view.completed()).isEqualTo(2);
        assertThat(view.statusCode()).isZero();
    }

    @Test
    @DisplayName("C-FIND：无 phi:read 时患者号与姓名掩码")
    void findMasksPhi() {
        when(gateway.findStudies(any())).thenReturn(List.of(
                new PacsDimseGateway.StudyResult(STUDY_UID, "P00123456", "张三",
                        "A1", "20261111", "093000", "胸部CT", "CT", 3, 120)));

        List<StudyView> masked = service.findStudies(
                new FindStudiesReq(null, null, "A1", null, null, "CT"), "tech01", false);

        assertThat(masked.get(0).patientId()).isEqualTo("P00****56");
        assertThat(masked.get(0).patientName()).isEqualTo("张*");
        assertThat(masked.get(0).accessionNumber()).isEqualTo("A1");

        when(gateway.findStudies(any())).thenReturn(List.of(
                new PacsDimseGateway.StudyResult(STUDY_UID, "P00123456", "张三",
                        "A1", "20261111", "093000", "胸部CT", "CT", 3, 120)));
        List<StudyView> plain = service.findStudies(
                new FindStudiesReq(null, null, "A1", null, null, "CT"), "tech01", true);
        assertThat(plain.get(0).patientName()).isEqualTo("张三");
    }

    @Test
    @DisplayName("C-FIND：日期起止颠倒直接 400，不打扰 PACS")
    void findRejectsInvalidDateRange() {
        assertThatThrownBy(() -> service.findStudies(
                new FindStudiesReq(null, null, null,
                        java.time.LocalDate.parse("2026-11-30"),
                        java.time.LocalDate.parse("2026-11-01"), null),
                "tech01", true))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(gateway, never()).findStudies(any());
    }
}
