"""Agent 问答 E2E 冒烟脚本的离线自检（评审 M-60 配套）。

脚本本身需要真实服务；这里只保证它可导入、参数契约稳定，
避免"资产存在但跑不起来"再次发生。
"""
from __future__ import annotations

import importlib.util
import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(REPO, "scripts", "e2e", "agent_ask_stream_smoke.py")


def _load():
    spec = importlib.util.spec_from_file_location("agent_ask_stream_smoke", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


mod = _load()


def test_dry_run_passes_without_service():
    assert mod.main(["--dry-run"]) == 0


def test_missing_password_returns_2():
    assert mod.main([]) == 2


def test_script_covers_stream_contract():
    """脚本必须显式断言 done 事件（M-53 的截断判据）。"""
    with open(SCRIPT, encoding="utf-8") as f:
        body = f.read()
    for marker in ("stream.starts_with_meta", "stream.has_delta",
                   "stream.ends_with_done", "stream.done_has_answer"):
        assert marker in body
