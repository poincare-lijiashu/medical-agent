"""可复跑的 Vue E2E 入口（评审 06-M12）。

背景：`tests/e2e/test_vue_parity.py` 依赖"本地服务已启动 + 已装 playwright"，
否则整文件 skip；这导致"真实浏览器验证"的结论在仓库里没有**一条命令可复现**。
本脚本把前置条件收敛为一条命令：

    python scripts/e2e/run_vue_parity.py

行为：
1. 探测 `http://127.0.0.1:8001/healthz`；
2. 未启动时自动用当前 Python 起一个 uvicorn（日志写到系统临时目录），
   测试结束后只停掉**本脚本启动的**进程；
3. 检查 playwright 与 chromium 是否可用（缺失时给出安装命令并以退出码 3 结束）；
4. 运行 `pytest tests/e2e/test_vue_parity.py -v`，原样返回其退出码。

退出码：0=全部通过；1=有失败；2=服务不可用/启动失败；3=环境缺 playwright。
"""
from __future__ import annotations

import argparse
import importlib.util
import os
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DEFAULT_BASE = "http://127.0.0.1:8001"
E2E_FILE = os.path.join(REPO_ROOT, "tests", "e2e", "test_vue_parity.py")


def service_up(base_url: str, timeout: float = 3.0) -> bool:
    """探测 /healthz；任何异常都视为未启动。"""
    try:
        with urllib.request.urlopen(base_url.rstrip("/") + "/healthz",
                                    timeout=timeout) as r:
            return r.status == 200
    except Exception:  # noqa: BLE001
        return False


def has_playwright() -> bool:
    return importlib.util.find_spec("playwright") is not None


def _start_backend(base_url: str, log_path: str) -> subprocess.Popen:
    """启动 uvicorn（仅用于本地 E2E）；输出重定向到临时日志。

    评审第 40 批：全量 E2E 会以同一演示账号连续加载多个页面（每个页面首刷约 10 个
    只读接口），瞬间超过生产默认 60 次/分钟限流，导致「路由预览」等用例假红。
    这里仅对**本脚本拉起的测试后端**放宽到 600 次/分钟；调用方显式设置的环境变量
    优先（setdefault），应用默认值不变、生产不受影响。
    """
    host = "127.0.0.1"
    port = "8001"
    try:
        parsed = urllib.parse.urlparse(base_url)
        host = parsed.hostname or host
        port = str(parsed.port or 8001)
    except Exception:  # noqa: BLE001
        pass
    cmd = [sys.executable, "-m", "uvicorn", "backend.main:app",
           "--host", host, "--port", port]
    env = os.environ.copy()
    env.setdefault("RATE_LIMIT_PER_MIN", "600")
    log = open(log_path, "w", encoding="utf-8")  # noqa: SIM115 —— 交给子进程持有
    return subprocess.Popen(cmd, cwd=REPO_ROOT, stdout=log, stderr=subprocess.STDOUT,
                            env=env)


def _wait_up(base_url: str, seconds: float) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if service_up(base_url, timeout=2.0):
            return True
        time.sleep(1.0)
    return False


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="可复跑的 Vue E2E（Playwright）")
    parser.add_argument("--base-url", default=DEFAULT_BASE,
                        help=f"后端地址（默认 {DEFAULT_BASE}）")
    parser.add_argument("--no-start", action="store_true",
                        help="服务未启动时不自动拉起，直接失败")
    parser.add_argument("--start-timeout", type=float, default=60.0,
                        help="等待服务就绪的秒数（默认 60）")
    args = parser.parse_args(argv)

    if not has_playwright():
        print("FAIL 未安装 playwright。请先执行：")
        print("  pip install playwright")
        print("  python -m playwright install chromium")
        return 3
    if not os.path.isfile(E2E_FILE):
        print(f"FAIL 找不到 E2E 文件：{E2E_FILE}")
        return 2

    proc: subprocess.Popen | None = None
    log_path = os.path.join(tempfile.gettempdir(), "medassist-e2e-backend.log")
    try:
        if not service_up(args.base_url):
            if args.no_start:
                print(f"FAIL 服务不可达：{args.base_url}/healthz（--no-start 已指定）")
                return 2
            print(f"INFO 服务未启动，正在拉起后端（日志：{log_path}）…")
            proc = _start_backend(args.base_url, log_path)
            if not _wait_up(args.base_url, args.start_timeout):
                print(f"FAIL 服务在 {args.start_timeout:.0f}s 内未就绪；日志尾部：")
                try:
                    with open(log_path, encoding="utf-8", errors="replace") as f:
                        lines = f.readlines()[-20:]
                    print("".join(lines))
                except Exception:  # noqa: BLE001
                    pass
                return 2

        print(f"INFO 运行 {os.path.relpath(E2E_FILE, REPO_ROOT)}（base={args.base_url}）")
        proc_pytest = subprocess.run(
            [sys.executable, "-m", "pytest", E2E_FILE, "-v", "--tb=short"],
            cwd=REPO_ROOT)
        return proc_pytest.returncode
    finally:
        if proc is not None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()


if __name__ == "__main__":
    raise SystemExit(main())
