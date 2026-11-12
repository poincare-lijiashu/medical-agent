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
