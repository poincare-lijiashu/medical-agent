package com.medassist.ops.service;

import com.medassist.ops.domain.GoLiveModel.Factor;
import com.medassist.ops.domain.GoLiveModel.ProjectSnapshot;
import com.medassist.ops.domain.GoLiveModel.Report;
import com.medassist.ops.domain.ImplementationModel.Project;
import com.medassist.ops.domain.ImplementationModel.Readiness;
import com.medassist.ops.domain.SloModel.Detail;
import com.medassist.ops.domain.SloModel.Slo;
import com.medassist.ops.repo.BackupDrRepository;
import com.medassist.ops.repo.GoLiveRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 上线指挥台（§302）。
 *
 * <p>只读聚合六个门禁：实施就绪、灾备演练、SLO 错误预算、重大事件、数据质量、变更冻结；
 * 关键数据源读取失败按"不可放行"处理（fail-closed），避免"查不到就当通过"。
 * 不写入业务数据，也不替代各域自身的审批。
 */
@Service
public class GoLiveCockpitService {

    /** 灾备演练有效期与发布门禁保持一致（90 天）。 */
    private static final int DRILL_MAX_AGE_DAYS = 90;

    private final GoLiveRepository repo;
    private final ImplementationService implementationService;
    private final BackupDrRepository backupDrRepository;
    private final SloService sloService;
    private final Clock clock;

    public GoLiveCockpitService(GoLiveRepository repo, ImplementationService implementationService,
                                BackupDrRepository backupDrRepository, SloService sloService,
                                Clock clock) {
        this.repo = repo;
        this.implementationService = implementationService;
        this.backupDrRepository = backupDrRepository;
        this.sloService = sloService;
        this.clock = clock;
    }

    public Report build(java.util.UUID projectId, String componentCode, String environment) {
        Instant now = clock.instant();
        String scope = componentCode == null || componentCode.isBlank()
                ? "ALL" : componentCode.trim();
        String env = environment == null || environment.isBlank() ? "PROD" : environment.trim();
        List<Factor> factors = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("generatedAt", now);
        evidence.put("scope", scope);
        evidence.put("environment", env);

        ProjectSnapshot project = projectFactor(projectId, factors, blockers, warnings, evidence);
        drillFactor(scope, env, now, factors, blockers, warnings, evidence);
        sloFactor(factors, blockers, warnings, evidence);
        incidentFactor(factors, blockers, warnings, evidence);
        qualityFactor(factors, blockers, warnings, evidence);
        freezeFactor(scope, now, factors, blockers, warnings, evidence);
        p1Factor(factors, warnings, evidence);

        boolean ready = factors.stream().noneMatch(Factor::blocking);
        return new Report(now, scope, env, project, factors, ready, blockers, warnings, evidence);
    }

    // ------------------------------------------------------------------ 各门禁

    private ProjectSnapshot projectFactor(java.util.UUID projectId, List<Factor> factors,
                                          List<String> blockers, List<String> warnings,
                                          Map<String, Object> evidence) {
        if (projectId == null) {
            factors.add(new Factor("IMPL", "实施就绪", "SKIP", "未选择实施项目", false));
            warnings.add("未选择实施项目，实施就绪未参与判定");
            return null;
        }
        try {
            Project project = implementationService.detail(projectId);
            Readiness readiness = project.readiness();
            boolean ready = readiness != null && readiness.ready();
            ProjectSnapshot snapshot = projectSnapshot(project, readiness, evidence);
            factors.add(new Factor("IMPL", "实施就绪",
                    ready ? "PASS" : "FAIL",
                    readiness == null ? "缺少就绪报告"
                            : "得分 " + readiness.score() + "/" + readiness.minScore()
                                    + "，缺失必填 " + readiness.missingRequiredTasks().size()
                                    + " 项，培训通过率 " + readiness.trainingPassRate() + "%",
                    !ready));
            if (!ready) {
                blockers.add("实施项目 " + project.projectNo() + " 未达到上线就绪条件");
            }
            return snapshot;
        } catch (com.medassist.common.exception.BusinessException e) {
            // 项目不存在/参数错误是调用方问题，不应被当成"数据源不可用"降级
            throw e;
        } catch (RuntimeException e) {
            factors.add(new Factor("IMPL", "实施就绪", "FAIL",
                    "无法读取实施项目，按不可放行处理", true));
            blockers.add("无法读取实施项目");
            return null;
        }
    }

