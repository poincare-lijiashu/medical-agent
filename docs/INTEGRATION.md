# MedAssist HIS 对接架构（integration 层）

> 批2 任务2 落地。对接哪家医院、用何种协议，核心业务零改动——所有院内系统对接收口在 `backend/integration/`。

## 一、端口-适配器边界（依赖倒置）

```
┌──────────────────────────────────────────────────────────────┐
│  核心业务（零感知，不改一行）                                    │
│  backend/api/v1/medical/qc.py（W4.3 前在 medical_router.py）    │
│      └─ qc_record 出质控结论后 → _his_push_qc_result()         │
│             │  只认识两个东西：                                 │
│             │   ① HisAdapter 抽象接口（backend/integration/base.py）│
│             │   ② get_adapter() 工厂（backend/integration/registry.py）│
└─────────────┼────────────────────────────────────────────────┘
              │ 依赖倒置：高层定义端口，低层实现并注册
┌─────────────▼────────────────────────────────────────────────┐
│  backend/integration/（对外边界，医院 HIS/EMR 对接唯一入口）      │
│   base.py        HisAdapter 抽象基类 + NullAdapter 空实现       │
│   registry.py    注册表工厂：settings.his_adapter 选择激活       │
│   demo_adapter.py DemoHisAdapter（内存假数据，演示/联调）         │
│   <vendor>.py    厂商实现（未来新增，注册即插即用）                │
└─────────────┬────────────────────────────────────────────────┘
              │ 唯一出界点
      ┌───────▼────────┐
      │  医院 HIS/EMR   │  视图库 / WebService / REST 网关
      └────────────────┘
```

依赖方向纪律（由 `tests/test_architecture.py` 静态锁定）：

- `api → integration` ✅（调用点在 api 层业务完成点）
- `integration → config/base` ✅（只依赖配置与自身抽象）
- `core/agents → integration` ❌（核心服务层不得感知对接边界）
- `integration → core/agents/api` ❌（对接层保持独立可替换，反向依赖会把它焊死在核心上）

## 二、接口面（`HisAdapter` 三个方法）

| 方法 | 预期语义 | 对接前置条件 |
|---|---|---|
| `fetch_patient(patient_id) -> dict \| None` | 按患者主索引拉摘要（姓名/性别/年龄/过敏史）；查无此人返回 None，不抛业务异常 | 院方开通只读查询账号；明确 patient_id 类型（门诊号/住院号/EMPI）与字段映射 |
| `push_qc_result(record_id, result) -> bool` | 质控结论幂等回写；成功 True；适配器侧明确拒收 False（不抛）；故障抛异常 | 院方提供回写接口规范（触发时机/字段映射/重试与幂等约定） |
| `fetch_orders(patient_id) -> list` | 拉医嘱/处方清单；无医嘱或查无此人返回 [] | 院方明确医嘱视图范围（在院/历史）与出界脱敏要求 |

## 三、激活方式（配置即扩展）

`.env.local`（模板见 `.env.example`）：

```
HIS_ADAPTER=none        # 默认：未对接（NullAdapter 全空转，零审计噪音）
HIS_ADAPTER=demo        # 演示/联调：内存假数据，验证推送链路与审计语义
HIS_ADAPTER=<vendor>    # 对接真实医院时填厂商标识（registry 注册名）
```

未对接（`none`）时核心流程**零行为变化、零审计噪音**——不需要任何「是否已对接」的判断分支。

## 四、接入点与失败语义（唯一真接入示例：qc 质控结论外推）

位置：`backend/api/v1/medical/qc.py` 的 `_his_push_qc_result()`（W4.3 router 拆分前在 `medical_router.py`；`qc_record` 三个出口各调用一次：自动驳回 / 自动归档 / 入队留痕后）。

推送 payload：`{"status": "auto_rejected"|"auto_archived"|"enqueued"|"rejected_escalated", "confidence", "defects", "escalated"}`，`record_id` 为本系统复核单号（自动分流路径为空串）。

失败语义（全兜底，绝不影响质控主流程）：

| 情形 | 行为 | 审计（event_type="his"） |
|---|---|---|
| `his_adapter=none` | 静默跳过 | 无（零噪音） |
| 适配器返回 True | — | `action="pushed"`（含 record_id/adapter/status） |
| 适配器返回 False（拒收/跳过） | 不重试（重试策略属厂商适配器内部职责） | `action="push_skipped"` |
| 适配器抛异常 | 吞掉 | `action="push_failed"`（含 error/adapter） |
| `his_adapter` 配置未知标识 | 吞掉 | `action="push_failed"`（`stage="resolve_adapter"`） |

