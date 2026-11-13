# Cell 开通与租户生命周期手册（§284 / ADR-001 P3）

| 项目 | 内容 |
| --- | --- |
| 适用对象 | 平台运营 / 交付工程师 / 值班 SRE |
| 关联决策 | [ADR-001-MULTI-TENANT.md](ADR-001-MULTI-TENANT.md) §5.1（Provisioning）、§7 P3 |
| 目标 | **30 分钟内开通一个空 Cell**；停用不删除数据且可恢复；注销按合同保留 |
| 自动化入口 | `POST /api/v2/auth/tenants/provisions`（平台运营）与 `scripts/provision_cell.ps1`（本地验收） |

## 1. 前置条件

- 合同已签、实例码（`instance_code`）已分配且**未被占用**；
- 已选定 Cell 规格（标准/大型），并准备好独立 PostgreSQL 实例（**不得复用控制面库或其他医院的库**）；
- 院方入口 DNS / 证书已就绪（如 `hospital-c.example`）；
- 授权书（license）已签发，或明确本次为"离线交付、先开通后补授权"（`requireLicense=false`）；
- 平台运营账号具备 `tenant:manage`（`PLATFORM_OPERATOR` 角色）。

> 一院一 Cell 是硬约束：控制面会拒绝把租户开进控制面数据库，也会拒绝两个租户共用同一个
> `(db_host, db_name)`；不存在"先共用、以后再拆"的灰色路径。

## 2. 生产开通步骤

### 2.1 建 Cell 基础设施（Helm）

```bash
helm upgrade --install medassist-hospital-c deploy/helm/medassist-platform \
  --namespace medassist-tenant-003 --create-namespace \
  -f deploy/helm/medassist-platform/values-cell-example.yaml \
  --set cell.tenantCode=tenant-003 \
  --set cell.instanceCode=MEDASSIST-HOSP-C-0001 \
  --set cell.hosts[0]=hospital-c.example
```

- 每院独立 namespace、独立 Secret、独立 PostgreSQL、独立 ResourceQuota；
- 迁移由 Helm Hook Job 执行（`--medassist.migrate-only=true`），控制面**不**代跑 Flyway。

### 2.2 等迁移就绪

```bash
kubectl -n medassist-tenant-003 wait --for=condition=complete job/migrate-auth --timeout=10m
# 其余 14 个服务同理；全部完成后 auth 域才有 tenant 表与 app_user.tenant_code
```

### 2.3 调用开通编排

```bash
TOKEN='<平台运营令牌>'
curl -sS -X POST https://console.example/api/v2/auth/tenants/provisions \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{
    "tenantCode": "tenant-003",
    "instanceCode": "MEDASSIST-HOSP-C-0001",
    "name": "某某市第三人民医院",
    "editionCode": "ENTERPRISE",
    "dbHost": "192.0.2.12:5432",
    "dbName": "medagent",
    "cellEndpoint": "https://hospital-c.example",
    "region": "cn-east",
    "quotas": { "maxBeds": 800, "maxUsers": 2500, "maxStorageGb": 800, "maxAiCallsPerDay": 20000 },
    "initialAdmin": { "username": "admin01", "displayName": "信息科管理员" },
    "requireLicense": true
  }' | jq '.data | {id,status,steps,oneTimeAdminPassword}'
```

执行顺序（每步结果落 `auth_svc.provision_step`）：

| # | 步骤 | 失败时最常见原因 |
| --- | --- | --- |
| 1 | `SPEC` | 编码/实例码格式、库坐标被占用、误填控制面库 |
| 2 | `TENANT` | 编码或实例码已存在、租户已在使用/已注销 |
| 3 | `CELL_CONNECT` | 网络不通、PG 未就绪、auth 域未迁移 |
| 4 | `CELL_TENANT` | Cell 内已有同编码不同实例码、Cell 内已注销 |
| 5 | `ADMIN` | 用户名已存在（会跳过并提示）、库权限不足 |
| 6 | `BACKUP` | 对象存储不可达/无建桶权限、Cell 未迁移到 V100、WAL 归档未开启（默认降级不阻塞） |
| 7 | `LICENSE` | 未导入授权/已吊销/已过期（`requireLicense=true` 时阻塞） |
| 8 | `ACTIVATE` | 目录被并发修改（版本冲突） |
| 9 | `HEALTH` | 开通后探测失败（通常与 3 相同） |

### 2.4 交付首个管理员（首登强制改密）

- 响应里的 `oneTimeAdminPassword` **只出现一次**，请通过安全渠道转交；
- 服务端只保存 BCrypt 哈希；丢失口令只能走重置密码流程，不能找回；
- 开通时管理员账号已置 `must_change_password=true`：**本人首次登录必须自行修改口令**，
  控制台会弹出不可跳过的改密对话框（不可 ESC、不可点遮罩关闭），改完才能进入工作台；
- 改密成功后：标记清零、该账号其他设备立即下线、当前浏览器换发新令牌继续使用；
- 重置口令同样属于临时凭据：控制台「重置密码」默认由系统生成一次性临时口令
  （只显示一次，可复制），并同样要求本人首次登录修改；
- 建议在交付单中记录：租户编码、实例码、入口、管理员登录名、口令交付方式（不记录口令本身）。

### 2.5 开通后验证

```bash
# 健康度：state=OK、authReady=true、userCount>=1
curl -sS https://console.example/api/v2/auth/tenants/tenant-003/health -H "Authorization: Bearer $TOKEN" | jq .data
# 迁移台账：15 个服务域、无失败
curl -sS "https://console.example/api/v2/auth/tenants/tenant-003/migrations?refresh=true" -H "Authorization: Bearer $TOKEN" \
  | jq '.data | {reachable,hasFailure,items:(.items|length)}'
```

院方侧验收：入口登录、患者主索引、门诊/住院核心链路冒烟、打印与医保连通性按项目清单执行。

## 3. 失败处理

1. 打开任务详情，确认 `steps[].status=FAILED` 的 `code`/`error`；
2. 按上表修复外部条件（网络、迁移、授权、实例码冲突）；
3. 调用 resume 从失败步骤继续（已成功步骤幂等，不会重复建管理员/重复激活）：

```bash
curl -sS -X POST https://console.example/api/v2/auth/tenants/provisions/<jobId>/resume \
  -H "Authorization: Bearer $TOKEN" | jq '.data | {status,steps}'
```

4. 仍失败时保留任务与步骤证据，不要手工改库绕过编排；必要时按 §5 回滚。

## 4. 停用 / 恢复 / 注销

| 操作 | 接口 | 效果 |
| --- | --- | --- |
| 停用 | `POST /tenants/{code}/status` `{"status":"SUSPENDED","reason":"...","version":N}` | 撤销全院刷新令牌；状态同步到 Cell（Cell 不可达时记录 `cellPropagated=false`，控制面仍立即生效） |
| 恢复 | 同上 `ACTIVE` | 立即可登录；用户需重新登录 |
| 注销 | 同上 `CLOSED` + `retentionUntil` | **终态**：写入 `closed_at/closed_reason/retention_until`；不可恢复；数据按保留期冻结 |

- 注销必须填写原因；`retentionUntil` 缺省按 365 天登记（建议按合同显式填写）；
- 保留期到期后的归档/销毁由数据处置流程执行，应用不会自动删除数据；
- 停用/注销期间 Cell 数据库与备份保持完整，恢复演练按季度执行。

## 5. 回滚（开通失败/交付取消）

- **尚未激活（PENDING）**：删除该 Cell 的 Helm release 与 namespace（含独立 PG），
  控制面把租户置为 `CLOSED` 并登记"交付取消"与保留期 0 天；
- **已激活**：不允许直接删除；先停用（SUSPENDED），与院方确认后再注销（CLOSED），
  按合同保留数据；如需立即下线入口，撤销 DNS 与证书；
- 控制面任务记录与审计（`TENANT_PROVISION`、`TENANT_STATUS`）永久保留。

## 6. 健康度与巡检

- `GET /api/v2/auth/tenants/health`：并行探测、单院 4 秒超时；离线医院只影响自己那一行；
- `state` 含义：`OK` / `PENDING` / `SUSPENDED` / `CLOSED` / `DEGRADED` / `DOWN`；
- `issues[]` 给出可读原因（库不可达、迁移失败、上次开通失败步骤、未登记坐标）；
- 建议告警：`state=DOWN` 立即通知该院值班；`DEGRADED` 进入当日巡检；`PENDING` 超过 24h 升级。

## 7. 30 分钟预算（参考）

| 阶段 | 目标耗时 |
| --- | --- |
| 基础设施（namespace/PG/Secret/配额） | 5–8 分钟 |
| 15 个服务迁移 Job | 8–12 分钟 |
| 开通编排（探测→管理员→激活） | < 1 分钟 |
| 健康度与迁移台账核验 | 2 分钟 |
| 院方冒烟与交付 | 5–8 分钟 |

超过 30 分钟时优先检查：PG 就绪、网络策略、迁移 Job 是否卡在锁等待、镜像拉取是否失败。

## 8. 安全要求

