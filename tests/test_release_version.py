"""评测 05 P1-8：版本号单一真源（VERSION）与发布清单一致性。

锁定：
1. 四处落点一致：`VERSION` / backend `settings.app_version` / `Chart.yaml appVersion` /
   `frontend-console/package.json version`；
2. `settings.app_version` 默认值确实来自 VERSION 文件（不是硬编码另一份）；
3. `scripts/release_check.py --json` 输出可归档的清单（版本/commit/镜像 tag/problems 为空）；
4. 漂移会被判为失败（防止"改了 VERSION 忘了改 Chart/前端"）；
5. CI 的 lint job 会执行该脚本（门禁不是摆设）。
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _load():
    spec = importlib.util.spec_from_file_location(
        "release_check", ROOT / "scripts" / "release_check.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def test_all_version_sources_consistent():
    mod = _load()
    info = mod.collect()
    assert not mod.problems(info), mod.problems(info)
    assert info["validSemver"] is True


def test_backend_default_reads_version_file():
    from backend.config import settings

    expected = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    assert settings.app_version == expected
    assert mod_version() == expected


def mod_version() -> str:
    return _load().read_version()


def test_chart_and_frontend_match_version():
    mod = _load()
    v = mod.read_version()
    assert mod.read_chart_app_version() == v
    assert mod.read_frontend_version() == v


def test_cli_json_manifest(capsys):
    mod = _load()
    assert mod.main(["--json"]) == 0
    data = json.loads(capsys.readouterr().out)
    assert data["version"] == mod.read_version()
    assert data["problems"] == []
    assert data["gitSha"]
    assert set(data["images"]) == {"aiCore", "platform", "frontend"}
    assert all(v.endswith(":" + data["version"]) for v in data["images"].values())


def test_drift_is_detected():
    mod = _load()
    info = mod.collect()
    info["frontend"] = "0.0.1"
    msgs = mod.problems(info)
    assert any("frontend" in m for m in msgs)


def test_invalid_semver_is_rejected():
    mod = _load()
    info = {"version": "v1", "validSemver": False,
            "backend": "v1", "chartAppVersion": "v1", "frontend": "v1"}
    assert mod.problems(info)


def test_ci_runs_release_check():
    ci = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
    assert "python scripts/release_check.py" in ci
