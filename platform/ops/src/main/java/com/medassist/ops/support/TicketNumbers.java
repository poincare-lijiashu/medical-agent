package com.medassist.ops.support;

import com.medassist.common.time.BusinessTime;

import java.time.format.DateTimeFormatter;

/**
 * 工单号：{@code OP + 业务日期 + 6 位序列}。
 *
 * <p>为什么不用纯 UUID 做人工沟通的单号：电话里报 "OP20261112000123" 比报 36 位 UUID 现实得多；
 * 序列由数据库 sequence 保证唯一，日期用医院业务时区，避免 UTC 造成"凌晨单号落到前一天"。
 */
public final class TicketNumbers {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private TicketNumbers() {
    }

    public static String ticket(long seq) {
        return "OP" + DAY.format(BusinessTime.today()) + String.format("%06d", seq);
    }
}
