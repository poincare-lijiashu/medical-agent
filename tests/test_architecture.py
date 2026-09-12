"""批2 任务3：分层依赖方向静态锁（读源码文本断言，防未来腐化）。

规则（docs/ARCHITECTURE.md「依赖方向规则」小节的机械可查子集——用正则从源码提取
`from backend.x import …` / `import backend.x` 语句，断言禁止方向的 import 不存在）：

允许：api → core|agents|integration（api 是组合根）；agents → core（业务复用核心服务）；
     core → config/db/core（核心内部与基础设施）；integration → config + integration 自身。

禁止（反向 = 腐化信号）：
- core/agents/integration → backend.api（下层不得感知 web 层）
- core → backend.agents（核心不得反向依赖编排层）
- integration → backend.core|backend.agents（对接边界层只依赖 config，保持独立可替换）

注：静态文本断言是简单锁，不追踪函数级延迟 import 之外的动态构造（如 importlib 字符串
拼接）——对本仓库已足够（grep 全量核验过无此类用法）。
"""
from __future__ import annotations

import re
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1] / "backend"

# 顶层包名（backend.a.b → backend.a）；from backend import x 形态归一为 backend.x
_IMPORT = re.compile(
    r"^[ \t]*(?:from[ \t]+(backend(?:\.[\w]+)*)[ \t]+import|import[ \t]+(backend(?:\.[\w]+)*(?:[ \t]+as[ \t]+\w+)?(?:[ \t]*,[ \t]*backend[\w.]*)*))",
    re.MULTILINE,
)


def _deps(rel_dir: str) -> dict[str, set[str]]:
    """扫描目录下全部 .py 的 backend.* import，返回 {文件相对路径: {顶层包, …}}。"""
    root = BACKEND / rel_dir
    out: dict[str, set[str]] = {}
    for p in sorted(root.rglob("*.py")):
        found: set[str] = set()
        for m in _IMPORT.finditer(p.read_text(encoding="utf-8", errors="replace")):
            mod = m.group(1) or (m.group(2) or "").split(",")[0].split(" as ")[0].strip()
            parts = mod.split(".")
            top = ".".join(parts[:2]) if len(parts) >= 2 else mod  # backend.core / backend.api / backend
            if parts[:1] == ["backend"] and len(parts) >= 2:
                found.add(top)
        if found:
            out[str(p.relative_to(BACKEND))] = found
    return out


def _assert_no(deps: dict[str, set[str]], *forbidden_prefixes: str) -> None:
    offenders = {f: d for f, d in deps.items()
                 if any(any(x == p or x.startswith(p + ".") for x in d) for p in forbidden_prefixes)}
    assert not offenders, f"依赖方向越界（规则见 docs/ARCHITECTURE.md）：{offenders}"


def test_core_never_imports_api_or_agents():
    """core 层（含共享服务）不得反向依赖 api 与 agents。"""
    _assert_no(_deps("core"), "backend.api", "backend.agents")


def test_agents_never_import_api():
    """agents 层（编排）不得感知 web 层；允许 agents → core。"""
    _assert_no(_deps("agents"), "backend.api")


def test_integration_stays_isolated():
    """integration 层只依赖 config 与自身：不得 import api/core/agents（可独立替换/测试）。"""
    _assert_no(_deps("integration"), "backend.api", "backend.core", "backend.agents")


def test_core_and_agents_do_not_import_integration():
    """integration 是 api 层专用的对外边界：core/agents 不得 import（包 docstring 纪律的机械锁）。"""
    _assert_no(_deps("core"), "backend.integration")
    _assert_no(_deps("agents"), "backend.integration")


def test_api_may_compose_all_layers():
    """正向依赖应真实存在（锁的是反向，正向断言防误删组合根）：api → core/agents。"""
    deps = set().union(*_deps("api").values())
    assert any(d == "backend.core" or d.startswith("backend.core.") for d in deps)
    assert any(d == "backend.agents" or d.startswith("backend.agents.") for d in deps)
