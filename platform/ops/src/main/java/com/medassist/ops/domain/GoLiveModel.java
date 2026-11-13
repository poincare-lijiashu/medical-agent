package com.medassist.ops.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 上线指挥台模型（§302）。
 *
 * <p>把分散在各域的"能不能上线"证据聚合成一张报告：实施就绪、灾备演练、
 * SLO 错误预算、重大事件、数据质量、变更冻结与 P1 工单。只读聚合，不写入业务数据。
 */
public final class GoLiveModel {

    private GoLiveModel() {
    }

    /** 单一门禁因子。 */
    public record Factor(
            String code,
            String name,
            String status,
            String detail,
            boolean blocking) {
    }

    /** 实施项目快照。 */
    public record ProjectSnapshot(
            UUID id,
            String projectNo,
            String hospitalName,
            String status,
            boolean ready,
            int score,
            int missingRequired,
            double trainingPassRate,
            int waivers) {
    }

    /** 上线报告。 */
    public record Report(
            Instant generatedAt,
            String scope,
            String environment,
            ProjectSnapshot project,
            List<Factor> factors,
            boolean ready,
            List<String> blockers,
            List<String> warnings,
            Map<String, Object> evidence) {
    }
}
