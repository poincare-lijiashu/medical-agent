# 02 · 可观测性与运营指标评审（Prometheus 闭环，G86–G105）

> **评审范围**：`backend/agents/consult_metrics.py`、`skill_usage.py`、`skill_trends.py`、`skill_ops.py`、`skill_advice.py`、`backend/core/observability.py`、`backend/main.py`（`/metrics`）、`backend/api/v1/agent/agent_router.py`（Prometheus 端点）、`backend/api/deps.py`（`require_metrics_access`）、`deploy/prometheus/medassist-agent-ops.rules.yml`、`deploy/observability/prometheus/prometheus.yml`、`deploy/observability/docker-compose.observability.yml`、`deploy/helm/medassist-platform/templates/servicemonitor.yaml` / `deployment-ai-core.yaml` / `values.yaml`、`scripts/agent_go_live_check.py`、`frontend-vue/src/views/AgentView.vue`。
> **评审人**：可观测性与运营指标评审（**只提意见，不改任何源码**）。
> **日期**：2026-11-06。
> **结论口径**：Must = 不改会让告警失真、让运营误判或让闭环实际断裂；Should = 口径/健壮性/可维护性缺口。标注"需人工确认"的条目依赖部署形态或产品语义，不能由代码单独判定。

---

## 摘要

G97–G105 搭出了一条完整的「计量 → 导出 → 抓取 → 告警 → 建议」链路，设计上有几处做得很对：**进程内聚合计数不落库、不记录问题/回答原文**（`consult_metrics.py:1-18`）、**token 只采信 provider 真实返回的 usage 绝不估算**（`consult_metrics.py:44-55`、`consult.py:164-190`）、**技能标签基数上限 200 并归入 `__other__`**（`consult_metrics.py:26-27,74-79`）、**路由模板代替真实路径防时延标签高基数**（`main.py:203`）、**G97 告警阈值与后端 `skill_trends` 阈值逐条对齐**（见「值得保持」）。

但这条链路目前**没有真正闭合**，断点集中在四处：

1. **抓取端点与告警规则错配**：`prometheus.yml` 抓的是 `/metrics`（`main.py:274` 只拼 `observability + consult_metrics`），而 7 条告警里有 5 条引用的是 `skill_trends` 的指标——这些指标**只在另一个端点** `/api/v1/agent/skill-library/metrics/prometheus` 导出，且该端点鉴权是 `require_role("admin","qc")`（`agent_router.py:730`），**Prometheus 拿不到**。→ 5/7 告警规则在真实抓取路径上永远无数据。
2. **Helm 侧没有 PrometheusRule**：`deploy/prometheus/*.rules.yml` 只在 compose 路径挂载（`docker-compose.observability.yml:103`），Chart 里没有任何规则模板 → K8s 形态下 7 条告警**一条都不生效**。
3. **多口径冲突会误导运营决策**：`skill_ops.unusedPublished`（Bot 引用口径）与 `skill_usage.unused`（问答生效口径）用同一个词；`skill_usage.usageRatio` 在有流量的 agent 上恒等于 ~1.0（因为 `/consult` 默认应用该 agent 的**全部**已发布技能卡），看板无判别力；`skill_advice` 的去重 key 让「回放通过率异常」「经验覆盖率低」全库各只保留一条建议。
4. **进程内计数在多 worker 下会破坏 counter 语义**：`docs/DEPLOY.md:288,366` 明确建议 `--workers $((nproc*2+1))`，此时每个 worker 各持一份计数，Prometheus 相邻两次抓取命中不同 worker 会看到「同一时序忽增忽减」——这比"重启归零"严重得多。

合计 **必须优化 11 条**、**建议优化 14 条**。

---

## 必须优化（Must）

### M1 · 抓取端点与告警规则错配：5/7 条 G97 告警在真实抓取路径上永远无数据

- **位置**：
  - `deploy/observability/prometheus/prometheus.yml:26-34`（`metrics_path: /metrics`）
  - `deploy/helm/medassist-platform/templates/servicemonitor.yaml:64`（`path: /metrics`）
  - `backend/main.py:267-275`（`/metrics` = `observability.exposition() + consult_metrics.prometheus_text()`，**不含 `skill_trends.prometheus_text()`**）
  - `backend/api/v1/agent/agent_router.py:729-733`（`skill_trends.prometheus_text()` **只在这里**）
  - `deploy/prometheus/medassist-agent-ops.rules.yml:13-60`（5 条规则引用 `skill_trends` 指标）
- **问题**：`/metrics` 的响应体里**根本没有**下列指标：`medassist_agent_experiences_total`、`medassist_agent_replay_pass_ratio`、`medassist_agent_replay_checks_total`、`medassist_agent_experience_coverage_ratio`、`medassist_agent_unused_published_skills`、`medassist_agent_operational_alerts`、`medassist_agent_proposals_total`（全部来自 `skill_trends.py:183-236`）。而 `prometheus.yml` 与 ServiceMonitor 都只抓 `/metrics`。
  - 规则文件头注释（`rules.yml:4`）写"配套接口：GET /api/v1/agent/skill-library/metrics/prometheus"，与实际抓取配置矛盾。
  - 该 Agent 端点鉴权是 `require_role("admin","qc")`（`agent_router.py:730`）→ **Prometheus 无法用 `METRICS_TOKEN` 抓它**；即便用 admin JWT 抓，JWT 有 720 分钟过期（`config.py:133`），不能作为长期抓取凭据。
- **为什么必须改**：这 5 条告警（回放通过率低、未被引用技能、候选积压、经验覆盖率低、高危运营告警）**永远不会触发**，运营会误以为"系统健康"。这是 G97 的核心交付物失效。
- **建议方向**：统一为**单一抓取端点**——把 `skill_trends.prometheus_text()` 并入 `main.py` 的 `/metrics`（并让两个端点返回**完全相同**的指标集合，避免"抓哪个端点决定能拿到哪些指标"的隐性差异）；同时让 Agent 端点也接受 `require_metrics_access`（或直接废弃该端点，只保留 `/metrics`）。规则文件与抓取配置的"配套接口"注释必须逐字对齐。

### M2 · Helm 形态下没有任何告警规则被安装（缺 PrometheusRule）

- **位置**：`deploy/helm/medassist-platform/templates/`（**无** PrometheusRule 模板，全目录 grep 无 `PrometheusRule`）、`deploy/prometheus/medassist-agent-ops.rules.yml`、`docker-compose.observability.yml:103`（`../../deploy/prometheus:/etc/prometheus/rules:ro`）
- **问题**：告警规则只以**纯 YAML 文件**形式存在于 compose 挂载里。K8s 部署走 Prometheus Operator，`deploy/helm/medassist-platform` 只提供了 `ServiceMonitor`（`servicemonitor.yaml`），**没有任何 `kind: PrometheusRule`**。
- **为什么必须改**：G105 的交付目标包含 K8s 抓取接入，但 K8s 里 Prometheus Operator 只认 `PrometheusRule` CRD。结果是：K8s 环境抓得到数据、**一条告警都不生效**，且与本地 compose 行为不一致——上线自检（`agent_go_live_check.py`）也只检查规则文件"存在"（`:104-106`），不会发现它从未被安装。
- **建议方向**：在 Chart 中增加 PrometheusRule 模板（把 `medassist-agent-ops.rules.yml` 的内容模板化，`ruleFiles` 或直接内联），并加上 `serviceMonitor.rules.enabled` 开关；同时让 `agent_go_live_check.py` 的 `--dry-run` 用 `helm template` 渲染一次并断言 PrometheusRule 存在（**需人工确认**：院内 Prometheus Operator 是否允许该 Chart 跨 namespace 部署规则，通常要求与 Prometheus 同 namespace 或配置 `ruleSelector`）。

### M3 · `agent` 标签基数在错误路径上**无上限**，用户可自造任意标签值

- **位置**：
  - `backend/agents/consult.py:334-337`（`except ConsultError: consult_metrics.record(agent_id or "unknown", "sync", "error", ...)`）
  - `backend/agents/consult.py:388-391`（stream 同构）
  - `backend/agents/consult.py:281-284`（`_prepare` 对未知 agent 抛 `ConsultInvalid(f"未找到智能体：{agent_id}")` → 正是上面那条 `except` 分支）
  - `backend/agents/consult_metrics.py:62`（`agent = (agent_id or "unknown")[:64]`，**只截断长度，不做白名单**）
  - 对照 `backend/agents/consult_metrics.py:26,74-79`（skill 标签**有** 200 上限）
- **问题**：`ConsultReq.agentId` 是用户可输入字段（`agent_router.py:178`，max_length=32）。当用户传入一个目录里不存在的 agentId，`_prepare` 抛错，异常分支用**用户原始字符串**作为 `agent` 标签写入 `medassist_agent_consult_requests_total{agent="..."}`。用户每次换一个随机 agentId 就产生一条**新时序**。成功路径因为经过 `catalog.get_agent` 校验是有界的，但错误路径完全无界。
  - `MAX_SKILL_LABELS` 的保护只覆盖了 skill 标签，**agent 标签没有任何等价保护**。
- **为什么必须改**：这是可被普通登录用户触发的**时序膨胀**：内存（`_requests` 字典只增不减）、Prometheus TSDB、告警求值成本同时上升；且大量伪造 agent 会把 `sum(rate(...))` 类规则的分子污染，掩盖真实错误率。
- **建议方向**：把 `agent` 标签规范化成**白名单取值**——未注册时统一归入固定桶（如 `agent="__unknown__"`），而不是回填用户原文；并对 `_requests` 的键数设上限 + 溢出归并（与 `MAX_SKILL_LABELS` 同一模式）。

### M4 · 多口径冲突：「未被引用的技能」有三个互不相同的定义，其中一个会诱导停用正在生效的技能卡

