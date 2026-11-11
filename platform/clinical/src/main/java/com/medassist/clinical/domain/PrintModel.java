package com.medassist.clinical.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 单据打印留痕（第 230 批）。
 *
 * <p>与业务单据弱关联：`doc_no` 是打印件上印的业务号（票据号/处方号/住院号），
 * `target_type + target_id` 指向被打印的对象，用于"这张单补打过几次"的反查。
 * 不建外键：单据可能跨服务（票据在 clinical、处方在 pharmacy），
 * 留痕不能因为跨服务而写不进去。
 */
public final class PrintModel {

    private PrintModel() {
    }

    /**
     * 一条打印留痕（第 238 批增补次序与原因）。
     *
     * <p>{@code reprintSeq} 是同一单号的第几次打印（1 = 首次），由服务端在入库时判定；
     * {@code reason} 只在补打时强制，首次为空。历史行（V61 之前）同样为空，
     * 报表按“未说明”统计——不能让没有原因的补打被当成已解释。
     */
    public record PrintEvent(UUID id, String docType, String docNo,
                             String targetType, UUID targetId, String patientNo,
                             String actor, UUID campusId, OffsetDateTime printedAt,
                             int reprintSeq, String reason) {
    }

    /**
     * 分页结果（第 235 批）。
     *
     * <p>为什么用 offset 而不是游标：留痕页是“质控按时间段核查”，
     * 允许期间内新记录造成下一页轻微位移；总量 `total` 让页面能显示“共 N 条”，
     * 而游标分页在并发追加下更稳但无法给出总数，取舍以核查体验优先。
     */
    public record PrintEventPage(java.util.List<PrintEvent> items, long total,
                                 int offset, int limit) {
    }

    /**
     * 补打异常（第 237 批）：同一单号在核查期间被打印多次。
     *
     * <p>为什么按单号聚合而不是按人：质控关心的是“这张单到底出了几张纸”，
     * 换人补打同样算重复出单；`actors` 只作为排查线索，不影响是否计入异常。
     *
     * <p>第 238 批追加 {@code unexplained}：其中没有登记原因的补打次数。
     * 有原因的是“可解释补打”（卡纸/遗失），没有原因的是真正需要追问的部分，
     * 两者混在一个次数里，质控仍要逐条点开才能判断。
     */
    public record PrintAnomaly(String docType, String docNo, String patientNo, long prints,
                               long actors, long unexplained,
                               OffsetDateTime firstAt, OffsetDateTime lastAt) {
    }
}
