"""运行时模型 ↔ 治理版本库绑定校验（评测 D-13）。

问题：治理库登记的 ACTIVE 模型是 ``deepseek-chat``，运行时实际启用的是
``deepseek-v4-flash``，两者从未比对——"任一输出可追溯到模型版本"只停留在台账。
更严重的是生产环境可以直接换模型而没有任何拦截。

做法：
- ``runtime_snapshot()``：只读运行时（``llm_config.active_provider``），不返回密钥；
- ``check(component)``：与同作用域的 ACTIVE MODEL 版本逐字段比对（model / provider）；
- ``assert_consistent()``：生产类环境不一致即抛错（fail-closed），开发只告警；
- ``capture(actor)``：把运行时快照登记为 **DRAFT** 版本（仍需四眼审批才生效），
  让"登记"这件事由运行时上报驱动，而不是人工抄写。
"""
from __future__ import annotations

from datetime import datetime, timezone

from backend.agents.governance import common, versions
from backend.core import llm_config

CAPABILITIES = ("chat", "vision")
DEFAULT_COMPONENT = "global"


class RuntimeBindingMismatch(RuntimeError):
    """运行时模型与治理版本库不一致。"""


def runtime_snapshot() -> dict:
    """只读运行时模型绑定（不含 api_key/base_url 明细）。"""
    snapshot: dict = {}
    for kind in CAPABILITIES:
        provider = llm_config.active_provider(kind)
        if not provider:
            continue
        snapshot[kind] = {
            "provider": provider.get("id"),
            "model": provider.get("model_id"),
            "format": provider.get("api_format"),
        }
    return snapshot


def _active_model_versions(component: str | None) -> list[dict]:
    """取生效的 MODEL 版本。

    <p>不传 component 时取**全部能力域**：实际登记按能力域（如 ``drug``）分开，
    只看 global 会得出"没有登记"的假结论（2026-11-13 实测 ACTIVE 版本挂在 drug 域）。
    传 component 时优先取该域，缺失再回落到 global（与 versions.trace 作用域规则一致）。
    """
    if component:
        comp = common.require_component(component)
        items = versions.list_versions(component=comp, status="ACTIVE", kind="MODEL")
        if items or comp == DEFAULT_COMPONENT:
            return items
        return versions.list_versions(component=DEFAULT_COMPONENT, status="ACTIVE",
                                      kind="MODEL")
    return versions.list_versions(status="ACTIVE", kind="MODEL")


def _view(item: dict) -> dict:
    return {
        "component": item.get("component"),
        "versionId": item.get("versionId"),
        "version": item.get("version"),
        "provider": item.get("provider"),
        "model": item.get("model"),
    }


def check(component: str | None = None) -> dict:
    """比对运行时与治理库；``consistent`` 为 None 表示"没有登记，无从比对"。"""
    snapshot = runtime_snapshot()
    items = _active_model_versions(component)
    runtime_chat = snapshot.get("chat") or {}

    mismatches: list[dict] = []
    if runtime_chat:
        for item in items:
            # 只比 model：登记侧 provider 是厂商名（deepseek），运行时是内部 provider id
            # （p-eca795bb），两者不同源，强行比对会制造"永远不一致"的假告警。
            if (item.get("model") or "") != (runtime_chat.get("model") or ""):
                mismatches.append({"component": item.get("component"), "field": "model",
                                   "registered": item.get("model"),
                                   "runtime": runtime_chat.get("model")})

    if not items or not runtime_chat:
        consistent: bool | None = None
    else:
        consistent = not mismatches

    return {
        "component": component or "*",
        "checked": bool(items and runtime_chat),
        "consistent": consistent,
        "runtime": runtime_chat or None,
        "registered": [_view(item) for item in items],
        "registryCount": len(items),
        "mismatches": mismatches,
        "capturedAt": common.now_iso(),
    }


def assert_consistent(component: str | None = None) -> dict:
    """生产类环境不一致直接抛错；开发环境返回结果由调用方记录告警。"""
    from backend.config import is_production_env

    result = check(component)
    if is_production_env() and result["consistent"] is False:
        detail = "、".join(f"{m.get('component')}/{m['field']}: 登记={m['registered']} "
                          f"运行时={m['runtime']}" for m in result["mismatches"])
        raise RuntimeBindingMismatch(
            "运行时模型与治理版本库不一致（" + detail + "）："
            "请用 POST /api/v1/agent/governance/versions/from-runtime 上报并按四眼流程激活")
    return result


def capture(actor: str, component: str = DEFAULT_COMPONENT) -> dict:
    """把运行时快照登记成 DRAFT 版本（同版本号已存在时直接返回既有记录）。"""
    comp = common.require_component(component)
    runtime_chat = runtime_snapshot().get("chat")
    if not runtime_chat or not runtime_chat.get("model"):
        raise common.GovernanceInvalid("运行时尚无可用的 chat 模型，无法上报版本")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    version = f"runtime-{stamp}"
    payload = {
        "component": comp,
        "kind": "MODEL",
        "version": version,
        "provider": runtime_chat.get("provider"),
        "model": runtime_chat.get("model"),
        "changeSummary": f"运行时上报：provider={runtime_chat.get('provider')} "
                         f"model={runtime_chat.get('model')}（需四眼审批后激活）",
    }
    try:
        return versions.create_version(payload, actor)
    except common.GovernanceConflict:
        for item in versions.list_versions(component=comp, kind="MODEL"):
            if item.get("version") == version:
                return item
        raise
