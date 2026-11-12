package com.medassist.auth.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cell 开通任务（第 284 批 / ADR-001 P3）。
 *
 * <p>开通是"一件事"，不是一个 HTTP 请求：它跨控制面与院方数据库、跨多个幂等步骤。
 * 把任务与步骤落库，运维才能回答"卡在哪一步、重跑几次、谁在什么时候做的"。
 *
 * <p>边界：
 * <ul>
 *   <li>任务状态只有 RUNNING / SUCCEEDED / FAILED——步骤是同步执行的，
 *       没有"排队中"这种既不前进也不失败的中间态；</li>
 *   <li>一次性管理员口令<b>不在任何记录里</b>，只在创建/重跑响应中返回一次；</li>
 *   <li>同一租户同时只允许一个 RUNNING 任务（数据库部分唯一索引兜底）。</li>
 * </ul>
 */
public final class Provisioning {

    private Provisioning() {
    }

    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    public static final String STEP_SUCCEEDED = "SUCCEEDED";
    public static final String STEP_FAILED = "FAILED";
    public static final String STEP_SKIPPED = "SKIPPED";

    /** 步骤顺序即执行顺序；调整顺序必须同步调整历史任务的 resume 语义。 */
    public static final List<String> STEP_CODES = List.of(
            "SPEC",          // 规格校验与控制面/数据面护栏
            "TENANT",        // 控制面租户目录（PENDING）
            "CELL_CONNECT",  // 连 Cell 库并确认迁移已就绪
            "CELL_TENANT",   // Cell 库内登记本租户（账号外键依赖）
            "ADMIN",         // 首个管理员（一次性口令）
            "BACKUP",        // 独立备份桶 + 备份计划 + WAL 归档就绪校验（§286）
            "LICENSE",       // 授权检查（可配置、可降级）
            "ACTIVATE",      // 控制面激活
            "HEALTH"         // 开通后健康快照
    );

    public record Job(
            UUID id,
            String tenantCode,
            String instanceCode,
            String status,
            String requestedBy,
            Map<String, Object> request,
            Map<String, Object> result,
            Instant startedAt,
            Instant finishedAt,
            List<Step> steps) {
    }

    public record Step(
            UUID jobId,
            int stepNo,
            String code,
            String status,
            Map<String, Object> detail,
            String error,
            Instant startedAt,
            Instant finishedAt) {
    }
}
