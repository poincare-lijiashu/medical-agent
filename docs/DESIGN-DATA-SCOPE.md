# 行级数据权限（DataScope）设计

> 状态：规则已定稿，基础设施已落地，按科室过滤在 clinical 试点
> 依据：三甲医院常见分工（科室隔离 + 特定角色跨科室只读）
> 对应评审项：`docs/reviews/20261103-V4-综合意见书.md` P0-01

---

## 1. 为什么必须先定规则

行级权限有两种失败方式，且都不报错：

1. **过滤过松**：看起来加了 `WHERE`，但绕得过去（比如只过滤列表不过滤详情），等于没做还多了一层"安全假象"。
2. **过滤过严**：医生看不到自己该看的病人，一线会集体申请提权，最后权限形同虚设。

所以规则必须先从业务来，代码只是把规则写死。

---

## 2. 可见范围规则

### 2.1 角色 → 范围

| 角色码 | 可见范围 | 理由 |
|---|---|---|
| `DOCTOR` | 本科室 | 门诊/住院医师只看自己科室的病人；跨科室需会诊授权 |
| `NURSE` | 本科室 | 护理执行按病区/科室划分 |
| `PHARMACIST` | 全院（只读患者标识 + 处方用药） | 审方必须看到跨科室处方，否则无法拦截配伍禁忌 |
| `ANESTHETIST` | 全院手术相关 | 术前访视天然跨科室（外科开刀、麻醉科评估） |
| `OR_NURSE` | 全院手术相关 | 手术排台需要全手术间视野 |
| `QC` | 全院只读 | 病案首页质控按全院抽样 |
| `REGISTRAR` | 全院只读（挂号/床位） | 住院处办理业务需要查任意患者 |
| `ADMIN` | 全院 | 系统管理员 |

> **管理员也要留痕**：`ADMIN` 全院可见，但每次跨科室读取都写审计，不做静默。

### 2.2 跨科室（会诊 / 转科）

三甲医院的真实做法是「主诊科室有读写权，参与科室有只读权」。系统用
`clinical_svc.consultation_request` 把这个业务事实落成数据，而不是在权限系统里开一个"跨科室总开关"。

规则：

- **写**：仍然只认 `visit.dept_id`（主诊科室）。受邀科室不能替主诊科室下医嘱、收费、改病历。
- **读**：`visit.dept_id ∈ scope.deptCodes` **或** 该就诊存在 `status IN (REQUESTED, ACCEPTED)`
  且 `to_dept_id ∈ scope.deptCodes` 的会诊（`VisitAccessGuard.requireRead`）。
- **授权随会诊生命周期收回**：REJECTED / COMPLETED / CANCELLED 立刻不再授予只读，不做"一次会诊永久可见"。
- **状态机**：发起（主诊科室）→ 受理/拒绝（受邀科室）→ 完成（受邀科室，必填意见）；主诊科室可撤回进行中的会诊。
- **并发**：状态迁移都 `SELECT ... FOR UPDATE` + `version` CAS；同一就诊同一受邀科室只允许一条进行中的会诊（部分唯一索引）。

已接入 `requireRead` 的读接口：就诊详情、医嘱列表、就诊诊断列表、手术病例列表、收费预览与收费单明细。
结算、按日计费、护理执行等**写**接口仍走 `require`（只认主诊科室）。

转科（主诊科室变更）是独立动作，不走"出院再入院"：

- **前提**：就诊为住院且 `IN_TREATMENT`，并且确有在院登记（`ADMITTED` / `IN_HOSPITAL`）。
- **目标**：必须是启用中的住院科室（`INPATIENT` / `BOTH`），且不能是当前主诊科室。
- **换床**：已分床的患者必须同时指定目标科室病区的空床；系统在同一事务内完成
  "占新床 → 改登记病区/床位 → 释放原床 → 改 `visit.dept_id`"，任一 CAS 失败整体回滚。
