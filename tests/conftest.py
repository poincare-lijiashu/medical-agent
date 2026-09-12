"""共享 fixtures：每个测试前后清理进程级鉴权状态（令牌撤销表/限流窗口）。"""
import os

# 必须在任何 backend import 之前：锁定 seed 种子口令，保证既有测试硬编码 Med@2026 不因 A5 随机化而破坏
os.environ.setdefault("AUTH_DEMO_PASSWORD", "Med@2026")

import pytest

from backend.api import deps
from backend.core import security_rate, stores


@pytest.fixture(autouse=True)
def _clean_auth_state():
    deps._revoked_before.clear()
    security_rate.reset()
    stores.reset_store()  # 轮 B2：重建共享 store 单例（撤销/会话键不跨测试残留）
    yield
    deps._revoked_before.clear()


@pytest.fixture(autouse=True)
def _isolate_runtime_flags(tmp_path, monkeypatch):
    """任务3：每个测试独立的 runtime_flags 文件——测试进程绝不读写真实
    data/runtime_flags.json（服务端 admin 运行时切换值不泄漏进测试，
    测试中的 set_flag 也不污染真实运行配置）。文件默认不存在 → 全部回落 settings。"""
    from backend.core import runtime_flags
    monkeypatch.setattr(runtime_flags, "FLAGS_FILE", str(tmp_path / "runtime_flags.json"))
    yield


@pytest.fixture(autouse=True)
def _isolate_case_archive(tmp_path, monkeypatch):
    """阶段4：每个测试独立的病例归档文件——qc approve 挂点在既有路由测试中触发归档时，
    绝不读写真实 data/case_archive.json（防测试数据污染真源）。文件默认不存在 → 空库。"""
    from backend.core import case_archive
    monkeypatch.setattr(case_archive, "CASE_ARCHIVE_FILE",
                        str(tmp_path / "case_archive.json"))
    yield
