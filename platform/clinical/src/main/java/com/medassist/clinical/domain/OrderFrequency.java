package com.medassist.clinical.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 医嘱频次受控词表（B2：长嘱 quantity 口径的配套口径）。
 *
 * <p>为什么要在代码里做受控词表而不是让客户端自由传字符串：
 * <ul>
 *   <li>频次决定"每日执行次数"，是护理执行与按日计费的共同依据；</li>
 *   <li>自由文本（"bid"/"2次/日"/"每日两次"）无法稳定解析，迟早出现两套口径；</li>
 *   <li>词表变更属于产品决策，走代码评审比改数据更可控（历史医嘱保存快照，不受影响）。</li>
 * </ul>
 *
 * <p>口径约定（与 {@code docs/CLINICAL-CORE-ROADMAP.md} §1.57 一致）：
 * <ul>
 *   <li><b>临时医嘱（TEMP）</b>：{@code quantity} = 本次总量，一次性计费；</li>
 *   <li><b>长期医嘱（LONG）</b>：{@code quantity} = <b>每日总量</b>，{@code amount} = 每日金额，
 *       按日计费每天计一次；{@code frequencyTimes} 只是执行次数快照（每日分几次给）。</li>
 * </ul>
 */
public enum OrderFrequency {

    QD("qd", "每日一次", 1, true),
    BID("bid", "每日两次", 2, true),
    TID("tid", "每日三次", 3, true),
    QID("qid", "每日四次", 4, true),
    Q12H("q12h", "每12小时一次", 2, true),
    Q8H("q8h", "每8小时一次", 3, true),
    Q6H("q6h", "每6小时一次", 4, true),
    Q4H("q4h", "每4小时一次", 6, true),
    Q2H("q2h", "每2小时一次", 12, true),
    Q1H("q1h", "每1小时一次", 24, true),
    HS("hs", "每晚一次", 1, true),
    QN("qn", "每晚一次", 1, true),
    ST("st", "立即一次", 1, false),
    ONCE("once", "一次", 1, false),
    PRN("prn", "必要时", 1, false),
    QOD("qod", "隔日一次", 1, false),
    TIW("tiw", "每周三次", 1, false);

    private final String code;
    private final String label;
    private final int timesPerDay;
    private final boolean daily;

    OrderFrequency(String code, String label, int timesPerDay, boolean daily) {
        this.code = code;
        this.label = label;
        this.timesPerDay = timesPerDay;
        this.daily = daily;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public int timesPerDay() {
        return timesPerDay;
    }

    /** 是否"每日执行"的频次：长嘱只接受这一类（隔日/每周频次无法按日计费）。 */
    public boolean daily() {
        return daily;
    }

    /** 大小写不敏感解析；不认识的频次返回空（由调用方决定是拒绝还是降级）。 */
    public static Optional<OrderFrequency> resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(f -> f.code.equals(normalized)).findFirst();
    }

    /** 词表快照（供前端下拉与接口文档）。 */
    public static List<OrderFrequency> all() {
        return List.of(values());
    }
}
