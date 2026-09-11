"""评测行动项 1：负载基线脚本轻测（scripts/loadtest.py）。

只测统计纯函数（分位数/RPS）与 argparse 默认值，monkeypatch 不触网——
不发任何 HTTP 请求；端点路径一致性断言只读路由表对象（导入即注册，无 IO）。
"""
import pytest

from scripts import loadtest as lt


# ---------- 分位数（线性插值语义） ----------

def test_percentile_linear_interpolation():
    assert lt.percentile([], 50) == 0.0
    assert lt.percentile([10], 95) == 10.0  # 单元素直接返回
    # numpy 'linear' 语义：pos=(n-1)*q/100，落点在两元素间时线性插值
    assert lt.percentile([10, 20, 30, 40], 50) == 25.0     # pos=1.5 → 20+(30-20)*0.5
    assert lt.percentile([1, 2, 3, 4, 5], 95) == pytest.approx(4.8)
    assert lt.percentile([1, 2, 3, 4, 5], 99) == pytest.approx(4.96)


# ---------- summarize：RPS / 错误数 / 分位数口径 ----------

def test_summarize_rps_errors_and_quantiles():
    samples = []
    for i in range(10):
        samples.append(("/a", True, 10.0 + i))   # /a 成功 10 次，延迟 10..19ms
        samples.append(("/b", True, 5.0))
        samples.append(("/b", False, 999.0))     # 失败样本：计入错误，不入分位数
    stats = lt.summarize(samples, wall_seconds=10.0)
    a, b = stats["/a"], stats["/b"]
    assert a["requests"] == 10 and a["errors"] == 0
    assert b["requests"] == 20 and b["errors"] == 10
    assert a["rps"] == 1.0 and b["rps"] == 2.0   # RPS = 总请求数 / 墙钟秒
    assert a["p50"] == pytest.approx(14.5)       # lat 10..19 的中位数
    assert a["p95"] == pytest.approx(18.55, abs=0.06)
    assert a["p99"] == pytest.approx(18.91, abs=0.06)
    assert b["p50"] == 5.0                        # 分位数只统计成功请求


def test_summarize_zero_wall_seconds_is_safe():
    stats = lt.summarize([("/a", True, 1.0)], wall_seconds=0.0)
    assert stats["/a"]["rps"] == 0.0  # 除零防护


# ---------- argparse 默认值与 ramp 档位 ----------

def test_argparse_defaults():
    args = lt.parse_args([])
    assert args.base_url == "http://127.0.0.1:8001"
    assert args.users == 16 and args.seconds == 25
    assert args.pace == 0.8 and args.ramp is False
    assert args.username == "doctor01" and args.timeout == 10.0


def test_ramp_stages_quarter_doubling():
    assert lt.ramp_stages(16) == [1, 4, 8, 16]  # 任务约定四档：1/4/8/16
    assert lt.ramp_stages(3) == [1, 3]          # 小用户数去重升序（不产生 0/重复档）


# ---------- 端点路径与实际注册路由一致（防路由改名后脚本静默 404） ----------

def test_endpoints_match_registered_routes():
    from backend.api.v1.medical.medical_router import router as med_router
    from backend.main import app

    med_paths = {getattr(r, "path", "") for r in med_router.routes}
    app_paths = {getattr(r, "path", "") for r in app.routes}
    for ep in lt.ENDPOINTS:
        if ep == "/healthz":
            assert ep in app_paths, "/healthz 应是 app 级路由"
        else:
            assert ep.startswith("/api/v1/medical")
            assert ep[len("/api/v1/medical"):] in med_paths, f"{ep} 不在 medical_router 注册路由中"


# ---------- 渲染：纯文本表包含关键列 ----------

def test_render_table_contains_columns():
    stats = lt.summarize([("/a", True, 1.0)], wall_seconds=1.0)
    table = lt.render_table(stats)
    for col in ("端点", "RPS", "p95(ms)", "错误", "/a"):
        assert col in table
