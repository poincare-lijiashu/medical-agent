"""统一系统迁移（第 200 批）：深度会诊 + 我发起的会诊。

锁死的约束：

1. **两条链路不能混**：`/medical/mdt/consult` 只收 `{case}`（AI 多智能体模拟会诊）；
   影像只在 `POST /medical/consults`（真实跨科室会诊）时随单发出。
2. **真实会诊的底稿**：发起时问题 = 病例原文 + `【AI 多智能体会诊底稿】` + AI 小结，
   这样目标科室医生看到的是同一份上下文；`<8` 字按后端预检同口径在前端先拦下。
3. **发起者是唯一能结束会诊的人**：面板必须只对 `status=open` 展示结束入口，
   结束后各科意见与 AI 意见并排汇总回记录。
4. **数据隔离**：`/medical/consults/mine` 由服务端按发起人过滤；前端不做二次权限判断。
5. **检索与分页下推服务端**：关键字 300ms 防抖、10 条/页，避免"前端过滤把总数算错"。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
SRC = CONSOLE / "src"
AI_API = SRC / "api/ai.ts"
CHAT_STORE = SRC / "stores/aiChat.ts"
CHAT_VIEW = SRC / "views/AiChatView.vue"
MDT = SRC / "components/domain/ai/MdtAnswer.vue"
REVIEW = SRC / "views/AiReviewView.vue"
PANEL = SRC / "components/domain/ai/ConsultInitiatedPanel.vue"
MARKDOWN = SRC / "utils/markdown.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_consult_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiMdtConsult", "aiConsultCreate", "aiMyConsults", "aiConsultClose"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "'/medical/mdt/consult'" in src
    assert "{ case: caseText }" in src, "MDT 只接受病例文本"
    assert "'/medical/consults'" in src
    assert "images.length ? { images } : {}" in src, "无影像时不应发送空数组"
    assert "'/medical/consults/mine'" in src
    assert "/close" in src


def test_chat_store_accepts_consult_mode():
    src = CHAT_STORE.read_text(encoding="utf-8")
    assert "export type ChatMode = 'literature' | 'ask' | 'consult'" in src
    assert "item.mode !== 'consult'" in src, "本地历史清洗必须接受 consult 会话"


def test_chat_view_third_mode():
    src = CHAT_VIEW.read_text(encoding="utf-8")
    assert "const MODES: ChatMode[] = ['literature', 'ask', 'consult']" in src
    assert "await aiMdtConsult(question, ac.signal)" in src
    assert '<MdtAnswer' in src
    assert "const CONSULT_MIN_CHARS = 8" in src
    assert "await aiConsultCreate(question, consultImages.value)" in src
    # 真实会诊必须带 AI 底稿
    assert "【AI 多智能体会诊底稿】" in src
    # 影像只在真实会诊时使用；切换会话/模式清空，防止旧问题配新图
    assert "consultImages.value = []" in src
    assert "appendImages(consultImages.value, files)" in src
    # 不引入 Element Plus / 不直连裸 fetch
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_mdt_answer_surfaces_consult_result():
    src = MDT.read_text(encoding="utf-8")
    assert "meta.value.urgency" in src
    for key in ("key_actions", "opinions", "disagreements", "report", "review_id"):
        assert key in src, f"会诊结果缺少 {key}"
    assert "$emit('initiate')" in src
    assert "meta.value.initiated" in src
    assert 'to="/ai/review?tab=mine-consults"' in src
    assert "aiChat.consult.urgency." in src


def test_review_center_has_initiated_tab():
    src = REVIEW.read_text(encoding="utf-8")
    assert "'mine-consults'" in src
    assert "ConsultInitiatedPanel" in src
    assert "aiReview.tab.mineConsults" in src
    assert "route.query.tab" in src, "必须支持从深度会诊结果卡直达"
    assert "v-if=\"tab === 'mine-consults'\"" in src


def test_initiated_panel_semantics():
    src = PANEL.read_text(encoding="utf-8")
    assert "aiMyConsults({" in src
    assert "limit: PER" in src and "offset: offset.value" in src
    assert "const PER = 10" in src
    assert "setTimeout(() => {" in src and "}, 300)" in src
    assert "aiConsultClose(target.id)" in src
    assert "item.status === 'open'" in src, "只有进行中的会诊才显示结束入口"
    assert "splitConsultAiByDept" in src
    assert "startsWith('data:image/')" in src, "随单影像必须做 data:image 白名单过滤"
    assert "aiReview.mineConsults.pager" in src
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_consult_analysis_split_port():
    src = MARKDOWN.read_text(encoding="utf-8")
    assert "export function splitConsultAiByDept" in src
    assert "out.length ? out : text.trim()" in src, "无【科室】前缀时必须回退单块"


def test_copy_is_bilingual():
    sources = [CHAT_VIEW, MDT, REVIEW, PANEL]
    keys: set[str] = set()
    for path in sources:
        text = path.read_text(encoding="utf-8")
        keys.update(re.findall(r"t\('((?:aiChat|aiReview)\.[A-Za-z0-9_.]+)'", text))
        keys.update(
            k
            for k in re.findall(r"t\(`((?:aiChat|aiReview)\.[A-Za-z0-9_.]+)", text)
            if not k.endswith(".")
        )
    keys.update({f"aiChat.consult.urgency.{x}" for x in ("high", "medium", "low")})

    assert len(keys) > 55, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        stripped = line.strip()
        if not (stripped.startswith("'aiChat.consult.") or stripped.startswith("'aiReview.mineConsults.")):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{stripped}"