- 开通/停用/注销只授予 `PLATFORM_OPERATOR`（`tenant:manage`）；医院管理员无此权限；
- 一次性口令不落库、不写审计正文、不写日志；交付后由本人**首次登录强制修改**
  （服务端 `app_user.must_change_password`，控制台不可跳过）；
- 管理员重置口令默认生成一次性临时口令，并在账号列表标记「待首次改密」；
- 口令策略在所有入口统一执行（长度、字母数字、不得包含用户名、弱口令黑名单）；
- `/internal/license/summary` 仅内网可达（网关拒绝 `/internal/**`），并强制 `X-Internal-Token`；
- 所有开通/状态变更写 `auth_svc.change_log`，记录 actor、job、步骤与失败原因。

## 9. 备份与恢复（§286）

### 9.1 交付即建仓（BACKUP 步骤）

开通第 6 步会：

1. 在该院独立对象存储上确保桶存在（默认 `medassist-backup-<tenant>`，一院一桶）；
2. 在 Cell 的 `auth_svc.tenant_backup` 登记 stanza / 端点 / 区域 / PITR 保留天数；
3. 读取该库的 `archive_mode`、`wal_level`、`pg_stat_archiver`，判定 PITR 是否真的可用。

判定口径（不猜、不看配置文件的"应该"）：

| 事实 | 结论 |
| --- | --- |
| `archive_mode=on` + `wal_level=replica/logical` + 归档失败 0 次 | `READY`（PITR 生效） |
| `archive_mode=off` 或 `wal_level=minimal` 或归档有失败 | `DEGRADED`：步骤 SKIPPED、健康度「需关注」，问题项写明原因 |
| Cell 未迁移到 V100（缺表） | 步骤 FAILED，提示先在该 Cell 执行迁移后 resume |

- 默认 `requireBackup=false`：先开通、后由 DBA 在窗口期开启归档；
- 合同要求"必须可 PITR"时传 `backup.requireBackup=true`：归档不就绪直接阻塞激活；
- 控制台开通弹窗可填写备份端点与 PITR 保留天数（1–35 天），桶名自动派生。

### 9.2 Cell 侧 pgBackRest（生产）

- 配置模板：[deploy/backup/pgbackrest-cell.conf.tmpl](../deploy/backup/pgbackrest-cell.conf.tmpl)
  （一院一桶、一院一密钥、一院一 stanza、AES-256 加密）；
- PostgreSQL 需 `wal_level=replica`、`archive_mode=on`、
  `archive_command='pgbackrest --stanza=<tenant> archive-push %p'`（修改后必须重启）；
- 初始化：`pgbackrest --stanza=<tenant> stanza-create` → `check` → 首次 `--type=full backup`；
- 保留窗口与台账 `pitr_days` 保持一致（`repo1-retention-full`）。

### 9.3 恢复演练（每季度 / 交付前）

```bash
# 逻辑全量恢复演练：在独立实例/一次性库中恢复并逐表比对行数
python scripts/e2e/backup_restore_drill.py \
    --source-container medagent_pg_tenant003 --database medagent
```

- 演练不会触碰源库：导出、恢复到一次性库、比对关键表行数、最后删除演练库；
- 报告落 `artifacts/backup/restore-drill-*.json`，包含归档事实、耗时、逐表行数；
- `PITR 已就绪` 表示归档生效；"未就绪"时报告会明确写"仅全量恢复已演练"，不冒充 PITR；
- WAL 回放到时间点按模板注释执行 `pgbackrest --type=time --target=... restore`，
  **必须在演练实例**进行，禁止覆盖生产数据目录。

### 9.4 单院全量备份（无 pgBackRest 环境的兜底）

```powershell
powershell -ExecutionPolicy Bypass -File scripts\backup\cell_backup.ps1 `
    -Container medagent_pg_tenant003 -Database medagent -BackupDir D:\backups\tenant-003
```

产物：`.dump`（自定义格式，支持选择性/并行恢复）、`.sha256`、`.json` 清单（含归档事实），
默认保留 14 份。归档未开启时会打印警告：只能恢复到本次全备，RPO≈备份间隔。

## 10. 指标与按院告警（§287）

### 10.1 auth 暴露的租户指标

| 指标 | 含义 |
| --- | --- |
| `medassist_tenant_health_state{tenant,state}` | 健康结论（每院一条为 1）：OK/DEGRADED/DOWN/PENDING/SUSPENDED/CLOSED |
| `medassist_tenant_cell_reachable{tenant}` | Cell 数据库可达性（1/0） |
| `medassist_tenant_backup_ready{tenant}` | 备份/PITR 是否 READY（1/0） |
| `medassist_tenant_backup_state{tenant,state}` | 备份状态明细（READY/DEGRADED/NONE/UNKNOWN…） |
| `medassist_tenant_active_users{tenant}` | 启用账号数 |
| `medassist_tenant_last_archived_timestamp_seconds{tenant}` | 最近成功 WAL 归档时刻（0=从未） |
| `medassist_tenant_health_refresh_timestamp_seconds` | 指标刷新时刻（停走说明刷新卡住） |
| `medassist_tenant_health_refresh_duration_seconds` / `..._errors_total` | 刷新耗时 / 失败次数 |

- 指标由 auth 每 60 秒刷新（`medassist.tenant-health.metrics-interval`），**抓取不触发 JDBC**；
- 单飞：上一轮未完成会跳过本轮，避免离线医院堆积任务；
- 排查：`curl -s localhost:8081/actuator/prometheus | grep medassist_tenant`。

### 10.2 告警规则与路由

- 规则：`deploy/prometheus/medassist-tenant.rules.yml`（本地 Prometheus 自动加载；改后
  `curl -X POST localhost:9090/-/reload`）；
- 关键告警：
  - `MedassistTenantCellDown`（critical，3 分钟）→ 该院数据面不可用；
  - `MedassistTenantBackupNotReady`（warning，1 小时）→ 无可恢复备份；
  - `MedassistTenantWalArchiveStale`（critical）→ PITR 的 RPO 正在退化；
  - `MedassistTenantHealthStale` / `HealthMetricsMissing` → 监控链路自身故障。

生成按院路由（生产必须按院配置值班渠道）：

```bash
# 1) 准备联系人（复制示例，替换 REPLACE_ME；不要把 key 提交到版本库）
cp deploy/observability/alertmanager/tenant-contacts.example.yaml /etc/medassist/tenant-contacts.yaml

# 2) 渲染（会校验 ACTIVE 租户是否都有渠道，缺失直接退出码 2）
python scripts/ops/render_alertmanager_tenants.py \
    --contacts /etc/medassist/tenant-contacts.yaml \
    --auth-url https://console.example --token '<平台运营令牌>' \
    --out /etc/alertmanager/alertmanager.yml

# 3) 校验配置与命中关系
amtool check-config /etc/alertmanager/alertmanager.yml
amtool config routes test --config.file=/etc/alertmanager/alertmanager.yml \
    severity=critical tenant=tenant-003
# 期望输出：tenant-003-oncall,medassist-critical（院方 + 平台双送达）
```

- 租户路由 `continue: true`：院方值班与平台值班都会收到，避免"只依赖院方"；
- 同一院 Cell 不可达时抑制其备份告警（`inhibit_rules`），防止一次故障刷屏；
- 新开一家医院：在 contacts 里加一条记录并重渲染；未加记录时渲染器会失败，
  不会出现"告警静默丢失"。

### 10.3 看板

- Grafana：`MedAssist · 租户 SLO`（`deploy/observability/grafana/dashboards/medassist-tenant-slo.json`）；
- 顶部 `tenant` 变量支持多选/全选；面板含状态、需关注数、备份未就绪数、刷新年龄、
  各院结论、探测耗时、活跃账号、WAL 归档年龄。

## 11. 授权签发、导入与到期（§288）

### 11.1 密钥纪律

```bash
python scripts/license/gen_keypair.py --out-dir secrets/license
```

- 私钥（PKCS#8 Base64）只配在**厂商签发构建**的 `LICENSE_SIGNING_KEY`，绝不能进院；
- 公钥（SPKI Base64）配到每个实例的 `LICENSE_VERIFY_KEY`，用于离线验签；
- `keyId`（公钥 SHA-256 前 16 位）写进交付单，便于核对"这份授权是谁签的"；
- 交付形态（§289）：`LICENSE_DEPLOYMENT_MODE=vendor|hospital`，**默认 hospital**；
  院方构建若把 `LICENSE_ISSUANCE_ENABLED` 打开，服务**拒绝启动**（不是静默降级）；
  厂商构建必须显式 `LICENSE_DEPLOYMENT_MODE=vendor` 才能签发。

### 11.2 签发与交付前复核（厂商侧）

```bash
python scripts/license/issue_license.py \
    --base-url http://127.0.0.1:8095 --permissions license:manage --actor vendor-ops \
    --license-no LIC-2026-0001 --instance MEDASSIST-HOSP-C-0001 \
    --hospital "某某市第三人民医院" --edition ENTERPRISE \
    --modules clinical,pharmacy,emr,tech,ai \
    --max-beds 800 --max-users 2500 --max-ai 20000 \
    --expires-at 2027-11-11T00:00:00Z --out artifacts/license/LIC-2026-0001.json