- **位置**：
  - `backend/agents/skill_ops.py:40-41,66`：`unusedPublished = published_codes - referenced_by_bots`（**Bot 配置引用**口径，持久化）
  - `backend/agents/skill_usage.py:64-66,117`：`unused = published - 成功问答引用过的`（**运行使用**口径，进程内）；`usageRatio = used/published`
  - `backend/agents/consult.py:61-65`：`select_skills` **默认应用该 agent 的全部 PUBLISHED 技能卡**（与 Bot 引用完全解耦）
  - `backend/agents/skill_advice.py:109-116`：`UNUSED_SKILL` 建议来源是 `skill_ops` 的 `unusedPublished`
  - `backend/agents/skill_trends.py:145-149` 与 `deploy/prometheus/medassist-agent-ops.rules.yml:24-31`：`UNUSED_PUBLISHED_SKILLS` 告警同样用 Bot 引用口径
  - `frontend-vue/src/views/AgentView.vue:319-320`（"未被引用的生效技能"）与 `:410-413`（"使用率"）**同屏并列**
- **问题**：一个技能卡若**没有任何 Bot 引用**、但其 agent 每天都有 `/consult` 流量，它在 `/consult` 里**每次都被应用**（`consult.py:63` 默认全部生效）；此时：
  - `skill_ops.unusedPublished` 把它列为"未被引用"→ 生成运营建议「评估绑定到合适 Bot，或停用该技能卡」（`skill_advice.py:111-116`）→ 触发 Prometheus 告警「存在未被任何 Bot 引用的生效技能」（`rules.yml:24-31`）；
  - `skill_usage.unused` 却**不会**列它（它被成功问答引用过）。
  - 反之，一个被 Bot 引用但从未被问答触发的技能卡，在 `skill_ops` 里是"有用"，在 `skill_usage.unused` 里是"未使用"。
- **为什么必须改**：这是**会改变临床侧可用性**的口径错配——运营按建议停用一个正在 `/consult` 主链路生效的技能卡，问答能力域的提示/护栏随即变窄；同时看板上两个数字（"未被引用的生效技能 3 个" vs "使用率 100%"）互相打脸，运营无法判断该信哪个。
- **建议方向**：把三个概念在代码与 UI 上**改名区分**并明确各自的**数据来源与窗口**：
  1. `unreferencedByBot`（配置口径，持久化）——用于"技能卡是否接入了生产编排"；
  2. `neverAppliedInConsult`（运行口径，进程内/持久化窗口）——用于"技能卡是否真的在问答中生效"；
  3. `notAppliedRecently`（闲置口径）——用于下线评估。
  `UNUSED_SKILL` 建议必须基于 (2)/(3) 而不是 (1)，或至少在建议文案里写明"未被 Bot 引用**不等于**未被问答使用"。

### M5 · `usageRatio` 在有流量的 agent 上恒等于 ~1.0，看板无判别力

- **位置**：`backend/agents/skill_usage.py:64-65,117`；`backend/agents/consult.py:61-65`
- **问题**：`consult.select_skills` 在 `skillCodes is None` 时取 `skills.published_for_agent(agent.id)` 的**全部**技能卡。`consult.py:344-345,372-373` 把 `chosen` 的**所有** code 都记进 `skill_usage`。因此只要某个 agent 发生过一次成功问答，它名下**所有**已发布技能卡都会出现在 `usage` 里 → `used == published` → `usageRatio = 1.0`，`unused` 为空。
- **为什么必须改**：G103 看板（`AgentView.vue:410-413`）和 `skill_usage.report()` 的核心 KPI 是一个**恒定饱和**的量，运营用它无法发现"某技能卡实际已不产生任何作用"；同时它与 `skill_ops` 的 `unusedPublished` 并排展示，进一步加深 M4 的混淆。
- **建议方向**：换一个有信息量的口径（例如"本次问答**显式指定**或**实际命中证据**的技能卡占比"），或在指标里显式区分 `applied`（默认全量装配）与 `cited/used`（真正被引用），并在 note 里写明默认装配语义。

### M6 · 错误率告警的分母混入 `prefilter` 成功请求，会系统性稀释真实错误率

- **位置**：
  - `backend/agents/consult.py:339-342`（`consult_metrics.record("prefilter", "sync", "ok", ...)`）、`:393-396`（stream 同构）
  - `deploy/prometheus/medassist-agent-ops.rules.yml:63-72`（分母 `sum(rate(medassist_agent_consult_requests_total[10m]))` **不带任何过滤**）
- **问题**：`prefilter` 是"闲聊/元问题"的**确定性前置应答**，不检索、不调模型（`consult.py:231-241` 的 note 明说"未检索、未调用模型、未使用技能卡"），但它被记成 `status="ok"`。规则的分母包含这部分流量。闲聊占比越高（问诊前寒暄、界面探问等），真实问答的错误率被稀释得越厉害。
- **为什么必须改**：这会让"统一问答错误率超过 20%"这条唯一的可用性告警**系统性迟钝**——真实模型故障被 prefilter 流量掩盖，等价于"告警永远不响"。
- **建议方向**：分母显式排除 `agent="prefilter"`（如 `sum(rate(...{agent!="prefilter"}))`），或把 prefilter 单独建模为一个指标（如 `medassist_agent_consult_prefilter_total`）并在看板中单列。

### M7 · `status="error"` 混合了「用户输入非法」与「模型/合规不可用」，20% 错误率无法定位

- **位置**：`backend/agents/consult.py:335-337`（`ConsultInvalid` → error）、`:359-362`（`ConsultUnavailable` 超时 → error）、`:363-366`（`ConsultError` → error）、`:367-370`（任意异常 → error）；`backend/agents/consult_metrics.py:64`（`status` 只有 ok/error/aborted 三值）
- **问题**：`ConsultInvalid`（问题太短、agent 不存在、角色无权、护栏超限）与 `ConsultUnavailable`（provider 不可用、模型不合规、超时）**共用同一个 `error` 标签**。前者是用户输入问题、后者是服务端可用性问题，运营处置方式完全相反。
- **为什么必须改**：`rules.yml:63-77` 的描述写"请检查模型 provider、合规策略与技能护栏"——把用户输错问题也引导去查 provider，会浪费大量排障时间；同时无法按原因维度做 SLO。
- **建议方向**：把 `status` 拆成可诊断的取值（如 `invalid` / `forbidden` / `unavailable` / `internal`），或在 `status` 之外增加一个 `reason` 标签（低基数枚举）；看板与规则分别统计"用户侧错误"与"服务端错误"。

### M8 · `skill_advice` 的去重 key 让「回放通过率异常」「经验覆盖率低」全库各只保留一条

- **位置**：`backend/agents/skill_advice.py:76-87`（`dedupeKey = f"{code}:{target_id or '-'}"`）、`:117-123`（`REPLAY_REVIEW`，`target_id=None`）、`:124-130`（`EXPERIENCE_GAP`，`target_id=None`）、`:134-138`（`existing` 集合跳过已存在 key）
- **问题**：`REPLAY_REVIEW` 与 `EXPERIENCE_GAP` 的 `target_id` 恒为 `None` → dedupeKey 恒为 `"REPLAY_REVIEW:-"` / `"EXPERIENCE_GAP:-"`。`generate()` 用 `existing` 跳过已存在的 key（无论其状态是 OPEN 还是 DONE/DISMISSED），因此**全库永远只有一条**这类建议，且一旦被处理完（标记 DONE/忽略），**后续任何新的回放异常都不会再产生建议**。
- **为什么必须改**：
  - 告警 → 建议这一环在这里是**断的**：后端 `skill_trends.alerts()` 的 `REPLAY_PASS_RATE_DROP`（`skill_trends.py:164-172`）可以反复触发，但建议侧只出第一条；
  - 与看板统计维度对不上：`skill_usage` 按 skill code 逐行统计，而建议在这个维度上**没有粒度**（`REPLAY_REVIEW` 无 targetId），运营对不上账（"通过率 60%、建议 0 条" / "有 1 条回放建议、看板显示 100%"）。
- **建议方向**：给这两类建议一个**可定位且可复用的维度**（如 `targetId = 触发告警的指标 + 维度键`，或按能力域/候选聚合键）；并把"已处理"从"已存在"中分离——已完成的建议不应阻止同类问题再次产生新建议（可保留"最近一次"语义但允许新建带时间戳的条目）。

### M9 · Helm ServiceMonitor 的 `authorization.credentials` 依赖"Secret 必须先于 Helm 存在"，且失败表现为 CrashLoop 而非安装期报错

- **位置**：`deploy/helm/medassist-platform/templates/servicemonitor.yaml:67-71`、`deploy/helm/medassist-platform/templates/deployment-ai-core.yaml:128-135`、`deploy/helm/medassist-platform/values.yaml:681-688`
- **问题**：
  - namespace 引用是**正确的**：ServiceMonitor 的 `metadata.namespace` = `.Release.Namespace`（`servicemonitor.yaml:49`），Operator 从 ServiceMonitor 所在 namespace 解析 `credentials.name`；而 `deployment-ai-core.yaml:133` 用同一个 Secret 名注入 `METRICS_TOKEN`，也在 `.Release.Namespace` → 两者一致 ✅（这点值得保持）。
  - 但 `required`（`servicemonitor.yaml:70`）只校验**名字非空**，不校验 Secret 存在。Secret 缺失时 Helm 安装**成功**，Pod 因 `secretKeyRef`（默认 `optional: false`）拉不到密钥而 `CreateContainerConfigError` → CrashLoopBackOff；Operator 侧则因读不到 Secret 而让 ServiceMonitor 静默失效。
  - `values.yaml:683-684` 注释要求"启用前先创建同 namespace 的 Secret" → **安装顺序依赖人工**，且 Helm 里没有 `secrets.create` 之类的兜底（`values.yaml:189-208` 的 `secrets.create` 不含 metrics token）。
