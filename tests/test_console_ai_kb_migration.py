"""统一系统迁移（第 197 批）：AI 知识库管理进入控制台。

锁死的约束：

1. **通道**：`/medical/admin/kb*` 是 AI 中台裸 JSON；删除是 DELETE 方法，
   必须走 `aiDelete`，不能用 GET/POST 伪装。
2. **上传契约是 JSON**：`{name, data_b64, force}`（不是 multipart）；前端必须把
   文件读成裸 base64（去掉 `data:` 前缀），否则后端 base64 解码失败。
3. **大文件异步任务**：返回 `task_id` 时按 2s 轮询（上限 600 次 = 20 分钟），
   状态查询偶发失败要重试而不是终止；用户可「停止等待」，但必须说明后台任务
   可能仍在运行（不能谎报已取消）。
4. **内置库删除**要给出「可重跑种子脚本恢复」的说明；完整性检查是只读的。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
AI_API = CONSOLE / "src/api/ai.ts"
HTTP = CONSOLE / "src/api/http.ts"
VIEW = CONSOLE / "src/views/AiKbView.vue"
NAV = CONSOLE / "src/navigation.ts"
ROUTER = CONSOLE / "src/router/index.ts"
ZH = CONSOLE / "src/i18n/zh-CN.ts"
EN = CONSOLE / "src/i18n/en.ts"


def test_kb_api_channels():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiKbList", "aiKbUpload", "aiKbTask", "aiKbIntegrity", "aiKbPreview", "aiKbDelete"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "'/medical/admin/kb'" in src
    assert "'/medical/admin/kb/upload'" in src
    assert "/medical/admin/kb/upload/status/" in src
    assert "'/medical/admin/kb/integrity'" in src
    assert "aiDelete<{ deleted?: string }>" in src, "删除必须走 DELETE 通道"
    http_src = HTTP.read_text(encoding="utf-8")
    assert "export function aiDelete<T>" in http_src
    assert "method: 'DELETE'" in http_src


def test_kb_wired_into_nav_and_router():
    assert "AiKbView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-kb'" in nav
    assert "group: 'AI 智能'" in nav
    assert "roles: ['ADMIN']" in nav, "知识库管理仅管理员可见"


def test_kb_view_keeps_upload_contract():
    src = VIEW.read_text(encoding="utf-8")
    # 裸 base64（去掉 data: 前缀）
    assert "readAsDataURL(file)" in src
    assert "split(',')[1]" in src
    assert "aiKbUpload(file.name, dataB64)" in src
    # 异步任务：2s 轮询、600 次上限、查询失败继续
    assert "i < 600" in src
    assert "setTimeout(resolve, 2000)" in src
    assert "aiKbTask(taskId)" in src
    assert "continue" in src
    # 停止等待要说清后台任务可能仍在跑
    assert "cancelFlag = true" in src
    assert "aiKb.msg.cancelled" in src
    # 内置库删除说明 + 只读完整性检查
    assert "aiKb.delete.builtinBody" in src
    assert "aiKbIntegrity(true)" in src
    # 不引入 Element Plus / 不直连 fetch
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_kb_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys = set(re.findall(r"t\('(aiKb\.[A-Za-z.]+)'", src))
    assert len(keys) > 50, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"
