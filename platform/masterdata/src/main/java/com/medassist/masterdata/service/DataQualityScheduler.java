package com.medassist.masterdata.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据质量周期扫描（§299）。
 *
 * <p>默认每 6 小时执行全部启用规则；首次延迟 5 分钟，避免服务刚启动就压数据库。
 * 调度失败只记录日志，不影响其他业务；规则本身有 5 秒查询超时。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.masterdata", name = "dq-scan-enabled",
        havingValue = "true", matchIfMissing = true)
public class DataQualityScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataQualityScheduler.class);

    private final DataQualityService service;

    public DataQualityScheduler(DataQualityService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${medassist.masterdata.dq-scan-ms:21600000}",
            initialDelayString = "${medassist.masterdata.dq-scan-initial-delay-ms:300000}")
    public void scan() {
        try {
            List<?> runs = service.runAll("system:dq");
            log.info("dq.scan.done rules={}", runs.size());
        } catch (Exception e) {
            log.warn("dq.scan.failed message={}", e.getMessage());
        }
    }
}