医生侧质控报告的返回**不受 HIS 可用性影响**（HIS 宕机 ≠ 质控不可用）。

## 五、新增厂商适配器的步骤（4 步，核心零改动）

1. 新建 `backend/integration/<vendor>.py`，实现 `HisAdapter` 三个方法（可抄 `demo_adapter.py` 作模板）：
   - 凭据经环境变量/secret 注入，**禁止硬编码**；
   - 字段映射、重试、限速全部封装在适配器内部；
   - 推送以 `record_id` 幂等（重复推送不产生重复缺陷单）。
2. 在 `backend/integration/registry.py` 底部（或部署入口处）注册一行：
   `register_adapter("<vendor>", VendorAdapter)`；
3. `.env.local` 配 `HIS_ADAPTER=<vendor>`，重启服务生效；
4. 测试：在 `tests/test_his_integration.py` 照 demo 用例补注册表/往返/兜底断言。

## 六、为什么核心不直接依赖 HIS

1. **可替换性**：每家医院接口规范、鉴权、字段映射都不同——把差异焊进核心，第二家医院接入就是一次全量回归；
2. **可测试性**：适配器是纯接口，单测用内存假数据即可覆盖接入点语义（含异常兜底），不需要真 HIS；
3. **可用性隔离**：HIS 宕机不能拖垮质控/会诊等核心流程——边界层把故障吞在适配器调用点上（见第四节失败语义）；
4. **合规边界清晰**：临床数据出院界只发生在 `integration/` 一个包里，安全审计范围最小化；
5. **节奏解耦**：HIS 对接受院方接口规范制约（见 DEPLOY.md「质控 HIS 对接与本地模型规划」——接口规范/标注数据两件事在等院方），边界层就位后规范到位即可插拔，不阻塞其它迭代。

## 七、测试

```powershell
python -m pytest tests\test_his_integration.py -v
```

覆盖：registry（none/demo/自定义注册/未知标识响亮失败）、demo 适配器数据往返、qc 接入点三态（none 零审计跳过 / demo 成功推送 `his.pushed` / 异常兜底 `his.push_failed` 且主流程不受影响）。

## 八、标准协议适配器：FHIR R4（`HIS_ADAPTER=fhir`）

> 目标：不再只依赖"院方私有接口 + 手工映射"。FHIR R4 是 HL7 正式标准，
> `Patient` / `ServiceRequest` / `MedicationRequest` / `DocumentReference` 均有规范定义，
> 院内集成平台（常见落地：Mirth Connect、HAPI FHIR，或厂商平台暴露的 `/fhir` 根）
> 可把厂商差异收敛到网关侧，AI 中台只对接标准资源。

### 8.1 激活方式

```bash
HIS_ADAPTER=fhir
FHIR_BASE_URL=https://his-gateway.hospital.local/fhir
FHIR_TOKEN=<由 Secret 注入，最小只读 + 回写权限>
FHIR_TIMEOUT_S=8
FHIR_VERIFY_TLS=true          # 生产禁止关闭；仅本机自签联调可临时 false
```

### 8.2 资源映射（`backend/integration/fhir_adapter.py`）

| 业务动作 | FHIR 交互 | 失败语义 |
|---|---|---|
| 患者摘要 | `GET /Patient/{id}` | 404 → `None`（调用方转 404）；4xx/5xx → 抛异常（调用方 502 + 审计） |
| 医嘱清单 | `GET /ServiceRequest?subject=Patient/{id}&_count=50&_sort=-authored` | 4xx/5xx 抛异常 |
| 质控回写 | `POST /DocumentReference`（`identifier` = 质控单号，JSON 附件 = 结论） | 2xx 成功；4xx 明确拒收（返回 False，不重试）；5xx/网络异常抛出（可重试） |

### 8.3 读端点（AI 中台第一次真正读取院内业务数据）

| 端点 | 说明 |
|---|---|
| `GET /api/v1/medical/integration/patient/{patient_id}` | 患者摘要（姓名/性别/年龄）；未对接 501，未找到 404，HIS 故障 502 |
| `GET /api/v1/medical/integration/orders/{patient_id}` | 医嘱清单（供用药核查/质控关联） |

审计：`his.patient_read` / `his.orders_read` / `his.patient_not_found` / `his.read_failed`，
payload 只记**患者标识哈希**（sha256 前 12 位）与适配器名，不落明文患者 ID/姓名。

