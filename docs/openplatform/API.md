# MedAssist 开放平台 API 接入说明

| 项 | 内容 |
|---|---|
| 版本 | v1.0（2026-11-13，评测 D-26 补齐） |
| 适用对象 | 院方信息科 / 第三方系统厂商（HIS、体检、随访、科研平台等） |
| 入口 | 统一网关 `https://<院内域名>/api/v2/...`（单机演示：`http://127.0.0.1:8080`） |
| 协议 | HTTPS + OAuth2 `client_credentials`（RFC 6749）= JWT Bearer |
| 数据边界 | 只读。开放平台**不提供写入**：任何 `POST/PUT/DELETE` 都按 scope 拒绝 |

> 本文件是接入契约，不替代各业务域的接口说明。FHIR 资源语义以 HL7 FHIR R4 为准。

---

## 1. 接入流程（申请 → 审批 → 发证）

开放平台客户端是"机器身份"，一旦泄露等于把只读数据面整体交出去，因此不允许自助注册。

| 步骤 | 动作 | 责任方 | 产物 |
|---|---|---|---|
| 1 | 提交申请：系统名称、用途、需要的数据范围、调用量预估、数据留存与销毁承诺 | 第三方 | 申请单（可走院内工单） |
| 2 | 数据合规审查：是否涉及患者身份标识（PHI）、是否跨院区、是否再转发 | 数据管理 + 信息安全（**双人**） | 审批结论（含 scope 清单） |
| 3 | 创建客户端：管理员在控制台「开放平台」按审批结论勾选 scope，设置客户端有效期 | 平台管理员（`auth:user:create` 权限域） | `client_id` + **一次性明文 `client_secret`** |
| 4 | 密钥交付：只交付一次，走加密渠道；平台不保存明文，丢失只能轮换 | 平台管理员 → 第三方 | 接入凭据 |
| 5 | 联调与验收：按第 4 节拿令牌、按第 6 节核对错误码与限流 | 双方 | 联调记录 |
| 6 | 运维：到期前轮换密钥（`POST /api/v2/auth/oauth-clients/{id}/rotate`），停用即时生效 | 平台管理员 | 轮换/停用审计 |

约束（服务端强制，不是约定）：

- 明文密钥只在**创建/轮换的响应里出现一次**，库里只存 SHA-256 与提示尾号；
- 客户端可设置绝对有效期（`expiresAt`），令牌 TTL 不会超过客户端剩余有效期；
- 停用后立即无法换取新令牌；已签发令牌在自身 TTL（≤15 分钟）内自然过期；
- 所有 scope 都是**只读**权限点，目录外的字符串直接 422。

---

## 2. 授权模型

```
第三方系统 ──(client_id/secret, client_credentials)──▶ /api/v2/auth/oauth/token
                                                        │ 签发 JWT（TTL 15 分钟）
                                                        ▼
                          Authorization: Bearer <access_token>
                                                        │
                                              网关校验签名/iss/撤销名单
                                                        │ 注入 X-Actor/X-Permissions/X-Role 等内部头
                                                        ▼
                                            目标服务按 scope 校验（Permissions.require）
```

- 令牌载荷：`roles=[INTEGRATION_CLIENT]`、`permissions=<授予的 scopes>`、`tenant`、`instance`；
- 网关会**覆盖**客户端自带的身份头，伪造 `X-Actor / X-Permissions` 无效；
- 令牌不刷新（无 refresh_token）：过期重新走 `client_credentials`，这是有意的短生命周期设计。

### 2.1 换取令牌

```bash
curl -X POST 'https://<院内域名>/api/v2/auth/oauth/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=client_credentials' \
  -d 'client_id=cli_xxxxxxxxxxxxxxxx' \
  -d 'client_secret=<一次性密钥>' \
  -d 'scope=fhir:read empi:patient:read'
```

成功响应（RFC 6749 §5.1，**不套平台统一响应壳**）：

```json
{"access_token":"eyJhbGciOiJIUzI1NiJ9...","token_type":"Bearer","expires_in":900,
 "scope":"fhir:read empi:patient:read"}
```

`scope` 可以留空（= 客户端全部已授予范围），也可以**收窄**；申请超出授予范围返回 `invalid_scope`。

---

## 3. Scope 目录（17 项，全部只读）

| Scope | 含义 | 是否含 PHI |
|---|---|---|
| `fhir:read` | FHIR R4 只读（不含患者身份标识） | 否（脱敏） |
| `fhir:read_phi` | FHIR R4 只读（含姓名/证件号等身份标识） | **是** |
| `empi:patient:read` | 患者主索引查询 | 是 |
| `clinical:visit:read` | 就诊与号源查询 | 是 |
| `clinical:diagnosis:read` | 诊断查询 | 是 |
| `clinical:report:read` | 检查检验报告查询 | 是 |
| `pharmacy:read` | 药品与库存查询 | 否 |
| `tech:read` | 医技申请与标本查询 | 是 |
| `insurance:settlement:read` | 医保结算清单查询 | 是 |
| `mdm:read` | 主数据查询 | 否 |
| `kb:search` | 知识库检索 | 否 |
| `kb:answer` | 知识库问答 | 否 |
| `integration:message:read` | 集成台账查询 | 否 |
| `ops:read` | 运营看板查询 | 否 |
| `release:read` | 发布信息查询 | 否 |
| `license:read` | 授权状态查询 | 否 |
| `audit:log:read` | 审计日志查询 | 否 |

