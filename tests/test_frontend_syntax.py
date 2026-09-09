"""任务5/6 前端回归：index.html 内联脚本语法校验（node --check）+ 新增功能标记存在性。

node 不可用时跳过（仅环境兜底；本机/CI 均有 node）。
"""
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest

FRONTEND = Path(__file__).resolve().parent.parent / "frontend" / "index.html"


def _inline_scripts() -> list[str]:
    """提取 index.html 全部内联 <script> 源码（外链 src= 跳过）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    out, pos = [], 0
    while True:
        s = html.find("<script", pos)
        if s == -1:
            break
        tag_end = html.find(">", s)
        if "src=" in html[s:tag_end + 1]:  # 外链脚本不校验
            pos = html.find("</script>", tag_end) + len("</script>")
            continue
        e = html.find("</script>", tag_end)
        assert e != -1, "存在未闭合的 <script> 标签"
        out.append(html[tag_end + 1:e])
        pos = e + len("</script>")
    return out


def test_index_html_inline_script_syntax():
    """node --check 校验全部内联脚本语法（任务5/6 前端改动回归防线）。"""
    node = shutil.which("node")
    if not node:
        pytest.skip("node 不可用，跳过前端语法校验")
    scripts = _inline_scripts()
    assert scripts, "index.html 应包含内联脚本"
    for i, src in enumerate(scripts):
        fd = tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8")
        fd.write(src)
        fd.close()
        try:
            r = subprocess.run([node, "--check", fd.name], capture_output=True,
                               text=True, timeout=60)
        finally:
            Path(fd.name).unlink(missing_ok=True)
        assert r.returncode == 0, f"内联脚本 #{i} 语法错误：{r.stderr[:600]}"


def test_task5_task6_frontend_markers_present():
    """新增功能关键标记存在：应用日志卡片（任务5）/ 重提链 UI（任务6）/ esc 转义纪律。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 任务5：应用日志 tail 卡片
        "appLogView", "appLogN", "loadAppLogs", "admin/logs?lines=",
        ".map(l=>esc(l)).join('\\n')",  # 日志行逐行 esc 防注入
        # 任务6：重新提交 / 预填 / 徽标 / escalated 状态
        'data-act="qc-resubmit"', "qcResubmitOf", "resubmit_of",
        "rejected_escalated", "已升级病案科复核", "第 ${att} 次提交",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
