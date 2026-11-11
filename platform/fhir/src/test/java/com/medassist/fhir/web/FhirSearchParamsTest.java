package com.medassist.fhir.web;

import com.medassist.common.exception.BusinessException;
import com.medassist.fhir.config.FhirProperties;
import com.medassist.fhir.repo.FhirQueries.DateRange;
import com.medassist.fhir.repo.FhirQueries.PatientRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 检索参数解析与分页边界：非法输入必须明确拒绝，不能静默忽略。 */
class FhirSearchParamsTest {

    @Test
    @DisplayName("date：ge + le 组合成闭区间，eq 收敛到同一天")
    void dateRange() {
        DateRange range = FhirSearchParams.date(List.of("ge2026-01-01", "le2026-01-31"));
        assertThat(range.from()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(range.to()).isEqualTo(LocalDate.parse("2026-01-31"));

        DateRange single = FhirSearchParams.date(List.of("eq2026-02-01"));
        assertThat(single.from()).isEqualTo(LocalDate.parse("2026-02-01"));
        assertThat(single.to()).isEqualTo(LocalDate.parse("2026-02-01"));

        DateRange strict = FhirSearchParams.date(List.of("gt2026-01-01"));
        assertThat(strict.from()).isEqualTo(LocalDate.parse("2026-01-02"));
        assertThat(strict.to()).isNull();

        assertThat(FhirSearchParams.date(List.of())).isNull();
    }

    @Test
    @DisplayName("date：格式错误、超过两个值、带时分秒都拒绝")
    void invalidDate() {
        assertThatThrownBy(() -> FhirSearchParams.date(List.of("2026-1-1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> FhirSearchParams.date(
                List.of("ge2026-01-01", "le2026-01-31", "eq2026-02-01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多");
        assertThatThrownBy(() -> FhirSearchParams.date(List.of("ge2026-01-01T08:00:00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    @DisplayName("identifier：支持裸值、患者号系统、跨系统标识与 urn 前缀")
    void identifier() {
        assertThat(FhirSearchParams.identifier("P001").system()).isNull();
        assertThat(FhirSearchParams.identifier("P001").value()).isEqualTo("P001");

        FhirSearchParams.Identifier patientNo =
                FhirSearchParams.identifier("urn:medassist:patient-no|P001");
        assertThat(patientNo.system()).isEqualTo("urn:medassist:patient-no");

        FhirSearchParams.Identifier crosswalk = FhirSearchParams.identifier("HIS|H0001");
        assertThat(crosswalk.system()).isEqualTo("HIS");
        assertThat(crosswalk.value()).isEqualTo("H0001");

        FhirSearchParams.Identifier urnCrosswalk =
                FhirSearchParams.identifier("urn:medassist:crosswalk:LIS|L0001");
        assertThat(urnCrosswalk.system()).isEqualTo("LIS");

        assertThatThrownBy(() -> FhirSearchParams.identifier("HIS|"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("patient：Patient/{uuid}、裸 uuid、患者号三种写法都能识别")
    void patientRef() {
        UUID id = UUID.randomUUID();
        PatientRef byReference = FhirSearchParams.patient("Patient/" + id);
        assertThat(byReference.patientId()).isEqualTo(id);
        assertThat(byReference.patientNo()).isNull();

        assertThat(FhirSearchParams.patient(id.toString()).patientId()).isEqualTo(id);

        PatientRef byNo = FhirSearchParams.patient("P20261111001");
        assertThat(byNo.patientId()).isNull();
        assertThat(byNo.patientNo()).isEqualTo("P20261111001");

        assertThat(FhirSearchParams.patient("")).isNull();
    }

    @Test
    @DisplayName("_id 与 status：非法值明确 400")
    void invalidIdAndStatus() {
        assertThatThrownBy(() -> FhirSearchParams.uuid("not-a-uuid", "_id"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UUID");
        assertThatThrownBy(() -> FhirSearchParams.status("bogus",
                FhirSearchParams.ENCOUNTER_STATUS, "Encounter"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持");
        assertThat(FhirSearchParams.status("in-progress",
                FhirSearchParams.ENCOUNTER_STATUS, "Encounter")).isEqualTo("in-progress");
    }

    @Test
    @DisplayName("分页：默认 20，超过上限截断到 100，非法 _count/_offset 拒绝")
    void paging() {
        FhirProperties properties = new FhirProperties();
        assertThat(properties.pageSize(null)).isEqualTo(20);
        assertThat(properties.pageSize(500)).isEqualTo(100);
        assertThatThrownBy(() -> properties.pageSize(0)).isInstanceOf(BusinessException.class);

        assertThat(properties.offset(null)).isZero();
        assertThatThrownBy(() -> properties.offset(-1)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> properties.offset(10_001)).isInstanceOf(BusinessException.class);
    }
}
