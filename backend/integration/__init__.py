"""医院信息系统（HIS）对接边界层——外部系统对接的唯一入口（批2 任务2）。

设计（端口-适配器，依赖倒置）：
- 核心代码（api 层）只依赖 base.HisAdapter 抽象接口与 registry.get_adapter() 工厂，
  不感知任何具体医院实现；对接哪家医院、用何种鉴权/数据映射/重试策略，核心链路零改动。
- 具体适配器经 registry.register_adapter("<name>", XXXAdapter) 注册，
  由 settings.his_adapter（默认 "none"→NullAdapter；厂商适配器经注册后按配置名激活）
  选择激活。
- 纪律：core/agents 层不得 import 本包；调用点在 api 层业务完成点
  （现例：medical_router.qc_record 出结论后 _his_push_qc_result → push_qc_result），
  适配器异常由调用方统一捕获审计（his.pushed/his.push_skipped/his.push_failed），
  绝不影响核心医疗流程。架构说明与新增厂商步骤见 docs/archive/INTEGRATION.md。
"""
from backend.integration.base import HisAdapter, NullAdapter
from backend.integration.registry import get_adapter, register_adapter

__all__ = ["HisAdapter", "NullAdapter", "get_adapter", "register_adapter"]
