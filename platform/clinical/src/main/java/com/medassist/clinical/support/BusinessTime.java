package com.medassist.clinical.support;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 业务时区。
 *
 * <p>为什么不能直接用 {@link LocalDate#now()}：容器默认时区通常是 UTC，
 * 而医院按本地日历日计费（"今天"的口径是东八区）。若用 UTC，
 * 每天 00:00–08:00 开立的医嘱、按日计费的日期、单号日期前缀都会落到前一天。
 *
 * <p>时区可由环境变量 {@code CLINICAL_ZONE} 覆盖（默认 {@code Asia/Shanghai}），
 * 便于海外部署或测试固定时区。
 */
public final class BusinessTime {

    private static final ZoneId ZONE = ZoneId.of(
            System.getenv().getOrDefault("CLINICAL_ZONE", "Asia/Shanghai"));

    private BusinessTime() {
    }

    /** 业务"今天"（按医院所在时区）。 */
    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static ZoneId zone() {
        return ZONE;
    }
}
