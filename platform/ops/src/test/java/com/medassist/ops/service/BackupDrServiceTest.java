package com.medassist.ops.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.ops.domain.OpsModel.BackupPolicy;
import com.medassist.ops.domain.OpsModel.BackupRun;
import com.medassist.ops.domain.OpsModel.DrDrill;
import com.medassist.ops.domain.OpsModel.DrDrillStep;
import com.medassist.ops.repo.BackupDrRepository;
import com.medassist.ops.repo.OpsRepository;
import com.medassist.ops.web.OpsDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 备份与灾备演练：幂等、步骤门槛、RTO/RPO 与四眼复核（§297）。
 */
class BackupDrServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T02:00:00Z");
    private static final UUID DRILL_ID = UUID.randomUUID();

    private BackupDrRepository repo;
    private BackupDrService service;

    @BeforeEach
    void setUp() {
        repo = mock(BackupDrRepository.class);
        OpsRepository opsRepository = mock(OpsRepository.class);
        service = new BackupDrService(repo, opsRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------ 备份策略

    @Test
    @DisplayName("备份策略：范围 ALL 不能带范围引用")
    void policyScopeAllRejectsRef() {
        OpsDtos.BackupPolicyReq req = policyReq("ALL", "clinical");

        assertThatThrownBy(() -> service.upsertPolicy(req, "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_BACKUP_POLICY_INVALID);
    }

    @Test
    @DisplayName("备份策略：指定范围类型必须带引用")
    void policyComponentRequiresRef() {
        OpsDtos.BackupPolicyReq req = policyReq("COMPONENT", " ");

        assertThatThrownBy(() -> service.upsertPolicy(req, "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_BACKUP_POLICY_INVALID);
    }

    @Test
    @DisplayName("备份策略：保存后返回库内最新记录")
    void policySaved() {
        when(repo.findPolicyByCode("pg-full")).thenReturn(Optional.of(policy()));
        BackupPolicy saved = service.upsertPolicy(policyReq("ALL", null), "ops01", null, null);

        assertThat(saved.code()).isEqualTo("pg-full");
        verify(repo).upsertPolicy(any());
    }

    // ------------------------------------------------------------------ 备份运行

    @Test
    @DisplayName("备份运行：同一幂等键重复上报相同结果直接返回原记录")
    void reportRunIdempotentReplay() {
        BackupRun existing = run("SUCCESS", "k-1", 0);
        when(repo.findPolicyByCode("pg-full")).thenReturn(Optional.of(policy()));
        when(repo.findRunByIdempotency("k-1")).thenReturn(Optional.of(existing));

        BackupRun result = service.reportRun(runReq("SUCCESS", "k-1"), "backup01", null, null);

        assertThat(result.id()).isEqualTo(existing.id());
        verify(repo, never()).insertRun(any());
        verify(repo, never()).finalizeRun(any(), anyLong(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    @DisplayName("备份运行：幂等键已结束为失败时不允许改写为成功")
    void reportRunConflict() {
        when(repo.findPolicyByCode("pg-full")).thenReturn(Optional.of(policy()));
        when(repo.findRunByIdempotency("k-2")).thenReturn(Optional.of(run("FAILED", "k-2", 0)));

        assertThatThrownBy(() -> service.reportRun(runReq("SUCCESS", "k-2"),
                "backup01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_BACKUP_RUN_CONFLICT);
    }

    @Test
    @DisplayName("备份运行：RUNNING 可被同一幂等键终结一次")
    void reportRunFinalize() {
        BackupRun running = run("RUNNING", "k-3", 0);
        when(repo.findPolicyByCode("pg-full")).thenReturn(Optional.of(policy()));
        when(repo.findRunByIdempotency("k-3")).thenReturn(Optional.of(running));
        when(repo.finalizeRun(eq(running.id()), eq(0L), eq("SUCCESS"), any(), any(), any(),
                any(), any())).thenReturn(true);
        when(repo.findRun(running.id())).thenReturn(Optional.of(run("SUCCESS", "k-3", 1)));

        BackupRun result = service.reportRun(runReq("SUCCESS", "k-3"), "backup01", null, null);

        assertThat(result.status()).isEqualTo("SUCCESS");
        verify(repo).finalizeRun(eq(running.id()), eq(0L), eq("SUCCESS"), any(), any(), any(),
                any(), any());
    }

    @Test
    @DisplayName("备份运行：成功上报缺少校验和直接拒绝")
    void reportRunSuccessRequiresChecksum() {
        when(repo.findPolicyByCode("pg-full")).thenReturn(Optional.of(policy()));
        OpsDtos.BackupRunReq req = new OpsDtos.BackupRunReq("pg-full", "FULL", "SUCCESS",
                "k-4", NOW.minusSeconds(600), NOW, 1024L, " ", "s3://backup/1",
                null);

        assertThatThrownBy(() -> service.reportRun(req, "backup01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_BACKUP_RUN_INVALID);
    }

    // ------------------------------------------------------------------ 演练

    @Test
    @DisplayName("演练：未提供步骤时按默认六步模板生成")
    void createDrillUsesDefaultSteps() {
        when(repo.nextDrillNo()).thenReturn("DR202611130001");
        when(repo.findDrill(any())).thenReturn(Optional.of(drill("PLANNED", "ops01", 120, 15)));

        service.createDrill(new OpsDtos.DrDrillCreateReq("季度灾备演练", "ALL", "PROD",
                null, 120, 15, null, "季度例行"), "ops01", null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DrDrillStep>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).insertSteps(captor.capture());
        assertThat(captor.getValue()).hasSize(6);
        assertThat(captor.getValue()).allMatch(s -> s.required() && "PENDING".equals(s.status()));
    }

    @Test
    @DisplayName("演练：执行人不能自己复核通过")
    void completeRejectsSelfVerify() {
        when(repo.findDrill(DRILL_ID)).thenReturn(Optional.of(drill("RUNNING", "ops01", 120, 15)));

        assertThatThrownBy(() -> service.complete(DRILL_ID, completeReq(30, 5),
                "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_DR_SELF_VERIFY);
    }

    @Test
    @DisplayName("演练：必填步骤未全部通过时不能判定通过")
    void completeRequiresAllSteps() {
        when(repo.findDrill(DRILL_ID)).thenReturn(Optional.of(drill("RUNNING", "ops01", 120, 15)));
        when(repo.countStepsNotPassed(DRILL_ID)).thenReturn(2L);

        assertThatThrownBy(() -> service.complete(DRILL_ID, completeReq(30, 5),
                "ops02", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_DR_STEPS_INCOMPLETE);
    }

    @Test
    @DisplayName("演练：实测 RTO 超过目标不能判定通过")
    void completeRejectsRtoExceeded() {
        when(repo.findDrill(DRILL_ID)).thenReturn(Optional.of(drill("RUNNING", "ops01", 120, 15)));
        when(repo.countStepsNotPassed(DRILL_ID)).thenReturn(0L);

        assertThatThrownBy(() -> service.complete(DRILL_ID, completeReq(121, 5),
                "ops02", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_DR_TARGET_EXCEEDED);
    }

    @Test
    @DisplayName("演练：步骤全部通过且 RTO/RPO 达标时，由第二人复核通过")
    void completePasses() {
        DrDrill drill = drill("RUNNING", "ops01", 120, 15);
        when(repo.findDrill(DRILL_ID)).thenReturn(Optional.of(drill));
        when(repo.countStepsNotPassed(DRILL_ID)).thenReturn(0L);
        when(repo.complete(eq(DRILL_ID), eq(drill.version()), eq(30), eq(5), eq(5), eq(true),
                any(), any(), eq("ops02"), eq(NOW))).thenReturn(true);

        service.complete(DRILL_ID, completeReq(30, 5), "ops02", null, null);

        verify(repo).complete(eq(DRILL_ID), eq(drill.version()), eq(30), eq(5), eq(5), eq(true),
                eq("恢复日志与冒烟截图已归档"), any(), eq("ops02"), eq(NOW));
    }

    @Test
    @DisplayName("演练：失败必须写原因")
    void failRequiresReason() {
        when(repo.findDrill(DRILL_ID)).thenReturn(Optional.of(drill("RUNNING", "ops01", 120, 15)));

        assertThatThrownBy(() -> service.fail(DRILL_ID, " ", "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_REASON_REQUIRED);
    }

    @Test
    @DisplayName("演练：步骤通过必须带证据")
    void stepPassRequiresEvidence() {
        when(repo.findDrill(DRILL_ID)).thenReturn(Optional.of(drill("RUNNING", "ops01", 120, 15)));
        when(repo.listSteps(DRILL_ID)).thenReturn(List.of(new DrDrillStep(UUID.randomUUID(),
                DRILL_ID, 1, "数据恢复", "PENDING", true, null, null, null, null, null)));

        assertThatThrownBy(() -> service.updateStep(DRILL_ID, 1,
                new OpsDtos.DrDrillStepUpdateReq("PASSED", " ", null), "ops01", null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.OPS_DR_EVIDENCE_REQUIRED);
    }

    @Test
    @DisplayName("门禁：没有有效期内已复核记录时 eligible=false")
    void gateWithoutDrill() {
        when(repo.findLatestPassed(eq("platform-clinical"), eq("PROD"), any()))
                .thenReturn(Optional.empty());

        var result = service.gate("platform-clinical", "PROD", 90);

        assertThat(result.get("eligible")).isEqualTo(false);
        assertThat(result.get("reason")).asString().contains("90 天");
    }

    // ------------------------------------------------------------------ 构造

    private static OpsDtos.BackupPolicyReq policyReq(String scopeType, String scopeRef) {
        return new OpsDtos.BackupPolicyReq("pg-full", "核心库全量备份", scopeType, scopeRef,
                "FULL", "0 2 * * *", 30, "AES256", "s3://backup/pg", 15, "ops01", "ACTIVE");
    }

    private static BackupPolicy policy() {
        return new BackupPolicy(UUID.randomUUID(), "pg-full", "核心库全量备份", "ALL", null,
                "FULL", "0 2 * * *", 30, "AES256", "s3://backup/pg", 15, "ops01",
                "ACTIVE", null, 0, NOW, NOW);
    }

    private static BackupRun run(String status, String key, long version) {
        return new BackupRun(UUID.randomUUID(), "BK2026111300001", UUID.randomUUID(), "pg-full",
                "FULL", status, key, status.equals("SUCCESS") ? 1024L : null,
                status.equals("SUCCESS") ? "sha256:abc" : null,
                status.equals("SUCCESS") ? "s3://backup/1" : null,
                NOW.minusSeconds(600), status.equals("RUNNING") ? null : NOW, null,
                "backup01", version, NOW, NOW);
    }

    private static OpsDtos.BackupRunReq runReq(String status, String key) {
        return new OpsDtos.BackupRunReq("pg-full", "FULL", status, key,
                NOW.minusSeconds(600), NOW, 1024L, "sha256:abc", "s3://backup/1", null);
    }

    private static DrDrill drill(String status, String executor, int rtoTarget, int rpoTarget) {
        return new DrDrill(DRILL_ID, "DR202611130001", "季度灾备演练", "ALL", "PROD", status,
                null, rtoTarget, null, rpoTarget, null, null, false, executor, null, null,
                null, null, status.equals("PLANNED") ? null : NOW.minusSeconds(3600), null,
                3, NOW, NOW, List.of());
    }

    private static OpsDtos.DrDrillCompleteReq completeReq(int rtoActual, int rpoActual) {
        return new OpsDtos.DrDrillCompleteReq(rtoActual, rpoActual, null, true,
                "恢复日志与冒烟截图已归档", "季度演练");
    }
}
