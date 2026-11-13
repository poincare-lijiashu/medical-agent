# 评测缺陷整改记录（2026-11-13）

> 对应 `docs/eval/2026-11-13/SUMMARY.md` 的 30 项 Top 缺陷。本轮遵循"能验证才动手、
> 不写凑绿断言"，逐项给出**改动 + 验证证据**；无法在本轮安全完成的大改造单列在 §3。

---

## 1. 本轮已修复

| 编号 | 原缺陷 | 整改内容 | 验证证据 |
|---|---|---|---|
| **D-01（Critical）** | 直连业务端口可伪造 `X-Actor/X-Permissions` 读 PHI（`internal-auth.mode=off` + 监听全网卡） | ①`InternalAuthAutoConfiguration` 新增 fail-closed 守卫：mode≠enforce 时必须监听回环（或显式 `medassist.internal-auth.unverified-allow-non-loopback=true`），否则**拒绝启动**；测试上下文（无 `server.port`）与随机端口不参与。②`scripts/run_platform_demo.ps1` 注入 `SERVER_ADDRESS=127.0.0.1`，16 个服务只绑回环。 | common 模块 111 项测试通过；启动脚本已带该变量；守卫逻辑有明确报错文案与三条修复指引 |
| **D-03** | 门禁红灯被 `continue-on-error` 掩盖；ruff 2 个 F401；16 个 E2E 全红 | ①`ruff check` 清零；②E2E 选择器改为语义选择器（`input[placeholder*="工号"]`/`input[type=password]`/`.nav-item`）；③登录前同时清 Cookie+Storage（此前只清 localStorage → 直接进驾驶舱，登录表单不存在）；④删除 e2e job 的 `continue-on-error`，红即阻断；⑤旧 vanilla UI 用例**显式 skip 并写明原因**（不再整组伪装执行），新增 `test_console_smoke_current.py`（3 项：登录落驾驶舱、侧栏跳转不被登出、无"数据加载失败"）作为真实门禁。 | `pytest -m e2e` → **3 passed, 16 skipped**（skip 带原因）；`ruff` → All checks passed |
| **D-04** | Helm 私有化交付缺授权服务 | `values.yaml` 增加 `license` 块（含 `LICENSE_DEPLOYMENT_MODE/ISSUANCE_ENABLED`）；新增 `templates/deployment-license.yaml`；`services.yaml` 补 license Service；`networkpolicy.yaml` 纳入 license。 | `job-migrate-license.yaml` 引用的 `.Values.license.*` 已全部存在；模板结构与 release 服务一致 |
| **D-05** | 上线指挥台在 IMPL/SLO 为 SKIP 时仍判 READY | `build()` 汇总 `gaps`（SKIP 因子）与 `participatingGates`；`ready = 无 blocking && gaps 为空`；有缺口时写入 blockers。测试改为断言 `ready=false` 且 `gaps` 含 IMPL/SLO。 | `GoLiveCockpitServiceTest` 全绿（含"无项目不得判 READY"） |
| **D-07** | SLO 目标 0 条 → 错误预算永不参与决策 | 新增 `ops/V7__slo_seed.sql`：按 `docs/SLO-20261111.md` 落 10 条目标（`ON CONFLICT DO NOTHING` 幂等）。另修 `sloFactor`：`NO_DATA` 不再算 PASS（有目标无快照 → SKIP 并提示，部分无数据 → WARN）。 | DB 实测 `SELECT count(*) ... WHERE status='ACTIVE'` → **10**；迁移 lint `scanned=308 new=0 PASS` |
| **D-12** | 引用精确率/拒答正确率/ECE 写了但从未接入 | `run_eval.py` 接入 `eval_metrics`：每题映射三指标 → `report.metrics` → 质量门槛（`EVAL_CITATION_MIN=0.80`、`EVAL_REFUSAL_MIN=0.80`、`EVAL_ECE_MAX=0.15`，样本不足不误杀）。 | `run_eval.py --dry-run` 正常；`_metric_rows` 单测式验证输出 `{'citation_precision_mean':0.25,'refusal_accuracy':1.0,'ece':0.35}` |
| **D-19** | 12 个错误码被 `default -> 400` 静默降级 | clinical 补 `ORG_NOT_FOUND→404`、`TRANSFUSION_PRECHECK_EXISTS/_REACTION_STATUS_INVALID→409`、5 个输血/手术检查项→422、`INTERNAL_ERROR→500`；emr 补 `INTERNAL_ERROR→500`；insurance 补 `INSURANCE_GROUPER_UNAVAILABLE→503`；auth 补 `SSO_DISABLED→401`。并新增契约测试逐条锁定。 | `ClinicalExceptionMappingTest` 新增 9 条断言通过；auth 契约测试同步修正并通过 |
| **D-21** | `INTERNAL_API_TOKEN` 无 fail-fast，dev 公开令牌可进生产 | `DevCredentialGuard` 扫描所有配置源，发现任意 `*internal-token`/`*internal-api-token` 等于 `medassist-dev-internal-token` 即记录；生产环境**拒绝启动**。 | common 全量测试通过；按后缀匹配，新增模块自动覆盖 |
| **D-22** | 许可配置默认 fail-open、未进环境变量模板 | `LicenseDeploymentGuard` 增加生产校验：实例码不得为 `MEDASSIST-DEV-0001`、`LICENSE_ENFORCE` 必须为 true，否则拒绝启动；`.env.example` 增补 Java 平台/授权/服务绑定/数据库四段变量。 | `LicenseDeploymentGuardTest` 7 项通过（含 3 项生产约束） |
| **D-28** | JaCoCo 只声明未执行 | 父 POM `<plugins>` 绑定 `prepare-agent` + `report(verify)`；新增 `coverage-gate` profile（`BUNDLE LINE ≥ 0.50`）供 CI 逐步收紧。 | 构建日志出现 JaCoCo agent 装载；`mvn -pl ops -am test` 通过 |
| **D-30** | 无模型卡/AI 政策 | 新增 `docs/ai/MODEL-CARD.md`：适用范围、不适用场景、模型与供应商分级、输入输出边界、已知局限、评测与监测、变更回滚、责任矩阵。 | 文档入库 |

