"""06-M12：可复跑 Vue E2E 入口脚本的前置检查与退出码。"""
from __future__ import annotations

import importlib.util
import os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(REPO, "scripts", "e2e", "run_vue_parity.py")


def _load():
    spec = importlib.util.spec_from_file_location("run_vue_parity", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_service_up_false_when_unreachable(monkeypatch):
    mod = _load()

    def boom(*_a, **_k):
        raise OSError("connection refused")

    monkeypatch.setattr(mod.urllib.request, "urlopen", boom)
    assert mod.service_up("http://127.0.0.1:1", timeout=0.1) is False


def test_main_requires_playwright(monkeypatch, capsys):
    mod = _load()
    monkeypatch.setattr(mod, "has_playwright", lambda: False)
    assert mod.main(["--no-start"]) == 3
    out = capsys.readouterr().out
    assert "playwright install chromium" in out


def test_main_no_start_fails_when_service_down(monkeypatch, capsys):
    mod = _load()
    monkeypatch.setattr(mod, "has_playwright", lambda: True)
    monkeypatch.setattr(mod, "service_up", lambda *_a, **_k: False)
    assert mod.main(["--no-start", "--base-url", "http://127.0.0.1:1"]) == 2
    assert "服务不可达" in capsys.readouterr().out
