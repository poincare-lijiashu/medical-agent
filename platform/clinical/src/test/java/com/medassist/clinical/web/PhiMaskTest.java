package com.medassist.clinical.web;

import com.medassist.clinical.domain.ClinicalModel.SurgeryBoardRow;
import com.medassist.clinical.domain.ClinicalModel.Visit;
import com.medassist.clinical.web.ClinicalDtos.AdmissionView;
import com.medassist.clinical.web.ClinicalDtos.InpatientAlert;
import com.medassist.clinical.web.TheatreDtos.SurgeryBoardView;
import com.medassist.common.security.DataScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHI 掩码出口：没有 phi:read 时患者号必须已被服务端改写，有权限时保持原值。
 */
class PhiMaskTest {

    private final UUID deptId = UUID.randomUUID();
    private final DataScope masked = DataScope.ofDepartment("nurse01", deptId, false);
    private final DataScope full = DataScope.ofDepartment("doctor01", deptId, true);

    private Visit visit() {
        return new Visit(UUID.randomUUID(), "V001", UUID.randomUUID(), "P20260101000001",
                deptId, "doctor01", null, "INPATIENT", Visit.IN_TREATMENT, LocalDate.now(), 0L);
    }

    private AdmissionView admission() {
        return new AdmissionView(UUID.randomUUID(), "A001", UUID.randomUUID(), "V001",
                UUID.randomUUID(), "P20260101000001", UUID.randomUUID(), "一病区",
                UUID.randomUUID(), "01", "doctor01", "IN_HOSPITAL", null, null, null);
    }

    @Test
    @DisplayName("就诊：无 phi:read 时患者号掩码，有权限时明文")
    void masksVisit() {
        assertThat(PhiMask.visit(visit(), masked).patientNo()).contains("****").endsWith("01");
        assertThat(PhiMask.visit(visit(), full).patientNo()).isEqualTo("P20260101000001");
    }

    @Test
    @DisplayName("住院登记与欠费条目：同样按权限掩码")
    void masksAdmissionAndAlert() {
        assertThat(PhiMask.admission(admission(), masked).patientNo()).contains("****");
        assertThat(PhiMask.admission(admission(), full).patientNo()).isEqualTo("P20260101000001");

        InpatientAlert alert = new InpatientAlert(UUID.randomUUID(), "A001", "P20260101000001",
                UUID.randomUUID(), "01", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ONE,
                new BigDecimal("9.00"));
        assertThat(PhiMask.alert(alert, masked).patientNo()).contains("****");
        assertThat(PhiMask.alert(alert, full).patientNo()).isEqualTo("P20260101000001");
    }

    @Test
    @DisplayName("排台看板：逐行掩码患者号，其余字段保持")
    void masksBoardRows() {
        SurgeryBoardRow row = new SurgeryBoardRow(UUID.randomUUID(), UUID.randomUUID(), "OR1",
                "一号手术间", "三楼", UUID.randomUUID(), "C001", UUID.randomUUID(), "V001",
                "P20260101000001", "ICD9-1", "阑尾切除术", "doctor01", "doctor02", "GA", "II",
                "I", "PLANNED", "SCHEDULED", 1, null, null, null, 0L, 3, "APPROVED");
        SurgeryBoardView view = new SurgeryBoardView(LocalDate.now(), 1, 0, List.of(row));

        SurgeryBoardView maskedView = PhiMask.board(view, masked);
        assertThat(maskedView.rows().get(0).patientNo()).contains("****");
        assertThat(maskedView.rows().get(0).operationName()).isEqualTo("阑尾切除术");
        assertThat(PhiMask.board(view, full).rows().get(0).patientNo()).isEqualTo("P20260101000001");
    }
}