- **会诊联动**：原主诊科室发起的进行中会诊随转科自动撤回（`cancelled_reason` 记录转科去向），
  避免受邀科室在授权前提消失后继续持有只读权限。
- **留痕**：每次转科向 `visit_transfer` 追加一行（append-only，UPDATE/DELETE 被触发器拒绝），
  审计写 `VISIT_TRANSFER`（含原/新科室、床位、撤回会诊条数）。
- **权限**：发起需要 `clinical:visit:treat` 且必须是当前主诊科室；查询转科记录需要
  `clinical:visit:read`（新主诊科室或会诊期只读科室可见）；越权一律 404。

### 2.3 失败方向：fail-closed

| 情况 | 处理 |
|---|---|
| 网关未下发 `X-Org`，且角色不属于全院范围 | **拒绝（403）**，不放开 |
| `X-Org` 不是合法 UUID | **拒绝（403）**，不猜 |
| 角色码无法识别 | **拒绝（403）**，不默认放行 |

"不确定就放开"是权限系统最常见的漏洞来源，这里一律反向。

### 2.4 患者主索引（MPI）为什么不按科室过滤

`empi` 的患者档案是**全院共享的身份目录**，不属于任何科室：

- 挂号处、住院处、药房、检验都要能找到"在别的科室就诊过"的同一个人；
- 按科室过滤会把同一个人拆成互不可见的两半，直接制造重复建档与用药风险；
- 患者姓名/证件号/手机号属于身份信息，不是"某科室的诊疗记录"，跨科室查看本身不构成越权。

因此规则是：

| 控制点 | 规则 |
|---|---|
| 检索条件 | 必须给出患者号或姓名，**不允许无条件翻全院名册**（422） |
| 返回字段 | 默认掩码（证件号保留前后各 4 位、手机前 3 后 4、地址保留前 6 字） |
| 明文 | 需 `empi:patient:read_phi` + 真实 `X-Actor`，并写 `PATIENT_VIEW_PHI` |
| 审计 | 每次检索写 `PATIENT_SEARCH`（是否按号/按名、页码、命中总数），不记录关键词原文 |

真正需要按科室隔离的是**诊疗数据**（就诊、医嘱、收费、手术、病历），这部分见 §5。

---

## 3. PHI（患者标识）掩码

### 3.1 默认掩码

| 字段 | 掩码规则 | 示例 |
|---|---|---|
| `patient_no` | 保留前 3 后 2，中间 `*` | `P00000123` → `P00****23` |
| `patient_name` | 姓 + `*` | `张建国` → `张**` |

### 3.2 谁能看全

需要权限点 `phi:read`。默认授予：`DOCTOR` / `NURSE` / `PHARMACIST` / `ANESTHETIST` / `OR_NURSE`（诊疗必需）。

`QC` / `REGISTRAR` 只拿掩码；确需查看全量时走单独的"解密"接口，单独审计。

### 3.3 掩码在哪一层做

**在服务端做，不在前端做**。前端拿到全量再遮，等于数据已经出库了。

### 3.4 实现位置

- 权限点 `phi:read` 由 auth `V35__phi_read_permission.sql` 下发：ADMIN / DOCTOR / NURSE / PHARMACIST；QC / REGISTRAR 只拿掩码。
- 网关把 JWT 里的 permissions 原样放进 `X-Permissions`，`DataScopeResolver` 解析成 `phiReadable`。
- clinical 在 Controller 出口用 `PhiMask` 统一改写：就诊、住院登记、欠费预警、费用看板、手术排台看板。
- pharmacy 同样在出口改写：处方列表/详情/审核结果、麻醉药品专册（含空安瓿回收返回）。
- 领域对象是不可变 `record`，掩码生成新实例——"库里的值"和"返回给客户端的值"是两条明确边界。

---

## 4. 实现结构

```
platform/common/
  security/DataScope.java          // 值对象：actor / deptCodes / allDepartments / phiReadable
  security/DataScopeResolver.java  // 从请求头解析；非法即 403
```