### 8.4 公开资料与开源参考

- HL7 FHIR R4 规范：<https://hl7.org/fhir/R4/patient.html>
- FHIR 官方站点：<https://fhir.hl7.org>
- 开源集成引擎 Mirth Connect（HL7 v2 / FHIR R4 / X12，社区与招聘面最大）：
  <https://mirth.support/best-hl7-integration-engines-2026>
- HL7 官方开源实现清单（HAPI FHIR 等）：<https://confluence.hl7.org/pages/viewpage.action?pageId=453906989>
- 国内标准：国家卫健委《医院信息互联互通标准化成熟度测评方案（2020 年版）》（数据集 / 共享文档 / 交互服务三类测试）；
  WS/T 846.1—2024《医院信息平台交互标准 第 1 部分：总则》；WS/T 500 系列电子病历共享文档规范
- Python 侧 HL7 v2 备选：`hl7apy`（<https://pypi.org/project/hl7apy/1.3.4/>）；
  医疗 AI SDK 参考：`healthchain`（<https://pypi.org/project/healthchain/>）

### 8.5 下一步（按收益排序）

1. **HL7 v2 MLLP 适配器**（`backend/integration/hl7v2_adapter.py`）：老院区仍以 HL7 v2.5
   ADT/ORM/ORU 为主，MLLP 长连接 + ACK 语义是另一条必备通道（第 170 批已落地）；
2. **Java 平台侧 AI 客户端**：HIS 界面内触发质控/审方（双向集成），避免医生开两个系统
   （第 169/173 批已落地：外部通道 `/api/v2/clinical/ai-assist/**`）；
3. **患者主索引（EMPI）唯一真源**：AI 侧只存 `empi_id` 引用 + 哈希，不复制患者主数据
   （第 174 批已落地，见 §8.6）；
4. **契约测试进 CI**：用 FHIR 官方示例资源 + MockTransport 已覆盖；再加 staging 双系统联调。

### 8.6 患者主索引（EMPI）唯一真源（第 174 批）

**原则**：患者主索引只存在于 EMPI；AI 中台不建患者表、不复制姓名/证件号等主数据，
审计与引用只用 `empi_id`（UUID）或其哈希（`sha256` 前 12 位）。

| 环节 | 事实 |
|---|---|
| 读取 | `GET /internal/patients/{uuid}`（同命名空间直连，不经网关；`X-Internal-Token`，fail-closed） |
| 字段 | 只映射姓名/性别/出生日期（推出年龄）/病案号/状态；证件号、手机号、住址不出 EMPI |
| 回退 | `EMPI_BASE_URL` 留空 → 患者摘要回落 HIS 适配器（FHIR / HL7 v2），兼容暂无 EMPI 的现场 |
| 失败 | 404 → 业务 404；401/403 → 502 + 审计（令牌/配置问题，不静默降级）；5xx → 502 |
| 配置 | `EMPI_BASE_URL` / `EMPI_INTERNAL_TOKEN` / `EMPI_TIMEOUT_S` / `EMPI_VERIFY_TLS`；Helm 侧 `aiCore.empi.baseUrl` + 复用 `external.internalApi` 的 Secret |
| 网络 | NetworkPolicy 放行 `ai-core → empi`（同命名空间）；网关对 `/internal/**` 永久 404 |

`GET /api/v1/medical/integration/patient/{id}` 响应新增 `source`（`empi` / `his`）与 `adapter`，
控制台「HIS 集成工作台」据此展示"EMPI（唯一真源）/ HIS 适配器"；`/integration/status`
新增 `empi.configured` 与 `empi.error`（不回显地址/令牌）。

## 九、Java 侧 AI 客户端（HIS → AI 反向调用，`clinical.ai.base-url`）

> 前面第八节解决的是"AI 读 HIS"；本节解决"医生在 HIS 界面里直接用 AI"——双向集成的另一半。

### 9.1 组成

| 文件 | 作用 |
|---|---|
| `clinical/client/AiAssistClient.java` | 接口：`qcCheck` / `rxCheck` |
| `clinical/client/HttpAiAssistClient.java` | HTTP 实现：`RestClient` + 3s 超时；**令牌透传**（医护端 JWT → AI 中台，不新增服务账号） |
| `clinical/web/AiAssistController.java` | `POST /internal/ai/qc-check`、`POST /internal/ai/rx-check`（网关内网白名单） |
| `application.yml` | `clinical.ai.base-url: ${CLINICAL_AI_BASE_URL:}`、`timeout: ${CLINICAL_AI_TIMEOUT:PT3S}` |

