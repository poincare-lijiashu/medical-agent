# 下一会话待办（2026-09-10 续）

> 状态：git `9c36140` · **370 passed** · 服务运行于 127.0.0.1:8001（LITERATURE_AGENTIC=on，PG/Milvus 在线，Docker healthy）

## 明日待办（按序，用户已批准方向）

1. **审核中心图片渲染**：imaging 留痕记录 images 只显示文字"images"——前端渲染 data:image 缩略图（点击放大，仅 data:image/ 前缀白名单，esc 纪律）
2. **病理学 PDF 真实可用验证**：上传已 done（1155 chunks），验证链路尾端——Milvus doc_count 应 ≈1390+1155；用教材相关问题（如"心肌肥厚的病理特征"）检索确认来源含该文档；若检索质量差考虑 chunk 策略（见下）
3. **切片策略升级（用户提出，先给设计再实施）**：现状=统一 chunk_text(600/100) 按句子边界；升级方向：①PDF 按章节标题层级切分（指南类）②chunk 大小按文档类型适配（指南 800-1200/文献摘要整段）③**扫描版/图片 OCR**（PaddleOCR 中文）或 VL 模型生成描述入库 ④表格转 markdown。实施前先给用户设计确认
4. 优化建议 backlog：检索质量评测集扩充（用病理学教材出题）、上传并发压测、多模态入库

## 注意事项（仍有效）
- VL=deepseek-v4-flash-vision-exp 实验模型偶发 400/空（已加重试+审计 vl_raw_len/stop_reason）；根治=管理端换稳定 vision 模型（如 Qwen-VL）
- 运维规则：用户/科室/配置变更必须走服务 API（PG 真源）
- 开关：QC_AUTO_SIGN_FULL 默认 on（runtime_flags 可 admin 面板切换）/ QC_AUTO_PASS off / LOG_TO_FILE 全环境 on（logs/app.log，admin 面板日志健康卡）
- 简历关键词：多智能体协作（动态组队+HITL 会诊闭环）、分级自主性、PG 真源+降级兜底、全交互留痕、评测闭环

## 注意事项
- 会诊专项已实现：backend/core/consults.py + 5 端点 + 动态组队（LLM 实时读 departments，无缓存，admin 改科室即时生效，测试锁定）+ 前端三处 UI；意见=建议权无驳回权（用户设计）
- 用户的运维规则：**用户/科室/配置变更必须走服务 API**（PG 真源），脚本改 JSON 不同步到运行中服务
- 开关矩阵：QC_AUTO_PASS(off)/QC_AUTO_SIGN(on)/QC_AUTO_SIGN_FULL(off，院方合规后开)/LOG_TO_FILE(production 自动)
- 审计人话化/fallback 不入队/offtopic 代码特征词/输入框隔离 均已落地（81f09fd..428e4dc）
- 简历叙事关键词：多智能体协作（动态组队+跨科室医生 HITL 闭环）、分级自主性、PG 真源+降级兜底、评测闭环

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
