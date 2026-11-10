"""统一系统迁移（第 198 批）：AI 对话（文献循证 + 专科问答）进入控制台。

锁死的约束：

1. **通道**：两条链路都是 AI 中台 SSE（`text/event-stream`），必须复用同一枚
   Bearer 与同一次 401 刷新；不能用 `EventSource`（无法带 Authorization/POST），
   也不能用 `aiPost`（`res.text()` 会等流结束才返回）。
2. **回落语义**：流在收到任何内容前失败才回落非流式；403/422/429/503 是后端
   开流前的最终结论，回落只会以同一原因再失败一次（还会二次消耗模型配额）。
3. **截断不能当完整结论**：文献没有 `result`、问答没有 `done` 时，界面必须显式
   标注"内容可能不完整"，不能把半截回答当临床结论展示。
4. **会话历史只在本机**：localStorage 持久化，流式中的半截回答刷新后标记为
   「被中断」；后端不存聊天记录（文献的 session_id 只用于推理上下文）。
5. **多轮上下文**：只回传已完成的问答对，最近 3 对、≤6000 字，首条永远是 user。
6. **技能卡上限 8 张**，与后端 `ConsultReq.skillCodes(max_length=8)` 同口径。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
SRC = CONSOLE / "src"
HTTP = SRC / "api/http.ts"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiChatView.vue"
STORE = SRC / "stores/aiChat.ts"
UTILS = SRC / "utils/aiChat.ts"
SESSIONS = SRC / "components/domain/ai/ChatSessions.vue"
LIT_ANSWER = SRC / "components/domain/ai/LiteratureAnswer.vue"
ASK_ANSWER = SRC / "components/domain/ai/AskAnswer.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_sse_channel_reuses_session_and_parses_events():
    src = HTTP.read_text(encoding="utf-8")
    assert "export async function aiStream" in src
    assert "text/event-stream" in src
    assert "Authorization = `Bearer ${tokens.accessToken}`" in src, "SSE 必须复用同一枚令牌"
    assert "doRefresh()" in src and "onSessionExpired" in src, "401 必须复用统一刷新/登出语义"
    assert "getReader()" in src
    assert "dataLines" in src and "field === 'event'" in src
    # 服务端未以空行收尾的残留事件必须分发
    assert "buffer.trim()" in src
    # EventSource 无法带 Bearer/POST，本项目不使用
    assert "EventSource" not in src


def test_chat_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiLiteratureAsk", "aiAgentCatalog", "aiAgentSkills", "aiAgentConsult"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "'/medical/literature/ask'" in src
    assert "'/agent/catalog'" in src
    assert "'/agent/skills'" in src
    assert "'/agent/consult'" in src
    view = VIEW.read_text(encoding="utf-8")
    assert "'/medical/literature/stream'" in view
    assert "'/agent/consult/stream'" in view
    assert "aiStream(" in view


def test_wired_into_nav_and_router():
    assert "AiChatView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-chat'" in nav
    assert "path: '/ai/chat'" in nav
    assert "group: 'AI 智能'" in nav
    # AI 对话对所有登录角色可见（与旧 AI 工作台一致），不能加 roles 门
    block = nav.split("name: 'ai-chat'", 1)[1].split("},", 1)[0]
    assert "roles:" not in block


def test_view_keeps_streaming_and_fallback_semantics():
    src = VIEW.read_text(encoding="utf-8")
    # 流式优先 + 非流式回落
    assert "aiLiteratureAsk(question, session.id" in src
    assert "aiAgentConsult(body" in src
    # 403/422/429/503 不回落
    assert "[401, 403, 422, 429, 503]" in src
    # 可停止 + 离开页面断开
    assert "controller.value?.abort()" in src
    assert "onBeforeUnmount" in src
    # 截断/中断必须显式标注
    assert "aiChat.ask.truncated" in src
    assert "aiChat.lit.stopped" in src
    assert "aiChat.lit.partial" in LIT_ANSWER.read_text(encoding="utf-8")
    # 多轮上下文：只取已完成问答对，排除当前提问与占位
    assert "buildHistory(session.turns.slice(0, -2))" in src
    # 技能卡上限与后端同口径
    assert "const SKILL_LIMIT = 8" in src
    assert "t('aiChat.ask.limit'" in src
    # 不引入 Element Plus / 不直连裸 fetch
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src
    # 会话 id 直接作为文献推理上下文（后端 session_id ≤64）
    assert "session_id: session.id" in src


def test_local_multi_session_store():
    src = STORE.read_text(encoding="utf-8")
    assert "medassist.console.aiChat.v1" in src, "本地会话存储键缺失"
    assert "MAX_SESSIONS = 30" in src
    assert "MAX_TURNS = 120" in src
    # 流式中的半截回答刷新后标记为中断，且空占位不落盘
    assert "interrupted: true" in src
    assert "if (!turn.answer) return null" in src
    # 配额写满不删内存会话，只减少落盘副本
    assert "packed.sessions.slice(0, 10)" in src
    # 会话/消息都做长度上限清洗（localStorage 是外部输入）
    assert "sanitizeSession" in src and "sanitizeTurn" in src


def test_history_rules_are_frozen_in_utils():
    src = UTILS.read_text(encoding="utf-8")
    assert "maxPairs = 3" in src
    assert "maxChars = 6000" in src
    # 失败/中断/流式中的回答不能进入历史
    assert "answer.streaming || answer.failed || answer.interrupted" in src
    # 以问答对为单位丢弃，首条永远是 user
    assert "kept = kept.slice(1)" in src
    # 来源只展示前 6 条，PMID 自动链接
    assert "slice(0, 6)" in src
    assert "pubmed.ncbi.nlm.nih.gov" in src


def test_answer_components_surface_evidence():
    lit = LIT_ANSWER.read_text(encoding="utf-8")
    assert ".evidence ?? []" in lit
    assert ".refine_trace ?? []" in lit
    assert "aiChat.lit.enqueued" in lit
    assert "to=\"/ai/review\"" in lit
    ask = ASK_ANSWER.read_text(encoding="utf-8")
    assert ".appliedSkills ?? []" in ask
    assert ".declaredTools ?? []" in ask
    assert "aiChat.ask.noApplied" in ask
    sessions = SESSIONS.read_text(encoding="utf-8")
    assert "aiChat.session.rename" in sessions
    assert "aiChat.session.delete'" in sessions
    assert "aiChat.session.deleteTitle" in VIEW.read_text(encoding="utf-8")


def test_chat_copy_is_bilingual():
    sources = [VIEW, STORE, SESSIONS, LIT_ANSWER, ASK_ANSWER]
    keys: set[str] = set()
    for path in sources:
        text = path.read_text(encoding="utf-8")
        keys.update(re.findall(r"t\('(aiChat\.[A-Za-z0-9_.]+)'", text))
        # 模板字符串动态 key 只能拿到基座（以 . 结尾），展开在下方显式补齐
        keys.update(
            k for k in re.findall(r"t\(`(aiChat\.[A-Za-z0-9_.]+)", text) if not k.endswith(".")
        )
    # 动态 key 的基座必须显式存在于字典（模板字符串无法被正则展开）
    keys.update(
        {
            f"aiChat.mode.{m}" for m in ("literature", "ask")
        }
    )
    keys.update(
        {
            f"aiChat.compliance.{m}" for m in ("literature", "ask")
        }
    )
    keys.update({f"aiChat.empty.{m}Desc" for m in ("literature", "ask")})
    keys.update({f"aiChat.composer.{m}" for m in ("literature", "ask")})
    keys.update({f"aiChat.composer.hint.{m}" for m in ("literature", "ask")})
    keys.update({"aiChat.lit.progress." + k for k in ("thinking", "retrieve", "grade", "generate", "fallback")})
    keys.update({"aiChat.lit.source." + k for k in ("prefilter", "intent", "drugRules")})
    keys.update({"aiChat.ask.strategy." + k for k in ("single", "supervisor", "explicit", "prefilter")})
    keys.update({f"aiChat.examples.lit.{n}" for n in range(1, 5)})
    keys.update({f"aiChat.examples.ask.{n}" for n in range(1, 4)})

    assert len(keys) > 60, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    # 英文值不得混入中文（key 本身可以含中文，例如 nav.group.*）
    for line in en.splitlines():
        if not line.strip().startswith("'aiChat."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
