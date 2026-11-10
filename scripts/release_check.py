"""发布一致性检查与发布清单（评测 05 P1-8：版本号散落、无产物清单）。

单一真源：仓库根 `VERSION`。必须一致的四类落点：
1. `backend/config.py` 的 `settings.app_version` 默认值（已改为读 VERSION，此处校验运行时值）；
2. `deploy/helm/medassist-platform/Chart.yaml` 的 `appVersion`（Helm 渲染进 Pod 标签/镜像默认 tag）；
3. `frontend-console/package.json` 的 `version`（唯一前端产物版本）；
4. `VERSION` 本身必须是合法 SemVer（允许 `-rc.1` 之类预发布后缀）。

用法（CI 与本地同一命令）：
    python scripts/release_check.py            # 一致性通过则打印发布清单
    python scripts/release_check.py --json     # 机器可读（CI 归档/发布说明用）
退出码：0 一致；1 存在漂移（阻断发布）。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION_FILE = os.path.join(ROOT, "VERSION")
CHART = os.path.join(ROOT, "deploy", "helm", "medassist-platform", "Chart.yaml")
PKG = os.path.join(ROOT, "frontend-console", "package.json")

_SEMVER = re.compile(r"^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$")


def read_version() -> str:
    with open(VERSION_FILE, encoding="utf-8") as f:
        return (f.read() or "").strip()


def read_chart_app_version() -> str:
    # utf-8-sig：Windows 编辑器/脚本可能写入 BOM（YAML 允许，但正则会被 BOM 挡住）
    with open(CHART, encoding="utf-8-sig") as f:
        for line in f:
            m = re.match(r'^appVersion:\s*"?([^"\s]+)"?\s*$', line.strip())
            if m:
                return m.group(1)
    return ""


def read_frontend_version() -> str:
    with open(PKG, encoding="utf-8-sig") as f:
        return str((json.load(f) or {}).get("version") or "")


def read_backend_version() -> str:
    """运行时值（含 APP_VERSION 覆盖）——发布检查要保证"实际提供的版本"也一致。"""
    try:
        if ROOT not in sys.path:
            sys.path.insert(0, ROOT)
        from backend.config import settings  # 延迟导入：脚本可在未装依赖时给出清晰报错
        return str(settings.app_version or "")
    except Exception as e:  # noqa: BLE001 —— 依赖缺失/导入失败按"不一致"处理并说明
        return f"<无法导入 backend.config: {type(e).__name__}: {e}>"


def git_sha() -> tuple[str, bool]:
    try:
        sha = subprocess.run(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"],
                             capture_output=True, text=True, timeout=10).stdout.strip()
        dirty = bool(subprocess.run(["git", "-C", ROOT, "status", "--porcelain"],
                                    capture_output=True, text=True, timeout=15).stdout.strip())
        return sha or "unknown", dirty
    except (OSError, subprocess.SubprocessError):
        return "unknown", False


def collect() -> dict:
    version = read_version()
    return {
        "version": version,
        "validSemver": bool(_SEMVER.match(version)),
        "backend": read_backend_version(),
        "chartAppVersion": read_chart_app_version(),
        "frontend": read_frontend_version(),
    }


def problems(info: dict | None = None) -> list[str]:
    info = info or collect()
    out: list[str] = []
    if not info["validSemver"]:
        out.append(f"VERSION 不是合法 SemVer：{info['version']!r}")
    for key, label in (("backend", "backend/config.py settings.app_version"),
                       ("chartAppVersion", "Chart.yaml appVersion"),
                       ("frontend", "frontend-console/package.json version")):
        if info[key] != info["version"]:
            out.append(f"{label} 为 {info[key]!r}，应为 VERSION={info['version']!r}")
    return out


def manifest(info: dict | None = None) -> dict:
    info = info or collect()
    sha, dirty = git_sha()
    return {
        **info,
        "gitSha": sha,
        "gitDirty": dirty,
        "images": {
            "aiCore": f"ai-core:{info['version']}",
            "platform": f"medassist-platform:{info['version']}",
            "frontend": f"frontend-console:{info['version']}",
        },
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="版本一致性与发布清单检查")
    ap.add_argument("--json", action="store_true", help="输出 JSON 清单（CI 归档用）")
    args = ap.parse_args(argv)
    info = collect()
    bad = problems(info)
    if args.json:
        print(json.dumps({**manifest(info), "problems": bad}, ensure_ascii=False, indent=2))
    else:
        m = manifest(info)
        print(f"版本：{m['version']}  git：{m['gitSha']}{'（有未提交改动）' if m['gitDirty'] else ''}")
        print("镜像： " + " / ".join(m["images"].values()))
        for p in bad:
            print("  [x] " + p)
        if not bad:
            print("版本一致性：OK（VERSION / backend / Chart.appVersion / frontend 四处一致）")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
