# MedAssist 服务间身份信任加固设计评审（MF-07）

- 文档编号：DESIGN-20261101-MF07
- 状态：**设计评审稿（未改代码）**
- 关联意见：`docs/REVIEW-20261031-ROUND3.md` MF-07（下游零校验身份头）、MF-09（dev 内部令牌入库）、MF-10（JWT 默认密钥）
- 适用版本：platform 0.1.0-SNAPSHOT（gateway/auth/empi/clinical/pharmacy/tech/emr/insurance）

---

## 0. 结论摘要

1. 当前**只有一条信任假设**：业务服务相信"能连到我的人一定是网关"，而实现上没有任何东西验证这一点。
   内网任意主机对 `:8086` 发 `curl -H "X-Permissions: emr:record:read"` 即可读全量病历。
2. 推荐**两阶段**落地：
   - **阶段一（1~2 个迭代，无 PKI）**：网关对每个转发请求追加 **HMAC-SHA256 签名头**，
     下游用公共过滤器校验；同时把"服务→服务"的内部令牌升级为**按调用方区分密钥 + 签名**。
   - **阶段二（有 Service Mesh 或 cert-manager 时）**：叠加 **mTLS**，身份从"签名字符串"升级为"证书主体"，
     HMAC 降级为可选的请求级完整性校验。
3. 不改数据模型、不改业务接口契约；失败语义统一为 **403**，灰度用 `mode=log|enforce` 开关，
   回滚只需切回 `off`（无迁移、无数据风险）。
4. 与 MF-09/MF-10 的关系：本设计**不能替代**"密钥类配置无默认值、缺失即拒启"，
   三者必须一起做，否则签名密钥本身也会被默认值化。

---

## 1. 现状与威胁模型

### 1.1 现状（代码事实）

| 环节 | 实现 | 问题 |
|---|---|---|
| 网关鉴权 | JWT 校验后注入 `X-Actor / X-User-Id / X-Roles / X-Permissions / X-Token-Id / X-Org / X-Client-Ip / X-Trace-Id` | 注入本身正确；`AuthHeaders.CLIENT_SUPPLIED` 已定义需剥离的客户端头 |
| 下游授权 | `Permissions.require(permissions, "emr:record:read")` 直接读 `X-Permissions` | **只信头，不验来源**；无 Filter/Interceptor |
| 监听地址 | 所有服务 `0.0.0.0:808x` | 同网段/同宿主机任意进程可直连 |
| 服务→服务 | `X-Internal-Token`（dev 值为仓库公开常量 `medassist-dev-internal-token`） | 令牌是**共享静态串**，无法区分调用方，泄露即全通 |
| 网关内部路径 | `/api/v2/*/internal/**` 一律 404 | 内部接口只能直连，这是对的，但无来源证明 |

### 1.2 攻击面

- **A1 伪造身份直读 PHI**：内网任意主机直连 8086 并自带 `X-Permissions` → 读/打印病历、检索 CDR。
- **A2 越权写**：伪造 `X-Actor` 归档/签名/确认医保清单，审计把责任记到无辜账号上。
- **A3 内部令牌扩散**：`X-Internal-Token` 出现在仓库、镜像层、值班手册；任何人拿到即可调用
  `clinical /internal/charges`、`empi /internal/patients`。
- **A4 中间人/重放**：纯 HTTP 内网若被横向移动，抓包重放即可重复调用（当前无时间戳/随机数）。

### 1.3 信任边界声明（设计目标）

> 业务服务只接受"**能证明自己来自网关，或能证明自己是被授权的服务**"的请求；
> 其他一切请求（包括带合法 `X-Permissions` 的请求）一律 403。

---

## 2. 目标与非目标

**目标**

1. 消除 A1/A2：直连业务端口无法伪造身份，即使拿到全部头字段也不行。
2. 消除 A3：内部令牌泄露不足以单独调用内部接口（需要密钥签名）。
3. 消除 A4：签名覆盖方法、路径、查询串、身份头、时间戳、随机数、请求体摘要。
4. 可运维：密钥轮换不停机；灰度可按服务推进；回滚 1 分钟内完成。
5. 离网可用：不依赖公网 CA、不依赖外部 KMS（院内可自建）。

**非目标**

- 不做终端用户认证改造（JWT 体系不变）。
- 不做字段级加密（已有 EMPI FieldCipher）。
- 不引入 Istio/Linkerd 作为**前置条件**（阶段二可选）。