---

## 2. 验证汇总

```
Java：common 111 / clinical / emr / insurance / auth / license / ops 全部通过（mvn -pl ... -am test）
Python：ruff 全绿；pytest -m e2e → 3 passed, 16 skipped（skip 均带原因）
迁移：migration_safety_lint.ps1 → scanned=308 known=11 new=0 PASS
运行时：ops_svc.ops_slo ACTIVE = 10 条（SLO 因子不再恒 SKIP）
```

---

## 3. 本轮未完成（需专门排期，不在本轮"能验证才动手"范围内）

| 编号 | 事项 | 为什么不在本轮做 |
|---|---|---|
| D-02 | GitHub 远端 + 分支保护 required checks | 需仓库管理员权限与网络；本地无法验证 |
| D-06 | PHI 字段级加密（name/birth_date/allergy） | 涉及存量数据迁移 + 盲化索引 + 应用回退读，需停机窗口与回归方案 |
| D-08 | 真实备份/演练台账回写 | 需真实备份目标与演练窗口 |
| D-09 | Alertmanager → 平台 hook 路由 | 平台 hook 需按 source 的接入令牌，需先完成集成源发放 |
| D-10/D-11 | 主数据标准映射、MLLP 服务端与 RDE/MFN | 依赖院方字典与真实 HIS/LIS 环境 |
| D-13 | 模型版本库与运行时强绑定 | 需改动 AI 中台启动契约，影响面大 |
| D-14/D-18 | 值班自动排班与升级、认证题库导入 | 独立功能增量，需与运维/培训流程确认口径 |
| D-15/D-16/D-17/D-23/D-24/D-25 | 指标执行引擎、医嘱 EXECUTED 态与给药五要素、危急值通知、质量规则扩充、主数据生效期、EMPI 概率匹配 | 均为多表/多模块改造，需单独设计与回归 |
| D-20 | 上帝类拆分（ClinicalRepository 9081 行 / pg_store 3661 行） | 纯重构，需完整回归与灰度；本轮优先修缺陷而非搬代码 |
| D-26/D-27/D-29 | 开放平台 API 文档与计费引擎、Java 侧 AI 合规分级 | 产品/商务口径未定，先做会返工 |
| — | 16 个旧 E2E 重写 | 已显式 skip 并登记；重写需逐页对齐当前 UI |

> 说明：已修复项均在本次提交内可复现；未完成项仍保留在 `SUMMARY.md` 的 Top 缺陷表中，
> 不被移出清单，避免"改完文档就消失"。