- **为什么必须改**：故障点从"安装期明确报错"推迟到"运行时 CrashLoop / 抓取静默失效"，院内排障成本高；且 `serviceMonitor.enabled=true` 但 `aiCore.enabled=false` 时 `required` 仍会触发（`servicemonitor.yaml:43`），语义易混。
- **建议方向**：要么在 Chart 里支持 `serviceMonitor.aiCore.createSecret=true`（用随机值生成并同时注入 Deployment，取代人工预置），要么用 `helm pre-install` Hook 做存在性校验并 `fail` 明确报错；并把 Secret 键名/namespace 在 values 注释里写清（**需人工确认**：院内是否统一由 External Secrets 托管，此路径可能应只读 Secret 而不支持创建）。

### M10 · `metrics-token` 示例文件是「占位符 + 尾随换行」，而文档明确指示直接复制 → 必然 401

- **位置**：
  - `deploy/observability/prometheus/metrics-token.example`（**实测内容 28 字节：`replace-with-a-random-token` + 末字节 `0x0A`**）
  - `deploy/observability/docker-compose.observability.yml:84-87`（"首次使用：Copy-Item metrics-token.example metrics-token"）
  - `deploy/observability/prometheus/prometheus.yml:5-8`（注释要求"UTF-8（无 BOM）、无多余空白/换行"）
  - `backend/api/deps.py:116-119`（`_metrics_token_matches(token.strip(), expected.strip())`）
- **问题**：文档让用户把 example **原样**复制成真实令牌文件。复制后得到的是**公开已知的占位串** `replace-with-a-random-token`（任何拿到仓库的人都知道这个值），并且带一个**尾随换行**。
  - 尾随换行：后端 `token.strip()` 会去掉尾部空白，所以**恰好**能容忍；但这依赖后端 strip 兜底，而非抓取侧保证。若哪天换成不 strip 的实现就会立刻 401。
  - BOM（`\xEF\xBB\xBF`）：`str.strip()` **不包含** BOM → 一定不匹配 → 401 且**无任何可读提示**（`_metrics_token_matches` 只是返回 False，`deps.py:123` 抛"未认证：需 admin 登录或有效抓取令牌"）。
  - 现有注释只"提醒"不要 BOM/换行，**没有任何机器校验**；`agent_go_live_check.py` 也完全没覆盖令牌链路。
- **为什么必须改**：这是 G105 抓取链路上的**首选失败点**，且失败表现为"目标 down / 401"，排查者必须同时怀疑令牌内容、BOM、换行、路径四件事。
- **建议方向**：把示例文件改成**不可直接用作令牌**的形态（例如不含有效占位串、或在 compose 里改用 `${METRICS_TOKEN}` 环境变量注入而非文件挂载）；并在 `agent_go_live_check.py` 增加一条检查：用 `METRICS_TOKEN` 实际请求 `/metrics` 断言 200（同时覆盖 BOM/错值/路径三类问题）。

### M11 · 多 worker 部署会破坏 counter 语义（比"重启归零"严重得多）

- **位置**：`docs/DEPLOY.md:288`、`:366`（明确建议 `--workers $((nproc*2+1))`）、`Dockerfile:47`（CMD 未固定 worker 数）、`backend/agents/consult_metrics.py:29-35`（进程内全局字典）
- **问题**：当前计量全部存在**进程内**（`_requests` / `_durations` / `_skill_usage` / `_tokens`）。uvicorn 多 worker 模式下每个 worker 各持一份独立计数，**内核会把相邻两次 `/metrics` 抓取负载均衡到不同 worker** → 同一个 `(agent, channel, status)` 时序在两次采样间出现**忽增忽减**。对 Prometheus 而言这不是"重启归零"（重启归零是标准 counter 语义，rate 能正确处理），而是**同一时序的非单调下降** → 被判定为 counter reset → `rate()`/`increase()` 被系统性放大或失真，G102 的两条告警会出现虚假尖峰。
- **为什么必须改**：这会让唯一的可用性告警（错误率 > 20%）在多 worker 生产环境下**持续误报**，运营最终会忽略整套告警（告警疲劳）。同时 `skill_usage` 前端看板读的是**本地进程**计数，多 worker 下每次刷新数字都可能不同。
- **建议方向**：二选一——(a) 明确把"计量端点单 worker"作为部署约束并写进 `Dockerfile`/启动脚本与部署文档（同时在 `/metrics` 里加 `worker_pid` 标签或暴露 `process_start_time_seconds` 供识别）；(b) 把计数移到共享存储（Redis/PG，`values.yaml:97-101` 已有 Redis 通道）实现跨 worker/跨副本聚合。这是当前闭环最大的可靠性缺口。

---

## 建议优化（Should）

### S1 · 告警规则缺少「target down / 抓取失败」「token 成本」「回答质量」三类关键告警

- **位置**：`deploy/prometheus/medassist-agent-ops.rules.yml:10-86`（共 7 条规则）
- **问题**：现有关警全部围绕**技能运营**与**问答计数**，缺少三类运维必备告警：
  1. **抓取失败本身**：`up{job="medassist-ai-core"} == 0` 缺失。`/metrics` 因为挂了 `require_metrics_access`（`deps.py:108-127`），一旦 `METRICS_TOKEN` 不匹配（401）或服务起不来，Prometheus 侧表现为"该 job 无数据"而非显式告警——运营看到的是**所有指标消失**（等同"零流量"），极易误判为"业务停了"而不是"监控断了"。同时 `absent()` 类规则也缺失。
  2. **token 成本 / 配额**：`medassist_agent_consult_tokens_total{kind}`（`consult_metrics.py:139-142`）已计量但**无任何规则消费**；也没有 `medassist_agent_consult_tokenized_requests_total` 覆盖率（token 采集率）的告警——provider 侧若某天不再返回 usage，token 指标会静默归零而无人知晓（这正是 G104 强调"绝不估算"的副作用，需要一个"采集率下跌"告警来对冲）。
  3. **回答质量下降**：链路里已有质量信号但**一个都没被计量**——`consult.py:322` 恒写 `needsHumanReview=True`（是常量，不是信号），没有任何"低质量回答/空答案/拒答/证据缺失"计数；`consult.py:356-357` 把"模型未返回内容"归为 `error`，无法区分"模型故障"与"模型拒答"。
- **建议方向**：补 `up == 0`（或 `absent(medassist_agent_consult_requests_total)`）规则；补 token 速率与 token 采集覆盖率规则；在咨询链路增加低质量信号（例如答案过短、缺证据引用、拒答）并单独计数/告警。severity 建议分层（critical/warning/info 已用 ✅）。

### S2 · `for` 时长与 `evaluation_interval` 未对齐，`for: 10m` 配 `rate[10m]` 会叠加成约 20 分钟的实际响应时间

- **位置**：`deploy/observability/prometheus/prometheus.yml:13-14`（`scrape_interval: 30s`、`evaluation_interval: 30s`）；`deploy/prometheus/medassist-agent-ops.rules.yml:63-86`（G102 两条 `for: 10m`，表达式用 `[10m]` 窗口）
- **问题**：`scrape_interval` 30s 意味着窗口内只有约 20 个样本，`rate()`/`increase()` 的样本密度偏低、噪声相对更大（对低 QPS 的院内场景尤其明显）。同时 `for: 10m` + `[10m]` 窗口 ⇒ 从"首次出错"到"告警 firing"实际约 20 分钟；而 G102 两条规则的语义是"近 10 分钟错误率 > 20%"，即**窗口已经在描述过去 10 分钟**，`for` 再加 10 分钟纯属叠加延迟。
  - 另需注意：`for` 期间若错误率回落，告警不会 firing（这是 `for` 的正常语义），但由于窗口与 `for` 等长，短暂尖峰会**完全被抹平**——一次 10 分钟内的模型故障风暴可能既不 firing、也不留下痕迹（Prometheus 不保留 firing 前的历史）。
- **建议方向**：G102 两条规则把窗口拉长到 `[30m]` 并把 `for` 缩短到 `5m`（或去掉 `for`，改用 `increase` 阈值本身做去噪）；`scrape_interval` 对低 QPS 场景可放宽到 30s–60s。G97 那几条 `for: 6h/24h` 的阈值型规则是合理的，保留。

### S3 · `medassist_agent_consult_requests_total` 的 counter 归零（进程内）在多副本聚合下的语义需明示

- **位置**：`backend/agents/consult_metrics.py:14-18`（"多实例部署时由 Prometheus 按实例聚合；进程重启计数归零（标准 counter 语义）"）、`:150-157`（`reset()`）
- **问题**：注释把"进程重启归零"称作"标准 counter 语义"——**Prometheus 的标准 counter 语义恰恰相反**：counter 必须在生命周期内单调递增，重启归零属于"进程重启型 counter"，Prometheus 通过 `rate()` 的 reset 检测来容忍，但**原始 counter 值不可跨重启比较**。
  - 实际影响：Helm 里 `aiCore.replicaCount: 1` + `strategy: Recreate`（`values.yaml:593-598`）⇒ **每次发布都会让全部 counter 归零**。`rate()` 能处理，但如果有人写 `sum(medassist_agent_consult_requests_total)` 这种"总量"查询，会得到一个随发布跳变的错误数字。
  - 另外 `consult_metrics.reset()`（`:150-157`）是**公开可调用的清零接口**，测试在用（`tests/test_consult_metrics.py:33-35`），生产代码路径上没调用——但它一旦被误用（例如想做个"运维清零"），Prometheus 侧会看到一次人为 reset，行为上等价于进程重启。当前没有把它做成私有或加保护，属于潜在风险点。
