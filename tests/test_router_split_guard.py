"""W4.3 拆分守护（评测 P0 建议落地）：把拆分时踩过的坑变成回归锁。

① 行数护栏：backend/api/v1/medical 单文件 ≤600 行（防重新膨胀回 2,275 行巨石）；
② 审计接入口纪律：**禁止** `from …medical.shared import get_audit_logger` 形式的
   from-import——它会在域模块创建局部绑定，使测试 monkeypatch
   「backend.api.v1.medical.shared.get_audit_logger」静默失效（2026-10-30 W4.3
   首跑 54 红的根因）。只允许 `from … import shared` 后经模块属性调用
   （请求时解析模块全局，patch 才能生效）。shared.py 内部定义/调用不受限。
③ 路由快照锁：64 个 (method, path) 的**注册顺序**固定——拆分/合并/新增端点遗漏
   include 会立即红；有意变更路由时更新本快照（评审 diff 可见）。
"""
from __future__ import annotations

import ast
import io
import os

from backend.api.v1.medical import medical_router as mr

MED_DIR = os.path.join(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__))), "backend", "api", "v1", "medical")
LINE_CEILING = 600

# 注册顺序快照（2026-10-30 W4.3 拆分后实测导出；顺序锁定防路由遮蔽回归）
ROUTE_SNAPSHOT: list[tuple[str, str]] = [
    ("GET", "/config"),
    ("POST", "/admin/config-toggle"),
    ("GET", "/overview"),
    ("POST", "/admin/users"),
    ("DELETE", "/admin/users/{username}"),
    ("GET", "/admin/departments"),
    ("POST", "/admin/departments"),
    ("DELETE", "/admin/departments/{name}"),
    ("GET", "/admin/audit"),
    ("GET", "/admin/audit/export"),
    ("GET", "/admin/audit/facets"),
    ("GET", "/admin/data"),
    ("POST", "/admin/review/purge-pending"),
    ("GET", "/admin/logs"),
    ("GET", "/admin/llm/providers"),
    ("POST", "/admin/llm/providers"),
    ("DELETE", "/admin/llm/providers/{kind}/{pid}"),
    ("POST", "/admin/llm/activate"),
    ("POST", "/admin/llm/deactivate"),
    ("GET", "/admin/llm/suggest-extra"),
    ("POST", "/admin/llm/test"),
    ("POST", "/literature/ask"),
    ("POST", "/literature/stream"),
    ("GET", "/drug/dict"),
    ("POST", "/admin/drug/dict"),
    ("POST", "/admin/drug/rules"),
    ("POST", "/admin/drug/rules/review"),
    ("POST", "/drug/ask"),
    ("GET", "/drug/my-rejections"),
    ("POST", "/prescriptions/suggest"),
    ("POST", "/prescriptions"),
    ("GET", "/prescriptions/mine"),
    ("GET", "/prescriptions/reviewed-by-me"),
    ("GET", "/prescriptions/pending"),
    ("POST", "/prescriptions/{rid}/review"),
    ("POST", "/prescriptions/{rid}/rewrite"),
    ("POST", "/imaging/ask"),
    ("POST", "/case/ask"),
    ("POST", "/mdt/consult"),
    ("POST", "/consults"),
    ("GET", "/consults/inbox"),
    ("GET", "/consults/mine"),
    ("POST", "/consults/{cid}/opinion"),
    ("POST", "/consults/{cid}/close"),
    ("GET", "/review/pending"),
    ("GET", "/review/item/{rid}"),
    ("GET", "/review/history"),
    ("GET", "/review/history/item/{rid}"),
    ("GET", "/review/status"),
    ("GET", "/review/my-pending-confirm"),
    ("POST", "/review/{rid}/self-confirm"),
    ("POST", "/review/{rid}/resolve"),
    ("POST", "/review/{rid}/reopen"),
    ("POST", "/qc/record"),
    ("GET", "/qc/my-rejections"),
    ("GET", "/case-archive"),
    ("GET", "/case-archive/mine"),
    ("POST", "/case-archive/{arch_id}/remove"),
    ("POST", "/qc/parse"),
    ("POST", "/kb/search"),
    ("POST", "/admin/kb/upload"),
    ("GET", "/admin/kb/upload/status/{task_id}"),
    ("GET", "/admin/kb"),
    ("GET", "/admin/kb/integrity"),
    ("GET", "/admin/kb/{tag}/preview"),
    ("DELETE", "/admin/kb/{tag}"),
    # 第 132 批：业务流程内联（开医嘱审方 / 病历质控）。新增端点必须在 /health 之前，
    # 否则 catch-all 顺序变化会改变路由解析结果。
    ("POST", "/inline/rx-check"),
    ("POST", "/inline/qc-check"),
    # 第 168 批：HIS 深度集成读端点（患者摘要 / 医嘱清单）
    ("GET", "/integration/patient/{patient_id}"),
    ("GET", "/integration/orders/{patient_id}"),
    ("GET", "/health"),
]


def _medical_sources() -> dict[str, str]:
    out = {}
    for fn in sorted(os.listdir(MED_DIR)):
        if fn.endswith(".py"):
            with io.open(os.path.join(MED_DIR, fn), encoding="utf-8") as f:
                out[fn] = f.read()
    return out


def test_medical_modules_line_ceiling():
    """① 行数护栏：单文件 ≤600 行。"""
    offenders = {fn: len(src.splitlines()) for fn, src in _medical_sources().items()
                 if len(src.splitlines()) > LINE_CEILING}
    assert not offenders, f"超过 {LINE_CEILING} 行的模块（请按业务域继续拆分）：{offenders}"


def test_no_from_import_of_audit_accessor():
    """② 审计接入口纪律：域模块不得 from-import get_audit_logger（patch 失效陷阱）。

    正确姿势：`from backend.api.v1.medical import shared as _shared` + 请求时
    `_shared.get_audit_logger()`。shared.py 自身的定义与内部调用不受限。"""
    offenders = {}
    for fn, src in _medical_sources().items():
        if fn == "shared.py":
            continue  # 定义与内部调用都在属主模块，天然生效
        tree = ast.parse(src)
        bad = []
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module and node.module.endswith("shared"):
                names = {a.name for a in node.names}
                if "get_audit_logger" in names:
                    bad.append(node.lineno)
        if bad:
            offenders[fn] = bad
    assert not offenders, (
        "from-import get_audit_logger 会创建局部绑定使 shared patch 失效"
        "（W4.3 首跑 54 红根因）；改为模块属性调用：offenders=%s" % offenders)


def test_route_snapshot_order_locked():
    """③ 路由快照锁：66 端点的 (method, path) 与注册顺序均不可变。"""
    actual = []
    for r in mr.router.routes:
        methods = sorted(m for m in getattr(r, "methods", ()) or ()
                         if m in ("GET", "POST", "PUT", "DELETE", "PATCH"))
        if methods and getattr(r, "path", None):
            actual.append((methods[0], r.path))
    assert actual == ROUTE_SNAPSHOT, (
        "路由注册顺序/集合与快照不符。若为有意变更，请评审后同步更新 "
        "tests/test_router_split_guard.py 的 ROUTE_SNAPSHOT；"
        f"差异 actual-only={set(actual) - set(ROUTE_SNAPSHOT)}, "
        f"snapshot-only={set(ROUTE_SNAPSHOT) - set(actual)}"
    )
