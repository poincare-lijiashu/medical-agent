# 下一会话待办（2026-09-10 晚 · 智能开药路线进行中）

> 状态：git 288ec0a · **533 passed** · 计划书：docs/plans/pharmacy_roadmap.md（7 阶段）

## 进行中：阶段 0+1 部分完成
- 阶段 0 全部完成（重提预填各栏直填已实现+测试锁；my_rejections 只留 qc 类）
- 阶段 1.1 完成（drug_dict/drug_rules PG repo+JSON 兜底）；1.3 完成（medical_drug 读字典，legacy 55 别名+39 规则全量迁移 data/drug_dict.json+drug_rules.json，等价校验通过）

## 下一轮（阶段 1 剩余）
1. **1.2 数据扩容**：drug_dict.json 扩到 ~300 药（12 类）+ drug_rules.json 扩到 ~300 规则（高危~80/中危~220）——AI 生成初稿+免责声明
2. 1.4 免责声明三处（开药工作台/字典管理页/DEPLOY）
3. 1.5 admin 字典管理页（pharmacist+admin 权限+变更审计）
4. 然后阶段 2 智能开药后端（按计划书）

## 注意
- 服务 127.0.0.1:8001 需重启载入（本轮已停旧进程，启动命令不变）
- 断点教训：子代理上下文超限多次中断——大任务拆小轮+及时 commit