### 9.2 错误语义（不静默降级）

| 场景 | 返回 |
|---|---|
| 未配置 `CLINICAL_AI_BASE_URL` | `503 UPSTREAM_UNAVAILABLE`（"AI 中台未配置…"） |
| 请求缺 Authorization | `401 UNAUTHORIZED` |
| AI 侧 4xx（如内联参数不合法） | `422 VALIDATION_FAILED` |
| AI 侧 5xx | `503 UPSTREAM_UNAVAILABLE` |
| 连不上/超时 | `504 GATEWAY_TIMEOUT` |

日志只记路径与状态码，不记病历正文/令牌（PHI 最小化）。

### 9.3 配置示例

```yaml
clinical:
  ai:
    base-url: ${CLINICAL_AI_BASE_URL:http://medassist-ai-core:8001}
    timeout: ${CLINICAL_AI_TIMEOUT:PT3S}
```

### 9.4 测试

`clinical/src/test/java/com/medassist/clinical/client/AiAssistClientTest.java`（8 条，JDK 内置
`HttpServer` 起临时端口，真实 HTTP）：令牌透传与路径、4xx→422、5xx→503、无令牌→401、
未配置→503、不可达→504、Controller Bearer 解析。

```
mvn -q -pl clinical -am "-Dtest=AiAssistClientTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
→ Tests run: 8, Failures: 0, Errors: 0
```

## 十、HL7 v2.5 MLLP 适配器（`HIS_ADAPTER=hl7v2`，老院区通道）

> FHIR 面向"新建/集成平台化"院区；老院区仍以 **HL7 v2 + MLLP** 为主（ADT/ORM/ORU + 逐条 ACK）。
> 两条通道同一 `HisAdapter` 端口，选型只改一行配置，核心业务零改动。

### 10.1 激活

```bash
HIS_ADAPTER=hl7v2
HL7_MLLP_HOST=<院内集成网关地址>
HL7_MLLP_PORT=2575
HL7_MLLP_TIMEOUT_S=5
HL7_ENCODING=utf-8
```

### 10.2 消息映射（`backend/integration/hl7v2_adapter.py`）

| 业务 | 发送报文 | 解析 | 失败语义 |
|---|---|---|---|
| 患者摘要 | `QBP^Q22^QBP_Q21`（`QPD` 按 `@PID.3` 查询） | 响应 `PID` 段（PID-3/5/7/8） | `MSA|AE/AR` → `None`；无 PID → `None`；网络/超时 → 抛异常 |
| 医嘱清单 | `QRY^R02^QRY_R02`（`QRD` 按 `@PID.3`） | 响应 `ORC`+`OBR` 段 | `AE/AR` → `[]`；网络 → 抛异常 |
| 质控回写 | `ORU^R01`（`OBR-2`=质控单号、`OBR-4`=QCRESULT、`OBX-5`=结论 JSON） | `MSA` | `AA`→True；`AE/AR`→False（不重试）；无 ACK → 抛异常 |

### 10.3 MLLP 协议层（`backend/integration/mllp.py`）

- 帧：`\x0b` + 报文 + `\x1c\x0d`；短连接 + 逐条 ACK（不维护长连接池）；
- 字段转义：`\ → \E\`、`| → \F\`、`^ → \S\`、`~ → \R\`、`& → \T\`（先转义反斜杠防二次转义）；
- 超时：连接超时与 ACK 等待共用同一个 `HL7_MLLP_TIMEOUT_S`，超时抛 `MllpTimeout`；
- 响应无 MLLP 帧边界 → `MllpError`（说明网关不是 MLLP 或代理改写了报文）。

### 10.4 与 FHIR 适配器的选择

| 院区形态 | 建议 |
|---|---|
| 有集成平台 / 新上 FHIR 网关 | `fhir`（JSON、REST、字段语义清晰） |
| 老 HIS 只有 HL7 v2 接口机 | `hl7v2`（QBP/QRY、ACK 明确） |
| 尚未对接 | `none`（默认，读端点 501、回写静默跳过） |

### 10.5 测试

`tests/test_mllp_hl7v2.py`（12 条）：本地 TCP 回环服务器验证 MLLP 帧/ACK/超时/无帧报错；
适配器用 mock 传输验证 PID/ORC/OBR 映射、AE→None/[]、AA/AE/无 ACK 三态、缺配置响亮失败、
registry 注册。

