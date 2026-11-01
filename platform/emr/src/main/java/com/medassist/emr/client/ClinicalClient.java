package com.medassist.emr.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
     * 病案首页数据源快照。
     *
     * <p>一次调用取齐住院信息、科室/病区/床位名称、出院诊断与费用汇总，
     * 避免多次调用之间数据漂移导致首页自相矛盾。
     *
     * @param admissionStatus 最近一次住院登记状态；门诊就诊为 null
     * @param feeSummary      收费项目类别 → 金额（已退费单据不计入）
     */
    record HomepageSource(UUID visitId, String visitNo, String visitType, String visitStatus,
                          UUID patientId, String patientNo, UUID deptId, String deptName,
                          UUID admissionId, String admissionNo, String admissionStatus,
                          Instant admittedAt, Instant dischargedAt,
                          UUID wardId, String wardName, UUID bedId, String bedNo,
                          String attendingDoctor, List<Diagnosis> diagnoses,
                          Map<String, BigDecimal> feeSummary, BigDecimal totalAmount) {
    }

    /** 诊断快照项。 */
    record Diagnosis(String code, String name, boolean primary) {
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

    /**
     * 病案首页数据源（必须成功，不能 fail-open）。
     *
     * <p>与时限质控不同：没有数据源就不能生成首页，宁可 503 也不能编造金额或诊断。
     *
     * @throws com.medassist.common.exception.BusinessException 就诊不存在（404）或上游不可用（503）
     */
    HomepageSource homepageSource(UUID visitId);
}