- **建议方向**：在注释与文档里把"重启归零"与"counter 单调性"的关系讲清楚，并明确"总量"查询必须用 `increase()`/`rate()` 而不是裸 counter 求和；把 `reset()` 标注为"仅测试用"或加 `if not _TESTING: raise`。G105 文档（`docs/AGENT-RUNTIME-20261105.md:90`）声称"真实容器抓取 target=up 并查询到 drug/ok=1、token 109/509"——这类**裸 counter 查询**在多副本下同样会失真，建议示例改用 `increase()`。

### S4 · `skill_trends` 的快照是"持久化数据快照"，与 `consult_metrics` 的"实时进程内计数"被放在同一运营面板下，窗口语义未在 UI 上区分

- **位置**：`backend/agents/skill_trends.py:63-77`（`snapshot()` 存的是 `skill_ops.metrics()`，**不含任何 consult/skill_usage/token 字段**）、`:86-115`（`trends()` 只对 experiences/proposals/published/unusedPublished/adoptionRate/replayPassRate 求差）、`frontend-vue/src/views/AgentView.vue:310-320` 与 `:385-443`（两块卡片）
- **问题**：`skill_trends` 的快照口径是**技能库资产的持久化状态**（经验/候选/技能卡/Bot），而 `skill_usage` 的口径是**问答运行时的进程内计数**。两者在同一个 Agent 控制台上并列展示，但：快照**没有**任何问答侧的字段（`requests`/`tokens`/`skillUsage` 一个都没有），所以"按日快照"无法用来做问答量的日环比——运营看到"有按日趋势"，但趋势里只有技能库资产，没有服务运行质量。
  - 另一处口径落差：`skill_usage.report()` 的 `window` 字段是 `"process"`（`skill_usage.py:85`），`note` 也写明"服务重启归零"（`:19-22`），而快照侧是持久化的——UI 上快照卡片与使用率卡片**紧邻**（`AgentView.vue:385` 紧跟 `:310` 区块），没有任何"这两个数字时间窗口不同"的视觉提示。
- **建议方向**：要么把问答侧计数也纳入按日快照（只存聚合值、不存原文，符合现有隐私边界），要么在 UI 上给两张卡片加显式的窗口标签（"持久化资产快照" / "本进程运行时"）。另：`skill_trends.prometheus_text()`（`:183-237`）每次被抓取都会**同步重算** `skill_ops.metrics()`（读全量 skills/bots/proposals/experiences）**并重跑 `alerts()`**（内部又调一次 `skill_ops.metrics()`），在 30s 抓取间隔下是对存储层的重复读放大——建议加短 TTL 缓存。

### S5 · `skill_advice` 的建议维度与 `skill_usage` 看板维度不一致，且建议生成会**只取前 5 条**

- **位置**：`backend/agents/skill_advice.py:102`（`for p in pending[:5]`）、`:110`（`for skill_code in unused[:5]`）、`:23`（`MAX_PER_RUN = 20`）、`:156-157`（`if len(created) >= MAX_PER_RUN: break`）
- **问题**：
  - `UNUSED_SKILL` 建议的 `targetId` 是**技能 code**，与 `skill_usage` 看板的 `items[].code` 维度**可对齐**（✅ 好的一面）；但 `PROPOSAL_REVIEW` 的 `targetId` 是**候选 id**，而看板没有候选维度，运营无法从建议跳到对应看板行。
  - `[:5]` 是**静默截断**：候选积压 20 个时只生成 5 条建议，且没有"还有 N 条未处理"的提示——`skill_trends` 的 `PROPOSAL_BACKLOG` 告警说的是"待处理候选 N 个"，建议列表却只有 5 条，运营无法对账。`MAX_PER_RUN` 的 `break` 同理。
  - `REPLAY_REVIEW` / `EXPERIENCE_GAP` 的 `targetId=None` 使其**完全无维度**（见 M8）。
- **建议方向**：把"截断"变成显式信息（在建议/告警里带上 `total` 与 `shown`）；`targetId` 尽量对齐看板已有维度；或引入 `targetType`+`targetId` 的统一寻址约定，让"建议 → 看板行 → 后台对象"三者可互相跳转（`targetType` 字段已经存在，`skill_advice.py:84`，但未用于前端聚合）。

### S6 · `usageRatio` 与 `skill_trends` 的采纳率/覆盖率在**语义命名**上会被混读

- **位置**：`backend/agents/skill_usage.py:117`（`usageRatio`）、`backend/agents/skill_ops.py:48`（`coverageRate`）、`:55`（`adoptionRate`）；`frontend-vue/src/views/AgentView.vue:316-317` 与 `:411-413`
- **问题**：三个"率"在 UI 上分别叫「覆盖」（`coverageRate` = 被候选覆盖的经验/经验总数）、「采纳」（`adoptionRate` = 已采纳候选/候选总数）、「使用率」（`usageRatio` = 被问答引用过的已发布技能/已发布技能）。三者分母完全不同（经验库 / 候选库 / 已发布技能卡），且「覆盖率」与「使用率」在中文语境下极易被当作同一件事的两种说法。`skill_trends` 的 Prometheus 侧又叫 `medassist_agent_experience_coverage_ratio`（`skill_trends.py:194`）与 `medassist_agent_proposal_adoption_ratio`（`:202`），而 `usageRatio` **根本没有 Prometheus 指标**——运营无法对"看板使用率"和"Prometheus 侧"做交叉验证。
- **建议方向**：给三者统一的命名规范（领域 + 分子分母），并在 UI 上把分母直接写在标签里（如"技能使用率 3/8"）；若要给 `usageRatio` 做长期趋势，应先按 M5 修正口径再导出 Prometheus 指标。

### S7 · `skill_ops.metrics()` 与 `skill_trends.prometheus_text()` 的**每次调用成本**在 30s 抓取间隔下偏高

- **位置**：`backend/agents/skill_ops.py:20-75`（每次调用都 `list_experiences` + `list_proposals` + `list_skills` + `list_bots` + `list_teams`，全部走 `pg_store`/JSON 全量加载）、`backend/agents/skill_trends.py:185-186`（`prometheus_text()` 内 **调用了两次** `skill_ops.metrics()`：一次直接调、一次经由 `alerts()`）
- **问题**：`prometheus_text()` 里 `skill_ops.metrics()` 被执行 **2 次**（`:185` 与 `alerts()` 内部的 `:124`），每次都做 5 次全量列表加载。在 `pg_store` 模式下这些是数据库全表读（`pg_store.load_json_list`）。30s 抓取 ⇒ 每分钟 10 次全量读 × 2 次 = 20 次全量读，在数据量增长后会成为存储层负担。
- **建议方向**：在 `prometheus_text()` 内先算一次 `m = skill_ops.metrics()` 并把结果**传入** `alerts()`（消除重复计算），并给 `skill_ops.metrics()` 加一个短 TTL 缓存（如 10–30s）——Prometheus 的抓取间隔天然容忍这种近似。

### S8 · `skill_trends.alerts()` 的 `REPLAY_PASS_RATE_DROP` 比较对象是**快照列表中相邻两条**，但 `list_snapshots(2)` 的排序键只有 `date`

- **位置**：`backend/agents/skill_trends.py:80-83`（`sorted(_load(), key=lambda i: i.get("date") or "")`）、`:164-172`（`prev = snaps[0]`、`cur = snaps[1]`，`drop = prev - cur`）
- **问题**：快照按"业务日期"幂等（同日 upsert，`skill_trends.py:63-77` + `pg_store.upsert_json_item`），所以**同一天只有一条**——`list_snapshots(2)` 拿到的确实是"前一个业务日"和"当前业务日" ✅ 逻辑成立。但：若某天**没有生成快照**（快照是**手动触发**的 `POST /skill-library/snapshot`，`agent_router.py:736-749`，**没有任何定时任务**——见 S9），则 `drop` 的比较跨越了任意长的时间间隔（例如昨天 vs 3 天前），却仍以"较上次快照下降"呈现（`skill_trends.py:171` 文案），**时间跨度未出现在告警/建议里**，运营会误判为"突然下降"。
- **建议方向**：在 `REPLAY_PASS_RATE_DROP` 的告警与建议里带上两个快照的日期与间隔天数；并把 `drop` 的比较限制在"相邻自然日"（跨度过大则不比较或不告警）。

### S9 · 按日快照**没有任何自动触发器**，G94 的"趋势"实际上依赖人工点按钮

- **位置**：`backend/api/v1/agent/agent_router.py:736-749`（`POST /skill-library/snapshot`，唯一入口）、`backend/agents/skill_trends.py:63-77`（`snapshot()`）；全仓无定时任务/调度器（`scripts/` 下无 cron、无 scheduler）
- **问题**：`snapshot()` 只能由管理员/质控**手动调用**。这意味着：
  - `trends()` 在没有人工点击时永远 `sufficient=false`（`skill_trends.py:89-95`），"趋势"功能形同虚设；
  - `REPLAY_PASS_RATE_DROP`（S8）同样依赖快照被持续生成；
  - Prometheus 侧的 `medassist_agent_*` 系列全部是**实时**值（`skill_trends.prometheus_text()` 每次重算），因此 Prometheus 有连续时序，但"按日"这条口径只有人工点出来的离散点——**同一指标在两个系统里一个是连续时序、一个是稀疏采样**，没有交叉校验。
- **建议方向**：提供容器内定时任务（cron/apscheduler，或一个 `python -m backend.agents.skill_trends --daily` 的 sidecar）按业务日幂等生成快照；或改为"由 Prometheus 时序本身承担日粒度趋势"，把 `skill_trends.snapshot` 降级为"按需留存"。**需人工确认**：院区内是否允许常驻定时任务（若有统一调度平台应接入而非自建）。

### S10 · `medassist_agent_proposals_total` 用 `status` 标签，但未覆盖全部状态取值

