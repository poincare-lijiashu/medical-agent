"""CI 门禁静态锁（第 70 批）：覆盖率阈值与红队阻断不得被静默移除。

为什么需要：这两个门禁都只存在于 CI 配置里，本地 `pytest -q` 全绿也不会发现它们被删。
历史教训（G63）是"配置写错 → 门禁常红 → 被忽略 → 等于没有"。本文件把以下事实锁住：
1. CI 的 pytest job 必须带 `--cov=backend --cov-fail-under=85`；
2. CI 必须先跑 `-m redteam`（顺序在覆盖率全量之前）；
3. pytest.ini 必须注册 `redteam` 标记；
4. 红队用例必须真实存在（至少 2 个模块、20 条用例），不是空标记；
5. `pytest-cov` 必须在 requirements.txt 里固定版本（否则 CI 装不上参数直接报错）。
"""
from __future__ import annotations

import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CI = os.path.join(REPO, ".github", "workflows", "ci.yml")
PYTEST_INI = os.path.join(REPO, "pytest.ini")
REQUIREMENTS = os.path.join(REPO, "requirements.txt")
REDTEAM_FILES = (
    os.path.join(REPO, "tests", "test_prompt_injection.py"),
    os.path.join(REPO, "tests", "test_security.py"),
)


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def test_ci_enforces_backend_coverage_floor():
    """覆盖率阈值必须固定在 CI 里；阈值只能上调，不能为跑绿而降。"""
    ci = _read(CI)
    assert "--cov=backend" in ci, "CI 必须统计 backend 覆盖率"
    m = re.search(r"--cov-fail-under=(\d+)", ci)
    assert m, "CI 必须有 --cov-fail-under 覆盖率阈值"
    assert int(m.group(1)) >= 85, f"覆盖率阈值不得低于 85%（当前 {m.group(1)}）"


def test_ci_redteam_runs_before_full_suite():
    """红队门禁必须独立成步且先于全量：对抗用例失败不应等到最后才暴露。"""
    ci = _read(CI)
    m = re.search(r"-m redteam", ci)
    assert m, "CI 必须有 `-m redteam` 红队门禁"
    assert ci.index("-m redteam") < ci.index("--cov-fail-under"), \
        "红队门禁应排在覆盖率全量之前（先快后慢，失败立即阻断）"


def test_redteam_marker_registered():
    """标记必须先注册，否则 pytest 会以 PytestUnknownMarkWarning 静默放过拼写错误。"""
    ini = _read(PYTEST_INI)
    assert "markers" in ini and re.search(r"(?m)^\s+redteam:", ini), \
        "pytest.ini 必须注册 redteam 标记"


def test_redteam_suite_is_not_empty():
    """防止"标记还在、用例被删空"：两个安全模块必须都打标记且用例总数 ≥20。"""
    total = 0
    for path in REDTEAM_FILES:
        src = _read(path)
        assert "pytestmark = pytest.mark.redteam" in src or \
            "pytest.mark.redteam" in src, f"{os.path.basename(path)} 未打 redteam 标记"
        total += len(re.findall(r"(?m)^(?:async )?def test_", src))
    assert total >= 20, f"红队用例太少（{total}），门禁形同虚设"


def test_pytest_cov_pinned_for_ci():
    """CI 用的 --cov 参数依赖 pytest-cov；未固定版本会在环境漂移时直接报 unknown option。"""
    req = _read(REQUIREMENTS)
    assert re.search(r"(?m)^pytest-cov==", req), "requirements.txt 必须固定 pytest-cov 版本"
