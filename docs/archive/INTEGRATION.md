# MedAssist HIS 对接架构（integration 层）

> 批2 任务2 落地。对接哪家医院、用何种协议，核心业务零改动——所有院内系统对接收口在 `backend/integration/`。

## 一、端口-适配器边界（依赖倒置）

```
┌──────────────────────────────────────────────────────────────┐
│  核心业务（零感知，不改一行）                                    │
│  backend/api/v1/medical/medical_router.py                     │
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
│   <vendor>.py    厂商实现（注册即插即用；内置仅 NullAdapter）      │
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
HIS_ADAPTER=<vendor>    # 对接真实医院时填厂商标识（registry 注册名）
```

未对接（`none`）时核心流程**零行为变化、零审计噪音**——不需要任何「是否已对接」的判断分支。

## 四、接入点与失败语义（唯一真接入示例：qc 质控结论外推）

位置：`medical_router._his_push_qc_result()`（`qc_record` 三个出口各调用一次：自动驳回 / 自动归档 / 入队留痕后）。

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

1. 新建 `backend/integration/<vendor>.py`，实现 `HisAdapter` 三个方法（接口定义见 `backend/integration/base.py`，NullAdapter 即空实现范例）：
   - 凭据经环境变量/secret 注入，**禁止硬编码**；
   - 字段映射、重试、限速全部封装在适配器内部；
   - 推送以 `record_id` 幂等（重复推送不产生重复缺陷单）。
2. 在 `backend/integration/registry.py` 底部（或部署入口处）注册一行：
   `register_adapter("<vendor>", VendorAdapter)`；
3. `.env.local` 配 `HIS_ADAPTER=<vendor>`，重启服务生效；
4. 测试：在 `tests/test_his_integration.py` 照厂商适配器用例补注册表/往返/兜底断言。

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
