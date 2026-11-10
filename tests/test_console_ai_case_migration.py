"""统一系统迁移（第 199 批）：AI 病例影像进入控制台。

锁死的约束：

1. **通道**：`/medical/case/ask` 与 `/medical/imaging/ask` 是 AI 中台裸 JSON，
   请求体 `{question, images}`；必须走 `aiPost`，不能再用 Java 信封通道。
2. **图片契约**：最多 10 张、单张压缩后 ≤6MB、长边 ≤2000；图片是 data URL，
   由浏览器先压缩再提交（后端再做一次 `data:image/` 收口）。
3. **引导态不是错误**：`status=need_input / need_image` 是后端给出的引导，
   界面必须用信息提示（info）展示，不能渲染成红色失败。
4. **合规**：`needs_human_review` 必须给双人核对提示与审核中心入口；
   影像页必须提示"请先去标识"；结果可复制但不能据以直接下诊断。
5. **可用性**：可停止（AbortController），历史只保留文字结果（不重复存图片），
   清空后旧请求不得把错误回写到界面。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
SRC = CONSOLE / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiCaseView.vue"
IMAGE = SRC / "utils/image.ts"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_multimodal_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiCaseAsk", "aiImagingAsk"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "'/medical/case/ask'" in src
    assert "'/medical/imaging/ask'" in src
    assert "images.length ? { images } : {}" in src, "无图时不应发送空 images 字段"


def test_browser_side_image_compression():
    src = IMAGE.read_text(encoding="utf-8")
    assert "IMG_MAX_EDGE = 2000" in src
    assert "IMG_MAX_BYTES = 6 * 1024 * 1024" in src
    assert "QUALITY_STEPS = [0.9, 0.8, 0.7, 0.6, 0.5, 0.4]" in src
    assert "export const MAX_IMG = 10" in src
    assert "f.type.startsWith('image/')" in src, "非图片文件必须被过滤"
    # base64 → 字节换算，避免只看字符串长度误判 6MB
    assert "* 0.75" in src
    assert "canvas.toDataURL('image/jpeg'" in src


def test_wired_into_nav_and_router():
    assert "AiCaseView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-case'" in nav
    assert "path: '/ai/case'" in nav
    assert "group: 'AI 智能'" in nav
    block = nav.split("name: 'ai-case'", 1)[1].split("},", 1)[0]
    assert "roles:" not in block, "病例影像对所有登录角色可见"


def test_view_channels_and_guards():
    src = VIEW.read_text(encoding="utf-8")
    assert "aiCaseAsk(question, images, controller.signal)" in src
    assert "aiImagingAsk(question, images, controller.signal)" in src
    assert "appendImages(state.value.images, files)" in src
    assert "const MAX_HISTORY = 8" in src
    assert "current.images = []" in src, "恢复历史必须清空当前图片，避免旧问题配新图"
    assert "current.controller !== controller" in src, "清空/重发后旧请求不得回写错误"
    assert "controller?.abort()" in src
    assert "onBeforeUnmount" in src
    assert "const LIMIT = 20000" in src
    # 不引入 Element Plus / 不直连裸 fetch
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_view_compliance_and_states():
    src = VIEW.read_text(encoding="utf-8")
    assert "aiCase.disclaimer" in src
    assert "needs_human_review" in src
    assert "to=\"/ai/review\"" in src
    assert "statusKey" in src
    assert "need_input" in src and "need_image" in src
    assert "aiCase.status.need_input" in src or "aiCase.status.${statusKey}" in src
    # 拖拽 + 键盘都可用
    assert "@drop.prevent=\"onDrop\"" in src
    assert "@keydown.enter.prevent=\"pickFiles\"" in src
    assert "role=\"button\"" in src
    # 去标识提示（文案随模式切换）
    assert "aiCase.form.note.${mode}" in src


def test_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiCase\.[A-Za-z0-9_.]+)'", src))
    keys.update(
        k for k in re.findall(r"t\(`(aiCase\.[A-Za-z0-9_.]+)", src) if not k.endswith(".")
    )
    # 模板字符串动态 key 的基座必须显式存在于字典
    keys.update({f"aiCase.mode.{m}" for m in ("case", "imaging")})
    keys.update({f"aiCase.form.title.{m}" for m in ("case", "imaging")})
    keys.update({f"aiCase.form.label.{m}" for m in ("case", "imaging")})
    keys.update({f"aiCase.form.placeholder.{m}" for m in ("case", "imaging")})
    keys.update({f"aiCase.form.submit.{m}" for m in ("case", "imaging")})
    keys.update({f"aiCase.form.note.{m}" for m in ("case", "imaging")})
    keys.update({f"aiCase.result.empty.{m}Desc" for m in ("case", "imaging")})
    keys.update({f"aiCase.result.scope.{m}" for m in ("case", "imaging")})
    keys.update({f"aiCase.confidence.{x}" for x in ("high", "medium", "low")})
    keys.update({f"aiCase.status.{x}" for x in ("need_input", "need_image")})
    keys.update({f"aiCase.status.{x}Desc" for x in ("need_input", "need_image")})
    keys.update({f"aiCase.examples.case.{n}" for n in range(1, 4)})
    keys.update({f"aiCase.examples.imaging.{n}" for n in range(1, 4)})

    assert len(keys) > 45, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiCase."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
