"""HIS 适配器注册表（工厂）：按 settings.his_adapter 选择激活的适配器实现。

注册表模式（对扩展开放、对修改关闭）：
- 内置 "none"（别名 ""/"noop"）→ NullAdapter：默认，未对接，全空转；
- 厂商实现：新建 backend/integration/<vendor>.py 实现 HisAdapter，在本模块底部（或
  部署时入口处）register_adapter("<vendor>", VendorAdapter) 一行注册，.env.local 配
  HIS_ADAPTER=<vendor> 即激活——核心业务零改动；
- 未知标识：抛 ValueError（配置错误要响亮失败，禁止静默降级成空实现掩盖问题）；
  api 层调用点对异常全兜底（审计 his.push_failed），不影响主流程。

纪律：本模块只依赖 backend.config 与本包内 base，不得反向引用 api/core/agents。
"""
from __future__ import annotations

from backend.config import settings
from backend.integration.base import HisAdapter, NullAdapter

_REGISTRY: dict[str, type[HisAdapter]] = {}
_NONE_ALIASES = {"", "none", "noop"}  # "noop" 为历史别名兼容（与 none 等价）


def register_adapter(name: str, adapter_cls: type[HisAdapter]) -> None:
    """注册厂商适配器实现（name 与 settings.his_adapter 配置值对应，统一小写归一）。"""
    key = (name or "").strip().lower()
    if not key:
        raise ValueError("适配器注册名不能为空")
    _REGISTRY[key] = adapter_cls


def get_adapter() -> HisAdapter:
    """按 settings.his_adapter 返回适配器实例（每次新建；实现方如持共享状态须自行保证
    线程安全）。未对接（none/noop/空）→ NullAdapter；未知标识 → ValueError（列出可选项）。"""
    key = (settings.his_adapter or "none").strip().lower()
    if key in _NONE_ALIASES:
        return NullAdapter()
    if key not in _REGISTRY:
        raise ValueError(f"未知的 HIS 适配器配置：{settings.his_adapter!r}；"
                         f"已注册：{', '.join(sorted(_REGISTRY)) or '（无）'}")
    return _REGISTRY[key]()
