# 下一会话待办（2026-09-08 续）

> 上次会话结束时状态：`7929739` wip(qc) · **189 passed** · 服务运行于 127.0.0.1:8001 · Docker/Milvus 离线（用户后来启动过，以实际为准）

## 已批准待执行的方案（用户已确认，直接做）

### 批次一（小修）
1. 来源标签中文映射（前端 srcLine）：`intent:prefilter`→"系统快捷应答（未使用检索）"、`drug_rules:curated_v2`→"内置药物规则库"、PMID/指南保持
2. doc_count 失败显示（后端已改 sentinel，**验证前端**是否对 -1 显示 "…" 而非 0）

### 批次二（质控强化——后端已大体完成见 7929739，**补前端**）
3. 医师签名字段：默认填登录账号名 + 锁定不可改（qcf_6）
4. 科室：admin 创建账号表单加**科室下拉**（数据源 backend/core/departments.py，admin 可增删科室）；质控界面自动带出当前账号科室并锁定
5. QC 三档分流的**前端呈现**：auto_reject / auto_pass / 人工 各自徽标（后端 triage 已实现，QC_AUTO_PASS 默认关）
6. 双控自动签发的**前端呈现**：reviewed_by="AI·阈值自动(规则库v2)" 显示为「自动签发」徽标 + 人工翻案按钮（QC_AUTO_SIGN 默认开）

### 批次三（模型 UI）
7. 模型配置卡片：对话/视觉各加 **「使用内置配置」回退按钮**（activate(kind, None) → 回落 .env 千问，用户配置保留可切回）

### 收口
8. pytest 全绿 + e2e_final.js 11 项 + 重启验证 + git 提交 + 质量报告更新

## 关键设计决策（用户已拍板，勿再讨论）
- QC_AUTO_PASS **默认关**（内涵质量判断涉法规终审权，院方上线时自行开启）
- QC_AUTO_SIGN **默认开，范围仅限规则库溯源的中低风险**；LLM 自由文本永不自动；自动签发 reviewed_by 标「AI·阈值自动(规则库v2)」，人工可翻案
- 确定性硬伤（缺签名等）自动驳回，无需开关（事实核对）
- 来源标签**不隐藏**，只做中文映射（溯源是合规卖点）
- 科室：固定清单 + admin 可增删（用户选定）

## 注意事项
- **不重排既有测试断言**（189 passed 为基线；F3 图片压缩失败语义=丢弃而非原样保留，测试已锁定）
- medical_review._load 有 PermissionError 重试（Windows 竞态防护），勿删
- E2E 脚本：D:\cache\temp\pwtest\e2e_final.js（11 项；literature 因外部 LLM 偶发超时记 false 属已知，脚本自带 reload 兜底）
- admin01 密码已重置回 Med@2026（用户曾改动）
- 历史 bug 修复语义见 git log 8 条提交信息，每条含原因

## Backlog（非本轮）
- HIS/EMR 对接（需医院接口规范）；Redis 多实例；GitHub 托管（医疗数据上云待决策）；KB 表虚拟化；URL 深链扩展到 review 分页
