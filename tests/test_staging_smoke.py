"""第 175 批：staging 双系统联调脚本的契约锁（CI 内跑，不连网络）。

检查 `scripts/staging_integration_smoke.py` 的检查目录是否符合既定边界：
- 入口收敛项必须是 404（/docs、/openapi.json、/internal/**、/api/v2/*/internal/**）；
- HIS 工作台通道必须是**外部**路径 /api/v2/clinical/ai-assist/**，未带令牌 401；
- 患者检查只在提供 --patient-id 时出现；内部接口不得带令牌去调用（业务上永远 404）。

这些断言等价于"联调脚本不能把错误的设计写成 PASS"，防止脚本本身腐化。
"""
from __future__ import annotations

import importlib.util
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCRIPT = os.path.join(ROOT, "scripts", "staging_integration_smoke.py")


def _load():
    spec = importlib.util.spec_from_file_location("staging_integration_smoke", SCRIPT)
    assert spec and spec.loader, "联调脚本缺失或不可导入"
    module = importlib.util.module_from_spec(spec)
    # 脚本用 `from __future__ import annotations` + dataclass：dataclasses 解析字符串注解时
    # 需要模块已登记在 sys.modules（直接 python 运行时由 __main__ 自动满足）。
    sys.modules["staging_integration_smoke"] = module
    spec.loader.exec_module(module)
    return module


def test_check_catalog_locks_entry_boundaries():
    smoke = _load()
    checks = {c.name: c for c in smoke.build_checks("patient-1")}

    for name in ("docs-blocked", "openapi-blocked"):
        assert name in checks, f"缺少入口收敛检查 {name}"
        assert checks[name].expect == (404,), f"{name} 必须期望 404"

    for name in ("internal-root-blocked", "internal-v2-blocked"):
        assert name in checks
        assert checks[name].expect == (404,)
        assert checks[name].auth is False, "内部接口永远 404，带令牌调用没有意义"

    assert "patient-read" in checks
    assert checks["patient-read"].path.endswith("/patient/patient-1")


def test_his_workbench_channel_is_external_and_requires_jwt():
    smoke = _load()
    checks = {c.name: c for c in smoke.build_checks()}
    assist = checks["ai-assist-requires-jwt"]
    assert assist.path == "/api/v2/clinical/ai-assist/qc-check", \
        "HIS 工作台必须走网关外部通道，不得复用 /internal"
    assert assist.expect == (401,)
    assert assist.auth is False, "该检查本身就验证未带令牌被拒"


def test_patient_check_is_optional():
    smoke = _load()
    names = {c.name for c in smoke.build_checks()}
    assert "patient-read" not in names, "未提供患者时不应生成读取检查（避免误报）"


def test_self_check_mode_passes_without_network(capsys):
    smoke = _load()
    assert smoke.main(["--self-check"]) == 0
    out = capsys.readouterr().out
    assert "检查项" in out and "/api/v1/medical/integration/status" in out