# 离线复核（不启动服务）：签名、payload 一致性、有效期
python scripts/license/verify_license.py artifacts/license/LIC-2026-0001.json \
    --public-key-file secrets/license/ed25519-public.b64 --require-active
```

### 11.3 院方导入（实例绑定）

- 控制台「授权与许可 → 导入授权书」，或 `POST /api/v2/license/import`（`license:manage`）；
- 导入即验签 + 逐字段比对签名原文 + 校验实例码；**实例不匹配直接拒绝**（一证一院）；
- 新授权导入后旧授权自动转「已替代」，历史不删除；吊销必须填写原因。

### 11.4 与开通联动

- `requireLicense=true` 时：无授权、未生效、已过期、已吊销都会在 `LICENSE` 步骤失败；
  `false` 时降级为 SKIPPED（离线交付先开通后补授权）；
- 版本必须与授权一致：授权 `STANDARD` 而申请 `ENTERPRISE` → 失败；
- 规模不许超卖：申请床位/账号/AI 调用超过授权上限 → 失败，错误文案给出"申请 vs 上限"；
- 未填配额时按授权上限补全租户配额，避免"先开小、后超限"。

### 11.5 到期与告警

| 阶段 | 含义 | 处理 |
| --- | --- | --- |
| `PENDING` | 未到生效日 | 核对合同起租日 |
| `ACTIVE` | 有效 | 剩余 ≤30 天触发 `MedassistLicenseExpiringSoon` |
| `GRACE` | 已到期但仍在宽限期 | `MedassistLicenseGrace`，窗口内完成续期 |
| `EXPIRED` | 超出宽限期 | `MedassistLicenseExpired`（critical），模块门禁按策略拦截 |
| `NONE` / `REVOKED` | 未导入 / 已吊销 | `MedassistLicenseMissing`（24h）或立即排查 |

- 指标：`medassist_license_phase / days_left / module_entitled / quota_utilization / quota_exceeded`；
- 抓取：Prometheus `medassist-license` job（8095 `/actuator/prometheus`）；
- 配额超限只告警不阻断（临床连续性优先），由运营扩容或修正用量上报。

## 12. AI 用量自动上报与配额对账（§289 / §290）

### 12.1 链路

```
AI 中台（8001） --POST /internal/license/usage/ai--> 授权服务（8095）
                  X-Internal-Token: <INTERNAL_API_TOKEN>