    private ProjectSnapshot projectSnapshot(Project project, Readiness readiness,
                                            Map<String, Object> evidence) {
        evidence.put("projectNo", project.projectNo());
        evidence.put("hospital", project.hospitalName());
        evidence.put("projectStatus", project.status());
        if (readiness != null) {
            evidence.put("implementationScore", readiness.score());
        }
        return new ProjectSnapshot(project.id(), project.projectNo(), project.hospitalName(),
                project.status(), readiness != null && readiness.ready(),
                readiness == null ? 0 : readiness.score(),
                readiness == null ? 0 : readiness.missingRequiredTasks().size(),
                readiness == null ? 0 : readiness.trainingPassRate(),
                readiness == null ? 0 : readiness.waivers());
    }

    private void drillFactor(String scope, String env, Instant now, List<Factor> factors,
                             List<String> blockers, List<String> warnings,
                             Map<String, Object> evidence) {
        try {
            Instant since = now.minus(Duration.ofDays(DRILL_MAX_AGE_DAYS));
            Optional<com.medassist.ops.domain.OpsModel.DrDrill> drill =
                    backupDrRepository.findLatestPassed(scope, env, since);
            if (drill.isEmpty()) {
                factors.add(new Factor("DR", "灾备演练", "FAIL",
                        "近 " + DRILL_MAX_AGE_DAYS + " 天无已复核的通过记录", true));
                blockers.add("缺少有效期内的灾备演练通过记录");
                return;
            }
            evidence.put("drillNo", drill.get().drillNo());
            evidence.put("drillCompletedAt", drill.get().completedAt());
            evidence.put("drillVerifier", drill.get().verifier());
            factors.add(new Factor("DR", "灾备演练", "PASS",
                    drill.get().drillNo() + " · " + fmt(drill.get().completedAt())
                            + " · 复核 " + drill.get().verifier(), false));
        } catch (RuntimeException e) {
            factors.add(new Factor("DR", "灾备演练", "FAIL",
                    "无法读取演练记录，按不可放行处理", true));
            blockers.add("无法读取灾备演练记录");
        }
    }

    private void sloFactor(List<Factor> factors, List<String> blockers, List<String> warnings,
                           Map<String, Object> evidence) {
        try {
            List<Detail> slos = sloService.list(true);
            long breached = slos.stream()
                    .filter(d -> "BREACHED".equals(d.evaluation().status())).count();
            long atRisk = slos.stream()
                    .filter(d -> "AT_RISK".equals(d.evaluation().status())).count();
            evidence.put("sloTotal", slos.size());
            evidence.put("sloBreached", breached);
            evidence.put("sloAtRisk", atRisk);
            if (slos.isEmpty()) {
                factors.add(new Factor("SLO", "SLO 错误预算", "SKIP", "尚未定义 SLO", false));
                warnings.add("尚未定义 SLO，错误预算未参与判定");
                return;
            }
            if (breached > 0) {
                factors.add(new Factor("SLO", "SLO 错误预算", "FAIL",
                        breached + " 条 SLO 低于目标，预算告急 " + atRisk + " 条", true));
                blockers.add("存在 " + breached + " 条低于目标的 SLO");
                return;
            }
            if (atRisk > 0) {
                factors.add(new Factor("SLO", "SLO 错误预算", "WARN",
                        "预算剩余不足 25% 的 SLO：" + atRisk + " 条", false));
                warnings.add("有 " + atRisk + " 条 SLO 错误预算告急");
                return;
            }
            factors.add(new Factor("SLO", "SLO 错误预算", "PASS",
                    slos.size() + " 条 SLO 均健康", false));
        } catch (RuntimeException e) {
            factors.add(new Factor("SLO", "SLO 错误预算", "FAIL",
                    "无法读取 SLO，按不可放行处理", true));
            blockers.add("无法读取 SLO 数据");
        }
    }

