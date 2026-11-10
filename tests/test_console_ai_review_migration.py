"""统一系统迁移（第 191 批）：审核中心进入控制台，继续共用同一会话。

本文件锁死的约束：

1. **通道**：AI 中台端点返回裸 JSON，必须走 `aiGet/aiPost`；用 Java 信封通道
   `get()/post()` 会把裸 JSON 当 `{code,data}` 解析，运行期直接抛「响应格式不正确」。
2. **入口**：路由 + 侧栏「AI 智能」分组 + 角色门三者必须同时存在。只加路由不加分组
   整组不渲染（第 126/178 批踩过两次）；只加分组不加角色门，护士/挂号员会看到入口
   并点进 403。侧栏、命令面板与路由守卫必须共用 `canSeeNav`，否则三处判定迟早漂移。
3. **临床安全语义**：双控（不能核对本人提交）、按 agent 分流的签字权、AI 自动签发的
   「知情确认」、影像 `data:image/` 白名单、清空待核对确认词——这些是合规边界，
   不许在重写中丢失。
4. **文案**：新增文案必须中英齐备（`en.ts` 由类型约束，本文件再从数据侧兜底）。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
AI_API = CONSOLE / "src/api/ai.ts"
VIEW = CONSOLE / "src/views/AiReviewView.vue"
NAV = CONSOLE / "src/navigation.ts"
ROUTER = CONSOLE / "src/router/index.ts"
SHELL = CONSOLE / "src/layouts/AppShell.vue"
PALETTE = CONSOLE / "src/components/ui/CommandPalette.vue"
ZH = CONSOLE / "src/i18n/zh-CN.ts"
EN = CONSOLE / "src/i18n/en.ts"
MARKDOWN = CONSOLE / "src/utils/markdown.ts"


def test_review_api_reuses_session_channel():
    src = AI_API.read_text(encoding="utf-8")
    for fn in (
        "aiReviewPending",
        "aiReviewHistory",
        "aiReviewItem",
        "aiMyPendingConfirm",
        "aiReviewResolve",
        "aiReviewReopen",
        "aiReviewSelfConfirm",
        "aiPurgePending",
        "aiRxPending",
        "aiRxReviewedByMe",
        "aiRxReview",
        "aiConsultInbox",
        "aiConsultOpinion",
        "aiCaseArchive",
        "aiCaseArchiveRemove",
    ):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "aiGet<AiReviewPage>('/medical/review/pending'" in src
    assert "aiPost<AiReviewItem>" in src
    # 裸 JSON 端点不得混用 Java 信封通道
    assert "get<AiReviewPage>" not in src
    assert "post<AiReviewItem>" not in src


def test_review_wired_into_nav_router_and_role_gate():
    assert "AiReviewView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-review'" in nav
    assert "group: 'AI 智能'" in nav
    assert "roles: ['ADMIN', 'DOCTOR', 'PHARMACIST', 'QC']" in nav, "审核中心必须按角色收窄"
    assert "export function canSeeNav" in nav, "导航可见性必须收敛到单一判定"
    # 侧栏 / 命令面板 / 路由守卫共用同一判定（否则三处会漂移）
    for path in (SHELL, PALETTE, ROUTER):
        assert "canSeeNav" in path.read_text(encoding="utf-8"), f"{path.name} 未使用 canSeeNav"


def test_review_view_keeps_safety_semantics():
    src = VIEW.read_text(encoding="utf-8")
    # 双控 + 签字权分流
    assert "row.submitted_by === me" in src, "必须保留双控提示（不能核对本人提交）"
    assert "function canSignItem(" in src
    assert "if (row.agent === 'drug') return isAdmin.value || isPh.value" in src
    # AI 自动签发 → 知情确认
    assert "function needsSelfConfirm(" in src
    assert "self_confirm_required" in src
    # 影像白名单 + 放大查看
    assert "startsWith('data:image/')" in src
    assert "function showImage(" in src
    # 清空待核对：服务端确认词必须硬编码（不随界面语言变化）
    assert "const PURGE_WORD = '清空'" in src
    assert "purgeWord.trim() !== PURGE_WORD" in src
    # 错误态：403/网络失败不能白屏
    assert "loadErr" in src and "<LoadError" in src
    # 不引入 Element Plus（控制台是自建设计系统）
    assert "<el-" not in src
    assert "element-plus" not in src
    # 不直连 fetch（必须走统一通道，保证 401 刷新语义）
    assert "fetch(" not in src


def test_review_view_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys = set(re.findall(r"t\('(aiReview\.[A-Za-z.]+)'", src))
    assert len(keys) > 90, f"解析到的文案 key 过少（{len(keys)}），模板可能已不在本文件"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"


def test_markdown_escapes_before_formatting():
    src = MARKDOWN.read_text(encoding="utf-8")
    # 先整体转义再做受限变换：v-html 消费方拿到的字符串不含可注入标签
    assert "escapeHtml(text)" in src
    assert src.index("escapeHtml(text)") < src.index("replace(/^#{1,4}")
    assert "splitConsultQuestion" in src
