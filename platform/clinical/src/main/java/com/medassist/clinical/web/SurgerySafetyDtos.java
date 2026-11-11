package com.medassist.clinical.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 手术安全核查契约（§265）：客户端只提交"勾选了哪些核查项"，模板由服务端固定。
 */
public final class SurgerySafetyDtos {

    private SurgerySafetyDtos() {
    }

    public record SafetyCheckReq(
            @NotBlank @Size(max = 32) String phase,
            List<@NotBlank @Size(max = 64) String> checkedItems,
            @Size(max = 1000) String issues) {
    }

    /** 单个核查项：label/required 来自服务端模板，checked 是本次提交结果。 */
    public record SafetyItemView(String code, String label, boolean required, boolean checked) {
    }

    /** 某个时点的最新核查结论；从未提交时 checkedBy/checkedAt 为空、complete=false。 */
    public record SafetyCheckView(String phase, String label, String checkedBy, Instant checkedAt,
                                  boolean complete, List<String> missingItems,
                                  List<SafetyItemView> items, String issues) {
    }

    /**
     * 三时点汇总。
     *
     * @param readyToStart    麻醉前 + 手术开始前均完成
     * @param readyToComplete 离室核查完成
     */
    public record SafetyChecklistView(UUID caseId, List<SafetyCheckView> phases,
                                      boolean readyToStart, boolean readyToComplete) {
    }
}