### 4.1 DataScope

```java
public record DataScope(String actor, Set<UUID> deptCodes, boolean allDepartments,
                        boolean phiReadable) {
    public boolean canSeeDepartment(UUID deptId) { ... }
    public String maskPatientNo(String patientNo) { ... }
}
```

- `allDepartments = true` 时忽略 `deptCodes`
- `deptCodes` 为空且 `allDepartments = false` → 构造时直接抛异常（fail-closed）

### 4.2 为什么不用 ThreadLocal

ThreadLocal 在 WebFlux / 线程池场景下容易泄漏，测试里还要手工清理。
改为**Controller 显式取参数**（`@RequestHeader` 或参数解析器），Service 里按需下传。
多写一个参数，换来"能一眼看出这个查询受不受权限约束"。

---

## 5. 落地顺序

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | `DataScope` + `DataScopeResolver` + `DataScopeArgumentResolver` + 单测（不改业务行为） | **已完成** |
| 2 | clinical 的 `visit → surgery-case` 列表按科室过滤 | **已完成** |
| 3 | 医嘱 / 收费按 visit 归属过滤（`VisitAccessGuard` 统一拦截） | **已完成** |
| 4 | 术前访视（按 caseId，经就诊科室校验） | **已完成** |
| 5 | 住院 ADT / 床位 / 护理执行 / 病区备药 / 挂号与诊断 / 麻醉与术中护理 / 术前讨论与知情同意 / 术中收费 / 分级审批 / 病案首页与病历（emr 服务） | **已完成** |
| 6 | empi 患者检索：**不按科室过滤**（见 §2.4），改为强制检索条件 + 默认掩码 + 检索审计 | **已完成** |
| 7 | PHI 掩码铺到所有返回患者标识的 DTO | **已完成**：`phi:read`（auth V35）；已掩码 clinical（就诊/住院/欠费/费用/排台）与 pharmacy（处方、麻醉药品专册）。emr / tech / insurance 按 §7 的暴露面分类，不做用户级掩码 |
| 8 | 会诊申请与会诊期只读授权（`consultation_request`，含受理/拒绝/完成/撤回状态机） | **已完成**：见 §2.2；授权随会诊结束立即收回 |
| 9 | 转科（变更主诊科室，含换床与进行中会诊自动撤回） | **已完成**：`visit_transfer`（append-only）+ `POST/GET /clinical/visits/{id}/transfers`；住院科室主数据由 clinical V37 补齐 |

### 1.1 `VisitAccessGuard` 的职责边界

| 已接入 | 接口 | 校验方式 |
|---|---|---|
| 医嘱 | `POST/GET /visits/{id}/orders`、`cancel`、`stop`、`transfer-retry` | `require(scope, visitId)` |
| 收费 | `preview`、`charge`、`settle`、`daily-bill`、`daily-bill/reverse` | `require(scope, visitId)` |
| 手术 | `GET /visits/{id}/surgery-cases` | `require(scope, visitId)` |
| 术前访视 | `GET/PUT /surgery-cases/{id}/preop-visit`、`/sign` | `requireCase(scope, caseId)`（JOIN 取就诊科室） |
| 术前管理 | 术前讨论、知情同意、术前核查清单（7 个接口） | `requireCase(scope, caseId)` |
| 术中记录 | 麻醉记录单 / 生命体征 / 术中护理（8 个接口） | `requireCase(scope, caseId)` |
| 术中收费 | `charge-preview`、`charges` | `requireCase(scope, caseId)` |
| 手术分级 | `GET`、`PUT`、`approve`、`reject` | `requireCase(scope, caseId)`（审批仅 ADMIN/QC，均全院范围） |
| 手术记录写路径 | `plan`、`start`、`complete`、`cancel` | `require(scope, visitId)`（服务层已校验 case∈visit） |
| 住院 ADT | 在院列表、分床、出院、预交金、费用看板、护理执行（11 个接口） | `requireAdmission(scope, admissionId)`；病区/床位用 `requireWard` |
| 病区备药 | 备药看板、收药、退药、二级库存（4 个接口） | `require(scope, visitId)` |
| 挂号与诊断 | 就诊详情/接诊/结束/退号、就诊诊断增删改（7 个接口） | `require(scope, visitId)`；按患者列就诊时按科室过滤 |
| 转科 | `POST /visits/{id}/transfers`、`GET /visits/{id}/transfers` | 写：`lockVisit` + `canSeeDepartment`（只认当前主诊科室）；读：`requireRead` |
| 病案首页（emr） | 生成、列表、详情、打印、补录、重算、提交、归档（8 个接口） | `EmrAccessGuard.requireHomepage`（记录自身 `dept_id`）；生成时用临床快照的科室判定 |
| 病历正文（emr） | 建档、列表、详情、检索、打印、保存、提交、质控、签名、归档、修订、修改申请审批（16 个接口） | `EmrAccessGuard.requireRecord`；列表与检索结果按科室过滤 |

