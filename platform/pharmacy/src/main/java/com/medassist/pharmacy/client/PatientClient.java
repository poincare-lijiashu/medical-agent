package com.medassist.pharmacy.client;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 患者主索引查询端口（依赖倒置）：药房不直连 EMPI 的库，也不信任前端传入的患者信息。
 *
 * <p>实现约定：患者不存在 → {@code PATIENT_NOT_FOUND}；EMPI 不可达 → {@code UPSTREAM_UNAVAILABLE}
 * （开方必须失败，不能"先开方后补患者"）。
 */
public interface PatientClient {

    /**
     * 患者最小投影（跨服务只传必要字段）。
     *
     * <p>{@code birthDate} 仅用于用药安全规则（儿童/老年禁忌），不参与展示。
     */
    record PatientRef(UUID id, String patientNo, String name, LocalDate birthDate) {
    }

    PatientRef requireActive(UUID patientId);
}
