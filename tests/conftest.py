"""共享 fixtures：每个测试前后清理进程级鉴权状态（令牌撤销表/限流窗口）。"""
import os

# 必须在任何 backend import 之前：锁定 seed 演示口令，保证既有测试硬编码 Med@2026 不因 A5 随机化而破坏
os.environ.setdefault("AUTH_DEMO_PASSWORD", "Med@2026")

import pytest

from backend.api import deps
from backend.core import security_rate


@pytest.fixture(autouse=True)
def _clean_auth_state():
    deps._revoked_before.clear()
    security_rate.reset()
    yield
    deps._revoked_before.clear()
