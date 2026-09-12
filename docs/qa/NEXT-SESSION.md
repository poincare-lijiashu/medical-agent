# 交接 DeepSeek 终评 · 项目状态快照（2026-09-12）

> 用户已完成人工全功能实测并宣布通过。本文件供下一模型（DeepSeek）终评前快速对齐。

## 终态指标

- **743 passed / 0 failed**（pytest-timeout=300 保护）· 82 commits · 工作区干净（HEAD=1859c1a）
- 前端：**Vue3 + Vite + Element Plus**（frontend-vue/，dist 随库提交）；legacy 已删，回退 tag `v-legacy-frontend`
- 后端：FastAPI + PG（真源）+ JSON 兜底（9 真源双写+原子写）+ Milvus（11753+ chunks）+ Redis 可选双后端（REDIS_URL）
- 服务：uvicorn 127.0.0.1:8001；docker：edu_agent_postgres/edu_agent_milvus（healthy）

## 功能全景（对照矩阵 docs/qa/vue_parity_matrix.md 42 点全 ✅）

文献查证(SSE流式) / 影像VL阅片 / MDT多智能体会诊(传图+分科理由) / 多模态病例总结 / **智能开药**(字典约束+幻觉拦截+字典外A方案+高危阻断+药师双控) / 病历质控 / 病例库归档 / 药品字典342药+307规则(药师审校工作流) / 知识库管理 / 审计全链 / 三级部署

## 终评建议切入点（常规功能已由用户人工验证过，建议攻工程纵深）

1. **对抗**：对抗测试已有基线（tests/test_prompt_injection.py 6 例+fault_drill 三场景）——可升级注入花样（多轮对话投毒/图片指令注入）
2. **数据一致性**：PG/JSON 双写对账（上轮发现过 rules 重复项与 queue 落后，已修——可再审 drift 场景：并发写/停机窗口长任务）
3. **Vue 迁移残留**：parity matrix 是核对基准；legacy 语义 diff 可抽查（git show v-legacy-frontend:...）
4. **性能**：负载基线在 DEPLOY（16/64 用户档）——可压更高端或长稳
5. **已知未做**（诚实清单）：药师人工审校（工具链就绪待人工）、法务合规、ESM 转换、Redis 实机演练、HIS 真适配器

## 关键文档索引

- 评测与整改史：docs/qa/PROJECT_EVALUATION.md（八/九节含 Vue 迁移全程）
- Vue 迁移+回退：docs/plans/vue3_migration.md（tag v-legacy-frontend 一键回退）
- 计划书主线路：docs/plans/pharmacy_roadmap.md（7 阶段全完成）
- 运维：docs/DEPLOY.md（部署矩阵/负载基线/恢复演练记录/故障注入记录）
