"""统一系统迁移（第 204 批）：Agent 编排治理总览（仅管理员）。

锁死的约束：

1. **治理视图不执行临床推理**：这一页只做开关、路由预览、目录、工具、合规与
   第二意见；技能卡 / Bot / 团队的增删改在后续批次。
2. **开关是 CAS 写**：PUT 必须带 `expected`（切换前的值），409 时回读真实值，
   不能静默覆盖另一名管理员的修改；成功后必须重新拉取，不能只改本地状态。
3. **高风险开关二次确认**：`risk === 'high'` 的开关，开启前必须弹确认框
   （数据可能出网），关闭不需要确认。
4. **路由预览可解释**：新请求先清空旧结果；失败就地显示；结果展示主责智能体、
   置信度、策略、最大跳数与候选，不只显示一个名字。
5. **第二意见有硬门槛**：可用合规模型 < 2 时按钮禁用并说明原因；两个模型必须
   不同；不做投票 / 裁判；结果恒提示人工复核。
6. **接口走 AI 中台裸 JSON**：`aiGet/aiPost/aiPut`，禁止 `fetch(`、禁止 Java 信封
   `get()/post()`、禁止 Element Plus。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
SRC = CONSOLE / "src"
AI_API = SRC / "api/ai.ts"
HTTP = SRC / "api/http.ts"
VIEW = SRC / "views/AiAgentView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_agent_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in (
        "aiAgentFlags",
        "aiAgentSetFlag",
        "aiAgentRoute",
        "aiAgentModelPolicy",
        "aiAgentWebPolicy",
        "aiAgentSecondOpinion",
    ):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"

    assert "'/agent/flags'" in src
    assert "'/agent/route'" in src
    assert "'/agent/model-policy'" in src
    assert "'/agent/web-policy'" in src
    assert "'/agent/second-opinion'" in src
    assert "aiPut<" in src, "运行时开关必须走 PUT（幂等更新）"
    assert "expected," in src, "开关必须带 expected 做 CAS"

    http = HTTP.read_text(encoding="utf-8")
    assert "export function aiPut" in http
    assert "method: 'PUT'" in http


def test_agent_wired_into_nav_and_router():
    assert "AiAgentView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-agent'" in nav
    assert "path: '/ai/agent'" in nav
    block = nav.split("name: 'ai-agent'", 1)[1].split("},", 1)[0]
    assert "group: '系统管理'" in block
    assert "roles: ['ADMIN']" in block, "Agent 编排仅管理员可见"
    groups = nav.split("export const NAV_GROUPS", 1)[1]
    assert "'系统管理'" in groups, "分组未登记会导致整组不渲染"


def test_agent_view_flags_cas_and_high_risk_confirm():
    src = VIEW.read_text(encoding="utf-8")
    assert "aiAgentSetFlag(flag.key, value, flag.enabled)" in src, "必须带切换前的值做 CAS"
    assert "e instanceof AiRequestError && e.status === 409" in src, "409 必须单独处理"
    assert "await loadFlags()" in src, "切换后必须回读服务端真实值"
    assert "if (!flag.enabled && flag.risk === 'high')" in src, "高风险开关开启前必须确认"
    assert "pendingFlag.value = flag" in src
    assert "role=\"switch\"" in src
    assert ":aria-checked=" in src


def test_agent_view_route_preview_is_explainable():
    src = VIEW.read_text(encoding="utf-8")
    # 新请求先清空旧结果，避免把上一次分派误读为本次
    assert "routeResult.value = null" in src
    assert "routeErr.value = ''" in src
    assert "routeResult.value = await aiAgentRoute(question, routeMulti.value)" in src
    for key in (
        "aiAgent.route.confidence",
        "aiAgent.route.hops",
        "aiAgent.route.fallback",
        "aiAgent.route.reason",
        "aiAgent.route.candidates",
    ):
        assert f"t('{key}')" in src, f"路由结果缺少 {key}"


def test_agent_view_catalog_tools_and_policy():
    src = VIEW.read_text(encoding="utf-8")
    assert "aiAgentCatalog()" in src
    assert "selectedAgentId" in src
    assert "aiAgentModelPolicy()" in src
    assert "aiAgentWebPolicy()" in src
    assert "policy?.providers || []" in src
    assert "webPolicy?.allowedDomains?.length" in src
    assert "tool.externalEgress" in src, "出网工具必须显式标记"
    assert "tool.phi === 'patient'" in src, "患者数据边界必须显式标记"
    assert "setTimeout(() => {" in src and "}, 300)" in src, "工具检索 300ms 防抖"


def test_agent_view_second_opinion_guards():
    src = VIEW.read_text(encoding="utf-8")
    assert "allowedProviders.value.length >= 2" in src
    assert ":disabled=\"!canSecondOpinion || opinionBusy\"" in src
    assert "providerA.value === providerB.value" in src, "两个模型不能相同"
    assert "aiAgentSecondOpinion(question, ids)" in src
    assert "providerIds: providerIds.length ? providerIds : undefined" in AI_API.read_text(encoding="utf-8")
    assert "t('aiAgent.second.review')" in src, "结果必须提示人工复核"
    assert "level ===" in src and "divergenceClass" in src


def test_agent_view_uses_ai_channel_only():
    src = VIEW.read_text(encoding="utf-8")
    assert "<el-" not in src
    assert "element-plus" not in src
    assert "fetch(" not in src
    assert "await get(" not in src and "await post(" not in src, "AI 端点不能走 Java 信封通道"


def test_agent_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiAgent\.[A-Za-z0-9_.{}]+)'", src))
    keys.update(
        {
            "nav.ai-agent.title",
            "nav.ai-agent.hint",
            "aiAgent.class.local",
            "aiAgent.class.domestic",
            "aiAgent.class.foreign",
            "aiAgent.class.unknown",
        }
    )

    assert len(keys) > 80, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiAgent."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
