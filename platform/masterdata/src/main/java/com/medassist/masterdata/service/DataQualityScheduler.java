package com.medassist.masterdata.service;

import com.medassist.masterdata.repo.DataQualityRepository;
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
 *
 * <p>扫描后追加"超期未闭环"提醒：问题单只会自动累加次数，没人 ack/解决时
 * 如果没有提醒，看板上就是一条长期 OPEN 的记录（评测 D-23 实测 2 单 09:46 起无人处理）。
 */
@Component
@ConditionalOnProperty(prefix = "medassist.masterdata", name = "dq-scan-enabled",
        havingValue = "true", matchIfMissing = true)
public class DataQualityScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataQualityScheduler.class);

    /** 超期阈值：24 小时未更新即视为需要提醒。 */
    private static final int STALE_HOURS = 24;
    private static final int STALE_LIMIT = 20;

    private final DataQualityService service;
    private final DataQualityRepository repo;

    public DataQualityScheduler(DataQualityService service, DataQualityRepository repo) {
        this.service = service;
        this.repo = repo;
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
        remindStaleIssues();
    }

    private void remindStaleIssues() {
        try {
            List<String> stale = repo.listStaleOpenIssueNos(STALE_HOURS, STALE_LIMIT);
            if (!stale.isEmpty()) {
                log.warn("dq.issue.stale hours={} count={} issues={}",
                        STALE_HOURS, stale.size(), String.join(",", stale));
            }
        } catch (Exception e) {
            log.warn("dq.issue.stale.failed message={}", e.getMessage());
        }
    }
}
