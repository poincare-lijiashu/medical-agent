package com.medassist.release.service;

import com.medassist.common.api.ErrorCode;
import com.medassist.common.exception.BusinessException;
import com.medassist.release.domain.ReleaseModel.Train;
import com.medassist.release.domain.ReleaseModel.TrainItem;
import com.medassist.release.repo.ReleaseRepository;
import com.medassist.release.repo.ReleaseRepository.CellDeployment;
import com.medassist.release.web.ReleaseDtos;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cell 发布对齐（第 283 批 / ADR-001 P2）。
 *
 * <p>物理隔离之后，"发布"是按 Cell 分批推进的。控制台必须能一眼回答：
 * <b>这家医院跟上了最近一次已发布列车吗？它上一次部署是成功还是失败？</b>
 *
 * <p>判定口径（刻意保持简单可解释，避免版本号字符串比较这种不可靠逻辑）：
 * <ul>
 *   <li>目标 = 最近一次 {@code RELEASED} 列车的车厢（组件 → 版本 ID）；</li>
 *   <li>事实 = 该 Cell 在 PROD 下每个组件最近一次 <b>成功</b> 部署的版本 ID；</li>
 *   <li>相等即 UP_TO_DATE；有记录但不同 = BEHIND；没有记录 = NEVER_DEPLOYED；</li>
 *   <li>最近一次尝试（不论成败）是 FAILED 的组件计入失败，页面优先红标。</li>
 * </ul>
 *
 * <p>没有已发布列车时返回 UNKNOWN，而不是把"全部未部署"渲染成落后——
 * 新交付环境还没发过车，这时的黄标只会造成噪音。
 */
@Service
public class CellReleaseStatusService {

    private static final String TENANT_PATTERN = "^[a-z0-9][a-z0-9-]{1,31}$";

    private final ReleaseRepository repo;
    private final Clock clock;

    public CellReleaseStatusService(ReleaseRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public ReleaseDtos.CellReleaseStatus status(String tenantCode) {
        String tenant = requireTenant(tenantCode);
        Map<String, CellDeployment> deployed = byComponent(repo.latestCellDeployments(tenant));
        Map<String, CellDeployment> attempts = byComponent(repo.latestCellAttempts(tenant));
        Optional<Train> train = repo.latestReleasedTrain();

        if (train.isEmpty()) {
            return new ReleaseDtos.CellReleaseStatus(tenant, null, null, null, "UNKNOWN",
                    0, 0, 0, List.of(), clock.instant());
        }

        List<ReleaseDtos.CellComponentStatus> components = new ArrayList<>();
        int behind = 0;
        for (TrainItem item : train.get().items()) {
            CellDeployment fact = deployed.get(item.componentCode());
            String state;
            if (fact == null) {
                state = "NEVER_DEPLOYED";
            } else if (fact.versionId().equals(item.versionId())) {
                state = "UP_TO_DATE";
            } else {
                state = "BEHIND";
            }
            if (!"UP_TO_DATE".equals(state)) {
                behind++;
            }
            components.add(new ReleaseDtos.CellComponentStatus(item.componentCode(),
                    item.versionNo(),
                    fact == null ? null : fact.version(),
                    state,
                    fact == null ? null : fact.startedAt()));
        }

        int failed = (int) attempts.values().stream()
                .filter(a -> "FAILED".equals(a.result()))
                .count();
        String overall = failed > 0 ? "FAILED" : behind > 0 ? "BEHIND" : "ALIGNED";

        return new ReleaseDtos.CellReleaseStatus(tenant, train.get().id(), train.get().trainCode(),
                train.get().releasedAt(), overall, components.size(), behind, failed,
                components, clock.instant());
    }

    private static Map<String, CellDeployment> byComponent(List<CellDeployment> rows) {
        Map<String, CellDeployment> map = new LinkedHashMap<>();
        for (CellDeployment row : rows) {
            map.putIfAbsent(row.componentCode(), row);
        }
        return map;
    }

    private static String requireTenant(String tenantCode) {
        String tenant = tenantCode == null ? "" : tenantCode.trim();
        if (!tenant.matches(TENANT_PATTERN)) {
            throw new BusinessException(ErrorCode.REL_RELEASE_INVALID,
                    "租户编码不合法（期望小写字母/数字/短横线，2-32 位）");
        }
        return tenant;
    }
}
