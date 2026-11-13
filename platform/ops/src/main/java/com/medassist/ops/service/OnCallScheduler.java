package com.medassist.ops.service;

import com.medassist.common.time.BusinessTime;
import com.medassist.ops.domain.OpsModel.OnCallRotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 值班排班自动生成。
 *
 * <p>为什么必须自动生成：值班表是"升级通知的收件人"，只在人工点"生成"时才有的排班，
 * 一旦忘记点，超时工单升级时找不到人（{@code /ops/oncall/today} 返回空）。
 * 生成是幂等的（已存在日期保留原值班人），所以启动时跑一次 + 每天跑一次都安全。
 */
@Component
public class OnCallScheduler {

    private static final Logger log = LoggerFactory.getLogger(OnCallScheduler.class);

    /** 预生成天数：覆盖两周，足够覆盖一次休假交接。 */
    private static final int HORIZON_DAYS = 14;

    private final OnCallService onCallService;

    public OnCallScheduler(OnCallService onCallService) {
        this.onCallService = onCallService;
    }

    /** 启动即补齐（新部署当天就能查到今日值班）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        generateUpcoming("startup");
    }

    /** 每天 00:10（北京时间）滚动补齐；放在交接时间之前，避免交接时当天班次还缺。 */
    @Scheduled(cron = "${medassist.ops.oncall-generate-cron:0 10 0 * * *}",
            zone = "Asia/Shanghai")
    public void scheduled() {
        generateUpcoming("schedule");
    }

    private void generateUpcoming(String trigger) {
        LocalDate today = BusinessTime.today();
        LocalDate to = today.plusDays(HORIZON_DAYS - 1L);
        int rotations = 0;
        int created = 0;
        try {
            for (OnCallRotation rotation : onCallService.listRotations()) {
                if (!"ACTIVE".equals(rotation.status()) || rotation.startDate().isAfter(today)) {
                    continue;
                }
                try {
                    var summary = onCallService.generate(rotation.id(), today, to,
                            "system:scheduler", "127.0.0.1", null);
                    rotations++;
                    created += ((Number) summary.getOrDefault("created", 0)).intValue();
                } catch (RuntimeException e) {
                    // 单个轮换失败（如成员被清空）不能拖累其他组的排班
                    log.error("ops.oncall.generate.failed group={}", rotation.groupCode(), e);
                }
            }
            if (created > 0) {
                log.info("ops.oncall.generate trigger={} rotations={} created={}",
                        trigger, rotations, created);
            }
        } catch (RuntimeException e) {
            // 定时任务不能把异常抛到调度线程；下一轮继续补齐
            log.error("ops.oncall.generate.failed trigger={}", trigger, e);
        }
    }
}
