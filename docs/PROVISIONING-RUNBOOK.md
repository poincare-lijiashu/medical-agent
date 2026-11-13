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
