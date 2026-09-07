"""共享 fixtures：每个测试前后清理进程级鉴权状态（令牌撤销表/限流窗口）。"""
import pytest

from backend.api import deps
from backend.core import security_rate


@pytest.fixture(autouse=True)
def _clean_auth_state():
    deps._revoked_before.clear()
    security_rate.reset()
    yield
    deps._revoked_before.clear()
