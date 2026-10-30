"""评测缺陷回归锁（2026-10-30）：两个"误触发代价高"的隐患。

① eval runner 输出隔离：自定义用例集必须各自落盘，不得覆盖难题集基线
   （实测事故：跑 guideline_cases.json 时覆盖了 results_hard_baseline.json 的 4 例基线）；
② 演练脚本参数守卫：drill_key_rotation 只接受恰好一个子命令，多余参数（含 --help）
   只打印文档退出——实测事故：`db --help` 误触发真实 PG role 演练并被外层管道中断。
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
_SPEC = importlib.util.spec_from_file_location("run_eval", _ROOT / "scripts" / "run_eval.py")


def _run_eval_main(argv: list[str]) -> str:
    """加载 run_eval 并以 --dry-run 调用 main()（早返回，不登录/不请求，仅回显输出路径）。

    ⚠️ 环境还原是硬要求：main() 会执行 os.environ.setdefault("PG_OFFLINE","on")（
    eval runner 非服务进程的离线纪律）——直接调用会把整个 pytest 进程锁进离线模式，
    后续所有 repo 假池测试静默走 JSON 分支（实测 16 项断言漂移 + 10 项 skip）。
    这里保存/还原该变量，让"测试被测代码"不污染宿主进程。"""
    import contextlib
    import io
    import os

    spec = importlib.util.spec_from_file_location("run_eval_under_test", _ROOT / "scripts" / "run_eval.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    buf = io.StringIO()
    old_argv = sys.argv
    had_offline = os.environ.get("PG_OFFLINE")
    sys.argv = ["run_eval.py"] + argv
    try:
        with contextlib.redirect_stdout(buf):
            mod.main()
    finally:
        sys.argv = old_argv
        if had_offline is None:
            os.environ.pop("PG_OFFLINE", None)   # 还原：不得把离线模式泄漏给后续测试
        else:
            os.environ["PG_OFFLINE"] = had_offline
    return buf.getvalue()


def test_hard_cases_keeps_legacy_output_name():
    """①a 向后兼容：hard_cases.json 仍写 results_hard_<mode>.json（历史消费者依赖）。"""
    out = _run_eval_main(["--cases", "eval/hard_cases.json", "--dry-run"])
    assert "out=" in out
    assert "results_hard_baseline.json" in out or "results_hard_" in out


def test_other_cases_get_own_output_file():
    """①b 隔离：其它用例集按文件名派生输出，绝不落到难题集基线文件。"""
    out = _run_eval_main(["--cases", "eval/guideline_cases.json", "--dry-run"])
    assert "results_guideline_cases_" in out, out
    assert "results_hard_baseline.json" not in out, \
        "自定义用例集不得复用难题集基线文件名（会覆盖基线）"


def test_drill_script_rejects_extra_args_without_running():
    """② 演练脚本参数守卫：`db --help` 必须只打印文档退出（exit 2），绝不执行演练。

    断言输出含文档首行、且不含任何演练步骤标记（A create+connect 等）——
    演练会真实创建/修改 PG role，误触发代价高。"""
    r = subprocess.run(
        [sys.executable, str(_ROOT / "scripts" / "drill_key_rotation.py"), "db", "--help"],
        capture_output=True, text=True, timeout=30, cwd=str(_ROOT))
    assert r.returncode == 2, f"多余参数应 exit 2（实际 {r.returncode}）"
    assert "W3.5 密钥轮换演练脚本" in r.stdout, r.stdout[:200]
    assert "A create+connect" not in (r.stdout + r.stderr), "不得实际执行演练步骤"


def test_drill_script_rejects_empty_args():
    """②b 无参数同样只打印文档退出（防误触）。"""
    r = subprocess.run(
        [sys.executable, str(_ROOT / "scripts" / "drill_key_rotation.py")],
        capture_output=True, text=True, timeout=30, cwd=str(_ROOT))
    assert r.returncode == 2
    assert "A create+connect" not in (r.stdout + r.stderr)