- **位置**：`backend/agents/skill_trends.py:196-200`（只输出 `proposed`/`adopted`/`rejected` 三个取值）、`backend/agents/skill_ops.py:31-32`（状态实际为 `PROPOSED`/`ADOPTED`/`REJECTED`）、`backend/agents/skill_library.py:275`（创建时 `status: "PROPOSED"`）
- **问题**：目前状态集合恰好是三者，一致 ✅。但 `skill_trends.py:198` 硬编码了三个取值，若将来引入新状态（例如 `WITHDRAWN`/`EXPIRED`），该指标会**静默漏掉**这部分而 `skill_ops.metrics()` 的 `proposals.total`（`:51`）却会计入 → `sum(medassist_agent_proposals_total) != proposals.total`，运营无从察觉。
- **建议方向**：从 `byStatus` 字典动态导出所有状态（`skill_ops` 已经有 `card_status` 的现成模式，`skill_ops.py:34-36`），或加一条断言"各分状态之和 == total"的规则/单测。

### S11 · `medassist_agent_skill_cards` 与 `medassist_agent_experiences_total` 等**聚合 gauge** 缺 `by` 维度，无法定位到具体能力域

- **位置**：`backend/agents/skill_trends.py:190-236`（`experiences_total` 单值无 `agentId`；`skill_cards` 有 `status` 标签但无 `agentId`）、对照 `backend/agents/skill_ops.py:45-47`（`experiences.byAgent` **已经**按 agentId 聚合了）
- **问题**：`skill_ops.metrics()` 明明已经算出了 `experiences.byAgent`（`skill_ops.py:46`），但 Prometheus 导出时丢掉了这个维度（`skill_trends.py:192` 只输出总数）。结果是告警「经验覆盖率偏低」**无法定位到哪个能力域**——运营只能自己去 API 查。对院内多科室场景，这是很实际的排查成本。
- **建议方向**：为 `experiences`/`skill_cards` 增加 `agentId` 标签（或至少导出 `byAgent` 分解），并让 `MedassistAgentExperienceCoverageLow` 这类告警能按能力域聚合（`sum by (agentId)`）。

### S12 · `consult_metrics` 的 `agent`/`channel`/`status` 标签值虽已白名单化，但 **`skill` 标签的 `__other__` 归并策略会让"溢出"不可观测

- **位置**：`backend/agents/consult_metrics.py:74-79`
- **问题**：`if key not in _skill_usage and len(_skill_usage) >= MAX_SKILL_LABELS: key = OTHER_SKILL`——正确 ✅（且 `_skill_last_used[key]` 也随之写入 `__other__`）。但归并后 `__other__` 的计数**混装了所有溢出技能**，且 Prometheus 侧没有任何"已发生归并"的信号（如 `medassist_agent_skill_labels_overflowed`）。
- **建议方向**：加一个"已发生归并"的布尔/计数指标，让 `__other__` 的出现本身可见；否则一个突然涌入 300 个技能的畸形场景会表现为"用量突然集中到 `__other__`"，难以定位。

### S13 · `consult_metrics.prometheus_text()` 每次都重新排序与拼接，且 `_durations` 的 `_count` 与 `_sum` **分两个指标族**暴露

- **位置**：`backend/agents/consult_metrics.py:99-147`（`sorted(...)` + 字符串拼接，每次抓取全量重建）
- **问题**：
  - `medassist_agent_consult_duration_ms_sum` / `_count` 是**两个独立 counter**，而不是一个 histogram/summary 的组成部分。Prometheus 惯例下 `_sum`/`_count` 后缀属于 **summary/histogram** 家族；这里手写两个 counter 会让 `histogram_quantile()` 完全不可用（**无法算 P95/P99 时延**——这恰恰是容量与故障演练最需要的指标，见 G97）。
  - 同理 `medassist_agent_consult_requests_total` 只按 `(agent,channel,status)` 聚合，没有直方图，**问答时延分布不可观测**。
  - 拼接效率问题相对次要（数据量小），但 `sorted()` + f-string 每次全量重建，在技能标签接近 200 上限时会做不少无用功。
- **建议方向**：把耗时改为标准 histogram（`medassist_agent_consult_duration_seconds` + `_bucket`），这样 P95/P99 与 `histogram_quantile` 都能用；并把 `rate` 类告警建立在 histogram 上（比 `sum/count` 手工平均更准）。这是 G97 容量演练真正缺的那块。

### S14 · `frontend-vue/src/views/AgentView.vue` 的看板把**进程内计数**与**持久化指标**放在同一页面，且错误率阈值与 Prometheus 规则阈值**未对齐**

- **位置**：`frontend-vue/src/views/AgentView.vue:394-414`（`errorRate > 0.2` 判红、`abortedRate > 0.2` 判黄、`usageRatio >= 0.5` 判绿）、`backend/agents/skill_usage.py:93-95`（errorRate/abortedRate/successRate 均为**进程累计占比**，不是时间窗比率）、`deploy/prometheus/medassist-agent-ops.rules.yml:63-72`（Prometheus 侧错误率是 **10 分钟窗口 rate 比率**）
- **问题**：
  - **同一阈值（20%）但两个完全不同的量**：看板的 `errorRate` 是"本进程启动以来 error/total 的累计占比"，Prometheus 的是"近 10 分钟窗口内 error rate"。低 QPS 下两者可能相差一个数量级（进程累计 2% 看着很安全，10 分钟窗口 40% 已经在报警）。运营无法解释为什么"看板不红但 Prometheus 在报"。
  - `abortedRate > 0.2` 判黄同理：`aborted` 来自客户端断开（`consult.py:467-470`），SSE 长连场景下用户正常关页面也会计入，累计占比天然偏高。
  - `usageRatio >= 0.5` 的绿/黄判定基于 M5 里那个饱和到 ~1.0 的口径，实际上永远不会黄。
  - 页面**没有**把"进程内、重启归零"这一关键前提放在显眼位置——只在 `usage.note`（`skill_usage.py:19-22`）里，而 `AgentView.vue:393` 的 `{{ usage.note }}` 用的是 `<div class="src">` 小字样式，容易被忽略。
- **建议方向**：统一看板与 Prometheus 的**时间窗口径**（看板改成"近 N 分钟窗口"，与规则一致），或者在 UI 上明确标注"累计值，重启归零"；并把阈值来源写成常量而非前端硬编码的 `0.2`/`0.5`。

---

## 专项核对：自检脚本与实际导出的一致性（任务项 5）

### 结论：`check_prometheus` 的指标名与后端导出**逐字一致**，但检查的**端点**与生产抓取端点**不是同一个** → 自检永远通过，却不能代表真实抓取链路可用

- **位置**：`scripts/agent_go_live_check.py:87-97`（`check_prometheus`）、`:154-159`（请求 `/api/v1/agent/skill-library/metrics/prometheus`）
- **逐字核对**（脚本 `required` 元组 `:88-94` vs 后端真实导出）：

| 脚本要求的指标名 | 后端导出位置 | 是否逐字一致 |
|---|---|---|
| `medassist_agent_experiences_total` | `skill_trends.py:192` | ✅ 一致 |
| `medassist_agent_replay_pass_ratio` | `skill_trends.py:209` | ✅ 一致 |
| `medassist_agent_unused_published_skills` | `skill_trends.py:222-223` | ✅ 一致 |
| `medassist_agent_operational_alerts` | `skill_trends.py:233-235` | ✅ 一致 |
| `medassist_agent_consult_requests_total` | `consult_metrics.py:102-108` | ✅ 一致 |
| `medassist_agent_skill_usage_total` | `consult_metrics.py:124-129` | ✅ 一致 |
| `medassist_agent_consult_tokens_total` | `consult_metrics.py:139-142` | ✅ 一致 |

- **但存在三个问题**：
  1. **检查的是错端点**：脚本打的是 Agent 运营端点（`agent_router.py:729-733`，鉴权 `require_role("admin","qc")`），而 Prometheus 实际抓的是 `/metrics`（`prometheus.yml:27`、`servicemonitor.yaml:64`、`main.py:267-275`）。按 M1，`/metrics` 上**没有**前 4 个 `skill_trends` 指标——如果自检改打 `/metrics`（这才是有意义的检查），它会**立刻失败**并报"missing=medassist_agent_experiences_total, ..."。也就是说：**自检现在永远通过，是因为它检查了一个 Prometheus 不会用的端点。**
  2. **不检查 `/metrics` 本身**：脚本从不请求 `/metrics`，因此 **BOM/尾随换行/令牌不匹配/路径错误**（M10）这一整类抓取失败，自检**完全测不出来**。而 G105 的文档恰恰把 `/metrics` 视为上线门槛（`docs/OPERATIONS.md:59-61`）。
  3. **不检查 token 采集率与 HELP/TYPE 完整性**：脚本用 `m not in text` 的子串匹配（`:95`），只能验证"指标名出现过"；它既无法区分"指标存在但值为 0"（例如 `medassist_agent_consult_tokens_total` 因为 provider 不返回 usage 而恒为 0），也无法发现 HELP/TYPE 缺失。
- **建议方向**：把 `check_prometheus` 拆成两项——(a) 对 **`/metrics`**（真实抓取端点）断言**完整指标名清单**（含 `skill_trends` 系列），这一项会直接暴露 M1；(b) 增加一条**用 `METRICS_TOKEN` 实抓 `/metrics` 并断言 200** 的检查，覆盖令牌/BOM/路径问题。并把 `check_prometheus` 的断言从"名字出现过"升级为"名字 + `# TYPE` 行存在"。

---

## 专项核对：抓取链路正确性（任务项 4）

### 4.1 `prometheus.yml` 的 scrape_configs

