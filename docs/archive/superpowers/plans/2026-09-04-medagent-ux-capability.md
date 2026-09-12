# MedAssist 体验与能力增强实施计划（2026-09-04）

> 执行方式：P0→P1→P2，每阶段末 pytest 全绿 + smoke 15/15 + eval 9/9 + detect_clean + 浏览器实测。
> 决策基线：模型兜底=降置信+标注"未检索·仅供参考"+入复核；角色化界面；预问诊患者侧；病历质控去 JSON 用表单；管理员前端上传入库。

## P0 体验速修（缺陷）
- **O1 审核中心详情**：行点击→弹窗展示完整问题/答案/证据/提交人；修 `.body` 滚动（列表卡去掉 overflow 裁剪）。
- **O2 模型名动态**：新增 `GET /api/v1/medical/config`（返回 chat/vl 真实模型名）；前端影像/文献文案由它渲染，不再硬编码 Qwen-VL。
- **O3 会话留存**：按 agent 保存消息历史（内存+localStorage），切回不丢；加"新建对话"清空当前会话。
- **O4 Markdown 渲染**：bot 答案安全渲染（先转义 HTML，再解析 **粗体**/### 标题/列表/换行），去除裸露 `**`。
- **O5 预问诊不纠缠**：`不知道/不清楚/记不清` 视为有效→记"患者未述"并推进；同题最多温和换问一次。
- **O6 MDT 正名**：标题/文案改"多智能体协作会诊（循证/药学/临床三 Agent）"。
- **O7 病历质控表单**：前端用字段表单（主诉/现病史/既往史/查体/辅助/诊断/签名 + 可选检验值），提交时组装 record 对象；去掉裸 JSON。

## P1 能力增强
- **O8 置信修正（bug）**：无实质证据的"拒答/兜底"不得显示高置信；兜底封顶 ~0.4。
- **O9 模型常识兜底层**：文献图检索无果时→LLM 常识作答，正文前置标注"⚠ 未检索到权威证据，以下为模型常识，仅供参考，请复核"，`needs_human_review=True`、来源标 `fallback:model-prior`，入复核队列。
- **O10 知识库规模化**：GPU 下重跑在线 PubMed 更大 cap（如 1500）+ 保留离线指南；评测复跑不退。

## P2 产品结构（角色化）
- **O11 角色导航**：doctor=临床 Agent+审核+MDT；pharmacist=药物+药学审核；admin=知识库管理/上传+用户+审计+系统（不做临床作答）；预问诊=患者侧独立入口。
- **O12 管理员上传入库**：`POST /admin/kb/upload`（仅 admin）→ 后台异步切分+向量化(GPU)+入库；知识库管理视图（列表/计数/删除）。

## 取舍
- 安全底线不变：兜底答案永远低置信+标注+入复核，不冒充循证结论。
- 角色区分用现有 JWT role 字段驱动前端 + 端点级 role 依赖（admin 端点校验 role）。

---

## 进度快照（2026-09-04 收工，明日续做）

### ✅ 已完成并验证（全绿）
- **P0 体验速修全部完成**：O1 审核详情弹窗、O2 /config+前端模型名动态、O3 会话留存+新建对话、O4 Markdown 安全渲染、O5 预问诊"不知道"=有效推进、O6 MDT 正名多智能体、O7 病历质控表单化。
- **P1 能力**：O8 置信修正（"感冒"从误显 0.84 → 0.4）、O9 模型常识兜底层（fallback 节点：检索不足→LLM 常识+标注"未检索·仅供参考"+降信≤0.4+入复核）。
- **P2 产品结构**：O11 角色化导航（doctor 临床/pharmacist 药学/admin 知识库·审核·架构，admin 无临床作答）、O12 管理员前端上传入库（`/admin/kb/upload|list|delete` + `core/kb_ingest.py`，GPU 向量化，幂等，医生越权 403）。
- 本轮更早：视觉模型切 `qwen3.8-flash`；`edu_agent` 装 `torch 2.7.1+cu128` 启用 5070（sm_120，FlagEmbedding/ST 兼容验证通过）；在线 PubMed 灌 480（知识库现 497 条）。

### 验证证据（收工态）
- `pytest 57 passed` · `smoke_all 15/15` · `eval 9/9` · 前端 `impeccable detect_clean` · 服务 `127.0.0.1:8001` UP（device=cuda）。
- 浏览器实测：概览仪表盘、审核详情弹窗、质控表单、角色化侧栏（doctor↔admin）、Markdown 渲染、会话切回留存。
- 管理员上传→列表→删除端到端（inserted 2 / delete_count 2；Milvus 软删致 `total` 计数短暂偏高属正常，检索不受影响）。

### ⏭ 明日续做（按优先级）
1. **O10 知识库规模化**（可选、GPU 已就绪）：`MEDICAL_TOTAL_CAP=1500 python scripts/seed_kb_pubmed.py` 扩到千级；跑后 `check_docs`/eval 复验不退。
2. **机器基线刷新**（环境门禁）：`edu_agent` 的 torch 变 cu128 且该环境不在基线里 → `refresh_baseline.ps1` + `check_docs.ps1`（须 CONSISTENCY_OK）。
3. **正式 security-scan**（发布/推送前门禁）。
4. 可选：影像多图、PG 由镜像升为权威读源、院方 `data/critical_values.json`/`data/icd_table.json` 到位即启用危急值/ICD 轨。

### 关键提醒（明日快速恢复）
- 启动：`cd medical_agent; python -m uvicorn backend.main:app --host 127.0.0.1 --port 8001`（GPU 环境，勿用默认 py312）。
- 种子账号 doctor01/pharm01/admin01 / Med@2026。
- 装/换 torch 前**必须先停后端**（DLL 锁）；5070 需 cu128+，禁 cu124。
- 计划/进度/回归脚本都在 `medical_agent/`；回归三件套：`pytest tests/` + `scripts/smoke_all.py` + `scripts/run_eval.py`。
