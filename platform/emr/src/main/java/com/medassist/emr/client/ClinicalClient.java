package com.medassist.emr.client;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 临床域查询端口：病历域只取"就诊归属 + 入院时间"，不跨库、不复制住院数据。 */
public interface ClinicalClient {

    /**
     * 就诊的最小画像。
     *
     * @param patientId  该就诊归属的患者（门诊/住院都有；上游未返回时为 null）
     * @param admittedAt 入院时间；门诊或未取到时为 null
     */
    record VisitRef(UUID patientId, Instant admittedAt) {
    }

    /**
     * 按就诊号取就诊画像。
     *
     * <p>返回空表示：就诊不存在、无住院登记（门诊），或临床服务暂不可用。
     * 时限质控在无入院时间时跳过，绝不因为上游不可用而阻断病历书写。
     */
    Optional<VisitRef> visit(UUID visitId);

    /** 入院时间（门诊/不可用返回空）。 */
    default Optional<Instant> admittedAt(UUID visitId) {
        return visit(visitId).map(VisitRef::admittedAt).filter(java.util.Objects::nonNull);
    }
}
