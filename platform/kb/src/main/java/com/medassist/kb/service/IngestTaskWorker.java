package com.medassist.kb.service;

import com.medassist.kb.config.KbProperties;
import com.medassist.kb.domain.KbModel.IngestTask;
import com.medassist.kb.repo.KbRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 索引任务 worker（多副本安全）。
 *
 * <p>抢占用 {@code FOR UPDATE SKIP LOCKED}，因此水平扩副本时同一个文档不会被两个实例处理；
 * 处理失败写回 {@code FAILED + next_run_at 退避}，超过最大次数后不再被抢占。
 *
 * <p>为什么不放到请求线程里做：向量化是外部调用，时延不可控；放请求线程会让"保存文档"
 * 接口在模型慢时超时，且在失败时丢失重试能力。
 */
@Component
@ConditionalOnProperty(prefix = "kb.ingest", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class IngestTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestTaskWorker.class);

    private final KbRepository repo;
    private final IngestService ingestService;
    private final KbProperties props;

    public IngestTaskWorker(KbRepository repo, IngestService ingestService, KbProperties props) {
        this.repo = repo;
        this.ingestService = ingestService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${kb.ingest.interval-ms:5000}")
    public void tick() {
        List<IngestTask> tasks = repo.claimTasks(props.ingest().batchSize(),
                props.ingest().maxAttempts());
        if (tasks.isEmpty()) {
            return;
        }
        for (IngestTask task : tasks) {
            try {
                ingestService.process(task.id(), task.documentId());
            } catch (Exception e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                repo.failTask(task.id(), reason, task.attempts());
                repo.markFailed(task.documentId(), reason);
                log.error("kb.ingest.failed documentId={} attempts={} err={}",
                        task.documentId(), task.attempts(), reason);
            }
        }
    }
}
