"""统一系统迁移（第 190 批）：AI 概览进入控制台，并与控制台共用同一会话。

设计约束（本文件锁死）：

1. 界面迁入控制台，接口仍打 AI 中台 `/api/v1` —— 必须走 `aiGet`（同一枚 Bearer、
   同一套 401 刷新/登出），不能用 `get()`（那是 Java `{code,data}` 信封通道）；
2. AI 概览必须真正进侧栏「AI 智能」分组并可深链访问——只加路由不加分组，
   整组不会渲染（第 126/178 批踩过两次）；
3. 审计人话表只剩控制台一份（`frontend-console/src/utils/audit.ts`）：迁移期靠
   「两份拷贝键集一致」兜底，旧前端下线后改为锁控制台表的覆盖规模与关键事件，
   防迁移收尾时被顺手删减。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
AI_API = CONSOLE / "src/api/ai.ts"
VIEW = CONSOLE / "src/views/AiOverviewView.vue"
NAV = CONSOLE / "src/navigation.ts"
ROUTER = CONSOLE / "src/router/index.ts"
AUDIT_CONSOLE = CONSOLE / "src/utils/audit.ts"


def test_ai_api_reuses_session_channel():
    src = AI_API.read_text(encoding="utf-8")
    assert "aiGet<AiOverview>('/medical/overview'" in src, "AI 概览必须走 aiGet 通道"
    assert "from './http'" in src
    assert "get<AiOverview>" not in src, "Python 端点返回裸 JSON，不能用 Java 信封通道 get()"


def test_view_wired_into_nav_and_router():
    assert "AiOverviewView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-overview'" in nav
    assert "group: 'AI 智能'" in nav
    assert "'AI 智能'," in nav, "分组未登记到 NAV_GROUPS 会导致整组不渲染"
    view = VIEW.read_text(encoding="utf-8")
    assert "humanizeAudit" in view, "最近活动必须走统一人话化"
    assert "aiOverview()" in view
    assert "loadErr" in view, "必须有错误态（403/网络异常不能白屏）"
    assert "kbUnknown" in view, "必须保留 kb_docs=-1 的未知态"


def _map_keys(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return set(re.findall(r"^    ([a-z_]+): \(\) =>", text, re.MULTILINE))


def test_audit_humanize_map_is_complete():
    """控制台是唯一人话表：规模不回退，关键事件族不得被删。"""
    keys = _map_keys(AUDIT_CONSOLE)
    assert len(keys) > 100, f"解析失败：仅提取到 {len(keys)} 条映射"
    for must in ("answered", "answer", "resolved_approved", "resolved_rejected",
                 "submitted", "reviewed", "config_toggle", "consult_created",
                 "drug_dict_changed", "audit_verify"):
        assert must in keys, f"人话表缺关键事件映射：{must}"