- **位置**：`deploy/observability/prometheus/prometheus.yml:21-34`
- **逐项核对**：
  - `scheme: http`（`:28`）✅ 与 AI 中台明文 HTTP 一致（Helm Service 走 ClusterIP + `path: /metrics`，无 TLS ✅）。
  - `authorization.type: Bearer` + `credentials_file: /etc/prometheus/metrics-token`（`:29-31`）✅ **配置形式正确**——Prometheus 2.26+ 支持 `authorization.credentials_file`，且 Prometheus 会**去掉文件内容的尾部换行**（与后端 `token.strip()` 双保险 ✅）。但 **BOM 不在 Prometheus 的清理范围内**，后端 `str.strip()` 也不清 BOM → BOM 必然 401（见 M10）。
  - `metrics_path: /metrics`（`:27`）⚠️ 见 M1。
  - `static_configs.targets: ["host.docker.internal:8001"]`（`:33`）+ compose `extra_hosts: host-gateway`（`docker-compose.observability.yml:105-107`）✅ 形态正确。**但**：AI 中台默认在**宿主机**直跑 uvicorn 8001（`docs/DEPLOY.md:277-278`），此时 Prometheus 容器访问 `host.docker.internal:8001` 命中的是**宿主机**进程 ✅；若改用 `docker compose --profile full` 的 `medagent_app` 容器（`docker-compose.yml:131-147`，同样暴露 8001），也仍能命中 ✅。两种形态都成立，✅ 可保持。
  - **缺失 `relabelings`**：`prometheus.yml` 里 ai-core job 只有静态 `labels: {component: ai-core}`（`:34-35`），**没有**把 `instance` 之外的信息（如 job 内多目标区分）保留下来。目前单目标，尚可；但**没有 `scrape_interval` 覆盖**（用全局 30s ✅），也没有 `scrape_timeout`（默认 10s ✅，Helm 侧显式给了 `scrapeTimeout: 10s`，`values.yaml:679`）。
  - **缺失 `honor_labels` / `metric_relabelings`**：不需要 ✅。
  - **告警规则挂载**：`rule_files: - /etc/prometheus/rules/*.yml`（`:19`）✅ 与 compose 挂载 `../../deploy/prometheus:/etc/prometheus/rules:ro`（`docker-compose.observability.yml:103`）对应。注意该目录当前**只有** `medassist-agent-ops.rules.yml` 一个文件 ✅（已实测确认）。

### 4.2 compose 里的相对路径解析基准（任务明确要求核对）

- **位置**：`deploy/observability/docker-compose.observability.yml:100-104`
- **问题**：Docker Compose 的相对路径**以 compose 文件所在目录为基准**（不是当前工作目录）。该文件的三条相对挂载：
  - `./prometheus/prometheus.yml` → 解析为 `deploy/observability/prometheus/prometheus.yml` ✅ **正确**（文件确实在此）。
  - `./prometheus/metrics-token` → `deploy/observability/prometheus/metrics-token` ✅ 正确（已在 `.gitignore:8`）。
  - `../../deploy/prometheus:/etc/prometheus/rules:ro` → 解析为 `<repo>/deploy/prometheus` ✅ **正确且刻意**（把规则文件与观测栈解耦，`deploy/prometheus` 才是规则的唯一真源）。
  - **结论：三条路径全部正确** ✅。但 `../../deploy/prometheus` 这种"上跳两级再进 deploy"的写法**脆弱**：若未来有人把观测栈目录层级调整、或用 `docker compose -f deploy/observability/docker-compose.observability.yml` 从不同 cwd 启动并叠加了其它 `-f` 文件（多文件时基准目录为**第一个** `-f` 文件所在目录），这条路径就会解析到错误位置。建议改为在 `deploy/observability/prometheus/` 下放一份 rules 或用符号链接/复制脚本，保证所有相对路径**同深度**。
  - 另外 `:104` 的 `medagent_prometheus:/prometheus` 与 `:19` 的 `rule_files` 指向的 `/etc/prometheus/rules` 是**只读**挂载 ✅，Prometheus 不需要写权限 ✅。

### 4.3 Helm ServiceMonitor 的 `authorization.credentials` 引用

- **位置**：`deploy/helm/medassist-platform/templates/servicemonitor.yaml:67-71`
- **核对结论**：
  - `credentials.name` 引用的 Secret 由 `.Release.Namespace` 决定（ServiceMonitor 的 `metadata.namespace: {{ .Release.Namespace }}`，`:49`）→ Prometheus Operator **在 ServiceMonitor 所在 namespace 内解析该 Secret** ✅。而 `deployment-ai-core.yaml:133` 用**同一个 Secret 名**注入 `METRICS_TOKEN`，也在同 namespace ✅。**两侧一致，引用方式正确** ✅。
  - `credentials.key` 取 `values.yaml:688` 的 `tokenSecretKey`（默认 `metrics-token`），与 `deployment-ai-core.yaml:134` 用的 `key` 是**同一个变量** ✅ 一致，不会出现"抓取用一个 key、应用注入用另一个 key"的错配。
  - `required`（`:70`）保证 `tokenSecretName` 为空时 Helm 直接失败 ✅（但只校验非空，不校验存在，见 M9）。
  - `port: http`（`:63`）✅ 与 `services.yaml:202` 的 `ports: - name: http` 匹配。
  - `selector` 用 `medassist.selectorLabels` + component=ai-core（`:61`）✅ 与 `services.yaml:206-207` 的 selector 一致。
  - `relabelings` 打 `component: ai-core`（`:72-74`）✅ 与 Java ServiceMonitor 的做法一致（`:38-42`），保证告警可按 component 过滤。
  - **`namespaceSelector` 只选本 namespace**（`:56-58`）——ai-core 与 Prometheus 若不同 namespace 需注意 ✅ 当前形态可用。
- **唯一实质缺口**：Secret 存在性（见 M9）与**该 ServiceMonitor 抓的 `/metrics` 不含 `skill_trends` 指标**（见 M1）。

### 4.4 令牌文件的 BOM / 换行对 Authorization 头的影响（任务明确要求核对）

- **位置**：`deploy/observability/prometheus/prometheus.yml:5-8`（注释已给出正确写法）、`deploy/observability/prometheus/metrics-token.example`（实测 28 字节，**含尾随 0x0A**）、`backend/api/deps.py:99-126`
- **核对结论**：
  - **尾随换行**：Prometheus 读 `credentials_file` 时会 `TrimSpace` ✅，且后端 `deps.py:118` 也做 `token.strip()`、`expected.strip()` ✅ → **双重容忍，安全**。
  - **BOM（`\xEF\xBB\xBF`）**：Prometheus **不清 BOM**，Python `str.strip()` **也不清 BOM**（BOM 不是空白字符，`\ufeff` 不在 `str.strip()` 的空白集合内）→ `secrets.compare_digest` 必然不相等 → **401**，且错误信息只有"未认证：需 admin 登录或有效抓取令牌"（`deps.py:123`），**没有任何"你的文件带 BOM"的提示**。这是真实可复现的失败模式。
  - **示例文件本身带尾随换行且内容是占位符** → 直接 Copy 会同时引入"占位令牌"和"换行"两个问题（见 M10）。
  - **`.env.local` 一侧**：`config.py:138` 的 `metrics_token` 走 pydantic settings，读 `.env.local` 时若该行末尾有 `\r`（Windows CRLF），`expected.strip()` 会清掉 ✅ 无风险。
- **建议方向**：把"无 BOM"从注释里的提醒升级为**机器校验**——`agent_go_live_check.py` 里加一步：读令牌文件 → 断言首 3 字节不是 `EF BB BF` → 用它实抓 `/metrics` 断言 200。示例文件改为不含换行、且内容明显不可用（如 `<generate-with-openssl-rand-hex-32>`）。

---

## 值得保持的做法

1. **隐私边界守得很死**：计量只做进程内聚合，**不落库、不记录问题/回答原文**（`consult_metrics.py:1-4`）；Prometheus 导出不含任何业务标识或 PHI（`skill_trends.py:184` 注释 + 全部指标均为聚合值）。审计侧也只记长度与耗时（`consult.py:196-205`）。这套边界在 G102–G105 新增指标时**没有被稀释** ✅。
2. **token 用量"绝不估算"**：`consult.py:164-190` 的 `_usage_from` 对缺项/非法值/负值一律返回 `None`；`consult_metrics.py:44-55` 的 `_normalize_tokens` 再做一次同样的校验（双层防护 ✅）。`tests/test_consult_metrics.py:88-108` 覆盖了 6 种 provider 返回变体。这是本批次最值得肯定的严谨之处——**宁可少数据也不造数据**。
3. **失败/中止不计入技能使用**：只有 `status == "ok"` 才累计 `skill_usage` 与 `skill_last_used`（`consult_metrics.py:72-79`），避免了"失败请求也算用到了技能"的虚高。
4. **同一请求内技能去重**：`dict.fromkeys(skill_codes or ())`（`consult_metrics.py:74`）保证同一请求里同编码只计 1 次 ✅。
5. **技能标签基数有上限并显式归并**：`MAX_SKILL_LABELS = 200` + `__other__`（`consult_metrics.py:26-27,74-79`），并有对应单测（`tests/test_consult_metrics.py:116-121`）。这是正确的 Prometheus 基数治理思路，只是**没有覆盖 agent 标签**（见 M3）。
6. **标签值做了 Prometheus 转义**：`_esc` 处理反斜杠/引号/换行（`consult_metrics.py:38-41`），有单测覆盖（`tests/test_consult_metrics.py:110-114`）。很多自研 exporter 在这一步翻车 ✅。
7. **路由模板代替真实路径做时延标签**：`main.py:203` 的 `getattr(request.scope.get("route"), "path", "unmatched")` —— 防止 `/api/v1/patient/{id}` 这类路径产生无限基数 ✅，这是 Prometheus 基数治理的标准做法。
8. **G97 告警阈值与后端 `skill_trends` 阈值逐条对齐**（人工核对 ✅）：回放通过率 0.8 且 `replayChecked>=3`（`skill_trends.py:26-27,133` ↔ `rules.yml:15-16`）；未引用技能 ≥3（`skill_trends.py:30,145-149` ↔ `rules.yml:25`）；候选积压 ≥5（`skill_trends.py:31,151-155` ↔ `rules.yml:34`）；经验覆盖率 0.5 且经验数 ≥4（`skill_trends.py:32-33,157-162` ↔ `rules.yml:44-45`）。**四处阈值全部一致** ✅，这在告警规则与业务代码分离的实现里并不常见，值得保持。
9. **告警规则描述里带"不要通过放宽校验降错误率"这类反作弊提示**（`rules.yml:77`、`skill_advice.py:122`）——把"指标可能被拿来操纵"的风险写进告警文案 ✅。
10. **运营建议只建议不执行，且可忽略/完成并留原因**（`skill_advice.py:1-7,172-195`）；同一 `code + targetId` 去重且**已忽略/已完成的不会被覆盖回 OPEN**（`:134-140`，G96 用 `ignore_existing=True` 修过并发覆盖）✅。
11. **快照按业务日期幂等 + 多实例行级 upsert**（`skill_trends.py:63-77` + `pg_store.upsert_json_item`），并发调用不会写出多条同日记录，有并发测试（`tests/test_agent_ops_drill.py:57`）✅。
12. **历史不足 2 天时显式 `sufficient=false`，绝不插值/推测**（`skill_trends.py:86-96`）✅ —— "没有历史就不画趋势"是运营指标的正确原则。
13. **抓取鉴权用常量时间比较**：`secrets.compare_digest`（`deps.py:99-105`）✅ 避免时序侧信道；且令牌未配置时该通道 fail-closed（`deps.py:117,120-127`）✅。
14. **抓取令牌与 admin 登录态二选一，且令牌只映射成 `role="metrics"` 的虚拟用户**（`deps.py:118-119`）——不映射真实用户 ✅ 审计上可区分。
15. **规则文件与代码分离**：告警 YAML 独立于后端代码，便于院内 Prometheus 自行管理 ✅；且规则文件头注释写明了配套接口与"指标均为聚合值，不含业务标识或 PHI"（`rules.yml:1-9`）——注释写得清楚（虽然 M1 指出它指向的接口与实际抓取端点不一致）。
16. **Prometheus 侧保留了 Java 服务与 AI 中台的区分**：`component` 标签在 Java ServiceMonitor（`servicemonitor.yaml:38-42`）与 ai-core（`:72-74`）上都打 ✅。