---

## 3. 方案对比

| 维度 | A. 网关 HMAC 签名头 | B. 服务间 mTLS | C. OAuth2 Client Credentials + JWT | D. 仅 NetworkPolicy |
|---|---|---|---|---|
| 防伪造身份 | ✅ 强（签名覆盖全部身份头） | ✅ 最强（身份=证书） | ✅ 强 | ❌ 只防跨网段，不防同网段 |
| 防重放 | ✅ 时间窗 + nonce | ⚠️ 需应用层补 | ✅ exp/nonce | ❌ |
| 防篡改（含 body） | ✅ 可覆盖 body 摘要 | ⚠️ 传输层保护，落地后仍需应用校验 | ⚠️ 仅头 | ❌ |
| 密钥/证书管理 | 简单（对称密钥，Secret 注入） | 复杂（CA、签发、轮换、Java keystore 重载） | 中（需授权服务器或自建） | 无 |
| 离线院内可行性 | ✅ 高 | ✅ 高（自建 CA） | ⚠️ 中（需 IdP 高可用） | ✅ |
| 性能开销 | HMAC-SHA256 微秒级；nonce 一次 Redis 往返（~0.2ms） | TLS 握手（长连接摊销） | 每次 introspection 或本地验签 | 0 |
| 改动面 | common 加过滤器 + 网关加 GlobalFilter（各 1 处） | 全服务端口/证书/探针/Helm | 全服务安全链改造 | 仅 K8s 清单 |
| 对现有代码侵入 | 低（不动业务控制器） | 中 | 高 | 无 |
| 结论 | **阶段一采用** | **阶段二叠加** | 不采用（收益/成本不匹配） | 始终保留为纵深防御 |

---

## 4. 推荐方案（两阶段）

### 阶段一：网关 HMAC + 内部调用方签名（默认落地）

- **通道 1（网关 → 业务服务）**：网关用网关专属密钥签名；下游公共过滤器验签。
- **通道 2（服务 → 服务）**：保留 `X-Internal-Token` 作为"第一因子"，新增**调用方签名**作为"第二因子"；
  每个调用方（clinical/emr/insurance/…）一对独立密钥，被调方按 `X-Internal-Caller` 选密钥验签。
- 纵深防御：NetworkPolicy 继续限制只有网关/指定服务可达业务端口；K8s 探针走独立 health 路径豁免。

### 阶段二：mTLS（可选增强）

- cert-manager 自建 CA，按服务签发证书（SAN 用 SPIFFE：`spiffe://medassist/ns/<ns>/sa/<service>`）。
- 下游从证书主体取"调用方身份"，与 HMAC 的 `X-Actor`/`X-Internal-Caller` **交叉校验**（不一致即 403）。
- HMAC 保留：防止"证书合法但请求被中间件改写"的重放/篡改场景，成本很低。

---

## 5. 详细设计：网关 → 下游签名

### 5.1 新增请求头契约（加入 `AuthHeaders`）

| 头 | 含义 | 示例 |
|---|---|---|
| `X-Internal-Sig` | HMAC-SHA256 结果，Base64URL（无 padding） | `q3J...` |
| `X-Internal-Ts` | 签名时间，Unix 秒 | `1793512800` |
| `X-Internal-Nonce` | 16 字节随机数的 Base64URL | `9tK...` |
| `X-Internal-Key-Id` | 密钥版本号（轮换用） | `gw-2026-11` |
| `X-Internal-Sig-Ver` | 规范化算法版本 | `v1` |

- 这些头**必须加入 `AuthHeaders.CLIENT_SUPPLIED`**，网关在入口一律剥离，防止客户端自带。
- 头部名沿用现有 `X-` 前缀风格；不做 `Authorization` 复用，避免与用户 JWT 混淆。

### 5.2 规范化字符串（canonical string，v1）

按固定顺序、`\n` 连接，任何字段缺失用空串（**不省略行**，避免歧义）：

```
v1
<METHOD 大写>
<原始 path（不含 query）>
<query 串：按 key 排序后原样拼接，空则空行>
<X-Actor>
<X-User-Id>
<X-Roles>
<X-Permissions>
<X-Token-Id>
<X-Org>
<X-Client-Ip>
<X-Trace-Id>
<X-Internal-Ts>
<X-Internal-Nonce>
<sha256(body) 的 Base64URL，GET/无体用 "-">
```

