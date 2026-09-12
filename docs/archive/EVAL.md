# Medical Agent 评测协议（历史文档 · 翻译自早期英文原稿）

> 本文是项目早期（阶段 2-3）的评测协议存档，反映当时的验收口径。部分"未达成项"在后续版本已实现（如 JSON→PG 真源、限流、恢复演练），最新状态见 [docs/archive/qa/PROJECT_EVALUATION.md](qa/PROJECT_EVALUATION.md)。

## 置信度 / 人在环（HITL）闸门

- `confidence < 0.70`（模型自评）→ `needs_human_review=True`
- 影像 / 开药 / 病例总结：**强制** `needs_human_review=True`
- PHI 正则（中文场景）：手机号 `1[3-9]\d{9}`、邮箱、18 位身份证号 → 占位符脱敏

## 文献助手评测用例

| 问题 | 期望关键词 | 来源 |
|---|---|---|
| 二甲双胍是一线治疗吗 | 二甲双胍 / 首选 | KB 种子指南 |
| 2 型糖尿病 HbA1c 控制目标 | <7% / 7.5-8%（老年） | KB |
| 华法林 布洛芬 相互作用 | TODO drug scaffold | drug |
| 头颅 CT 影像判读 | TODO imaging scaffold (BiomedCLIP) | imaging |
| 多模态病例摘要 | TODO case scaffold | case |

## 运行方式

```powershell
# 单元/集成回归（离线）
python -m pytest tests/ -v

# 可执行评测基线（需后端在 127.0.0.1:8001 运行）
python scripts/run_eval.py
# 输出 hit_rate 摘要 + data/eval/eval_result.json；退出码 0 当 hit_rate>=0.8
```

## 生产化标准（当时未达成）

- 每个 agent ≥20 个带金标准标签的真实临床病例
- 红队测试集（对抗性医疗问题）
- 留出集上的离线评测框架，AI 与临床医生判定 Cohen's κ 一致性
- CPU 环境单查询延迟 SLO：p95 < 8s

## 商用就绪 backlog（✅ 均已完成）

- [x] C1 可执行评测基线 + 强化单元测试（药物规则、HITL 闸门）——`scripts/run_eval.py` 9/9，19 pytest
- [x] 自包含：本地 bge-m3 权重 + 独立 docker-compose；`medical_kb` 改用 `embedder`/settings（去除 EduAgent 回退）
- [x] C2 JWT 鉴权（PyJWT HS256 + pbkdf2）+ 全部 `/ask` 端点基于角色的 Bearer 闸门——验证 401（无/坏 token）vs 200
- [x] C3 真实知识库：10 主题 30 篇 PubMed 摘要（`scripts/seed_kb_pubmed.py`），来源=`PMID:xxx`；跨语言检索
- [x] C4 影像/病例 agent：Qwen-VL（`qwen3-vl-plus`）图片→结构化所见，强制 HITL；药物库扩至 15 对

## 受监管生产剩余事项（诚实缺口 · 部分已在后续版本完成）

- 将 PubMed 种子演示语料替换为医院采购的权威指南库
- ~~刷新演示凭据 / 用户存储从 JSON 迁移数据库~~（v1.0 已完成 PG 真源迁移）；~~限流~~（已实现）；TLS + 审计保留年限策略（待办）
- 合规声明：本项目为辅助工具，非已注册医疗器械；临床验证与签批流程 required
- 完整药物数据库（drugbank/OpenFDA）以穷尽相互作用覆盖；BiomedCLIP 以图搜图检索
