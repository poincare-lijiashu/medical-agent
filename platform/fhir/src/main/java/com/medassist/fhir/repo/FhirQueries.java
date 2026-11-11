package com.medassist.fhir.repo;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 已校验的检索条件（解析与校验在 web 层完成，仓储只拼 SQL）。
 *
 * <p>{@code count}/{@code offset} 已按服务端上限收敛，SQL 不再二次判断。
 */
public final class FhirQueries {

    private FhirQueries() {
    }

    /** 患者引用：优先 UUID，其次院内患者号（两者互斥）。 */
    public record PatientRef(UUID patientId, String patientNo) {

        public static PatientRef ofId(UUID id) {
            return new PatientRef(id, null);
        }

        public static PatientRef ofNo(String patientNo) {
            return new PatientRef(null, patientNo);
        }
    }

    /** 日期闭区间（与 FHIR date 检索的常见语义一致：ge/le 可组合）。 */
    public record DateRange(LocalDate from, LocalDate to) {

        public static DateRange empty() {
            return new DateRange(null, null);
        }
    }

    /**
     * @param identifierSystem 已解析的标识系统；{@code null} 表示"院内患者号或任意跨系统标识"
     * @param identifierValue 标识值（必填才有此查询）
     */
    public record PatientQuery(
            UUID id,
            String identifierSystem,
            String identifierValue,
            String name,
            Boolean active,
            int count,
            int offset) {
    }

    public record EncounterQuery(
            UUID id,
            PatientRef patient,
            String status,
            DateRange date,
            int count,
            int offset) {
    }

    public record ObservationQuery(
            UUID id,
            PatientRef patient,
            String category,
            String status,
            DateRange date,
            int count,
            int offset) {
    }

    public record MedicationRequestQuery(
            UUID id,
            PatientRef patient,
            String status,
            DateRange date,
            int count,
            int offset) {
    }
}
