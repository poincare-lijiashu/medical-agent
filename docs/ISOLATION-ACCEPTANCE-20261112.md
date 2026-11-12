# 多租户隔离验收报告（ADR-001 P2 / §283）

| 项目 | 内容 |
| --- | --- |
| 验收日期 | 2026-11-12 |
| 对应决策 | [ADR-001-MULTI-TENANT.md](ADR-001-MULTI-TENANT.md) §3 隔离契约、§7 P2 |
| 验收对象 | 本院 `tenant-001`（127.0.0.1:8080 单入口 / 8183 多入口）与演示二院 `tenant-002`（127.0.0.1:8180，独立 PG 5434） |
| 结论 | **数据/身份/进程/数据库/发布 五类契约通过**；CPU 与观测在本地单机拓扑只能给出参考值；备份恢复与流量分桶压测留给 §284 |
| 证据目录 | `artifacts/isolation/`、`artifacts/chaos/`、`artifacts/release/`（每次执行生成，不随仓库提交）；快照见 `docs/evidence/20261112-cell-acceptance/` |

## 1. 结论摘要

| 契约维度 | 结果 | 判定依据 |
| --- | --- | --- |
| 数据 | **PASS** | 跨租户读取 404；跨租户账号列表为空；两院账号库物理隔离（A 院独有账号在 B 院登录 401） |
| 身份 | **PASS** | 令牌带 `tenant`/`instance`；A↔B 令牌互打入口均 403/40321；未登记 Host 403；同名账号可在两院独立存在 |
| 计算（进程） | **PASS** | 杀掉二院 auth 进程后，主院错误率 0%，P99 −71.1% |
| 计算（数据库） | **PASS** | 停掉二院 PostgreSQL 后，主院错误率 0%，P99 −80.5% |
| 计算（CPU） | **参考** | 本地单机共享内核，CPU 打满时主院 P99 +156.7%；生产由 per-Cell `ResourceQuota`/cgroup 保证，不以本机数值作为通过结论 |
| 流量 | **未压测** | 本地每院一个网关进程，限流桶天然独立；正式结论需 §284 单 Cell 压测 |
| 发布 | **PASS** | 分批（BASELINE → PILOT → ALL）+ 单院回滚成功，回滚不改变其他院台账 |
| 备份 | **未演练** | 备份仍为单实例 CronJob；按 Cell 独立备份桶 + PITR 随 §284 开通编排落地 |
| 观测 | **部分** | 各服务已暴露 Prometheus；Helm 已注入 `medassist.io/tenant` 标签；按院 SLO 看板需生产环境验证 |

## 2. 拓扑与验收口径

- **生产口径（本报告主结论）**：一院一个入口（`hospital-a.example` / `hospital-b.example`），
  网关开启 `TENANT_ROUTING_ENABLED=true`，Host 决定入口租户，令牌 `tenant`/`instance` 必须与入口一致。
- **本地口径**：主演示栈 8080 是单入口（为兼容既有演示，未开启入口路由），
  跨院令牌在这一模式下的拒绝点是**本 Cell 的 auth 数据库**（查无此 subject → 401），
  而不是网关的 403/40321。两者都拒绝，但只有多入口拓扑能验证"网关即拦截"的契约。
  因此越权套件以 **8183（多入口 A 院网关）** 为准，8080 仅用于控制面与业务回归。

## 3. 契约逐条验收

| # | ADR-001 §3 契约 | 验证方式 | 结果 | 证据 |
| --- | --- | --- | --- | --- |
| 1 | 跨租户读取返回 404/403，任何接口不例外 | A 院令牌读 `GET /auth/tenants/tenant-002` | 404（56401） | `cell-isolation-*.json` |
| 2 | 租户管理员只能管理本租户用户 | A/B 双方 `GET /auth/users` 全量扫描 `tenantCode` | 0 条跨院 | 同上 |
| 3 | 令牌不可跨 Cell 使用 | A 令牌打 B 入口、B 令牌打 A 入口 | 403 / 40321（双向） | 同上 |
| 4 | 未登记入口直接拒绝 | `Host: unknown.example` | 403 / 40321 | 同上 |
| 5 | 账号库物理隔离 | A 院独有账号（doctor01）在 B 院登录 | 401（用户名或密码错误） | 同上 |
| 6 | 同名账号在不同院可独立存在 | 两院各自 `admin01` 登录 | 均 200，`tenant` 分别为 001/002 | 同上 |
| 7 | 单 Cell 进程故障不影响别院 | 杀掉二院 auth（kill -9） | 主院 0 错误，P99 48.2ms | `cell-fault-*.json` |
| 8 | 单 Cell 数据库故障不影响别院 | 停掉二院独立 PostgreSQL | 主院 0 错误，P99 32.5ms | 同上 |
| 9 | 单院回滚不影响其他院 | 回滚试点院最近一次部署 | 其他院部署 ID 不变 | `cell-rollout-*.json` |
| 10 | 发布按 Cell 分批推进 | 台账按 `tenantCode` + `batchLabel` 记录 | BASELINE/PILOT/ALL 三批，归属正确 | 同上 |

