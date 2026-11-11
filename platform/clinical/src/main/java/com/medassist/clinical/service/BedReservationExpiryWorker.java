package com.medassist.clinical.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期床位预留回收（第 253 批）。
 *
 * <p>为什么必须后台回收而不是读时顺手改：床位看板是只读高频接口，
 * 在查询里做写操作会把“看一眼床位”变成持锁事务；而预留过期后不释放，
 * 床位就会一直显示“已预留”，比不预留更糟。
 *
 * <p>默认每分钟扫一批（最多 50 条），可多实例并行：每个实例只处理自己锁到的行。
 */
@Component
public class BedReservationExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(BedReservationExpiryWorker.class);
    private static final int BATCH = 50;

    private final BedReservationService service;

    public BedReservationExpiryWorker(BedReservationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${medassist.bed-reservation.sweep-ms:60000}",
            initialDelayString = "${medassist.bed-reservation.sweep-initial-ms:15000}")
    public void sweep() {
        try {
            service.sweepExpired(BATCH);
        } catch (RuntimeException e) {
            // 定时任务失败不能把调度线程打死；下一轮继续
            log.warn("clinical.bed_reservation.sweep_failed err={}", e.getMessage());
        }
    }
}
