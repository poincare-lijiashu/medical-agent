"""G86 Agent 控制面开关：把「联网搜索 / 多智能体协同 / A2A 能力广告」收敛为
可审计的运行时开关，供管理端即时切换。

为什么单独一层而不是继续堆在 ``runtime_flags``：
- ``runtime_flags`` 是通用键值桶（PG 真源 + JSON 兜底 + 白名单写入）；
- 本模块提供 Agent 语义（默认值、风险说明、前端展示元数据），避免前端硬编码中英文案；
- 所有读取仍走 ``runtime_flags.get_flag``，因此沿用同一份持久化与测试隔离机制。

安全默认：
- ``agent_web_search`` 默认 **关**：院内默认无外网，开启意味着数据出网；
- ``agent_multi_agent`` 默认 **关**：单智能体路由结果可预测，多智能体只做编排预览；
- ``agent_a2a`` 默认 **关**（2026-11-06 评审 M-12 整改）：能力卡与 ``/a2a``
  路由入口都会暴露内部能力域拓扑；默认不开，需要互操作时由管理员显式开启。
"""
from __future__ import annotations

from collections import OrderedDict

from backend.core import runtime_flags

# 评审 03-S9：CAS 冲突类型透出，路由层据此映射 409
FlagConflict = runtime_flags.FlagConflict

# key -> (默认值, 中文名, 说明, 风险等级)
_FLAG_DEFS: "OrderedDict[str, tuple[bool, str, str, str]]" = OrderedDict([
    (
        "agent_web_search",
        (
            False,
            "联网搜索",
            "允许智能体调用外部检索服务补充最新证据；开启即代表院内数据可能出网。",
            "high",
        ),
    ),
    (
        "agent_multi_agent",
        (
            False,
            "多智能体协同",
            "由主路由智能体把问题分派给多个专科子智能体并汇总；关闭时单智能体直达。",
            "medium",
        ),
    ),
    (
        "agent_a2a",
        (
            False,
            "A2A 能力广告",
            "对外提供 Agent Card（名称/技能/协议版本），供其它智能体发现与互操作。",
            "low",
        ),
    ),
])

# 允许外部遍历的键（顺序与前端展示一致）
FLAG_KEYS = tuple(_FLAG_DEFS)


def get_flag(key: str) -> bool:
    """读单个 Agent 开关；非本模块键返回 False（fail-closed）。"""
    if key not in _FLAG_DEFS:
        return False
    default = _FLAG_DEFS[key][0]
    return runtime_flags.get_flag(key, default)


def set_flag(key: str, value: bool, expected: bool | None = None) -> bool:
    """写开关（白名单在 runtime_flags 中同样校验）；非法键抛 ValueError。

    评审 03-S9：传 ``expected`` 时做 CAS（并发切换不再静默覆盖）。
    """
    if key not in _FLAG_DEFS:
        raise ValueError(f"不支持的 Agent 开关：{key}")
    default = _FLAG_DEFS[key][0]
    return runtime_flags.set_flag(key, bool(value), expected=expected, default=default)


def all_flags() -> list[dict]:
    """全部开关的当前生效值 + 展示元数据（前端开关卡直接消费）。"""
    return [
        {
            "key": key,
            "enabled": get_flag(key),
            "default": default,
            "title": title,
            "description": description,
            "risk": risk,
        }
        for key, (default, title, description, risk) in _FLAG_DEFS.items()
    ]


def enabled_map() -> dict[str, bool]:
    """{key: bool} 快照，供目录/工具/路由一次性过滤。"""
    return {key: get_flag(key) for key in FLAG_KEYS}
