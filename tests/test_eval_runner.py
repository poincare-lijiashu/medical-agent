"""run_eval.py 脚本质量测试（不起真实服务、不触网）。

scripts/ 无 __init__.py → 用 importlib 从文件路径加载（与 test_mcp_server 同法）。
覆盖：login 在服务不可达/认证失败时必须输出人话指引并以退出码 2 结束，绝不裸抛
traceback（排查点：服务不可达时的报错质量）；成功路径正常记录 token。
"""
from __future__ import annotations

import importlib.util
import io
import urllib.error
from pathlib import Path

import pytest

_MODULE_PATH = Path(__file__).resolve().parents[1] / "scripts" / "run_eval.py"
_spec = importlib.util.spec_from_file_location("medassist_run_eval_under_test", _MODULE_PATH)
ev = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ev)


@pytest.fixture
def creds(monkeypatch):
    """终评 F3：评测凭据改为必填环境变量（无内置默认）——本文件登录路径测试统一注入。"""
    monkeypatch.setattr(ev, "EVAL_USER", "doctor01")
    monkeypatch.setattr(ev, "EVAL_PASS", "Med@2026")


def test_login_missing_credentials_exits_with_hint(monkeypatch, capsys):
    """凭据环境变量缺失 → 中文提示 + 退出码 2，不发请求（终评 F3）。"""
    monkeypatch.setattr(ev, "EVAL_USER", "")
    monkeypatch.setattr(ev, "EVAL_PASS", "")
    with pytest.raises(SystemExit) as ei:
        ev.login()
    assert ei.value.code == 2
    err = capsys.readouterr().err
    assert "MEDICAL_EVAL_USER" in err and "MEDICAL_EVAL_PASS" in err


def test_login_unreachable_exits_with_hint(creds, monkeypatch, capsys):
    """服务不可达（连接拒绝/DNS 失败）→ 人话提示 + 退出码 2，绝不裸抛 URLError。"""
    def boom(req, timeout):
        raise urllib.error.URLError("ConnectionRefusedError(10061)")

    monkeypatch.setattr(ev.urllib.request, "urlopen", boom)
    with pytest.raises(SystemExit) as ei:
        ev.login()
    assert ei.value.code == 2
    err = capsys.readouterr().err
    assert "无法连接" in err
    assert ev.SERVER in err                       # 报错带上当前服务地址，便于核对 MEDICAL_SERVER
    assert "backend.main" in err                  # 给出启动指引


def test_login_401_exits_with_hint(creds, monkeypatch, capsys):
    """认证失败（HTTP 401）→ 指明账号口令来源环境变量，退出码 2。"""
    def unauthorized(req, timeout):
        raise urllib.error.HTTPError(ev.SERVER + "/api/v1/auth/login", 401, "Unauthorized",
                                     None, io.BytesIO(b'{"detail":"bad credentials"}'))

    monkeypatch.setattr(ev.urllib.request, "urlopen", unauthorized)
    with pytest.raises(SystemExit) as ei:
        ev.login()
    assert ei.value.code == 2
    err = capsys.readouterr().err
    assert "认证失败" in err
    assert "MEDICAL_EVAL_USER" in err and "MEDICAL_EVAL_PASS" in err


def test_login_success_records_token(creds, monkeypatch):
    """登录成功 → _TOKEN 记录 access_token，不退出。"""
    class _Resp:
        def read(self):
            return b'{"access_token": "tok-123"}'

    monkeypatch.setattr(ev.urllib.request, "urlopen", lambda req, timeout: _Resp())
    ev.login()
    assert ev._TOKEN == "tok-123"