设计要点：

- **包含 query**：防止把 `?limit=1` 改成 `?limit=100000` 绕过限流/批量导出限制。
- **包含全部身份头**：改 `X-Permissions` 会导致验签失败，这是本设计最核心的一条。
- **包含 traceId**：与日志/审计串联，签名值本身不可复用。
- **body 摘要**：仅对 `Content-Length>0` 且 `Content-Type` 为 JSON/文本的请求计算；上限 1 MB，
  超限直接 413（病历正文已有大小限制，不会命中）。流式上传（未来影像）走"摘要头"方案，不在本期。
- **query 排序**：同一语义不同顺序不得产生不同签名（网关与下游用同一实现，放在 common 里）。

### 5.3 校验流程与失败语义

```
请求进入业务服务
  ├─ path ∈ /actuator/health/**, /actuator/info → 放行（仅监听内网/探针）
  ├─ path ∈ /internal/** → 走"服务→服务"校验（见 §6）
  ├─ mode=off → 放行（仅允许 dev profile；prod 启动即拒绝 off）
  ├─ 校验：头齐全 → keyId 已知 → |now-ts| ≤ 60s → nonce 未用过 → HMAC 相等
  │    └─ 任一失败：403 INTERNAL_AUTH_FAILED（不区分原因，避免探测）
  └─ 通过 → 写入请求属性（caller=gateway, keyId），供审计/指标使用
```

- **失败一律 403**，响应体仍用 `ApiResponse.fail(FORBIDDEN, "请求来源未通过校验")`，
  不暴露"是时间戳过期还是签名错"，防止攻击者做 oracle。
- **指标**：`medassist_internal_auth_total{result=ok|bad_sig|skew|replay|unknown_key}`；
  连续 `bad_sig` 突增即告警（通常意味着密钥未同步或有人扫描）。
- **启动自检**：`mode=enforce` 且未配置密钥 → **拒绝启动**（与 EMPI FieldCipher 同规格），
  杜绝"配错就静默放行"。

### 5.4 防重放

- 时间窗：`±60s`（院内 NTP 必须可用；无 NTP 时放宽到 ±300s 并记录告警）。
- nonce 去重：Redis `SET iauth:nonce:{keyId}:{nonce} 1 NX EX 120`；
  - Redis 不可用时：`enforce` 模式 **fail-closed**（503，宁可业务短暂不可用，也不放开 PHI），
    `log` 模式降级为本地 Caffeine 缓存（多副本窗口内可重放，仅用于灰度期）。
  - key 前缀按 keyId 区分，轮换后旧 nonce 不阻塞新密钥。
- 不用"仅时间戳"方案：60s 内重放足以重复触发结算/归档。

### 5.5 密钥管理与轮换

- 存储：K8s Secret `medassist-internal-hmac`，JSON：`{"active":"gw-2026-11","keys":{"gw-2026-11":"<base64 32B>","gw-2026-10":"<base64 32B>"}}`。
  - 与现有 `medassist-internal`（内部令牌）分开放，便于独立轮换与最小权限挂载。
  - 非 K8s 部署（compose/裸机）用 `INTERNAL_HMAC_KEYS` 环境变量，**无默认值**。
- 轮换（常规，90 天）：
  1. 生成新 key `gw-2026-12`，加入 Secret（`keys` 同时含新旧），滚动下游 → 下游"接受多把密钥"；
  2. 切换网关 `active=gw-2026-12` → 观察 `result=ok` 比例 100%；
  3. 7 天后从 `keys` 移除旧 key，滚动下游。
  - 全程不停机；网关与下游解耦。
- 轮换（应急，密钥疑似泄露）：
  1. 立即从所有下游 `keys` 移除泄露 key（滚动），此时旧 key 请求 403；
  2. 同一步骤切换网关 active 到新 key；
  3. 预期有 < 1 分钟 403 抖动，记录变更单；**不允许**用"临时关闭校验"应急。
- 生成要求：`openssl rand -base64 32`，禁止人工设定、禁止进 Git；
  CI 增加密钥扫描（gitleaks）防止再次把 dev 令牌提交入库（MF-09 同源问题）。

### 5.6 性能评估

