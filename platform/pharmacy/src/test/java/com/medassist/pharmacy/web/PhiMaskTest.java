package com.medassist.pharmacy.web;

import com.medassist.common.security.DataScope;
import com.medassist.pharmacy.domain.PharmacyModel.NarcoticRegister;
import com.medassist.pharmacy.domain.PharmacyModel.Prescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 药房 PHI 掩码：麻醉药品专册与处方列表在没有 phi:read 时不得返回完整患者号。
 */
class PhiMaskTest {

    private final DataScope masked = DataScope.all("pharm01", false);
    private final DataScope full = DataScope.all("pharm01", true);

    private Prescription prescription() {
        return new Prescription(UUID.randomUUID(), "RX001", UUID.randomUUID(),
                "P20260101000001", UUID.randomUUID(), "V001", UUID.randomUUID(), "doctor01",
                Prescription.PENDING_REVIEW, null, null, null, null, 0L, Instant.now(),
                Prescription.BILLING_ORDER, null, LocalDate.now());
    }

    private NarcoticRegister register() {
        return new NarcoticRegister(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "RX001", UUID.randomUUID(), "P20260101000001",
                UUID.randomUUID(), "D001", "吗啡注射液", "1ml:10mg", 2, "支", "B001",
                "doctor01", "pharm01", "pharm02", Instant.now(), 0, null, null,
                NarcoticRegister.PENDING_RETURN, 0L, Instant.now());
    }

    @Test
    @DisplayName("处方列表：无 phi:read 时掩码，有权限时明文")
    void masksPrescription() {
        assertThat(PhiMask.prescription(prescription(), masked).patientNo())
                .contains("****").endsWith("01");
        assertThat(PhiMask.prescription(prescription(), full).patientNo()).isEqualTo("P20260101000001");
    }

    @Test
    @DisplayName("麻醉药品专册：无 phi:read 时掩码，其余字段（药品/数量）保持")
    void masksNarcoticRegister() {
        NarcoticRegister maskedRow = PhiMask.narcotic(register(), masked);

        assertThat(maskedRow.patientNo()).contains("****").endsWith("01");
        assertThat(maskedRow.drugName()).isEqualTo("吗啡注射液");
        assertThat(maskedRow.quantity()).isEqualTo(2);
        assertThat(PhiMask.narcotic(register(), full).patientNo()).isEqualTo("P20260101000001");
    }

    @Test
    @DisplayName("处方详情视图：内层处方同样按权限掩码")
    void masksPrescriptionView() {
        var view = new PharmacyDtos.PrescriptionView(prescription(), List.of());

        assertThat(PhiMask.view(view, masked).prescription().patientNo()).contains("****");
        assertThat(PhiMask.view(view, full).prescription().patientNo()).isEqualTo("P20260101000001");
    }
}
