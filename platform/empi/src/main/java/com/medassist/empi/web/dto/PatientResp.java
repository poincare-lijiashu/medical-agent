package com.medassist.empi.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 患者对外视图。
 *
 * <p>{@code phiMasked=true} 表示敏感字段已脱敏（默认）；管理员经 reveal 参数取明文时
 * 该字段为 false，且调用会被审计。
 */
public record PatientResp(
        UUID id,
        String patientNo,
        String name,
        String gender,
        LocalDate birthDate,
        String idType,
        String idNo,
        String phone,
        String address,
        String bloodType,
        String allergy,
        String status,
        UUID mergedInto,
        long version,
        Instant createdAt,
        boolean phiMasked,
        List<CrosswalkItem> crosswalk
) {

    /** 跨系统标识。 */
    public record CrosswalkItem(String systemCode, String externalId) {
    }
}