    private void incidentFactor(List<Factor> factors, List<String> blockers, List<String> warnings,
                                Map<String, Object> evidence) {
        try {
            List<Map<String, Object>> incidents = repo.openMajorIncidents();
            evidence.put("openMajorIncidents", incidents);
            if (!incidents.isEmpty()) {
                factors.add(new Factor("INCIDENT", "重大事件", "FAIL",
                        "未关闭的 SEV1/SEV2 事件：" + incidents.size() + " 起", true));
                blockers.add("存在 " + incidents.size() + " 起未关闭的重大事件");
                return;
            }
            factors.add(new Factor("INCIDENT", "重大事件", "PASS", "无未关闭的 SEV1/SEV2", false));
        } catch (RuntimeException e) {
            factors.add(new Factor("INCIDENT", "重大事件", "FAIL",
                    "无法读取事件台账，按不可放行处理", true));
            blockers.add("无法读取重大事件台账");
        }
    }

    private void qualityFactor(List<Factor> factors, List<String> blockers, List<String> warnings,
                               Map<String, Object> evidence) {
        try {
            List<Map<String, Object>> issues = repo.openQualityIssues();
            long blockersCount = issues.stream()
                    .filter(i -> "BLOCKER".equals(String.valueOf(i.get("severity")))).count();
            long high = issues.size() - blockersCount;
            evidence.put("openQualityIssues", issues);
            if (blockersCount > 0) {
                factors.add(new Factor("QUALITY", "数据质量", "FAIL",
                        "未闭环 BLOCKER 问题：" + blockersCount + " 个", true));
                blockers.add("存在 " + blockersCount + " 个未闭环 BLOCKER 数据质量问题");
                return;
            }
            if (high > 0) {
                factors.add(new Factor("QUALITY", "数据质量", "WARN",
                        "未闭环 HIGH 问题：" + high + " 个", false));
                warnings.add("有 " + high + " 个未闭环 HIGH 数据质量问题");
                return;
            }
            factors.add(new Factor("QUALITY", "数据质量", "PASS", "无未闭环 BLOCKER/HIGH", false));
        } catch (RuntimeException e) {
            factors.add(new Factor("QUALITY", "数据质量", "FAIL",
                    "无法读取质量问题，按不可放行处理", true));
            blockers.add("无法读取数据质量问题");
        }
    }

    private void freezeFactor(String scope, Instant now, List<Factor> factors,
                              List<String> blockers, List<String> warnings,
                              Map<String, Object> evidence) {
        try {
            List<Map<String, Object>> freezes = repo.activeFreezes(now, scope);
            evidence.put("activeFreezes", freezes);
            if (!freezes.isEmpty()) {
                factors.add(new Factor("FREEZE", "变更冻结", "FAIL",
                        "生效中的冻结窗口：" + freezes.size() + " 个", true));
                blockers.add("当前处于变更冻结窗口，禁止上线");
                return;
            }
            factors.add(new Factor("FREEZE", "变更冻结", "PASS", "无生效冻结窗口", false));
        } catch (RuntimeException e) {
            factors.add(new Factor("FREEZE", "变更冻结", "FAIL",
                    "无法读取变更窗口，按不可放行处理", true));
            blockers.add("无法读取变更冻结窗口");
        }
    }

    private void p1Factor(List<Factor> factors, List<String> warnings,
                          Map<String, Object> evidence) {
        try {
            List<Map<String, Object>> tickets = repo.openP1Tickets();
            evidence.put("openP1Tickets", tickets);
            if (!tickets.isEmpty()) {
                factors.add(new Factor("P1", "P1 工单", "WARN",
                        "未关闭 P1 工单：" + tickets.size() + " 单", false));
                warnings.add("有 " + tickets.size() + " 张未关闭的 P1 工单");
                return;
            }
            factors.add(new Factor("P1", "P1 工单", "PASS", "无未关闭 P1", false));
        } catch (RuntimeException e) {
            factors.add(new Factor("P1", "P1 工单", "WARN", "无法读取工单台账", false));
            warnings.add("无法读取 P1 工单台账");
        }
    }

    private static String fmt(Instant instant) {
        return instant == null ? "—" : instant.toString();
    }
}
