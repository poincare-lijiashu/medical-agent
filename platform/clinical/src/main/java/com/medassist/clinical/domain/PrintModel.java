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

    public record PrintEvent(UUID id, String docType, String docNo,
                             String targetType, UUID targetId, String patientNo,
                             String actor, UUID campusId, OffsetDateTime printedAt) {
    }
}
