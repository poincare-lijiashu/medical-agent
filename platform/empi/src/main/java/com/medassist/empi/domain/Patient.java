package com.medassist.empi.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 患者主索引实体（敏感字段为密文形态；对外展示前必须经 {@code FieldCipher#decrypt} 并按权限脱敏）。
 */
public record Patient(
        UUID id,
        String patientNo,
        String name,
        String gender,
        LocalDate birthDate,
        String idType,
        String idNoEnc,
        String idNoHash,
        String phoneEnc,
        String phoneHash,
        String addressEnc,
        String bloodType,
        String allergy,
        String status,
        UUID mergedInto,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_MERGED = "MERGED";
    public static final String STATUS_DECEASED = "DECEASED";

    public boolean active() {
        return STATUS_ACTIVE.equals(status);
    }
}