| 项 | 量级 | 说明 |
|---|---|---|
| HMAC-SHA256（<4KB 规范串） | ~10 µs | 可忽略 |
| body SHA-256（4KB） | ~10 µs | 病历正文上限 256 KB 时 ~0.6 ms |
| Redis nonce SETNX | ~0.2 ms（同机房） | 每请求一次；可按 traceId 合并（非本期） |
| 预期 P99 增量 | < 2 ms | 相对医院内网 RTT（1~3 ms）可接受 |

### 5.7 配置项（common 自动配置）

```yaml
medassist:
  internal-auth:
    mode: enforce            # off | log | enforce（prod 只允许 log/enforce）
    clock-skew-seconds: 60
    keys-json: ${INTERNAL_HMAC_KEYS:}       # 无默认值
    active-key-id: ${INTERNAL_HMAC_ACTIVE:}
    nonce-store: redis       # redis | local（local 仅 log 模式）
    exempt-paths:
      - /actuator/health/**
      - /actuator/info
```

- `mode=log`：校验并打点，但不拒绝——用于灰度验证"是否所有流量都经过网关"。
- 部署侧：Helm 增加 `internalAuth.enabled/mode/existingSecret`；dev 默认 `log`，staging/prod 默认 `enforce`。

---

## 6. 服务 → 服务（`/internal/**`）加固

现状：`X-Internal-Token` 静态共享串 + 未配置时 fail-closed（已做）。问题是一次泄露全通、无法定位调用方。

**设计**：

1. 调用方身份：新增 `X-Internal-Caller`（如 `clinical`），被调方按此选密钥；
   未知 caller 直接 403。
2. 双因子：`X-Internal-Token`（保留，作为"是不是内部流量"的粗筛）+ 调用方 HMAC（同 §5.2 规范串，
   身份头段只取 `X-Internal-Caller`，其余为空）+ `X-Internal-Ts/Nonce/Key-Id`。
3. 密钥隔离：每对调用关系一组密钥（`clinical→empi`、`emr→clinical`、`insurance→clinical`…），
   泄露只影响一条边，可单独轮换。
4. 未来可选：内部接口只允许来自 mTLS 证书主体的白名单（`clinical` 证书只能调 empi 的 `/internal/patients/**`）。
5. 兼容迁移：先让被调方"可选验签"（有签名则校验、无签名按 token 放行 + 打点），
   全量调用方上线签名后再切"必须验签"，避免大爆炸式停机。

---

## 7. 阶段二：mTLS 演进路径

| 项 | 设计 |
|---|---|
| CA | 院内自建（cert-manager + 内部 CA），离线签发；不依赖公网 |
| 证书主体 | SPIFFE URI SAN：`spiffe://medassist/ns/<ns>/sa/<service>` |
| Java 侧 | 挂载 PKCS12 keystore/truststore（Secret），`server.ssl.*` 开启；证书轮换需滚动重启（记录在运维手册） |
| Go/Python（AI 中台） | 同样挂载证书；Python `ssl.SSLContext` 双向校验 |
| 授权 | 控制器从证书主体取 caller，与 `X-Internal-Caller` 交叉校验；不一致 403 |
| 探针 | kubelet 探针走 HTTPS + 仅校验服务端证书（或独立 health 端口） |
| 性能 | 长连接摊销；JVM 默认 TLS1.3，握手开销 < 1 ms（同机房） |
| 风险 | 证书过期导致全院不可用 → 必须配 `cert-manager` 到期告警（提前 30/7/1 天）+ 启动日志打印剩余天数 |

> mTLS 是"更强"，但不能替代应用层签名：Service Mesh 侧车若被绕过（hostNetwork、直连 Pod IP），
> 传输层身份就失去意义；两层同时存在才是完整链路。

---

## 8. 灰度与回滚

**灰度顺序**（每步观察 1~2 天）：

1. 阶段 0：密钥下发 + common 过滤器以 `log` 模式上线（所有服务），只打点不拦截；
   目标：`result=bad_sig` 必须为 0，否则说明存在绕过网关的直连调用（正好暴露真实调用图）。
2. 阶段 1：staging `enforce`，跑全量单测 + `emr_smoke.ps1` / `insurance_smoke.ps1`；
   补一条"直连业务端口无签名 → 403"断言。
3. 阶段 2：prod 按 **emr → insurance → clinical → tech → pharmacy → empi → auth** 逐个 `enforce`
   （先做 PHI/资金域，后做低风险域；auth 最后，避免登录链路抖动影响排障）。