```

- 直连 8095，**不经网关**（网关对 `/internal/**` 返回 404，这是有意的边界）；
- AI 中台配置 `PLATFORM_LICENSE_BASE_URL=http://127.0.0.1:8095` + `INTERNAL_API_TOKEN=<同值>`；
  任一为空即关闭上报（离线/单机形态），问答不受影响；
- 每 `AI_USAGE_FLUSH_SECONDS`（默认 60 秒）落盘并推送；计数无变化时不写授权库，
  另有 15 分钟心跳刷新 `captured_at`（让"用量陈旧"告警有判断依据）；
- 授权服务只接受**当日/前一日**（UTC），同一天重复上报幂等（最后值生效）。

### 12.2 计费口径（写进合同的唯一口径）

- **每一次成功的模型 HTTP 调用 = 1 次 AI 调用**（§290 起，计量点在 provider 传输层）：
  问答、影像描述、结构化抽取、审方、摘要压缩、意图识别等**全部模型路径统一计数**；
- 失败/被拒（非 2xx）不计；前置闲聊（prefilter）不调用模型，天然不计；
- 重试与 thinking 降级各产生一次真实请求，因此各计 1 次（与供应商账单口径一致）；
- 本地嵌入模型（BGE）不产生 HTTP、不占 AI 配额；
- 同一天重启或多 worker：AI 中台按 `日期#进程实例` 分行记账，合计=所有行之和，
  **不丢也不重**（`agent_usage_daily`，PG 真源 + JSON 镜像）；
- 床位/账号**不由 AI 中台上报**：AI 上报只更新 `ai_calls`，避免把院方台账的床位/账号覆盖成 0。

### 12.3 核对方法

```bash
# AI 中台视角（需要 METRICS_TOKEN 或 admin 登录态）
curl -H "Authorization: Bearer <METRICS_TOKEN>" http://127.0.0.1:8001/metrics \
  | findstr medassist_agent_model_calls_today
# 授权服务视角
curl -H "X-Permissions: license:read" http://127.0.0.1:8095/license/status
```

- `usage.aiCalls` 应等于 AI 中台当日计数；`usage.source` 首次写入为 `AI_CORE`；
- 指标：`medassist_license_usage_ai_calls`、`medassist_license_usage_age_seconds`、
  `medassist_license_usage_ingest_total{result}`；
- 告警：`MedassistLicenseUsageStale`（>26 小时未更新）、
  `MedassistLicenseUsageIngestRejected`（令牌/日期/口径非法）。

### 12.4 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 授权页 AI 用量一直为 0 | 地址或内部令牌未配 | 看 AI 中台启动日志：`ai_usage_disabled` / `ai_usage_started` |
| 日志出现 `ai_usage.report_failed` | 8095 不可达或被网关挡 | 确认直连 8095；不要走 8080 |
| 上报返回 `54411` | 日期早于前一日、计数为负或明显超限 | 校准容器时钟/NTP；历史补数走人工 `POST /license/usage` |
| 授权侧计数小于 AI 中台指标 | 多 worker 计数分散且未按行求和 | 台账按进程行求和；建议固定单 worker，以授权侧日快照为准 |
| 审计出现 `USAGE_AI_DECREASE` | 重启丢增量或上报口径变化 | 立即排查；该条审计不可改写，用于商务对账 |

### 12.5 授权超限时的行为

超过 `maxAiCallsPerDay` **只告警不阻断**（与床位/账号一致，临床连续性优先）。
首次跨越上限会写一条 `USAGE_AI_QUOTA_EXCEEDED` 审计；运营据此扩容或与院方结算。

## 13. 医保日结/月结对账（§291 / 计划书 W2.5）

### 13.1 什么时候做

- **日结**：每日终了，核对当天就诊业务日期的已确认清单与省局回传；
- **月结**：次月 1–3 日，核对完整自然月；关闭后作为当月医保结算结论留档。

### 13.2 口径

| 维度 | 口径 |
| --- | --- |
| 期间归属 | 就诊业务日期 `service_date`（不是确认时间、不是提交时间） |
| 清单范围 | `CONFIRMED` + `VOIDED` 但省局已 `SETTLED` 的清单 |
| 本地金额 | 清单快照的总额/基金/个人自付；作废单不计入本地收入 |
| 省局金额 | 最近一笔 `SETTLE` 响应中的 `output.fund_pay_sumamt` / `output.psn_pay` |
| 不可验证 | 省局未回传金额 → 只计数，不伪造差异 |
| 差额 | 金额不一致按基金/个人分别求绝对差；未结算/失败按本地基金；作废后已结算按基金+自付 |

### 13.3 操作流程

1. 医保运维 →「结算对账」→ 选日结/月结 → 生成批次；
2. 看差异列表：`未结算 / 结算失败 / 金额不一致 / 作废后已结算`；
3. 逐条处理：补提交结算、联系省局冲销、或标记「已解决 / 已知悉并接受」（说明必填）；
4. 全部 OPEN 处理完后「关闭批次」；关闭后不可重算，结论留档。

```bash
# 生成日结（需 insurance:recon:manage）
curl -X POST http://127.0.0.1:8080/api/v2/insurance/recon/batches \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"periodType":"DAY","periodFrom":"2026-11-12","periodTo":"2026-11-12"}'
# 查看批次与差异（需 insurance:recon:read）
curl "http://127.0.0.1:8080/api/v2/insurance/recon/batches?limit=20" -H "Authorization: Bearer <token>"
```

### 13.4 权限与范围

- `insurance:recon:read`：ADMIN / INSURANCE_OFFICER / OPS_MANAGER；
- `insurance:recon:manage`：ADMIN / INSURANCE_OFFICER；
- **院区受限账号不能生成/查看对账**（全院财务动作，避免拿部分数据当全量结论）；
- 窗口（REGISTRAR）不授予 manage：出单与对账结论分离。

### 13.5 指标与排障

- `medassist_insurance_recon_action_total{action=compute|resolve|close,result}`；
- `medassist_insurance_recon_open_diffs`：最近一次操作的未处理差异数；
- 常见问题：
  - 生成报「期间内清单超过上限」→ 改为按月或缩小日期；
  - 差异关闭不了 → 仍有 OPEN 差异，先处理或标记忽略；
  - 重算报 409 → 他人已重算/关闭，刷新后再操作；
  - 大量「不可验证」→ 省局回执未回传金额，需与省局确认字段口径。

## 14. 三方对账与多支付方分摊（§292 / 计划书 W2.5+W2.6）

### 14.1 三方对账

| 腿 | 数据来源 | 口径 |
| --- | --- | --- |
| 临床实收 | 临床域内部接口 `/internal/finance/visits/collections` | 收费单 `PAID` 金额；按就诊业务日期归属 |
| 本地清单 | `settlement_bill` | 已确认清单的总额/基金/自付快照 |
| 省局回传 | 最近一笔 `SETTLE` 响应 | `fund_pay_sumamt` / `psn_pay`，解析不到计"不可验证" |

- 新增差异类别：`CLINIC_UNDER_COLLECTED`（清单有金额但未实收/已退费）、
  `CLINIC_OVER_COLLECTED`（实收大于清单）；
- 临床差异**不计入省局差额**（`diff_amount` 仍只统计清单 vs 省局）；
- 临床汇总单批上限 5000 条，超过时生成失败，须按月或缩小范围；
- 临床服务不可达时对账返回 503：**不允许把"读不到实收"当成"已收清"**。

排障：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 生成报"临床收费汇总暂不可用" | 8083 不可达或令牌缺失 | 确认临床服务、`INSURANCE_CLINICAL_TOKEN` 一致 |
| 大批「临床未实收」 | 清单确认后患者未结算，或退费后未红冲 | 收费处补收/退费，医保办按结果红冲清单 |
| 「临床多收」 | 清单漏报或退费未红冲 | 核对收费明细后重新生成清单 |

### 14.2 多支付方分摊

1. 医保运维 →「支付方分摊」→ 先确认支付方字典（BASIC/SELF/COMMERCIAL/ASSISTANCE）已启用；
2. 「待分摊」选择已确认清单 → 填各方金额：
   - BASIC（医保统筹）合计必须等于清单统筹支付；
   - 其余支付方合计等于个人自付；全部合计必须等于清单总额；
3. 提交后写入追加式台账；调整必须先对原分摊「退费」，再重新分摊；
4. 退费必须写原因，累计不得超过原分摊余额；台账禁止修改/删除（数据库触发器兜底）；
5. 清单作废前必须先把分摊全部冲销，否则作废会被拒绝。

```bash
# 查看待分摊清单（需 insurance:payer:read）
curl "http://127.0.0.1:8080/api/v2/insurance/allocations/bills?pending=true&limit=20" \
  -H "Authorization: Bearer <token>"
# 首次分摊（需 insurance:payer:manage；金额合计必须等于清单总额）
curl -X POST http://127.0.0.1:8080/api/v2/insurance/allocations/bills/<billId> \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"items":[{"payerCode":"BASIC_INSURANCE","amount":70.00},{"payerCode":"SELF_PAY","amount":30.00}]}'
```

### 14.3 权限与指标

- `insurance:payer:read`：ADMIN / INSURANCE_OFFICER / OPS_MANAGER；
- `insurance:payer:manage`：ADMIN / INSURANCE_OFFICER（窗口不可见）；
- `medassist_insurance_payer_action_total{action,result}`：
  `create_payer / update_payer / enable_payer / disable_payer / allocate / refund`。

## 15. 指标字典与口径治理（§293 / 计划书 W4.5）

### 15.1 为什么必须治理

同一指标在驾驶舱、月报、医保对账、绩效里各算各的（口径、时间窗、分母不同），
出数后无法回答“这个数按什么口径算的、谁负责、改了会影响哪些报表”。
指标字典把指标变成**有版本、有责任人、有上下游血缘**的治理对象：
发布后的口径不可被静默覆盖，修订必须走新版本，影响面可查。

### 15.2 操作流程

1. 系统管理 →「指标字典」→ 新建指标（状态为**草稿**）；
2. 补全口径定义、公式、单位、时间窗、统计粒度、责任科室与**至少一个上游源表**；
3. 信息科/质控审批后「发布」：系统落 PUBLISH 版本快照（SHA-256），对外口径生效；
4. 已发布指标不能直接编辑：点「修订」生成下一版本草稿，旧口径继续生效；
5. 新版本发布后替换生效版本；不再使用的指标「停用」，历史版本保留；
6. 报表/看板接入时，在详情里登记**下游血缘**（REPORT/DASHBOARD/METRIC），
   修订前先看影响分析（上下游数量、版本事件、是否修订中）。

```bash
# 查询指标字典（需 mdm:read）
curl "http://127.0.0.1:8080/api/v2/mdm/metrics?domain=FINANCE&status=PUBLISHED" \
  -H "Authorization: Bearer <token>"
# 新建草稿（需 mdm:write）
curl -X POST http://127.0.0.1:8080/api/v2/mdm/metrics \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"code":"OUTPATIENT_VISIT_COUNT","name":"门诊就诊人次","domain":"CLINICAL",
       "definition":"统计期内实际发生就诊的人次，不含取消挂号","formula":"count(distinct visit.id)",
       "aggregation":"COUNT_DISTINCT","unit":"人次","timeWindow":"DAY","granularity":"DEPT",
       "sourceSchema":"clinical_svc","sourceTables":["visit"],"ownerDept":"医务科"}'
# 发布（需 mdm:approve；version 必须等于当前 head）
curl -X POST http://127.0.0.1:8080/api/v2/mdm/metrics/OUTPATIENT_VISIT_COUNT/publish \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"version":2}'
```

### 15.3 口径与安全规则

| 规则 | 说明 |
| --- | --- |
| 草稿可编辑 | 只有 DRAFT 能修改；修改走版本 CAS，冲突返回 409 |
| 发布需完整 | 定义 ≥10 字、公式、单位、时间窗、粒度、责任人、至少一个上游源表/接口 |
| 发布即快照 | PUBLISH 事件写入版本链与 SHA-256，事后可还原“当时口径” |
| 修订不破历史 | revise 后 head 变 DRAFT（版本 +1），`published_version` 不动，旧口径仍生效 |
| 血缘只增不改 | metric_version / metric_lineage 禁止 UPDATE/DELETE（触发器兜底） |
| 审计同链 | 指标动作写入 `mdm_svc.md_change_log`，不另建审计表 |

### 15.4 权限与排障

- `mdm:read`：查询、版本链、血缘、影响分析；
- `mdm:write`：新建/修改草稿、修订、登记血缘；
- `mdm:approve`：发布与停用（口径上线/下线）。

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 发布报“至少登记一个上游源表或接口” | 草稿没有源表且没有 UPSTREAM 血缘 | 补源表或登记上游接口 |
| 修改报 409 | 他人已修改/发布，版本不一致 | 刷新后重试 |
| 修改报“已发布指标不能直接修改” | 试图直接改生效口径 | 先「修订」，再编辑草稿 |
| 详情没有下游 | 报表/看板尚未登记血缘 | 在详情页登记 DOWNSTREAM |

## 16. 传染病上报与漏报监测（§294 / 计划书 W1.9）

### 16.1 时限与状态

| 类别 | 时限 | 说明 |
| --- | --- | --- |
| 甲类 | 诊断后 **2 小时** | 鼠疫、霍乱；按甲类管理的乙类病种按目录配置 |
| 乙/丙类 | 诊断后 **24 小时** | 肺结核、病毒性肝炎、流感等 |

状态机：`DRAFT`（待报）→ `SUBMITTED`（已提交）→ `ACCEPTED`（疾控受理）/ `RETURNED`（退回）；
`RETURNED` 可重报；`DRAFT`/`RETURNED` 可作废；**已提交/已受理不可作废**。

### 16.2 自动匹配与扫描

- 每 10 分钟扫描一次新诊断（水位 `infection_scan_state.last_diagnosed_at`），
  按法定目录的 ICD-10 前缀匹配，生成待报卡；
- 同一次就诊的同一种传染病只生成一张有效卡（部分唯一索引兜底）；
- 运维可在页面点「立即扫描」，或调 `POST /clinical/infections/detect`；
- 目录新增/修正后，历史漏报用「手工补录」建立卡片。

```bash
# 汇总（需 clinical:infection:read）
curl "http://127.0.0.1:8080/api/v2/clinical/infections/summary" \
  -H "Authorization: Bearer <token>"
# 提交（需 clinical:infection:report；version 为卡片当前版本）
curl -X POST http://127.0.0.1:8080/api/v2/clinical/infections/reports/<id>/submit \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"reporter":"张医生","reporterDept":"呼吸内科","version":1}'
# 受理（需 clinical:infection:manage；必须登记疾控回执号）
curl -X POST http://127.0.0.1:8080/api/v2/clinical/infections/reports/<id>/accept \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"receiptNo":"CDC-2026-0001","version":2}'
```

### 16.3 漏报与迟报口径

- **逾期**：`DRAFT` 且 `report_deadline < now()`；
- **迟报**：`submitted_at > report_deadline`；
- **及时率**：按时提交卡数 / 已提交卡数 × 100%；
- 页面 KPI 与 `medassist_clinical_infection_pending/overdue` 指标同源（SQL 聚合）。

### 16.4 权限与排障

- `clinical:infection:read`：医生/护士/质控/运营；
- `clinical:infection:report`：医生/护士（补录、提交/重报）；
- `clinical:infection:manage`：质控/管理员（扫描、受理、退回、作废）。

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 诊断后没有待报卡 | 病种停用 / ICD 前缀不匹配 / 扫描水位已过 | 核对目录；点「立即扫描」；必要时手工补录 |
| 提交报 409 | 他人已提交/退回，版本变化 | 刷新后重试 |
| 作废报“已提交不能作废” | 法定报告已提交，不能物理撤回 | 联系疾控走更正流程 |
| 逾期数量持续增加 | 临床未及时提交 | 防保科按逾期列表督办 |

---

## 17. IHE XDS 文档共享（§295 / 计划书 W3.5）

### 17.1 定位与边界

- 本服务（fhir，默认 8090）在既有 FHIR 只读投影之外，**新增 XDS 文档注册表**；
  业务域（clinical/tech/empi）仍是只读，写入只发生在 `fhir_svc`；
- 实现 ITI-41（单文档注册）、ITI-18（注册表检索）、ITI-43（文档调阅）、
  ITI-62（元数据停用）的 **REST/JSON 子集**；
- 完整 SOAP/ebXML 与国家级 CDA Schematron 校验、CA 签名/时间戳不在本服务内完成。

### 17.2 上线前配置

| 环境变量 | 说明 | 默认 |
| --- | --- | --- |
| `XDS_MAX_DOCUMENT_BYTES` | 单份文档上限（字节） | 5242880（5MB） |
| `XDS_ROOT_OID` | 院方在区域平台注册的 OID 根 | `1.2.156.112.1.1`（仅联调） |
| `XDS_CUSTODIAN_NAME` | CDA custodian 机构名 | 铭医医院信息平台 |
| `XDS_DISCHARGE_TEMPLATE_ID` | 出院小结模板 OID（WS 445） | `2.16.156.10011.2.1.1.31` |
| `XDS_LAB_TEMPLATE_ID` | 检验报告模板 OID（WS 445） | `2.16.156.10011.2.1.1.28` |

> `XDS_ROOT_OID` 上线必须替换为院方 OID；默认值只用于本地联调，写进区域平台的
> 文档会带错误的机构标识。

### 17.3 常用操作

```bash
# 生成出院小结（需 fhir:xds:generate；visitId 必须是住院就诊）
curl -X POST http://127.0.0.1:8080/api/v2/fhir/xds/documents/discharge-summary \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"visitId":"<visit-uuid>"}'

# 生成检验报告（需报告已 FINAL/AMENDED）
curl -X POST http://127.0.0.1:8080/api/v2/fhir/xds/documents/lab-report \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"reportId":"<report-uuid>"}'

# 检索（至少给 patient / uniqueId / 时间范围之一）
curl "http://127.0.0.1:8080/api/v2/fhir/xds/registry/stored-query?patient=P20261113001&limit=20" \
  -H "Authorization: Bearer <token>"

# 调阅（响应头 X-XDS-Hash / X-XDS-Size / X-XDS-Status）
curl -OJ "http://127.0.0.1:8080/api/v2/fhir/xds/retrieve?uniqueId=urn:uuid:<uuid>" \
  -H "Authorization: Bearer <token>"

# 停用元数据（需 fhir:xds:manage；保留内容与审计）
curl -X POST "http://127.0.0.1:8080/api/v2/fhir/xds/documents/urn:uuid:<uuid>/deprecate" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"reason":"患者信息登记错误","rowVersion":0}'
```

### 17.4 完整性校验与排障

- 注册时服务端按字节计算 SHA-256 与大小；调阅时重新计算并与元数据比对，
  任一不一致都**拒绝返回内容**（`57404`），同时写 `HASH_MISMATCH` 审计；
- 唯一标识重复返回 `57409`，不会覆盖历史文档；需要更正时先停用旧文档，
  再用新 uniqueId 注册；
- 检索过宽返回 `57407`：必须提供患者、唯一标识或时间范围，避免整院导出；
- 报告未签发返回 `57410`：先在医技系统完成审核签发。

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 生成出院小结报“不是住院就诊” | visitId 是门诊就诊 | 改用住院 visitId |
| 调阅 500 且码 57404 | 内容或元数据被改动/恢复不完整 | 停用该文档，核对备份后重新注册 |
| 检索 422 码 57407 | 未给患者/唯一标识/时间范围 | 增加检索条件 |
| 页面患者号显示为掩码 | 账号缺 `fhir:xds:phi` | 由管理员按岗位授予 |
| 区域平台拿不到文档 | 只用 SOAP/ebXML 端点 | 在外围适配器转换到 REST/JSON 子集 |

### 17.5 备份与恢复

- `fhir_svc.xds_document` 与 `xds_document_content` 必须与业务库一起纳入备份；
- 恢复后先跑一次调阅校验：任一份文档哈希不一致会直接暴露为 57404；
- 审计表 `xds_access_log` 只增不改，恢复时不得用旧快照覆盖较新的审计数据。

---

## 18. AI 验证与治理（§296 / 计划书 W8.1–W8.4）

### 18.1 四个控制与对应接口

| 控制 | 计划书 | 接口 | 关键规则 |
| --- | --- | --- | --- |
| 模型/提示词版本 | W8.2 | `/api/v1/agent/governance/versions` | 四眼审批；同组件同类型单一生效；回滚重激活旧版 |
| 临床验证 | W8.1 | `/api/v1/agent/governance/studies` | ≥30 例且阳性/阴性各 ≥10；敏感度/特异度 + Wilson CI；批准冻结 |
| 漂移监测 | W8.3 | `/api/v1/agent/governance/drift` | 样本 <20 不出信号；基线由管理员设定；8 类信号 |
| 误报闭环 | W8.4 | `/api/v1/agent/governance/incidents` | 根因/纠正措施必填；关闭需非解决人；逾期统计 |

看板：`GET /api/v1/agent/governance/summary`（admin/qc）。

### 18.2 上线前最小动作

1. **登记并生效模型版本**：`component` 可为 `global` 或具体能力域；`MODEL` 必填
   provider/model，`PROMPT` 必填 promptHash（SHA-256）。
2. **登记并生效提示词版本**：与模型版本分开审批，便于单独回滚。
3. **建立漂移基线**：先产生至少 20 次问答，抓取快照后由管理员「设为基线」。
4. **完成至少一项临床验证**：录入 30 例（阳性/阴性各 10 例），提交并由另一人批准。
5. **确认问答可追溯**：调用 `/agent/consult`，响应中的 `governance.traceable`
   应为 true；`complete=false` 表示模型或提示词缺一。

### 18.3 常用命令

```bash
# 治理看板（admin/qc）
curl "http://127.0.0.1:8001/api/v1/agent/governance/summary" -H "Authorization: Bearer <token>"

# 登记模型版本（admin）
curl -X POST http://127.0.0.1:8001/api/v1/agent/governance/versions \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{
    "component":"drug","kind":"MODEL","version":"2026.11.1",
    "provider":"deepseek","model":"deepseek-chat",
    "changeSummary":"升级模型并收紧证据引用要求"
  }'

# 批准（qc/admin，不能是创建人）→ 生效（admin）
curl -X POST .../versions/<versionId>/review -d '{"approve":true,"comment":"同意","rowVersion":2}'
curl -X POST .../versions/<versionId>/activate -d '{"rowVersion":3}'

# 抓取漂移并设基线
curl -X POST .../drift/snapshots -d '{"agentIds":["drug"]}'
curl -X POST .../drift/baseline -d '{"agentId":"drug","snapshotId":"<id>"}'

# 登记事件（doctor/qc/admin）→ 解决（根因/措施）→ 他人关闭
curl -X POST .../incidents -d '{"agentId":"drug","kind":"FALSE_POSITIVE","severity":"HIGH","title":"...","description":"..."}'
```

### 18.4 漂移阈值与判读

| 信号 | 触发条件 | 处置 |
| --- | --- | --- |
| `ERROR_RATE_HIGH` | 错误率 >20%（>30% 为 HIGH） | 查模型可用性、限流与上游错误 |
| `ERROR_RATE_UP` | 较基线上升 >10 个百分点 | 对比最近版本/提示词变更 |
| `ABORTED_RATE_HIGH` | 中止率 >20% | 查前端断开、超时与并发槽 |
| `QUALITY_FLAG_HIGH` | 质量风险率 >30% | 抽查回答是否过短/缺证据/拒答 |
| `QUALITY_FLAG_UP` | 较基线上升 >10 个百分点 | 复核提示词与技能卡 |
| `LATENCY_UP` | >基线 1.5 倍且 >3s（>2 倍且 >5s 为 HIGH） | 查模型延迟与连接池 |
| `TOKEN_UP` | token/次 >基线 1.5 倍 | 查提示词膨胀、历史轮数 |
| `TRAFFIC_DROP` | 请求量较基线下降 >50% | 进程重启也会归零，先核对重启 |

> 快照是**进程内**计量，服务重启归零；跨重启/多副本请以 Prometheus 近 30 分钟
> 窗口为准，快照用于"同一窗口内与基线比较"。

### 18.5 临床验证口径

- 敏感度 = TP/(TP+FN)，特异度 = TN/(TN+FP)，PPV/NPV/准确率/F1 同二分类定义；
- 置信区间用 Wilson 95%，小样本不会给出 <0 或 >1 的区间；
- `qualityGate=PASS` 需同时满足：≥30 例、阳性/阴性各 ≥10、敏感度与特异度均达到
  研究登记的验收阈值；
- 批准后 `approvedResult` 冻结；病例不可再改，需要新结论请新建研究。

### 18.6 误报闭环与排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 批准版本报 403 | 审批人是创建人 | 换另一名 admin/qc 复核 |
| 生效报 422 | 版本未批准 / 已停用且无审批记录 | 先完成审批；回滚只能选曾审批生效的版本 |
| 研究提交报 422 | 不足 30 例或阳性/阴性不足 10 例 | 补录病例后重新提交 |
| 漂移一直是 INSUFFICIENT | 请求量 <20 次 | 增加流量或拉长窗口，不要强行设基线 |
| 关闭事件报 403 | 关闭人 = 解决人 | 由另一名 admin/qc 复核关闭 |
| 问答 `traceable=false` | 未登记生效版本 | 按 18.2 登记模型/提示词并生效 |

### 18.7 数据与备份

- 治理表：`agent_model_versions`、`agent_validation_studies`、`agent_drift_snapshots`、
  `agent_drift_baselines`、`agent_incidents`（PG；JSON 兜底）；
- 这些表与会话/技能卡同库，纳入日常备份；恢复后检查版本状态与事件流是否连续；
- 事件标题/描述可能含临床细节，导出或截图前先脱敏；审计只存业务编号。



## 19. 备份与灾备演练（§297）

### 19.1 数据与门槛

- 表：`ops_svc.ops_backup_policy`、`ops_backup_run`、`ops_dr_drill`、`ops_dr_drill_step`；
- 备份运行以 `idempotencyKey` 去重：同一作业重试必须复用，RUNNING 只能终结一次；
- 演练默认六步：备份可恢复性 → 基础设施拉起 → 数据恢复 → 应用启动与健康检查 →
  核心业务冒烟 → 数据一致性与丢失窗口核对；
- `PASSED` 同时要求：必填步骤全 PASSED、实测 RTO/RPO ≤ 目标、一致性核对通过、
  证据非空、复核人 ≠ 执行人。

### 19.2 上线前最小动作

1. 为每个数据域登记备份策略（范围、cron、保留、加密、存储目标、RPO、负责人）；
2. 接入备份平台上报：成功带大小/校验和/位置，失败带错误原因；
3. 至少完成一次 `scope=ALL`、`environment=PROD` 的演练，并由第二人复核通过；
4. 在「灾备演练 → 发布门禁自检」中确认 `eligible=true`；
5. 生产部署检查单仍须完成（备份/回滚方案/冒烟/值班/变更单），门禁不是替代品。

### 19.3 常用命令

```bash
# 令牌
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v2/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin01","password":"<demo-password>"}' | jq -r .data.accessToken)
H="Authorization: Bearer $TOKEN"

# 备份策略
curl -s -X POST http://127.0.0.1:8080/api/v2/ops/backup/policies -H "$H" \
  -H 'Content-Type: application/json' -d '{
    "code":"pg-full","name":"核心库全量备份","scopeType":"ALL","backupType":"FULL",
    "scheduleCron":"0 2 * * *","retentionDays":30,"encryption":"AES256",
    "storageTarget":"s3://backup/pg","rpoMinutes":15,"owner":"ops01","status":"ACTIVE"}'

# 备份运行上报（同一作业重试复用幂等键）
curl -s -X POST http://127.0.0.1:8080/api/v2/ops/backup/runs -H "$H" \
  -H 'Content-Type: application/json' -d '{
    "policyCode":"pg-full","backupType":"FULL","status":"SUCCESS",
    "idempotencyKey":"pg-full-20261113-0200","startedAt":"2026-11-13T02:00:00Z",
    "finishedAt":"2026-11-13T02:18:00Z","sizeBytes":1073741824,
    "checksum":"sha256:...","storageLocation":"s3://backup/pg/20261113"}'

# 演练：创建 → 开始 → 逐步登记 → 第二人复核通过
curl -s -X POST http://127.0.0.1:8080/api/v2/ops/dr/drills -H "$H" \
  -H 'Content-Type: application/json' -d '{
    "title":"2026 Q4 全院灾备演练","scope":"ALL","environment":"PROD",
    "rtoTargetMinutes":120,"rpoTargetMinutes":15}'
curl -s -X POST .../ops/dr/drills/<id>/start -H "$H"
curl -s -X POST .../ops/dr/drills/<id>/steps/1 -H "$H" \
  -H 'Content-Type: application/json' -d '{"status":"PASSED","evidence":"恢复日志已归档"}'
# complete 必须换人（ops:dr:verify）
curl -s -X POST .../ops/dr/drills/<id>/complete -H "$H" \
  -H 'Content-Type: application/json' -d '{
    "rtoActualMinutes":30,"rpoActualMinutes":5,"consistencyVerified":true,
    "evidence":"恢复日志/冒烟截图/一致性核对报表"}'

# 门禁自检
curl -s ".../ops/dr/gate?scope=ALL&environment=PROD&days=90" -H "$H" | jq .data
```

### 19.4 发布门禁与排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 部署返回 422 / 53425 | 90 天内没有已复核的 PASSED 演练 | 先完成演练并由第二人复核；确认 scope=组件或 ALL |
| 部署返回 422 / 53425，但刚做过演练 | 演练通过但未复核（verifier 为空） | 由非执行人执行 complete |
| 部署返回 422 / 53425，查询报“无法读取” | ops_svc 不可达或迁移未执行 | 检查 ops 服务与 V4 迁移；门禁 fail-closed，不会放行 |
| 演练 complete 返回 409 / 52439 | 执行人自己复核 | 换 OPS_MANAGER/ADMIN 复核 |
| 演练 complete 返回 422 / 52437 | 仍有必填步骤未 PASSED | 补登步骤；必填步骤不能跳过 |
| 演练 complete 返回 422 / 52438 | 实测 RTO/RPO 超目标 | 优化恢复流程后重测，不能改小目标凑数 |
| 备份上报返回 409 / 52433 | 幂等键已结束为另一种结果 | 新作业用新幂等键；不要覆盖历史结果 |

### 19.5 演练执行清单

- [ ] 明确范围（ALL 或组件）、环境（PROD/STAGING/DRILL）与目标 RTO/RPO；
- [ ] 关联一次成功的备份运行作为快照；
- [ ] 每步留证据（日志地址/截图/还原结果），失败步骤写清断点；
- [ ] 一致性核对：关键表行数、金额汇总、抽样业务对象；
- [ ] 由另一名负责人复核通过；执行人不得自审；
- [ ] 记录数据丢失窗口，超过 RPO 时判定失败而不是“差不多”。

### 19.6 数据与备份

- 演练与备份台账属于运营数据，纳入日常备份；恢复后检查状态机与审计流是否连续；
- 证据文本可能含内部地址，导出前按院内制度脱敏；
- 备份策略的 `last_run_at` 只反映最近一次上报，不代表备份软件真实调度状态。

## 20. 实施交付与培训考核（§298）

### 20.1 数据与口径

- 模板：`ops_svc.impl_template*`；项目与任务：`impl_project` / `impl_project_task`；
  培训：`impl_course` / `impl_question` / `impl_exam` / `impl_attempt`；
- 项目创建时复制模板任务快照，模板后续修改不影响在建项目；
- 任务 `DONE` 必须有证据，`BLOCKED` 必须写原因，例外只能 `WAIVED`（写原因 + 复核人）；
- 就绪分 = Σ(阶段权重 × 阶段完成率)，门槛默认 90；培训通过率门槛默认 95%；
- 通过率按唯一学员最新成绩统计：同一考试重考覆盖成绩并累加次数。

### 20.2 上线前最小动作

1. 确认内置模板 `STD-HOSP-v1` 或自建模板的阶段权重合计为 100；
2. 从模板创建项目，指定医院、负责人、目标上线日与必修课程；
3. 开始实施，逐项完成任务并上传证据；阻塞项写原因，确实无法完成的走豁免；
4. 为每门必修课程开考并录入成绩，确认通过率达到 95%；
5. 在项目详情确认"具备上线条件"，由非负责人执行"批准上线"；
6. 上线后完成验收签署（验收证据必填）。

### 20.3 常用命令

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v2/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin01","password":"<demo-password>"}' | jq -r .data.accessToken)
H="Authorization: Bearer $TOKEN"

# 模板与课程
curl -s ".../ops/impl/templates?onlyActive=true" -H "$H" | jq
curl -s ".../ops/impl/courses?onlyActive=true" -H "$H" | jq

# 创建项目（从模板快照任务）
curl -s -X POST ".../ops/impl/projects" -H "$H" -H 'Content-Type: application/json' -d '{
  "templateCode":"STD-HOSP-v1","tenantCode":"tenant-001","hospitalName":"示范医院",
  "startDate":"2026-11-20","targetDate":"2027-01-20",
  "requiredCourseCodes":["SYS-BASIC","ROLE-DOCTOR"]}' | jq .data.projectNo

# 开始实施 / 登记任务
curl -s -X POST ".../ops/impl/projects/<pid>/start" -H "$H"
curl -s -X POST ".../ops/impl/projects/<pid>/tasks/<taskId>" -H "$H" \
  -H 'Content-Type: application/json' -d '{"status":"DONE","assignee":"ops01","evidence":"签字件地址"}'

# 开考 + 录成绩（answers 的键是题目 id）
curl -s -X POST ".../ops/impl/projects/<pid>/exams" -H "$H" \
  -H 'Content-Type: application/json' -d '{"courseCode":"SYS-BASIC","passScore":80}'
curl -s -X POST ".../ops/impl/exams/<examId>/attempts" -H "$H" \
  -H 'Content-Type: application/json' -d '{"trainee":"nurse01","answers":{"<qid>":"B"}}'

# 就绪报告 → 第二人批准上线 → 验收
curl -s ".../ops/impl/projects/<pid>/readiness" -H "$H" | jq .data
curl -s -X POST ".../ops/impl/projects/<pid>/go-live" -H "$H" \
  -H 'Content-Type: application/json' -d '{"note":"复核通过"}'
curl -s -X POST ".../ops/impl/projects/<pid>/accept" -H "$H" \
  -H 'Content-Type: application/json' -d '{"evidence":"验收报告编号","note":"院方签署"}'
```

### 20.4 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 批准上线按钮禁用 | 就绪分不足 / 必填任务未完成 / 培训通过率不足 / 自己是负责人 | 看就绪报告逐项补齐；换非负责人复核 |
| 上线返回 409 / 52452 | 就绪条件未满足 | 补齐必填任务或豁免；确认培训通过率 ≥95% |
| 上线返回 409 / 52453 | 操作人 = 项目负责人 | 由运营经理或其他负责人复核 |
| 任务登记返回 422 / 52449 | 完成未填证据 | 填签字件/截图/报告地址 |
| 任务登记返回 422 / 52450 | 阻塞未填原因 | 写清阻塞点与协调对象 |
| 开考报 422 / 52457 | 课程无题目 / 重复开考 / 课程停用 | 先补题；一项目一课程只开一场 |
| 录成绩报 422 / 52458 | 漏答或选项越界 | 完整作答；选项必须是题目给出的键 |
| 通过率一直是 0 | 未开考或未录成绩 | 按课程开考并录入成绩 |

### 20.5 数据与备份

- 实施与培训数据属于运营数据，纳入日常备份；恢复后检查项目状态与成绩是否连续；
- 培训材料与验收证据只存地址，不存大文件；
- 题库答案键仅对实施管理员可见，导出前按院内制度管理。

## 21. 数据质量与指标稽核（§299）

### 21.1 数据与口径

- 规则：`mdm_svc.md_dq_rule`；执行：`md_dq_run`；问题：`md_dq_issue`；
- 探针只允许单条 SELECT/WITH，5 秒超时，结果取第一列数值；
- 比较符：`LTE/GTE/EQ/BETWEEN`；FAILED 自动开单，ERROR 只记录不开单；
- 同一规则只保留一个未闭环问题，重复失败累加次数；
- 周期扫描默认每 6 小时，首次延迟 5 分钟。

### 21.2 上线前最小动作

1. 确认内置 4 条规则可执行（主数据映射、指标发布、指标血缘、主数据同步时效）；
2. 按院方口径补充临床/集成/财务质量规则（只读 SQL）；
3. 执行「全部执行」，确认 PASS/FAIL 与预期一致；
4. 对 FAILED 建单问题分派负责人，解决必须写方案；确需接受的走豁免（数据治理负责人）；
5. 观察 24h 通过率与未闭环问题，纳入数据治理例会。

### 21.3 常用命令

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v2/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin01","password":"<demo-password>"}' | jq -r .data.accessToken)
H="Authorization: Bearer $TOKEN"

# 看板与规则
curl -s ".../mdm/quality/dashboard" -H "$H" | jq .data
curl -s ".../mdm/quality/rules?status=ACTIVE" -H "$H" | jq

# 新建规则（只读 SQL）
curl -s -X POST ".../mdm/quality/rules" -H "$H" -H 'Content-Type: application/json' -d '{
  "code":"DQ-OPS-ORDER","name":"超时未处理工单数","domain":"CLINICAL",
  "dimension":"TIMELINESS",
  "sqlText":"SELECT count(*) FROM ops_svc.ops_ticket WHERE status = ''NEW'' AND created_at < now() - interval ''2 days''",
  "comparator":"LTE","threshold":0,"unit":"COUNT","severity":"HIGH",
  "owner":"data-steward","status":"ACTIVE"}'

# 执行单条 / 全部
curl -s -X POST ".../mdm/quality/rules/<ruleId>/run" -H "$H" | jq .data
curl -s -X POST ".../mdm/quality/rules/run-all" -H "$H" | jq .data

# 问题闭环
curl -s ".../mdm/quality/issues?status=OPEN" -H "$H" | jq
curl -s -X POST ".../mdm/quality/issues/<issueId>/acknowledge" -H "$H" -d '{"note":"已分派"}'
curl -s -X POST ".../mdm/quality/issues/<issueId>/resolve" -H "$H" \
  -d '{"resolution":"补齐映射并重新同步"}'
curl -s -X POST ".../mdm/quality/issues/<issueId>/waive" -H "$H" \
  -d '{"reason":"历史数据无法回溯，院方确认接受"}'
```

### 21.4 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 保存规则 422 / 50425 | SQL 含写入/DDL/分号/注释/系统目录 | 改为单条 SELECT/WITH；不要访问 pg_catalog |
| 执行 ERROR / 50426 | 表或列不存在 / 结果多列 / 超时 | 检查 SQL；结果只能一列；大表加条件或预聚合 |
| 执行 ERROR 但无问题单 | ERROR 不自动开单（防止规则错误刷屏） | 先修规则；业务失败才开单 |
| FAILED 后没有新问题 | 已有未闭环问题 | 查看同一规则的问题并累加次数 |
| 豁免返回 403 | 当前账号没有 `mdm:approve` | 由数据治理负责人操作 |
| 24h 通过率低但问题很少 | 规则重复失败只累加同一问题 | 看「执行记录」定位具体观测值 |

### 21.5 数据与备份

- 质量规则与执行记录属于治理数据，纳入日常备份；恢复后检查未闭环问题是否连续；
- 探针 SQL 可能含内部表名，导出前按院内制度脱敏；
- 生产建议使用只读库账号执行探针，并限制规则维护权限到数据管家。

## 22. SLO 与错误预算（§300）

### 22.1 口径与数据

- 定义：`ops_svc.ops_slo`；快照：`ops_slo_snapshot`；
- 达标率 = good/total；错误预算 = total×(100−target)/100；燃烧率 = bad/预算；
- 状态：BREACHED（低于目标）> AT_RISK（剩余 <25%）> HEALTHY；无快照为 NO_DATA；
- 快照幂等键唯一，重试必须复用；窗口结束不得超前当前时间 5 分钟。

### 22.2 上线前最小动作

1. 在服务目录中确认关键服务已登记；
2. 为网关、临床、药事、医保等关键服务定义 SLO（目标/窗口/负责人）；
3. 接入 exporter 或人工上报最近一个窗口的 total/good；
4. 核对达标率、错误预算与燃烧率是否符合预期；
5. 将 BREACHED / AT_RISK 纳入变更评审与值班交接。

### 22.3 常用命令

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v2/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin01","password":"<demo-password>"}' | jq -r .data.accessToken)
H="Authorization: Bearer $TOKEN"

# 服务目录与 SLO 列表
curl -s ".../ops/slo/services" -H "$H" | jq
curl -s ".../ops/slo?onlyActive=true" -H "$H" | jq

# 新建 SLO（可用性 99.9%，30 天）
curl -s -X POST ".../ops/slo" -H "$H" -H 'Content-Type: application/json' -d '{
  "code":"SLO-GATEWAY-AVAIL","name":"网关可用性","serviceCode":"platform-gateway",
  "indicator":"AVAILABILITY","targetPercent":99.9,"windowDays":30,
  "owner":"ops01","status":"ACTIVE"}'

# 上报窗口快照（exporter 重试必须复用幂等键）
curl -s -X POST ".../ops/slo/<sloId>/snapshots" -H "$H" \
  -H 'Content-Type: application/json' -d '{
    "windowStart":"2026-11-13T02:00:00Z","windowEnd":"2026-11-13T03:00:00Z",
    "totalEvents":120000,"goodEvents":119990,"source":"EXPORTER",
    "idempotencyKey":"slo-gateway-20261113-0300"}'

# 看板与详情
curl -s ".../ops/slo/dashboard" -H "$H" | jq .data
curl -s ".../ops/slo/<sloId>" -H "$H" | jq .data.evaluation
```

### 22.4 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 保存 SLO 422 / 52461 | 目标越界、延迟类缺阈值或非延迟类带阈值 | 按指标类型修正 |
| 上报快照 422 / 52462 | good>total、total=0、窗口倒置或超前 | 核对窗口与计数 |
| 上报快照 409 / 52463 | 幂等键已用于另一组内容 | 新窗口用新键，不要覆盖历史 |
| 状态一直 NO_DATA | 没有快照或 total=0 | 接入 exporter 或补报窗口 |
| 达标率很低但预算仍显示 100% | 样本量太小或窗口未覆盖 | 拉长窗口/补报历史；注意小样本波动 |
| 停用后无法上报 | SLO 已 INACTIVE | 需要补报先启用 |

### 22.5 数据与备份

- SLO 与快照属于运营治理数据，纳入日常备份；恢复后检查窗口连续性；
- 快照只存计数，不含用户/患者标识；
- 生产建议 exporter 使用独立只读账号与固定周期上报，避免人工口径漂移。

## 23. HL7 v2 联调测试台（§301）

### 23.1 数据与口径

- 端点：`clinical_svc.hl7_endpoint`；用例：`hl7_case`；执行：`hl7_run`；
- 执行链路：本地结构校验 → MLLP 发送 → 解析 MSA → 与期望 ACK 比对；
- 校验失败不发送（ERROR）；ACK 不一致（FAILED）；网络/回执异常（ERROR）；
- 摘要各保留 2000 字符，日志不打印完整报文。

### 23.2 联调前最小动作

1. 在内网建立可访问的 MLLP 测试端点（host/port）；
2. 确认内置 ADT/ORM/ORU 用例符合院内接口规范，必要时自建；
3. 先对测试端点执行，确认 ACK 码与期望一致；
4. 对失败项看「执行详情」的校验/ACK/错误，修正后复测；
5. 联调通过后再切换到院方集成引擎地址，保留执行记录作为验收证据。

### 23.3 常用命令

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v2/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin01","password":"<demo-password>"}' | jq -r .data.accessToken)
H="Authorization: Bearer $TOKEN"

# 看板/用例/端点
curl -s ".../clinical/integration/hl7/dashboard" -H "$H" | jq
curl -s ".../clinical/integration/hl7/cases" -H "$H" | jq
curl -s ".../clinical/integration/hl7/endpoints" -H "$H" | jq

# 新建端点
curl -s -X POST ".../clinical/integration/hl7/endpoints" -H "$H" \
  -H 'Content-Type: application/json' -d '{
    "code":"HIS-TEST","name":"HIS 测试环境","host":"192.0.2.20","port":2575,
    "timeoutMs":3000,"status":"ACTIVE"}'

# 执行用例
curl -s -X POST ".../clinical/integration/hl7/run" -H "$H" \
  -H 'Content-Type: application/json' -d '{"caseId":"<caseId>","endpointId":"<endpointId>"}' | jq

# 执行历史
curl -s ".../clinical/integration/hl7/runs?caseCode=ADT-A01" -H "$H" | jq
```

### 23.4 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| ERROR：报文必须以 MSH 段开始 | 报文被粘贴成普通文本/首段不是 MSH | 修正首段与分段符 |
| ERROR：MSH-9 与用例不一致 | 类型/触发事件填错 | 改用例或报文，保持一致 |
| ERROR：缺少必填段 | 必填段配置过严或报文缺段 | 补段或调整必填段（保留 MSH） |
| ERROR：发送失败/超时 | 端点不可达、端口错、防火墙 | 核对 host/port 与网络策略 |
| ERROR：ACK 缺少 MSA | 对端不是 HL7 或返回非标准 | 用对端集成引擎日志核对 |
| FAILED：ACK 码不一致 | 对端业务拒绝（AE/AR） | 按 MSA-3/ERR 段排查业务原因 |
| 页面无执行记录 | 未点执行或权限不足 | 确认 `integration:hl7:run` |

### 23.5 安全与数据

- 用例必须使用合成患者；生产患者数据不得粘贴到测试台；
- 报文摘要可能含姓名/就诊号，导出与截图前按院内制度脱敏；
- 执行记录属于集成验收证据，纳入备份；恢复后检查最近一次回归结果。

## 24. 上线指挥台（§302）

### 24.1 用途与口径

- 只读聚合六门禁：实施就绪、灾备演练、SLO、重大事件、数据质量、变更冻结；P1 工单仅提醒；
- 阻断：DR 缺失、SLO 违约、SEV1/2、BLOCKER 质量问题、冻结窗口；
- 提醒：SLO 预算告急、HIGH 质量问题、P1 工单；
- 关键数据源读取失败按不可放行处理；项目不存在返回 404；
- 报告与证据包同源，可导出 JSON 作为上线评审附件。

### 24.2 上线评审流程

1. 完成实施项目任务与培训（§298），确认就绪分与通过率达标；
2. 完成灾备演练并由第二人复核（§297），确认在 90 天有效期内；
3. 在「SLO 与错误预算」确认无 BREACHED；有 AT_RISK 时准备解释与缓解措施；
4. 清零 SEV1/SEV2 与 BLOCKER 数据质量问题，确认不在冻结窗口；
5. 打开「上线指挥台」选择项目与组件，生成报告；READY 后导出证据包；
6. 评审会以证据包为准；结论不替代院方签字与外部资质。

### 24.3 常用命令

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/v2/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin01","password":"<demo-password>"}' | jq -r .data.accessToken)
H="Authorization: Bearer $TOKEN"

curl -s ".../ops/golive/cockpit?projectId=<pid>&componentCode=platform-gateway&environment=PROD" \
  -H "$H" | jq '.data | {ready, blockers, factors: [.factors[] | {code,status,detail}]}'

curl -s ".../ops/golive/evidence?projectId=<pid>&componentCode=platform-gateway" -H "$H" \
  > golive-evidence.json
```

### 24.4 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| IMPL=FAIL | 实施项目未达标/必填任务或培训通过率不足 | 回实施交付页补齐 |
| DR=FAIL | 90 天内无已复核演练 | 完成演练并由第二人复核 |
| SLO=FAIL | 有 SLO 低于目标 | 修复服务或在评审中说明并延期 |
| INCIDENT=FAIL | 有未关闭 SEV1/SEV2 | 先关闭事件或降级并留复盘 |
| QUALITY=FAIL | 有 BLOCKER 质量问题 | 解决或由数据治理负责人豁免 |
| FREEZE=FAIL | 处于冻结窗口 | 等窗口结束或走紧急变更 |
| 报告打不开/500 | 项目 ID 非法或依赖库不可用 | 核对项目；查看 ops 日志 |
| 证据包与页面不一致 | 两次生成时间不同 | 以同一次生成的导出文件为准 |

### 24.5 数据与备份

- 指挥台不落库，不产生新数据；证据包由使用方保存；
- 证据包含内部编号、主机与责任人，导出/外发前按院内制度脱敏；
- 建议上线评审证据包与发布部署记录一起归档。

## 25. 控制台入口与登录（§303）

### 25.1 唯一入口

- **控制台：`http://127.0.0.1:8001/`**（AI 中台同源托管构建产物 + `/api/v1` + `/api/v2` 反代）；
- Java 服务：`powershell -NoProfile -ExecutionPolicy Bypass -File scripts\run_platform_demo.ps1`
  （已在跑会自动跳过；停止加 `-Stop`）；
- 网关直连：`http://127.0.0.1:8080/api/v2/**`（排障用，不用于浏览器页面）。

### 25.2 演示账号

`admin01` / `doctor01` / `qc01` / `pharm01`，口令 `<demo-password>`（仅本地演示库；
生产由院方身份源或运维初始化，禁止沿用演示口令）。

### 25.3 请求路径

| 浏览器请求 | 去向 | 说明 |
| --- | --- | --- |
| `/`、`/dashboard`、`/assets/*` | AI 中台静态产物 | SPA 深链刷新由 fallback 回 index.html |
| `/api/v1/**` | AI 中台自身 | SSE 直连，不经网关，避免 30s 读超时截断长文 |
| `/api/v2/**` | 反向代理到网关 8080 | 控制台全部业务接口（登录、临床、运维、发布） |

### 25.4 配置项

| 变量 | 默认 | 作用 |
| --- | --- | --- |
| `PLATFORM_GATEWAY_BASE_URL` | `http://127.0.0.1:8080` | `/api/v2` 反代目标；生产若由 ingress 直接分流可留空关闭 |
| `AUTH_JWT_SECRET` | 无（必须配置） | Java 与 Python 必须同值，否则"一次登录两边不认" |
| `INTERNAL_API_TOKEN` | 无（必须配置） | 服务间 `/internal/**` 调用；Java 与 Python 必须同值 |

### 25.5 排障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 页面能打开但登录 405/500 | `/api/v2` 未被反代（或反代目标未配） | 确认 `PLATFORM_GATEWAY_BASE_URL`，并检查是否走的是 8001 而非已被淘汰的旧入口 |
| 登录成功但页面接口 401 | Python/Java 的 `AUTH_JWT_SECRET` 不一致 | 两端设为同值后重启 |
| 接口 502 `code=50302` | 网关未启动或不可达 | 启动 8080，`/actuator/health` 应为 200 |
| 接口 503 `code=50301` | 反代目标为空 | 配置 `PLATFORM_GATEWAY_BASE_URL` 或改由 ingress 分流 |
| 身份同步日志 403 | `INTERNAL_API_TOKEN` 两端不一致 | 对齐令牌并重启 AI 中台与 auth |
| 用 5174/5175 时登录间歇 500 | vite 代理与网关 keep-alive 关闭时序冲突 | 改用 8001 入口（推荐），或仅用于前端热更新调试 |
| 点侧栏直接回登录页、重登仍被踢 | Java 用开发默认 JWT 密钥启动（手工 `java -jar`），与 AI 中台密钥不一致 | 用 `run_platform_demo.ps1` 重启全部服务（§304） |
| 多个页面同时"数据加载失败" | 服务矩阵不全（8080–8095 有缺失） | 用脚本启动并确认 16 个端口全部监听 |
