package com.medassist.pharmacy.service;

import com.medassist.pharmacy.client.ClinicalChargeClient;
import com.medassist.pharmacy.domain.PharmacyModel.Dispense;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import com.medassist.pharmacy.repo.PharmacyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0-D3 回归：发药计费异常必须由扫描器自动补偿，不能只靠人工 billing-retry。
 *
 * <p>覆盖"一条失败不阻断其余"与"异常态全部收敛"两个关键语义。
 */
class BillingCompensationWorkerTest {

    private PharmacyRepository repo;
    private ClinicalChargeClient client;
    private PharmacyBillingService billing;
    private BillingCompensationWorker worker;

    private final UUID rxMissing = UUID.randomUUID();
    private final UUID rxOk = UUID.randomUUID();
    private final UUID visitId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PharmacyRepository.class);
        client = mock(ClinicalChargeClient.class);
        billing = new PharmacyBillingService(repo, client, JsonMapper.builder().build());
        worker = new BillingCompensationWorker(billing, 50);
    }

    private Dispense abnormal(UUID id, UUID prescriptionId) {
        return new Dispense(id, "DP20261103000001", prescriptionId, "pharm01", null,
                Dispense.DISPENSED, new BigDecimal("10.00"), Instant.now(), 0L,
                Dispense.BILLING_CHARGE_FAILED);
    }

    private Prescription dispenseModeRx() {
        return new Prescription(rxOk, "RX20261103000001", UUID.randomUUID(),
                "P0001", visitId, "V0001", null, "doctor01", Prescription.DISPENSED,
                null, null, null, new BigDecimal("10.00"), 0L, Instant.now(),
                Prescription.BILLING_DISPENSE, null, null);
    }

    @Test
    @DisplayName("补偿扫描：一条失败不阻断其余（P0-D3 回归）")
    void retryAbandonedContinuesPastFailures() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(repo.listAbnormalBillingDisps(anyInt())).thenReturn(List.of(
                abnormal(bad, rxMissing), abnormal(good, rxOk)));
        // bad：处方不存在 → retry 抛异常被跳过；good：正常收敛为 CHARGED
        when(repo.findDispense(bad)).thenReturn(Optional.of(abnormal(bad, rxMissing)));
        when(repo.findPrescription(rxMissing)).thenReturn(Optional.empty());
        when(repo.findDispense(good))
                .thenReturn(Optional.of(abnormal(good, rxOk)))
                .thenReturn(Optional.of(new Dispense(good, "DP20261103000002", rxOk,
                        "pharm01", null, Dispense.DISPENSED, new BigDecimal("10.00"),
                        Instant.now(), 0L, Dispense.BILLING_CHARGED)));
        when(repo.findPrescription(rxOk)).thenReturn(Optional.of(dispenseModeRx()));
        when(repo.listDispenseItems(good)).thenReturn(List.of());
        when(client.charge(any(), any(), any(), anyString()))
                .thenReturn(new ClinicalChargeClient.Charged("C001", new BigDecimal("10.00")));
        when(repo.updateDispenseBillingStatus(eq(good), eq(Dispense.BILLING_CHARGED), anyList()))
                .thenReturn(true);

        int recovered = billing.retryAbandoned(50, "worker");

        assertThat(recovered).isEqualTo(1);
        verify(client, times(1)).charge(any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("补偿扫描：无异常单时不触达收费域")
    void retryAbandonedNoopWhenClean() {
        when(repo.listAbnormalBillingDisps(anyInt())).thenReturn(List.of());

        int recovered = billing.retryAbandoned(50, "worker");

        assertThat(recovered).isZero();
        verify(client, never()).charge(any(), any(), any(), anyString());
        verify(repo, never()).updateDispenseBillingStatus(any(), anyString(), anyList());
    }

    @Test
    @DisplayName("worker 单轮调用补偿（调度钩子连通）")
    void workerCycleCallsCompensation() {
        when(repo.listAbnormalBillingDisps(anyInt())).thenReturn(List.of());

        worker.compensate();

        verify(repo).listAbnormalBillingDisps(50);
    }
}
