package com.medassist.pharmacy.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.pharmacy.domain.PharmacyModel.Drug;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticPrescriber;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticRegister;
import com.medassist.pharmacy.repo.PharmacyRepository;
import com.medassist.pharmacy.web.NarcoticDtos.AmpouleReturnReq;
import com.medassist.pharmacy.web.NarcoticDtos.GrantPrescriberReq;
import com.medassist.pharmacy.web.NarcoticDtos.RevokePrescriberReq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 麻醉药品五专规则：处方权名单、撤销留原因、空安瓿回收数量、专册查询边界。
 */
class NarcoticServiceTest {

    private PharmacyRepository repo;
    private NarcoticService service;

    private final UUID patientId = UUID.randomUUID();
    private final UUID registerId = UUID.randomUUID();
    private final Drug morphine = new Drug(UUID.randomUUID(), "DRUG-MOR-10", "盐酸吗啡注射液",
            null, "10mg:1ml", "INJECTION", "支", "支", 1, new BigDecimal("5.50"), null, "B", 0,
            true, true, 0L, null, false, false);

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        service = new NarcoticService(repo, JsonMapper.builder().build());
    }

    private NarcoticPrescriber prescriber(boolean enabled, long version) {
        return new NarcoticPrescriber(UUID.randomUUID(), "doctor01", "MZ-001", "admin01",
                Instant.now(), enabled ? null : "admin01", enabled ? null : Instant.now(),
                enabled ? null : "离职", enabled, version);
    }

    private NarcoticRegister register(String status, int quantity, int returned) {
        return new NarcoticRegister(registerId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "RX20261101000001", patientId, "P1", morphine.id(),
                morphine.drugCode(), morphine.name(), morphine.spec(), quantity, "支", "M-1",
                "doctor01", "pharm01", "pharm02", Instant.now(), returned,
                returned > 0 ? Instant.now() : null, returned > 0 ? "pharm01" : null, status, 0L,
                Instant.now());
    }

    // ---------------------------------------------------------------- 处方权

    @Test
    @DisplayName("授予处方权：upsert 并回读，留审计")
    void grantUpsertsAndAudits() {
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.of(prescriber(true, 0L)));

        NarcoticPrescriber saved = service.grant(new GrantPrescriberReq(" doctor01 ", "MZ-001"),
                "admin01");

        verify(repo).upsertNarcoticPrescriber(any());
        assertThat(saved.prescriber()).isEqualTo("doctor01");
        verify(repo).logChange(eq("admin01"), eq("NARCOTIC_PRESCRIBER_GRANT"), eq("NARCOTIC"),
                eq("doctor01"), anyString());
    }

    @Test
    @DisplayName("撤销处方权：不存在 → 404")
    void revokeUnknownPrescriber() {
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke("doctor01",
                new RevokePrescriberReq(0L, "离职"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_PRESCRIBER_NOT_FOUND));
    }

    @Test
    @DisplayName("撤销处方权：已撤销 → 409")
    void revokeAlreadyRevoked() {
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.of(
                prescriber(false, 1L)));

        assertThatThrownBy(() -> service.revoke("doctor01",
                new RevokePrescriberReq(1L, "离职"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode())
                                .isEqualTo(ErrorCode.NARCOTIC_PRESCRIBER_STATE_INVALID));
        verify(repo, never()).revokeNarcoticPrescriber(anyString(), anyString(), anyString(),
                anyLong());
    }

    @Test
    @DisplayName("撤销处方权：版本已变化 → 409")
    void revokeStaleVersion() {
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.of(
                prescriber(true, 2L)));
        when(repo.revokeNarcoticPrescriber("doctor01", "离职", "admin01", 2L)).thenReturn(false);

        assertThatThrownBy(() -> service.revoke("doctor01",
                new RevokePrescriberReq(2L, "离职"), "admin01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("撤销处方权：成功并留原因")
    void revokeOk() {
        when(repo.findNarcoticPrescriber("doctor01"))
                .thenReturn(Optional.of(prescriber(true, 2L)))
                .thenReturn(Optional.of(prescriber(false, 3L)));
        when(repo.revokeNarcoticPrescriber("doctor01", "离职", "admin01", 2L)).thenReturn(true);

        NarcoticPrescriber saved = service.revoke("doctor01",
                new RevokePrescriberReq(2L, "离职"), "admin01");

        assertThat(saved.enabled()).isFalse();
        verify(repo).logChange(eq("admin01"), eq("NARCOTIC_PRESCRIBER_REVOKE"), eq("NARCOTIC"),
                eq("doctor01"), anyString());
    }

    @Test
    @DisplayName("处方权校验：无记录 / 已撤销 → 422")
    void requireLicensedRejects() {
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireLicensed("doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_PRESCRIBER_REQUIRED));

        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.of(
                prescriber(false, 1L)));
        assertThatThrownBy(() -> service.requireLicensed("doctor01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_PRESCRIBER_REQUIRED));
    }

    @Test
    @DisplayName("处方权校验：启用中放行")
    void requireLicensedAllows() {
        when(repo.findNarcoticPrescriber("doctor01")).thenReturn(Optional.of(
                prescriber(true, 0L)));

        assertThatCode(() -> service.requireLicensed("doctor01")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空安瓿门槛：存在未回收登记 → 422")
    void requireAmpouleReturnedRejects() {
        when(repo.countOutstandingNarcotic(patientId, morphine.id())).thenReturn(1L);

        assertThatThrownBy(() -> service.requireAmpouleReturned(patientId, morphine))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_AMPOULE_OUTSTANDING));
    }

    @Test
    @DisplayName("空安瓿门槛：无未回收登记 → 放行")
    void requireAmpouleReturnedAllows() {
        when(repo.countOutstandingNarcotic(patientId, morphine.id())).thenReturn(0L);

        assertThatCode(() -> service.requireAmpouleReturned(patientId, morphine))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 专册查询

    @Test
    @DisplayName("专册查询：区间倒置 / 超 92 天 / 非法状态 → 422")
    void registersRejectInvalidQuery() {
        LocalDate day = LocalDate.of(2026, 11, 1);

        assertThatThrownBy(() -> service.registers(day.plusDays(1), day, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.registers(day.minusDays(100), day, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        assertThatThrownBy(() -> service.registers(day, day, null, "UNKNOWN", null))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("专册查询：按状态过滤并限制上限")
    void registersPassFilters() {
        when(repo.listNarcoticRegisters(any(), any(), any(), eq("PENDING_RETURN"), eq(500)))
                .thenReturn(List.of(register(NarcoticRegister.PENDING_RETURN, 2, 0)));

        List<NarcoticRegister> rows = service.registers(null, null, null, "pending_return", 9999);

        assertThat(rows).hasSize(1);
        verify(repo).listNarcoticRegisters(any(), any(), any(), eq("PENDING_RETURN"), eq(500));
    }

    // ---------------------------------------------------------------- 空安瓿回收

    @Test
    @DisplayName("回收登记：不存在 → 404")
    void returnAmpouleUnknownRegister() {
        when(repo.lockNarcoticRegister(registerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.returnAmpoule(registerId,
                new AmpouleReturnReq(2, "pharm01"), "pharm01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_REGISTER_NOT_FOUND));
    }

    @Test
    @DisplayName("回收登记：已回收 → 422")
    void returnAmpouleAlreadyReturned() {
        when(repo.lockNarcoticRegister(registerId)).thenReturn(Optional.of(
                register(NarcoticRegister.RETURNED, 2, 2)));

        assertThatThrownBy(() -> service.returnAmpoule(registerId,
                new AmpouleReturnReq(2, "pharm01"), "pharm01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_AMPOULE_INVALID));
    }

    @Test
    @DisplayName("回收登记：数量与发药数量不一致 → 422")
    void returnAmpouleQuantityMismatch() {
        when(repo.lockNarcoticRegister(registerId)).thenReturn(Optional.of(
                register(NarcoticRegister.PENDING_RETURN, 2, 0)));

        assertThatThrownBy(() -> service.returnAmpoule(registerId,
                new AmpouleReturnReq(1, "pharm01"), "pharm01"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.NARCOTIC_AMPOULE_INVALID);
                    assertThat(e.getMessage()).contains("应回收 2");
                });
        verify(repo, never()).returnNarcoticAmpoule(any(), anyInt(), anyString(), anyLong());
    }

    @Test
    @DisplayName("回收登记：版本冲突 → 409")
    void returnAmpouleStaleVersion() {
        when(repo.lockNarcoticRegister(registerId)).thenReturn(Optional.of(
                register(NarcoticRegister.PENDING_RETURN, 2, 0)));
        when(repo.returnNarcoticAmpoule(registerId, 2, "pharm01", 0L)).thenReturn(false);

        assertThatThrownBy(() -> service.returnAmpoule(registerId,
                new AmpouleReturnReq(2, "pharm01"), "pharm01"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("回收登记：成功 → RETURNED 并留审计")
    void returnAmpouleOk() {
        when(repo.lockNarcoticRegister(registerId)).thenReturn(Optional.of(
                register(NarcoticRegister.PENDING_RETURN, 2, 0)));
        when(repo.returnNarcoticAmpoule(registerId, 2, "pharm01", 0L)).thenReturn(true);
        when(repo.findNarcoticRegister(registerId)).thenReturn(Optional.of(
                register(NarcoticRegister.RETURNED, 2, 2)));

        NarcoticRegister saved = service.returnAmpoule(registerId,
                new AmpouleReturnReq(2, "pharm01"), "pharm01");

        assertThat(saved.status()).isEqualTo(NarcoticRegister.RETURNED);
        verify(repo).logChange(eq("pharm01"), eq("NARCOTIC_AMPOULE_RETURN"), eq("NARCOTIC"),
                eq("RX20261101000001"), anyString());
    }
}
