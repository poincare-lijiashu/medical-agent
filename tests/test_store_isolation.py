"""测试基础设施不变量（评审 05-S1）：新增持久化出口必须被自动发现并隔离。

背景：``tests/conftest.py`` 的 ``_isolate_agent_stores`` 是**手写常量清单**。
``backend/agents/`` 下新增第 10 个持久化模块时，漏加一行不会报错，只会静默写真实
``data/`` 目录，表现为"单独跑绿、全量跑脏"。

本测试用 AST 扫描模块级 ``*_FILE = os.path.join(BASE_DIR, "data", ...)``，
断言每个常量在当前测试里都指向 pytest 的 ``tmp_path``——把"忘记隔离"从
靠人记得，变成收集期就能看见的失败。
"""
from __future__ import annotations

import ast
import importlib
import os

AGENTS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "backend", "agents")


def _data_file_constants() -> list[tuple[str, str]]:
    found: list[tuple[str, str]] = []
    for name in sorted(os.listdir(AGENTS_DIR)):
        if not name.endswith(".py") or name.startswith("__"):
            continue
        path = os.path.join(AGENTS_DIR, name)
        with open(path, encoding="utf-8") as f:
            tree = ast.parse(f.read(), filename=path)
        for node in tree.body:
            if not isinstance(node, ast.Assign) or len(node.targets) != 1:
                continue
            target = node.targets[0]
            if not isinstance(target, ast.Name) or not target.id.endswith("_FILE"):
                continue
            source = ast.unparse(node.value)
            if "'data'" in source or '"data"' in source:
                found.append((name[:-3], target.id))
    return found


def test_ast_scan_finds_known_persist_constants():
    """扫描规则本身必须有效（防止 AST 写法调整后测试静默变成空断言）。"""
    found = set(_data_file_constants())
    assert {("skills", "SKILLS_FILE"), ("bots", "BOTS_FILE"),
            ("bot_memory", "MEMORY_FILE")} <= found


def test_all_agent_persist_paths_are_isolated(tmp_path):
    leaks = []
    for module_name, const in _data_file_constants():
        module = importlib.import_module(f"backend.agents.{module_name}")
        value = str(getattr(module, const, ""))
        if not value.startswith(str(tmp_path)):
            leaks.append(f"{module_name}.{const} -> {value}")
    assert not leaks, (
        "以下持久化出口未被 conftest._isolate_agent_stores 隔离，测试会写真实 data/：\n"
        + "\n".join(leaks))