> `fhir:read` 与 `fhir:read_phi` 是两个独立授权：需要身份标识时必须单独审批，
> 不能通过参数"顺便带出来"。

---

## 4. 端点示例（只读）

| 方法 | 路径 | 作用 | 需要 scope |
|---|---|---|---|
| GET | `/api/v2/fhir/metadata` | FHIR CapabilityStatement | `fhir:read` |
| GET | `/api/v2/fhir/Patient?identifier=...` | 患者检索（分页/条件见 FHIR 约定） | `fhir:read` / `fhir:read_phi` |
| GET | `/api/v2/fhir/Patient/{id}` | 单个患者 | 同上 |
| GET | `/api/v2/fhir/Encounter?...` | 就诊/住院 | `fhir:read` / `fhir:read_phi` |
| GET | `/api/v2/fhir/Encounter/{id}` | 单次就诊 | 同上 |
| GET | `/api/v2/fhir/Observation?...` | 检验/体征观察 | `fhir:read` / `fhir:read_phi` |
| GET | `/api/v2/fhir/Observation/{id}` | 单条观察 | 同上 |
| GET | `/api/v2/fhir/MedicationRequest?...` | 用药请求 | `fhir:read` / `fhir:read_phi` |
| GET | `/api/v2/fhir/MedicationRequest/{id}` | 单条用药 | 同上 |
| GET | `/api/v2/empi/patients/{id}` | 患者主索引档案 | `empi:patient:read` |
| GET | `/api/v2/kb/search?...` | 知识库检索 | `kb:search` |

响应约定：

- FHIR 端点返回标准 `Bundle`/资源 JSON（`Content-Type: application/fhir+json`）；
- 其他端点返回平台统一壳 `{"code":0,"message":"成功","data":{...},"timestamp":"..."}`；
- 分页参数与上限由各域定义，超限返回 422 而不是静默截断。

---

## 5. 错误码

### 5.1 令牌端点（RFC 6749 §5.2）

| HTTP | `error` | 触发条件 |
|---|---|---|
| 400 | `unsupported_grant_type` | 只支持 `client_credentials` |
| 401 | `invalid_client` | client_id 不存在 / 密钥错误 / 客户端已停用或过期 |
| 400 | `invalid_scope` | 申请的 scope 超出已授予范围 |
| 400 | `invalid_request` | 其他参数问题（响应里带 `trace_code` 便于对日志） |

### 5.2 业务端点（平台统一壳）

| HTTP | `code` 段 | 含义 |
|---|---|---|
| 400 | 422xx | 参数校验失败（缺字段/格式错/分页越界） |
| 401 | 401xx | 未认证或令牌过期 |
| 403 | 403xx | 无该 scope / 数据范围外 |
| 404 | 404xx | 资源不存在（或无权看到） |
| 409 | 409xx | 状态冲突 / 版本冲突（并发） |
| 429 | — | 触发入口限流（见第 6 节），响应头带 `Retry-After` |
| 503 | — | 下游熔断或依赖不可用 |

---

## 6. 限流与容量

网关按**路径前缀**做入口保护（Sentinel，`medassist.gateway.sentinel`），超出即 429；
慢调用比例过高会短时熔断并返回 503（`Retry-After` 提示退避）。

| 路径前缀 | 默认 QPS | 覆盖环境变量 |
|---|---:|---|
| `/api/v2/auth/oauth` | 30 | `SENTINEL_QPS_OAUTH` |
| `/api/v2/auth` | 200 | `SENTINEL_QPS_AUTH` |
| `/api/v2/empi` | 200 | `SENTINEL_QPS_EMPI` |
| `/api/v2/clinical/ai-assist` | 20 | `SENTINEL_QPS_AI_ASSIST` |
| `/api/v1`（AI 中台） | 500 | `SENTINEL_QPS_AI_CORE` |

熔断（慢调用比例）：`/api/v2/clinical` 8s、`/api/v2/clinical/inpatient` 6s、`/api/v2/empi` 5s，
慢调用比例 > 0.5 且窗口内请求数达标即熔断 10–15 秒。

接入要求：

- 批量同步必须分页 + 指数退避，遇到 429 按 `Retry-After` 退避，不得原地重试；
- 令牌缓存到 `expires_in - 60s`，禁止每个请求都换令牌（令牌端点单独 30 QPS）；
- 单客户端配额如与上表冲突，以书面审批的配额为准，并通过环境变量在网关侧落地。

---

## 7. 数据合规

- 仅按审批范围取数；`fhir:read_phi` 的调用必须能对应到具体业务用途；
- 开放平台调用全部写审计（谁、何时、取了哪个资源），审计不可篡改且可被 `audit:log:read` 查询；
- 不得将数据再转发给未审批的第三方；合作终止时由院方停用客户端并轮换密钥；
- 生产环境禁止使用仓库中的开发客户端，客户端有效期到期后必须重新审批。

---

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-13 | 首版：补齐鉴权流、scope 目录、端点示例、错误码、限流与合规要求（评测 D-26） |
