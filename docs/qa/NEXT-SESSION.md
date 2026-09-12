# 项目收尾快照（2026-09-12 · 全部交付完毕）

> 用户已完成实测验收 + 开源上线，转入其它项目。本文档是" everything 的位置"索引。

## 两份仓库

| 仓 | 路径 | 状态 | 用途 |
|---|---|---|---|
| **开发仓**（本地档案） | `<repo>` | 518756f · 工作区干净 · **无 remote**（纯本地） | 全部 86 提交历史 + 全套过程文档；回溯用 |
| **发布仓**（= GitHub） | `<release-repo>` | 6cb814d · 与 origin/main 同步 | 开源发布副本（单提交起始 + MIT + v1.0.0） |

**公开地址**：https://github.com/<github-owner>/medical-agent （public，CI 绿徽章）

## 关键 tag / 基线

- 开发仓 tag `v-legacy-frontend`：vanilla 前端最后状态（Vue3 迁移前，一键回退锚点）
- 发布仓 tag `v1.0.0`：首个公开版本
- 测试基线：开发仓 759 passed / 发布仓 755 passed（CI 747+4 skip，差异=playwright e2e 不进 CI）

## 未来继续开发时

1. **日常开发在开发仓做**（保留提交史习惯），重要里程碑用 archive 导出法同步发布仓（参考 docs/plans/vue3_migration.md 的 archive 手法）
2. 或直接在发布仓开发（丢失细粒度历史）——二选一，建议前者
3. backlog 清单（未做项）：见开发仓 docs/qa/PROJECT_EVALUATION.md 第十节（多实例写竞态/httpOnly cookie/路由拆分/重复代码去重等）
4. 待人工两件事：药师审校 318 规则（工具链就绪）、法务合规专项
5. 运行手册：发布仓 docs/DEPLOY.md（部署矩阵/备份恢复/故障注入记录）；docs/README.md 索引

## 本次会话累计

86+ 提交 · 6 轮评测/整改闭环 · Vue3 迁移 · 开源上线 · 保密审计通过（无密钥/无个人数据泄漏）
