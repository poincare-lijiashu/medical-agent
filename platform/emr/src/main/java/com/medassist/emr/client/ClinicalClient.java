package com.medassist.emr.client;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 临床域查询端口：病历域只取"入院时间"，不跨库、不复制住院数据。 */
public interface ClinicalClient {

    /**
     * 就诊号对应的入院时间。
     *
     * <p>返回空表示：门诊就诊、无住院登记，或临床服务暂不可用。
     * 时限质控在无入院时间时跳过，绝不因为上游不可用而阻断病历书写。
     */
    Optional<Instant> admittedAt(UUID visitId);
}
