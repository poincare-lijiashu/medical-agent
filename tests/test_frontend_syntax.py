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


def test_task1234_frontend_markers_present():
    """任务1/3/4 前端关键标记存在：
    - 任务1：ICON.myconsults 图标（「undefined我的会诊」根因）+ 概览 humanizeAudit 全覆盖；
    - 任务3：空响应兜底文案 + admin 留痕模式开关卡（config-toggle）；
    - 任务4：审核中心 tab 未读徽标。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 任务1：myconsults 导航图标（缺失时 renderRail 渲染出 "undefined我的会诊"）
        "myconsults:'<svg",
        "esc(humanizeAudit(e))",  # 概览最近活动同样人话化
        # 任务3：空响应兜底 + 留痕模式开关卡
        "AI 未生成所见，请重新上传或补充描述",
        "admin/config-toggle", "flagFullBtn", "toggleFullFlag", "留痕模式（全交互留痕 · 运行时开关）",
        # 任务4：会诊 tab 未读徽标
        "updateConsultBadge", "refreshConsultBadge",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_diag_fixes_frontend_markers_present():
    """诊断1/2/3 前端关键标记存在（锁定防回退）：
    - 诊断1：imaging 视图「图片已发送」提示（区分待发送图与历史已发送图）；
    - 诊断2：admin 留痕模式开关卡显示 runtime_flags 当前值来源 + 变更 toast；
    - 诊断3：uploadKb 失败提示带 HTTP 状态码+响应原因（不再吞原因）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 诊断1：输入区「已发送」提示 + imaging 专属触发
        "sentImgHint", "showSentHint", "图片已发送，如需继续问诊请重新上传",
        # 诊断2：留痕模式开关卡状态来源（runtime_flags 值）+ 变更 toast 文案
        "状态来源：runtime_flags（qc_auto_sign_full=", "（runtime_flags 已更新，即时生效）",
        # 诊断3：uploadKb 失败统一「失败：文件名（状态码 原因）」格式（原因读响应 detail/文本）
        "'（'+r.status+' '+", "String(txt||'').trim().slice(0,80)",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
