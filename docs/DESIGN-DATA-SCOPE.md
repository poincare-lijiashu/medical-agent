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

三甲医院的真实做法是「主诊科室有读写权，参与科室有只读权」。当前数据模型只有 `visit.dept_id`（主诊科室），没有会诊表。

因此本期规则：

- 可见性判定 = `visit.dept_id ∈ scope.deptCodes`
- **会诊/转科的只读授权暂不实现**，等会诊模块落地后由 `consultation_request` 表提供"参与科室"集合

这条要写进已知边界，不能让人误以为"已经支持会诊"。

### 2.3 失败方向：fail-closed

| 情况 | 处理 |
|---|---|
| 网关未下发 `X-Org`，且角色不属于全院范围 | **拒绝（403）**，不放开 |
| `X-Org` 不是合法 UUID | **拒绝（403）**，不猜 |
| 角色码无法识别 | **拒绝（403）**，不默认放行 |

"不确定就放开"是权限系统最常见的漏洞来源，这里一律反向。

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
| 5 | 病案首页（emr 服务）/ 住院床位 / 护理执行 | 待做 |
| 6 | empi 患者检索按科室过滤 | 待做 |
| 7 | PHI 掩码铺到所有返回患者标识的 DTO | 待做 |

### 1.1 `VisitAccessGuard` 的职责边界

| 已接入 | 接口 | 校验方式 |
|---|---|---|
| 医嘱 | `POST/GET /visits/{id}/orders`、`cancel`、`stop`、`transfer-retry` | `require(scope, visitId)` |
| 收费 | `preview`、`charge`、`settle`、`daily-bill`、`daily-bill/reverse` | `require(scope, visitId)` |
| 手术 | `GET /visits/{id}/surgery-cases` | `require(scope, visitId)` |
| 术前访视 | `GET/PUT /surgery-cases/{id}/preop-visit`、`/sign` | `requireCase(scope, caseId)`（JOIN 取就诊科室） |

未接入（阶段 5+）：病案首页（emr 服务，需跨服务落地）、住院床位、护理执行、empi 检索、PHI 掩码。

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

- **会诊/转科的参与科室只读授权未实现**（缺 `consultation_request` 表）。
- **`X-Org` 目前只有一个值**，多院区/多科室兼职的医生需要"科室多选"token，属于 auth 服务的后续改动。
- **PHI 掩码只覆盖 `patient_no` / `patient_name`**；身份证、电话、住址尚未纳入。
