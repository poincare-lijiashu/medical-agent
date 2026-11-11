"""全局任务待办中心（第 220 批）。

壳层待办角标只聚合“本人有权限、有明确处理入口”的事项，计数全部走服务端
summary 接口，避免为几个数字拉整表：

- AI 待核对：`review/pending` 的 total（ADMIN/DOCTOR/PHARMACIST/QC）；
- 会诊待我填：`consults/inbox` 的 pendingForMe（DOCTOR，silent 轮询不写读审计）；
- 处方被驳回：`prescriptions/mine` 的 counts.rejected（DOCTOR）。

锁死：
1. 角色门：无权限不请求、不显示该来源；
2. 失败 ≠ 0：取数失败显示“失败”，角标不把 null 当 0 累加；
3. 轮询只在标签页可见时进行（60s），打开面板立即刷新；
4. 入口必须是真实可处理页面，不能落到空壳。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
AI_API = SRC / "api/ai.ts"
TODOS = SRC / "todos.ts"
SHELL = SRC / "layouts/AppShell.vue"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_todo_counts_use_summary_endpoints():
    src = _read(AI_API)
    assert "'/medical/consults/inbox'" in src
    assert "summary: 1" in src and "limit: 0" in src
    assert "silent: 1" in src, "轮询不能写读审计"
    assert "'/medical/prescriptions/mine'" in src
    assert "pendingForMe" in src
    assert "counts" in src and "rejected" in src


def test_todo_sources_are_role_gated_and_deep_linked():
    src = _read(TODOS)
    assert "hasRole(['ADMIN', 'DOCTOR', 'PHARMACIST', 'QC'])" in src
    assert "hasRole('DOCTOR')" in src
    assert "pendingForMe" in src
    assert ".rejected" in src
    for link in (
        "'/ai/review?tab=pending'",
        "'/ai/review?tab=consults'",
        "'/rx'",
    ):
        assert link in src, f"待办必须直达可处理页面：{link}"


def test_todo_failure_is_not_zero():
    src = _read(TODOS)
    assert "count: null" in src and "failed: true" in src
    assert "Math.max(0, raw)" in src
    shell = _read(SHELL)
    assert "item.failed" in shell and "t('todo.failed')" in shell
    assert "t('todo.failedNote')" in shell
    assert "todoTotal > 0" in shell, "失败项不应让角标显示 0"


def test_shell_polls_only_when_visible():
    shell = _read(SHELL)
    assert "60_000" in shell
    assert "document.visibilityState === 'visible'" in shell, "后台标签页不做无谓轮询"
    assert "window.clearInterval(todoTimer)" in shell, "卸载必须清理定时器"
    assert "if (todoOpen.value) void refreshTodos()" in shell, "打开面板立即刷新"


def test_todo_menu_accessibility_and_copy():
    shell = _read(SHELL)
    assert 'class="cmd todo-trigger"' in shell
    assert 'aria-haspopup="menu"' in shell and ":aria-expanded=\"todoOpen ? 'true' : 'false'\"" in shell
    assert "todoTotal > 99 ? '99+' : todoTotal" in shell
    assert "e.key === 'Escape'" in shell and "closeTodos()" in shell
    assert "el.closest('.todo-trigger')" in shell and "el.closest('.todo-menu')" in shell

    zh, en = _read(ZH), _read(EN)
    for key in ("todo.title", "todo.empty", "todo.failed", "todo.review", "todo.consults",
                "todo.rxRejected", "todo.printAnomaly"):
        assert f"'{key}':" in zh and f"'{key}':" in en, f"文案缺 key：{key}"
    for line in en.splitlines():
        if line.strip().startswith("'todo."):
            value = line.split("':", 1)[-1]
            assert not re.search(r"[\u4e00-\u9fff]", value), f"英文混入中文：{line.strip()}"


def test_print_anomaly_todo_is_role_gated_and_deep_linked():
    """第 239 批：补打异常待核查必须只对能核查的人显示，且直达异常区。"""
    src = _read(TODOS)
    assert "clinicalApi.printAnomalySummary(" in src, "补打异常待办必须走服务端 summary，不能拉整表"
    assert "data.pending" in src, "待办只认 pending（已核查的不再打扰）"
    assert "'/print-events?anomaly=1'" in src, "待办必须直达留痕页异常区"
    # 与 shell 的角色门一致：只有 ADMIN/QC 能核查补打异常
    assert "hasRole(['ADMIN', 'QC'])" in src