阶段 1–9 已全部落地；empi 与 PHI 掩码的处理方式见 §2.4 与 §3.4。

**每个接入点都必须有越权用例**：用 B 科室身份请求 A 科室的就诊，必须 404 且不查业务表。

**每个阶段都必须有"越权用例"测试**：用 B 科室身份请求 A 科室数据，必须 403/404。
没有这条测试的改动，不算完成。

---

## 6. 验收标准

1. B 科室医师请求 A 科室的 `surgeryCaseId` → 403/404，且审计有拒绝记录。
2. 药师可以读到跨科室处方（不能被科室过滤挡住）。
3. `QC` 看到的患者号是掩码，`DOCTOR` 看到明文。
4. 网关未下发 `X-Org` 且角色为 `DOCTOR` → 403，不是"看到全部"。
5. 单测覆盖：全院角色 / 单科室角色 / 无科室头 / 非法科室头 / PHI 掩码五种分支。

---

## 7. 已知边界

- **转科只覆盖住院**：门诊/急诊就诊不能转科；跨病区换床必须走转科（ADT 的 `assignBed` 仍限定在原病区内，避免绕过科室校验）。
- **转科不改医嘱/收费归属**：医嘱、诊断、费用继续挂在原 `visit` 上，只改主诊科室；由新科室承接后续诊疗。
- **`X-Org` 目前只有一个值**，多院区/多科室兼职的医生需要"科室多选"token，属于 auth 服务的后续改动。
- **PHI 掩码只覆盖 `patient_no` / `patient_name`**；身份证、电话、住址尚未纳入。

### 7.1 为什么不是"所有服务一律掩码"

掩码的目的是防止"越权的人看到不该看的患者身份"，而不是让业务没法干活。按暴露面分三类：

| 暴露面 | 处理 | 理由 |
|---|---|---|
| 面向医护/药师的列表与看板（clinical、pharmacy） | 按 `phi:read` 掩码 | 读者是自然人，掩码不影响业务判断（姓名首字 + 患者号前后位足以区分） |
| 病历与病案首页（emr） | 不按用户掩码，靠 `emr:record:read` / `emr:homepage:read` + 科室校验 + 每次查看留痕 | 病历正文与首页本身就是 PHI 文档，掩码后无法阅读、无法打印归档；这里的边界是"有没有权进这份病历"，而不是"字段遮几位" |
| 医技工作站与医保结算（tech、insurance） | 不按用户掩码，靠受控终端/内网 + 设备令牌 + 业务权限 | 设备 worklist 用患者号做匹配、医保结算单要与医保系统逐笔对照；掩码会直接打断设备对接与对账 |

第三条的前提是**终端受控**：设备接入走内网与设备令牌，不走公网浏览器；如果将来开放医生在个人电脑上看医技报告，应在 tech 侧增加按 `phi:read` 的列表掩码，而不是继续复用设备接口。