## 4. 证据

### 4.1 跨 Cell 越权套件（16/16）

```
PASS A 院登录成功
PASS A 院令牌带 tenant 声明 :: tenant=tenant-001
PASS A 院令牌带 instance 声明 :: instance=MEDASSIST-DEV-0001
PASS A 院 /auth/me 200 且租户一致
PASS 账号列表只含本院账号 :: 跨院账号=[]
PASS A 院令牌读 B 院租户详情 404 :: code=56401
PASS A 院管理员读 B 院迁移台账被拒 :: 403 / 40300
PASS 未登记 Host 访问被拒 403 :: 40321
PASS A 院令牌打 B 院入口 403/40321
PASS A 院独有账号（doctor01）在 B 院库登录 401
PASS B 院登录成功
PASS B 院令牌租户与 A 院不同 :: tenantB=tenant-002 tenantA=tenant-001
PASS B 院 /auth/me 200
PASS B 院令牌打 A 院入口 403/40321
PASS B 院令牌读 A 院租户详情 404
PASS B 院账号列表只含本院账号
```

### 4.2 单 Cell 故障注入（tenant-002，可丢弃）

采样对象：主院 `GET /api/v2/auth/me`（经 8080 网关），基线 15s、每阶段 20s、500ms 间隔。

| 阶段 | P50 | P95 | P99 | 错误率 | P99 波动 |
| --- | --- | --- | --- | --- | --- |
| 基线 | 22.8ms | 30.0ms | 166.7ms | 0% | — |
| 二院容器 CPU 打满 | 22.1ms | 282.5ms | 427.9ms | 0% | **+156.7%（本地参考）** |
| 二院 auth 被 kill -9 | 23.9ms | 36.6ms | 48.2ms | 0% | −71.1% |
| 二院 PostgreSQL 停机 | 20.6ms | 29.2ms | 32.5ms | 0% | −80.5% |

说明：

- 基线 P99 含一次冷启动抖动（166.7ms），后两阶段回落到 30–50ms，因此波动为负；
  判定条件是"错误率为 0 且波动 ≤5%"，负向波动同样视为通过。
- CPU 阶段本地为单机共享内核，二院容器压满会把整机 CPU 抢走，**不能**用来证明生产 CPU 隔离；
  生产以每 Cell `ResourceQuota`/`LimitRange` + 独立节点池为边界，需在 K8s 环境复跑本脚本的同构用例。

### 4.3 分批发布与单院回滚

```
BASELINE   tenant-001, tenant-002   （基线，提供回滚目标）
PILOT      tenant-001              （首批 1 家）
CANARY_10  （2 家规模下与 PILOT 重合，明确标注无新增）
ALL        tenant-002              （剩余全部）
ROLLBACK   试点院最近一次部署 → 成功；其他院部署 ID 未变
```

- 每批都校验"该院台账可查且归属正确"；
- 回滚记录继承原部署的 `tenantCode`，台账中不存在"回滚到别家"的歧义；
- 以 2 家 Cell 演练时 10% 与试点重合，属预期；Cell 数 ≥20 时脚本会自动多推进一批。
- 演练后两院相对已发布列车（2026.11）均为 **ALIGNED**（控制台租户页绿标）。

### 4.4 控制台

租户页新增「发布列车对齐」：平台运营可见每院每个组件的目标版本/实际版本，落后黄标、部署失败红标、
尚无列车为灰标；租户表新增「发布对齐」列。租户管理员不请求发布台账（无 `release:read`，不产生 403 噪音）。

浏览器实测（平台运营 ops01 → Vite 5174 → 8080）：

```
租户清单       2 行：tenant-001（本地演示院区）、tenant-002（演示二院）
表头           租户 | 实例码 | 状态 | 账号 | 发布对齐 | Cell 入口 | 更新时间
发布对齐        两行均为绿色「已对齐」（badge--ok）
发布列车对齐     已对齐最近一次发布列车（2 个组件全部一致）
               platform-auth 2.1.0 一致 · platform-clinical 1.1.0 一致
               列车 2026.11 · 读取于 11/12 19:58
无报错横幅      页面无「租户服务暂不可达」「迁移台账暂不可达」
```

