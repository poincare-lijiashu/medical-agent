# 培训矩阵与认证规则

> 目标：让每个角色在**自己的业务场景**里能正确操作系统，并知道"出问题找谁、怎么留证据"。
> 培训不是功能宣讲；认证不是走形式——未通过认证的账号不得在 production 执行高风险操作。

## 1. 能力矩阵

| 角色 | 必须掌握 | 不得执行（需授权或他人操作） |
|---|---|---|
| 医生 DOCTOR | 接诊、诊断、医嘱、病历书写/签名、报告查看、患者检索 | 收费结算、退费、药品发放、权限配置 |
| 护士 NURSE | 分诊、生命体征、护理执行、医嘱核对、交接班、标本采集 | 医嘱开立、收费结算、药品发放 |
| 药师 PHARMACIST | 审方、发药、退药、麻精药品、库存批次/效期 | 医嘱开立、收费结算、患者合并 |
| 收费员 CASHIER | 挂号、收费、退费/作废、日结、票据 | 医嘱开立、病历修改、主数据维护 |
| 医技 TECH | 申请接收、标本流转、报告录入/审核、危急值闭环 | 收费结算、病历签名、药品发放 |
| 病案编码 CODER | 病案首页、ICD 编码、质控、归档、修改申请 | 医嘱开立、收费结算、患者合并 |
| 医保 INSURANCE | 目录对照、参保登记、结算清单、对账、作废冲正 | 医嘱开立、病历修改、药品发放 |
| 运维 OPS | 服务目录、SLA、工单、值班、事件指挥、复盘 | 发布审批、补丁审批、生产部署 |
| 发布经理 RELEASE | 版本注册、发布列车、兼容矩阵、补丁审批、冻结窗口 | 直接改生产数据、绕过检查单部署 |
| 数据治理员 DATA_STEWARD | 主数据域、映射、变更申请、导入、质量看板 | 审批自己提交的变更、患者合并 |
| 实施工程师 IMPLEMENTATION | 迁移模板、映射档案、预检、应用、对账、回滚演练 | 在未预检的情况下应用迁移、跳过回滚演练 |

## 2. 课程目录

| 课程 | 名称 | 对象 | 目标 | 前置 |
|---|---|---|---|---|
| C01 | 平台总览与登录安全 | 全员 | 理解系统边界、登录/MFA、会话与登出、常见错误码 | 无 |
| C02 | 门诊全流程 | 医生、护士、收费 | 挂号→接诊→诊断→医嘱→收费→报告闭环 | C01 |
| C03 | 住院全流程 | 医生、护士、收费 | 入院→床位→医嘱→护理→出院→结算 | C01 |
| C04 | 药房与合理用药 | 药师、医生 | 审方、发药/退药、麻精、库存效期、前置审方 | C01 |
| C05 | 医技与危急值 | 医技、护士、医生 | 申请、标本、报告、危急值确认与闭环 | C01 |
| C06 | 病历与电子签名 | 医生、病案 | 模板、质控、三级签名、归档、修改审批 | C01 |
| C07 | 医保结算与对账 | 医保、收费 | 目录对照、参保、结算清单、对账、冲正 | C02/C03 |
| C08 | 主数据治理 | 数据治理员、实施 | 域、条目、版本链、映射、审批、导入、质量看板 | C01 |
| C09 | 历史数据迁移 | 实施、数据治理员 | 模板、映射档案、预检、应用、对账、链式回滚 | C08 |
| C10 | 运营与值班 | 运维、值班人员 | 工单状态机、SLA 口径、挂起/恢复、事件与复盘 | C01 |
| C11 | 版本与补丁 | 发布经理、运维 | 版本发布门槛、列车冻结、兼容矩阵、补丁审批、部署/回滚 | C01 |
| C12 | 安全与合规 | 全员（重点：管理员） | 最小权限、PHI 保护、审计、等保要求、应急报告 | C01 |

## 3. 认证规则

- **形式**：闭卷机考，从 `certification-bank.json` 按角色抽题；单选，四选一；
- **及格线**：80 分（100 分制）；高风险角色（OPS、RELEASE、DATA_STEWARD、IMPLEMENTATION）必须 90 分；
- **题量与配比**：20 题；本角色模块 ≥70%，安全与合规 ≥20%，平台通用 ≥10%；
- **有效期**：12 个月；平台发生**破坏性变更**（`breaking_changes=true`）后，相关角色需在下次发布前完成再认证；
- **补考**：未通过者需完成一次补训后补考；连续两次未通过，暂停对应高风险权限并由主管复核；
- **记录**：姓名、角色、成绩、试卷版本、时间、监考人，归档到项目文档库；平台侧以角色授权为最终生效条件。

## 4. 培训组织

- 培训必须使用**培训环境**与**脱敏数据**，禁止在 production 演示；
- 每个模块至少包含：场景讲解 → 演示 → 学员实操 → 异常场景（权限不足/必填缺失/冲突提示）；
- 关键用户（Key User）按科室选配，承担上线后一线答疑与工单初筛；
- 培训材料随版本更新：每次发布列车发布后，检查课程与截图是否过期。

## 5. 与平台权限的对应

| 角色 | 平台角色码示例 | 关键权限点 |
|---|---|---|
| 医生 | DOCTOR | `clinical:visit:treat`、`clinical:order:create`、`empi:patient:read` |
| 护士 | NURSE | `inpatient:nursing:execute`、`mobile:nursing:execute` |
| 药师 | PHARMACIST | `pharmacy:prescription:review`、`pharmacy:dispense:execute` |
| 收费员 | CASHIER | `clinical:registration:create`、`clinical:charge:settle` |
| 医技 | TECH | `tech:application:create`、`tech:report:write`、`tech:critical:acknowledge` |
| 病案 | CODER | `emr:coding:write`、`emr:record:archive` |
| 医保 | INSURANCE | `insurance:settlement:write`、`insurance:export:download` |
| 运维 | OPS_AGENT / OPS_MANAGER | `ops:read`、`ops:ticket:write`、`ops:incident:write` |
| 发布经理 | RELEASE_MANAGER | `release:write`、`release:approve`、`release:deploy` |
| 数据治理员 | DATA_STEWARD / DATA_GOVERNOR | `mdm:write` / `mdm:approve` |
| 实施工程师 | MIGRATION_ENGINEER | `migration:write`、`migration:apply` |

> 角色码以 auth 域实际配置为准；本表用于培训时解释"为什么这个按钮是灰的"。

## 6. 认证题库维护

- 题库文件：[certification-bank.json](certification-bank.json)；
- 新增/修改题目必须同时更新 `explanation`，并保证答案在选项中唯一正确；
- `tests/test_implementation_package.py` 会校验题库结构、角色覆盖与文档完整性，
  题库不合规会导致 CI 失败——这是"培训材料不腐烂"的机制保障。
