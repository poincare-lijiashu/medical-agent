package com.medassist.emr.client;

import java.time.LocalDate;
import java.util.UUID;

/** 患者主索引查询端口（依赖倒置）：病历不直连 EMPI 数据库。 */
public interface PatientClient {

    /**
     * 患者最小必要字段（病案首页需要姓名/性别/出生日期；证件号、联系方式不出域）。
     *
     * @param patientSex EMPI 的 MALE / FEMALE / UNKNOWN（可能为空，历史数据）
     */
    record PatientRef(UUID id, String patientNo, String name, String patientSex,
                      LocalDate birthDate) {
    }

    /**
     * 可书写病历的患者：必须存在且非 MERGED（已合并的档案不再接受新业务）。
     *
     * <p>注意：**DECEASED 也允许**——死亡病例必须能生成病案首页（第六轮 MF-06）。
     */
    PatientRef requireExists(UUID patientId);

    /**
     * 可建立"活动业务"的患者：存在且状态为 ACTIVE。
     *
     * <p>仍用于需要患者在场/可随访的场景；病案首页请改用 {@link #requireExists}。
     */
    PatientRef requireActive(UUID patientId);
}
