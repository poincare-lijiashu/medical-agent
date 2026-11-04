package com.medassist.clinical.client;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 患者主索引查询端口（依赖倒置）：clinical 不直连 EMPI 的库，也不信任前端传入的患者信息。
 *
 * <p>实现约定：
 * <ul>
 *   <li>患者不存在 → 抛 {@code PATIENT_NOT_FOUND}；</li>
 *   <li>EMPI 不可达/超时 → 抛 {@code UPSTREAM_UNAVAILABLE}（挂号必须失败，不能"先挂号后补人"）；</li>
 *   <li>返回的 {@code patientNo}/{@code name} 只用于快照与打印，不作为后续授权依据。</li>
 * </ul>
 */
public interface PatientClient {

    /** 患者最小投影（跨服务只传必要字段，避免把 PHI 扩散到临床域）。 */
    record PatientRef(UUID id, String patientNo, String name) {
    }

    /** 体检/病案所需的患者人口学快照（性别与出生日期，不含证件号与联系方式）。 */
    record PatientProfile(UUID id, String patientNo, String name, String gender,
                          LocalDate birthDate) {
    }

    PatientRef requireActive(UUID patientId);

    /** 默认实现保持旧客户端兼容；EMPI 客户端会返回真实性别与出生日期。 */
    default PatientProfile requireProfile(UUID patientId) {
        PatientRef ref = requireActive(patientId);
        return new PatientProfile(ref.id(), ref.patientNo(), ref.name(), null, null);
    }
}