4. 阶段 3：把 `/internal/**` 从"可选验签"切"必须验签"。
5. 阶段 4（可选）：mTLS。

**回滚**：把对应服务 `medassist.internal-auth.mode` 改为 `off` 并滚动重启（ConfigMap 变更，无 DB 迁移）。
回滚窗口目标 < 1 分钟/服务；`log` 模式可作为中间态（仍打点、不阻断）。
**回滚不等于关闭 NetworkPolicy**：网络层限制始终保留。

---

## 9. 测试与验收

**单元测试（common 模块）**

| 用例 | 断言 |
|---|---|
| canonical v1 稳定性 | 字段顺序/空值/query 排序/中文/特殊字符不变 |
| 篡改 `X-Permissions` | 403 |
| 篡改 path/query/body | 403 |
| 时间戳过期/超前 | 403（skew=60s 边界） |
| nonce 重放 | 第二次 403 |
| 未知 keyId | 403 |
| 双密钥并行（轮换中） | 新旧密钥均 200 |
| 缺失密钥 + enforce | 应用启动失败（fail-fast） |
| exempt path | 无签名也 200 |
| `mode=log` | 放行且指标 `bad_sig` +1 |

**集成/E2E**

- 新增脚本断言：`curl` 直连 `8086/emr/records/{id}/print`（带伪造 `X-Permissions`）→ **403**；
  经网关同请求 → 200。
- 网关侧：客户端自带 `X-Internal-Sig` 被剥离（用同名头伪造不应影响验签结果）。
- 医保资金链路（generate/confirm）在 `enforce` 下全量回归，确保内部调用签名齐备。

**CI 门禁**

- `gitleaks` 扫描（dev 令牌/密钥不入库）；
- Helm 渲染断言：prod values 必须 `mode != off` 且挂载了 HMAC Secret；
- 性能基线：`enforce` 下核心接口 P99 增量 ≤ 5 ms。

---

## 10. 风险与未决项

| # | 风险 | 缓解 |
|---|---|---|
| R1 | Redis 故障导致 nonce 无法校验 | enforce 下 fail-closed（503）；Redis 走 HA；提供只读降级预案（仅限短时、需审批） |
| R2 | 院内 NTP 不可用导致时间窗误判 | 放宽到 ±300s + 告警；核心交换机侧启用 NTP |
| R3 | 有未发现的绕过网关直连（定时任务、运维脚本） | 阶段 0 的 `log` 模式先摸清流量，逐个改造后再 enforce |
| R4 | 证书/密钥轮换操作失误 | 双密钥并行 + 7 天过渡；变更单 + 演练脚本 |
| R5 | 性能影响 | 实测 P99；body 摘要设 1 MB 上限 |
| R6 | dev 令牌仍留在仓库历史 | 视为已泄露并轮换（MF-09）；本设计不解决历史清理，需单独做 secrets 清理 |
| 未决 1 | 是否强制 body 摘要 | 建议第一阶段对 JSON 强制，影像/大文件后续用摘要头 |
| 未决 2 | 服务网格选型 | 若院内已有 Istio，可提前进入阶段二；否则先完成阶段一 |

---

## 11. 任务拆解（供下一批排期，本文不含代码）

| 任务 | 交付物 | 验收 |
|---|---|---|
| T1 | `common/security/InternalSignature`（canonical + HMAC）+ 单测 | §9 单元用例全过 |
| T2 | `common/web/InternalAuthFilter`（auto-config，`mode` 开关）+ 指标 | `log/enforce/off` 行为正确 |
| T3 | 网关 `InternalSignatureGlobalFilter`（签名 + 剥离客户端伪造头） | 伪造同名头不影响结果 |
| T4 | Helm：`internalAuth.*`、`medassist-internal-hmac` Secret、prod 守卫 | 渲染断言通过 |
| T5 | `/internal/**` 调用方签名（按边密钥 + caller 白名单） | 边级密钥可独立轮换 |
| T6 | E2E 断言（直连 403）+ CI gitleaks | 脚本与流水线通过 |
| T7 | 运维手册：轮换/应急/回滚 + 演练记录 | 演练一次通过 |
| T8（可选） | mTLS：cert-manager、证书挂载、主体交叉校验 | staging 全链路双向校验 |

---

*本文为设计评审稿：落实前需完成 T1~T4 并先在 staging 以 `log` 模式观测真实调用图；
阶段二（mTLS）另行评审。*