---

## 证据附录

### 附录 A · 关键片段

**A1 · `/metrics` 与 Agent 端点导出的指标集合不一致（M1 证据）**

`backend/main.py:267-275`：
```python
@app.get("/metrics", include_in_schema=False)
async def metrics(user: dict = Depends(require_metrics_access)):
    """运维指标：admin 登录态或 Prometheus 抓取专用 METRICS_TOKEN。..."""
    from backend.agents import consult_metrics
    return PlainTextResponse(observability.exposition() + consult_metrics.prometheus_text(),
                             media_type="text/plain; version=0.0.4")
```
→ 只有 `observability`（`backend/core/observability.py`）与 `consult_metrics` 两族。

`backend/api/v1/agent/agent_router.py:729-733`：
```python
@router.get("/skill-library/metrics/prometheus", response_class=PlainTextResponse)
async def skill_library_prometheus(user: dict = Depends(require_role("admin", "qc"))):
    """Prometheus 文本导出（聚合指标，不含业务标识/PHI）；供监控采集。"""
    return PlainTextResponse(skill_trends.prometheus_text() + consult_metrics.prometheus_text(),
                             media_type="text/plain; version=0.0.4; charset=utf-8")
```
→ `skill_trends` 族只在这里，且鉴权是 admin/qc（Prometheus 的静态令牌进不来）。

**A2 · 规则引用的 5 个指标不在 `/metrics` 上（M1 证据）**

`deploy/prometheus/medassist-agent-ops.rules.yml:13-16`（示例）：
```yaml
      - alert: MedAssistAgentReplayPassRateLow
        expr: |
          medassist_agent_replay_pass_ratio < 0.8
          and medassist_agent_replay_checks_total >= 3
```
`medassist_agent_replay_pass_ratio` 的唯一定义点在 `backend/agents/skill_trends.py:207-209`（在 `skill_trends.prometheus_text()` 内），`/metrics` 路径不经过该函数。

**A3 · agent 标签在错误路径上回填用户输入（M3 证据）**

`backend/agents/consult.py:281-284`（抛错）：
```python
    if agent_id:
        agent = catalog.get_agent(agent_id)
        if agent is None:
            raise ConsultInvalid(f"未找到智能体：{agent_id}")
```
`backend/agents/consult.py:331-337`（把用户原文写进指标）：
```python
    started = time.perf_counter()
    try:
        ctx = _prepare(question, role, agent_id, skill_codes, history)
    except ConsultError:
        consult_metrics.record(agent_id or "unknown", "sync", "error",
                               (time.perf_counter() - started) * 1000)
        raise
```
`backend/agents/consult_metrics.py:62`（只截长不校验）：
```python
    agent = (agent_id or "unknown")[:64]
```

**A4 · `unusedPublished` 与 `unused` 的口径对照（M4 证据）**

`backend/agents/skill_ops.py:40-41,66`：
```python
    published_codes = {c["code"] for c in cards if c.get("status") == "PUBLISHED"}
    referenced_by_bots = {code for b in bot_list for code in (b.get("skillCodes") or [])}
    ...
            "unusedPublished": sorted(published_codes - referenced_by_bots),
```
`backend/agents/skill_usage.py:64-66`：
```python
    used_published = [c for c in usage if c in published]
    unused = sorted(c for c in published if c not in usage)
    used_not_published = sorted(c for c in usage if c not in published)
```
`backend/agents/consult.py:61-65`（默认全量生效，使二者可以同时为真/为假）：
```python
def select_skills(agent: catalog.AgentSpec, codes: list[str] | None) -> list[dict]:
    published = sorted(skills.published_for_agent(agent.id), key=lambda s: s["code"])
    if codes is None:
        chosen = published
```

**A5 · `skill_advice` 去重 key 使两类建议全库唯一（M8 证据）**

`backend/agents/skill_advice.py:76-87`：
```python
def _advice(code: str, severity: str, title: str, detail: str, action: str,
            target_type: str, target_id: str | None) -> dict:
    return {..., "dedupeKey": f"{code}:{target_id or '-'}"}
```
`backend/agents/skill_advice.py:117-130`（两处 `target_id=None`）：
```python
        elif code in ("REPLAY_PASS_RATE_LOW", "REPLAY_PASS_RATE_DROP"):
            drafts.append(_advice(
                "REPLAY_REVIEW", "high", ..., "LIBRARY", None))
        elif code == "EXPERIENCE_COVERAGE_LOW":
            drafts.append(_advice(
                "EXPERIENCE_GAP", "low", ..., "LIBRARY", None))
```
`backend/agents/skill_advice.py:134-137`（已存在即跳过，**不区分状态**）：
```python
        existing = {i.get("dedupeKey") for i in items}
        created = []
        for d in drafts:
            if d["dedupeKey"] in existing:
                continue
```

**A6 · prefilter 计入 error 率分母（M6 证据）**

`backend/agents/consult.py:339-342`：
```python
    if "prefilter" in ctx:
        consult_metrics.record("prefilter", "sync", "ok",
                               (time.perf_counter() - started) * 1000)
        return ctx["prefilter"]
```
`backend/agents/consult.py:231-241`（prefilter 的真实语义：不检索、不调模型）：
```python
def _prefilter_payload(decision: dict) -> dict:
    return {..., "appliedSkills": [], "declaredTools": [],
            "note": "前置应答：未检索、未调用模型、未使用技能卡。"}
```
`deploy/prometheus/medassist-agent-ops.rules.yml:66-68`（分母不过滤）：
```yaml
            sum(rate(medassist_agent_consult_requests_total{status="error"}[10m]))
            /
            sum(rate(medassist_agent_consult_requests_total[10m]))
```

**A7 · 令牌示例文件实测字节（M10 证据）**

`deploy/observability/prometheus/metrics-token.example` 实测 28 字节：
```
72 65 70 6C 61 63 65 2D 77 69 74 68 2D 61 2D 72 61 6E 64 6F 6D 2D 74 6F 6B 65 6E 0A
```
→ ASCII `replace-with-a-random-token` + **尾随 0x0A**。而 `docker-compose.observability.yml:85-86` 指示直接 `Copy-Item` 它当真实令牌。

**A8 · Helm ServiceMonitor 与 Deployment 用同一个 Secret 名与 key（M9 正确侧证据）**

`deploy/helm/medassist-platform/templates/servicemonitor.yaml:67-71`：
```yaml
      authorization:
        type: Bearer
        credentials:
          name: {{ required "serviceMonitor.aiCore.tokenSecretName 必填（抓取令牌 Secret）" .Values.serviceMonitor.aiCore.tokenSecretName }}
          key: {{ .Values.serviceMonitor.aiCore.tokenSecretKey }}
```
`deploy/helm/medassist-platform/templates/deployment-ai-core.yaml:128-135`：
```yaml
            {{- if .Values.serviceMonitor.aiCore.enabled }}
            # G105：Prometheus 抓取专用令牌（与 ServiceMonitor authorization 同一 Secret）
            - name: METRICS_TOKEN
              valueFrom:
                secretKeyRef:
                  name: {{ required "serviceMonitor.aiCore.tokenSecretName 必填（抓取令牌 Secret）" .Values.serviceMonitor.aiCore.tokenSecretName }}
                  key: {{ .Values.serviceMonitor.aiCore.tokenSecretKey }}
            {{- end }}
```
→ 两侧同名同 key ✅。**但**：`deployment-ai-core.yaml:128` 的条件是 `serviceMonitor.aiCore.enabled`，而 ServiceMonitor 本身的渲染条件是 `and .Values.aiCore.enabled .Values.serviceMonitor.aiCore.enabled`（`servicemonitor.yaml:43`）→ 若 `aiCore.enabled=false` 但 `serviceMonitor.aiCore.enabled=true`，**Deployment 会注入令牌但没有 ServiceMonitor**（`required` 仍会因 `tokenSecretName` 为空而 fail，实际会被挡下 ✅，但语义不对称，值得注意）。

