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
   ADT/ORM/ORU 为主，MLLP 长连接 + ACK 语义是另一条必备通道；
2. **Java 平台侧 AI 客户端**：HIS 界面内触发质控/审方（双向集成），避免医生开两个系统；
3. **患者主索引（EMPI）唯一真源**：AI 侧只存 `empi_id` 引用 + 哈希，不复制患者主数据；
4. **契约测试进 CI**：用 FHIR 官方示例资源 + MockTransport 已覆盖；再加 staging 双系统联调。
