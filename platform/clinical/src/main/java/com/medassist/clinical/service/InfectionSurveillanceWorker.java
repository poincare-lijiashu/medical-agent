package com.medassist.clinical.service;

import com.medassist.clinical.domain.ClinicalModel.InfectionDetection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 传染病自动扫描（§294）。
 *
 * <p>每 10 分钟扫描一次水位之后的诊断，匹配法定目录并生成待报卡；
 * 手工「立即扫描」与定时任务共用同一服务，避免两套匹配逻辑。
 * 扫描失败只记录日志，不抛出（不能让一次数据库抖动把调度线程打死）。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.infection", name = "scan-enabled",
        havingValue = "true", matchIfMissing = true)
public class InfectionSurveillanceWorker {

    private static final Logger log = LoggerFactory.getLogger(InfectionSurveillanceWorker.class);

    private final InfectionReportService service;

    public InfectionSurveillanceWorker(InfectionReportService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${medassist.infection.scan-ms:600000}",
            initialDelayString = "${medassist.infection.initial-delay-ms:60000}")
    public void scan() {
        try {
            InfectionDetection result = service.detect("system");
            if (result.created() > 0) {
                log.info("infection.scan scanned={} created={} skipped={}",
                        result.scanned(), result.created(), result.skipped());
            }
        } catch (RuntimeException e) {
            log.warn("infection.scan_failed type={} message={}",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