**A9 · compose 相对路径解析基准核对结果**

`deploy/observability/docker-compose.observability.yml:100-104`：
```yaml
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/metrics-token:/etc/prometheus/metrics-token:ro
      - ../../deploy/prometheus:/etc/prometheus/rules:ro
      - medagent_prometheus:/prometheus
```
→ 以 compose 文件所在目录 `deploy/observability/` 为基准解析：第 1、2 条 → `deploy/observability/prometheus/...` ✅ 存在；第 3 条 → `<repo>/deploy/prometheus` ✅ 存在（实测该目录仅含 `medassist-agent-ops.rules.yml`，与 `rule_files: /etc/prometheus/rules/*.yml` 匹配 ✅）。

### 附录 B · 口径对照表

**B1 · 「未被使用的技能」三种定义**

| 模块 | 字段 | 位置 | 分母/范围 | 时间窗口 | 数据源 | 与其他口径的关系 |
|---|---|---|---|---|---|---|
| `skill_ops.py` | `skillCards.unusedPublished` | `:66` | 已发布技能 − **被任意 Bot 的 `skillCodes` 引用**的 | 无（持久化当前状态） | PG/JSON 持久化 | 与 `/consult` 的生效**完全解耦**：默认全部生效 ⇒ 可与"正在生效"同时为真 |
| `skill_usage.py` | `skills.unused` | `:65` | 已发布技能 − **被成功问答引用过**的 | 当前进程（重启归零） | `consult_metrics` 进程内计数 | 只认 `status="ok"` 的问答；与 `skill_ops` 的口径**可同时为真/为假** |
| `skill_usage.py` | `skills.usedNotPublished` | `:66` | 被计量过但当前非已发布 | 当前进程 | 同上 | 是"历史版本/已停用"的残留，**不混入分母** ✅ |
| `skill_usage.py` | `skills.usageRatio` | `:117` | `used / published` | 当前进程 | 同上 | 因 `/consult` 默认全量生效，有流量的 agent 上**饱和为 1.0**（M5） |
| `skill_trends.py` | `medassist_agent_unused_published_skills` | `:220-223` | 同 `skill_ops.unusedPublished` 的**条数**（非列表） | 每次抓取重算 | `skill_ops.metrics()` | 与 Prometheus 告警 `MedAssistAgentUnusedPublishedSkills` 同源 ✅ |
| `skill_advice.py` | `UNUSED_SKILL` 建议 | `:109-116` | 基于 `skill_ops.unusedPublished` | 持久化 | `skill_ops.metrics()` | **建议文案说"长期闲置"但口径是"未被 Bot 引用"** ⚠️ 措辞与口径不符 |

**B2 · 「率」的四种定义（分母完全不同）**

| 指标 | 位置 | 公式 | 分母 | Prometheus 侧 |
|---|---|---|---|---|
| 经验覆盖率 `coverageRate` | `skill_ops.py:48` | 被候选引用的经验数 / 经验总数 | 经验库 | `medassist_agent_experience_coverage_ratio`（`skill_trends.py:194-195`） |
| 候选采纳率 `adoptionRate` | `skill_ops.py:55` | ADOPTED 候选数 / 候选总数 | 候选库 | `medassist_agent_proposal_adoption_ratio`（`:202-203`） |
| 技能使用率 `usageRatio` | `skill_usage.py:117` | 被成功问答引用的已发布技能 / 已发布技能 | 已发布技能卡 | **无对应指标** ⚠️ |
| token 采集覆盖率 `coverageRatio` | `skill_usage.py:112` | 返回 usage 的成功问答数 / 成功问答数 | 成功问答 | **无对应指标** ⚠️（仅 `medassist_agent_consult_tokenized_requests_total` 一个计数，无比率） |
| 技能审核通过率 `reviewPassRate` | `skill_ops.py:63` | (PUBLISHED + DISABLED) / (PUBLISHED + DISABLED + REJECTED) | 已决技能卡 | `medassist_agent_skill_review_pass_ratio`（`:217-219`） |

**B3 · 快照（`skill_trends`）vs 实时（`consult_metrics`）vs 看板（`skill_usage`）**

| 维度 | `skill_trends.snapshot()` | `consult_metrics` | `skill_usage.report()` |
|---|---|---|---|
| 位置 | `skill_trends.py:63-77` | `consult_metrics.py:86-96` | `skill_usage.py:36-123` |
| 持久化 | ✅ PG `agent_skill_metrics` 行级 upsert（按业务日幂等） | ❌ 纯进程内 | ❌ 纯进程内（读 snapshot） |
| 覆盖内容 | 仅 `skill_ops.metrics()`：经验/候选/技能卡/Bot/团队 | 请求数/耗时/技能使用/token | `consult_metrics` + 已发布技能卡对齐后的使用率视图 |
| 问答侧字段 | **完全没有**（无 requests/tokens/skillUsage） | 全部 | 全部 |
| 时间粒度 | 按业务日（**需手动触发**，见 S9） | 实时累计 | 实时累计（`window: "process"`） |
| 触发方式 | `POST /skill-library/snapshot`（admin/qc） | 自动（每次问答） | 前端拉取 |

**B4 · 告警规则 → 数据来源 → 抓取路径 对照（可执行性核验）**

| 告警规则（`rules.yml`） | 引用指标 | 指标定义点 | 在 `/metrics` 上？ | 抓取路径可触发？ |
|---|---|---|---|---|
| `MedAssistAgentReplayPassRateLow`（`:13`） | `medassist_agent_replay_pass_ratio` / `_checks_total` | `skill_trends.py:207-209` / `:204-206` | ❌ | **否**（M1） |
| `MedAssistAgentUnusedPublishedSkills`（`:24`） | `medassist_agent_unused_published_skills` | `skill_trends.py:220-223` | ❌ | **否**（M1） |
| `MedAssistAgentProposalBacklog`（`:33`） | `medassist_agent_proposals_total{status="proposed"}` | `skill_trends.py:196-200` | ❌ | **否**（M1） |
| `MedAssistAgentExperienceCoverageLow`（`:42`） | `medassist_agent_experience_coverage_ratio` / `medassist_agent_experiences_total` | `skill_trends.py:193-195` / `:190-192` | ❌ | **否**（M1） |
| `MedAssistAgentHighOperationalAlerts`（`:53`） | `medassist_agent_operational_alerts{level="high"}` | `skill_trends.py:231-235` | ❌ | **否**（M1） |
| `MedAssistAgentConsultErrorRateHigh`（`:63`） | `medassist_agent_consult_requests_total` | `consult_metrics.py:102-108` | ✅ | 是（但分母含 prefilter，M6） |
| `MedAssistAgentConsultAbortedSpike`（`:79`） | `medassist_agent_consult_requests_total{status="aborted"}` | `consult_metrics.py:102-108` | ✅ | 是 |
| （缺失）抓取失败 | `up{job="medassist-ai-core"}` / `absent(...)` | — | — | S1 |
| （缺失）token 成本/采集率 | `medassist_agent_consult_tokens_total` / `..._tokenized_requests_total` | `consult_metrics.py:138-145` | ✅ | S1 |
| （缺失）回答质量 | — | 无任何质量指标 | — | S1 |

### 附录 C · 复核方法与实测证据

- 逐字比对了 `scripts/agent_go_live_check.py:88-94` 的 7 个指标名与 `consult_metrics.py:99-147` / `skill_trends.py:183-237` 的实际输出行，**7/7 一致**（详见"专项核对"章节表格）。
- 实测 `deploy/observability/prometheus/metrics-token.example` 的字节序列（28 字节，末位 `0x0A`），用于验证 M10。
- 实测 `deploy/prometheus/` 目录仅含 `medassist-agent-ops.rules.yml` 一个文件，确认与 `rule_files: /etc/prometheus/rules/*.yml` 的 glob 匹配且无隐藏规则文件。
- 检索 `deploy/helm/medassist-platform/` 全目录，**无 `PrometheusRule` 任何字样**（M2 证据）。
- 检索 `scripts/` 与全仓，**无 cron / scheduler / 定时任务**调用 `skill_trends.snapshot()`（S9 证据）。
- 检索 `backend/` 内 `consult_metrics.reset()` / `observability.reset()` 的调用点：仅测试文件使用（`tests/test_consult_metrics.py:33-35`、`tests/test_observability.py:23`），生产路径未调用（S3 证据）。
- 核对了 `docs/DEPLOY.md:288,366` 的多 worker 建议与 `Dockerfile:47` 的启动命令（M11 证据）。

### 附录 D · 待人工确认项汇总

1. **Helm 形态的规则安装路径**：院内 Prometheus Operator 是否允许本 Chart 跨 namespace 安装 PrometheusRule，是否需要单独的 `prometheus` Chart 值或由平台组统一托管（M2）。
2. **Secret 托管方式**：院内是否统一由 External Secrets 同步 `medassist-metrics` Secret；若是，则 Chart 侧只需校验引用、不应支持创建（M9）。
3. **常驻定时任务的合规性**：是否允许 AI 中台容器内跑 cron/sidecar 生成按日快照，还是应接入院内统一调度平台（S9）。
4. **多 worker 是否为实际部署形态**：若院内固定单 worker 单副本，M11 可降级为"文档约束 + 启动脚本固定"，不必引入共享存储改造。
5. **令牌文件形态**：院内是否接受"从环境变量注入"替代 `credentials_file`（Prometheus 2.55 支持 `authorization.credentials`，但 Secret/环境变量二选一需与平台组约定）（M10）。


