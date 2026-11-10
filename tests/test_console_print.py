"""打印通道一致性回归（第 225 批）。

单据打印此前是每个页面自己 `window.open` + 拼 HTML + `document.write`：
转义函数各写一份（其中一份遗漏了 `&` 之外的顺序问题）、`<title>` 未转义、
打印版式各写一套。医院每天都要打票据/同意书，这类重复实现一旦漂移，
典型后果是“某个单据里的 `<` 把后面整段吞掉”或“打印机任务名乱码”。

本锁把打印收口成一条通道，并用静态断言防止回退：
- 只允许 `utils/print.ts` 使用 `window.open('', '_blank')` 与 `document.write`；
- `escapeHtml` 全仓库只有一份实现（`utils/html.ts`），`markdown.ts` 复用它；
- 通道必须具备 A4 `@page`、标题转义、`script` 剥离、打印后关窗；
- 业务页面不得再直接调用 `win.print()`。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
PRINT_UTIL = SRC / "utils" / "print.ts"
HTML_UTIL = SRC / "utils" / "html.ts"


def _sources() -> list[Path]:
    return [p for p in SRC.rglob("*") if p.suffix in {".vue", ".ts"}]


def test_only_print_util_opens_blank_window():
    offenders: list[str] = []
    for path in _sources():
        if path == PRINT_UTIL:
            continue
        text = path.read_text(encoding="utf-8")
        if re.search(r"window\.open\(\s*''", text):
            offenders.append(str(path.relative_to(REPO)).replace("\\", "/"))
    assert not offenders, f"打印窗口必须在 utils/print.ts 统一创建，以下文件自建：{offenders}"


def test_document_write_is_centralized():
    offenders: list[str] = []
    for path in _sources():
        if path == PRINT_UTIL:
            continue
        if "document.write" in path.read_text(encoding="utf-8"):
            offenders.append(str(path.relative_to(REPO)).replace("\\", "/"))
    assert not offenders, f"document.write 只允许出现在 utils/print.ts：{offenders}"


def test_escape_html_has_single_implementation():
    impls: list[str] = []
    for path in _sources():
        if re.search(r"(export\s+)?function\s+escapeHtml\s*\(", path.read_text(encoding="utf-8")):
            impls.append(str(path.relative_to(REPO)).replace("\\", "/"))
    assert impls == ["frontend-console/src/utils/html.ts"], (
        f"escapeHtml 应只有 utils/html.ts 一份实现，实际：{impls}"
    )
    markdown = (SRC / "utils" / "markdown.ts").read_text(encoding="utf-8")
    assert "from './html'" in markdown, "markdown.ts 必须复用 utils/html.ts 的 escapeHtml"


def test_print_util_has_a4_and_hardening():
    text = PRINT_UTIL.read_text(encoding="utf-8")
    assert "@page" in text, "打印通道必须声明 A4 页面（@page）"
    assert "escapeHtml(doc.title)" in text, "打印窗口标题必须转义"
    assert re.search(r"script", text, re.I), "打印正文必须剥离 script 片段"
    assert "onafterprint" in text, "打印结束后应回收窗口"


def test_views_print_through_channel():
    for name in (
        "ReceiptView.vue",
        "ConsentsView.vue",
        "PharmacyView.vue",
        "PivasView.vue",
        "InpatientView.vue",
    ):
        text = (SRC / "views" / name).read_text(encoding="utf-8")
        assert "printHtml(" in text, f"{name} 应通过 printHtml 打印"
        assert not re.search(r"\.print\(\)", text), f"{name} 不应再直接调用 window.print()"
