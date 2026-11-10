## 变更说明

<!-- 这次改了什么、为什么改（贴相关评测编号 / issue 号） -->

## 影响面

- [ ] 涉及数据库结构（新增迁移 / ALTER）
- [ ] 涉及鉴权、审计、PHI 数据路径
- [ ] 涉及部署清单（Helm / compose / 环境变量）
- [ ] 仅前端展示 / 文案
- [ ] 无运行时影响

## 验证证据

<!-- 贴真实命令输出，不要只写“已测试” -->

```
python -m ruff check backend tests scripts
python -m pytest -q <定向用例>
python -m pytest -q --ignore=tests/e2e
```

## 回滚方式

<!-- 如何回退：revert 提交是否足够？是否有数据/配置需要同步回退？ -->

## 检查清单

- [ ] 未提交任何密钥 / 真实患者数据（PHI）
- [ ] 新增审计动作已在 `frontend-console/src/utils/audit.ts` 有中文人话映射（如适用）
- [ ] 触及 `docs/AGENT-RUNTIME-20261105.md` 台账（本仓库按批记录改动与验证）
- [ ] CI 全绿（pytest / lint / frontend / helm）
