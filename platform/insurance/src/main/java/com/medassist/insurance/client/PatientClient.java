package com.medassist.insurance.client;

import java.util.UUID;

/** 患者主索引查询端口（依赖倒置）：医保域不直连 EMPI 数据库。 */
public interface PatientClient {

    record PatientRef(UUID id, String patientNo, String name) {
    }

    PatientRef requireActive(UUID patientId);
}
