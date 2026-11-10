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


# ---------------------------------------------------------------- 评测 C5（第 139 批）
RUFF_CFG = os.path.join(REPO, "ruff.toml")
REQUIREMENTS_DEV = os.path.join(REPO, "requirements-dev.txt")
GITHUB_DIR = os.path.join(REPO, ".github")


def test_lint_gate_is_real_and_blocking():
    """评测 C5：Python 静态门禁必须真实存在且会红。

    - CI 必须真的跑 ruff（而不是只装工具）；
    - 规则集必须落在"正确性"（E9/F）——基线已清零，因此可阻断；
    - 工具版本必须固定（否则 ruff 升级引入新规则会让同一份代码突然变红）。
    """
    ci = _read(CI)
    assert "python -m ruff check backend tests scripts" in ci
    assert "pip install ruff==" in ci
    cfg = _read(RUFF_CFG)
    assert 'select = ["E9", "F"]' in cfg
    dev = _read(REQUIREMENTS_DEV)
    assert re.search(r"(?m)^ruff==", dev), "requirements-dev.txt 必须固定 ruff 版本"


def test_dependency_vulnerability_scan_is_wired():
    """评测 C5：供应链扫描必须覆盖 Python 与唯一前端，并把报告留档。"""
    ci = _read(CI)
    assert "pip_audit -r requirements.txt" in ci
    assert ci.count("npm audit --audit-level=high") == 1, "唯一前端（frontend-console）必须扫"
    assert "working-directory: frontend-console" in ci, "扫描目录应为控制台"
    assert "vuln-reports" in ci, "扫描报告必须作为 artifact 留档（否则等于没扫）"
    dev = _read(REQUIREMENTS_DEV)
    assert re.search(r"(?m)^pip-audit==", dev), "requirements-dev.txt 必须固定 pip-audit 版本"


def test_test_reports_are_archived():
    """评测 C5：用例清单与覆盖率 XML 必须留档，才能回溯"哪次提交开始掉"。"""
    ci = _read(CI)
    assert "--junitxml=reports/junit.xml" in ci
    assert "--cov-report=xml:reports/coverage.xml" in ci
    assert "pytest-reports" in ci


def test_review_governance_files_exist():
    """评测 C5：CODEOWNERS / PR 模板 / ISSUE 模板必须存在（此前 .github 只有 ci.yml）。

    注意：分支保护本身是仓库设置项，代码侧只能提供所有者声明与模板，
    真正的"必须 PR + 必须过 CI"要在 Settings → Branches 里开启。
    """
    for rel in ("CODEOWNERS", "pull_request_template.md",
                os.path.join("ISSUE_TEMPLATE", "bug_report.md")):
        assert os.path.isfile(os.path.join(GITHUB_DIR, rel)), f"缺少 .github/{rel}"
    assert "@medassist-maintainers" in _read(os.path.join(GITHUB_DIR, "CODEOWNERS"))


def test_ci_runs_e2e_in_layered_job():
    """评测 C6：e2e 必须被 CI 真正执行（此前只 skip 不跑），且按 marker 与单测分层。"""
    ci = _read(CI)
    assert "\n  e2e:" in ci, "CI 必须有独立 e2e job"
    assert "-m e2e" in ci
    assert "AUTH_SEED_DEMO" in ci, "e2e 依赖演示账号，必须显式开 seed"
    assert "playwright install" in ci
    ini = _read(PYTEST_INI)
    assert re.search(r"(?m)^\s+e2e:", ini), "pytest.ini 必须注册 e2e 标记"
    e2e_dir = os.path.join(REPO, "tests", "e2e")
    for name in ("test_smoke_flow.py", "test_vue_admin.py",
                 "test_vue_assist.py", "test_vue_parity.py"):
        with open(os.path.join(e2e_dir, name), encoding="utf-8") as f:
            assert "pytestmark = pytest.mark.e2e" in f.read(), f"{name} 缺 e2e 标记"