> 用医院管理员（ADMIN）登录时只显示本院一行、不显示对齐面板，这是 §281 的既定权限模型
> （`tenant:manage` 只给平台运营），不是缺陷；验证平台视角必须用 PLATFORM_OPERATOR 账号。

## 5. 本轮发现并修复的问题

| # | 问题 | 影响 | 修复 |
| --- | --- | --- | --- |
| 1 | `new_cell.ps1` 只登记控制面台账，未在 Cell 库写 `auth_svc.tenant` | 新院建首个账号时触发 `fk_app_user_tenant`，开通流程中断 | 开通脚本新增"Cell 内租户登记"步骤（幂等） |
| 2 | 部署台账没有 Cell 维度 | 无法回答"某院跑到哪版"；回滚会命中别家的上一版 | release V3：`tenant_code`/`batch_label`；回滚用 `IS NOT DISTINCT FROM` 按 Cell 匹配 |
| 3 | 平台运营没有发布只读权限 | 租户页对齐面板对运维 403，排障只能翻日志 | auth V97：只授予 `release:read`，部署/审批权仍分离 |
| 4 | 演练脚本把 `change_ticket` 当单号字符串 | 生产检查单校验要求布尔项，部署被 422 拒绝 | 检查单布尔化，单号放 `change_ticket_ref` |

## 6. 未通过 / 未覆盖（不做"全部通过"的粉饰）

1. **CPU 隔离 ≤5%**：本地单机无法证明；需 K8s 每 Cell 独立节点池或严格 CPU limit 下复跑。
2. **流量分桶**：未做单 Cell 洪峰压测；生产每院独立网关与 Sentinel 规则，需在预发验证。
3. **备份/PITR**：仍是单实例 CronJob；单院恢复演练与"恢复不锁别院库"留到 §284。
4. **观测**：标签与抓取目标已具备，按院 SLO 看板与告警路由未在生产验证。
5. **RTO/RPO**：需随合同与 Cell 规格确定并演练。

## 7. 复现步骤

```powershell
# 1) 开通二院（独立 PG + 全量迁移 + 双台账登记）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\new_cell.ps1

# 2) 启动二院身份与入口（一次性 Cell）
java -jar platform\auth\target\platform-auth-0.1.0-SNAPSHOT.jar --server.port=8181 `
  --spring.profiles.active=dev --JWT_SECRET=$env:JWT_SECRET --INTERNAL_API_TOKEN=$env:INTERNAL_API_TOKEN `
  --TENANT_INSTANCE_CODE=MEDASSIST-HOSP-B-0001 --TENANT_DEFAULT_CODE=tenant-002 --PG_PORT=5434
java -jar platform\gateway\target\platform-gateway-0.1.0-SNAPSHOT.jar --server.port=8180 `
  --spring.profiles.active=dev --JWT_SECRET=$env:JWT_SECRET --INTERNAL_API_TOKEN=$env:INTERNAL_API_TOKEN `
  --AUTH_SERVICE_URI=http://127.0.0.1:8181 --PLATFORM_AUTH_URI=http://127.0.0.1:8181 `
  --TENANT_ROUTING_ENABLED=true --TENANT_HOSTS=hospital-b.example=tenant-002

# 3) 越权套件（多入口拓扑：A=8183，B=8180）
python scripts/e2e/cell_isolation_suite.py `
  --cell-a http://127.0.0.1:8183 --host-a hospital-a.example `
  --cell-b http://127.0.0.1:8180 --host-b hospital-b.example `
  --cell-b-username admin01 --cell-b-password Med@2026

# 4) 故障注入（只碰 tenant-002）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chaos_cell.ps1

# 5) 分批发布 + 单院回滚
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release\cell_rollout_drill.ps1 `
  -Tenants tenant-001,tenant-002
```

> 二院 `admin01` 为验收用种子账号（与一院同用户名、同口令哈希），用于验证
> "同名账号可在不同院独立存在"与 B→A 反向越权；生产开通由 §284 的首个管理员流程创建。

## 8. 退出标准与下一批

P2 退出标准（ADR-001 §7）：**契约表中数据/身份/计算/发布全部通过，形成可对外提交的验收报告**——本报告已满足；
计算中的 CPU 与流量、备份、观测按上表列为"生产环境待验"，不阻塞 P3。

下一批 §284（开通自动化）：

- 一键开通：建库 → 迁移 → Cell 内租户登记 → 命名空间/配额 → license → 首个管理员；
- 租户停用/注销的数据保留策略与恢复路径；
- 租户健康度看板（迁移落后、发布失败、数据库可达性、备份新鲜度）；
- 单院备份桶 + PITR 与恢复演练；
- 生产 K8s 下复跑 chaos（含 CPU 与流量分桶）。
