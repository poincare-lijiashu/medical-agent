"""统一系统迁移（第 202 批）：AI 开药工作台（医生侧）。

锁死的约束：

1. **AI 只推字典内药**：后端把幻觉药丢弃；前端字典搜索/内联判定用同一份
   `name + aliases + brand_names`，避免把别名当成"字典外"。
2. **高危组合不静默放行**：药单仍含高危组合时必须填联用理由；改写高危处方
   （原 `forced_high_risk`）同样要求理由，避免必然 422。
3. **字典外药可提交但必须留痕**：行内理由 ≥5 字，随 `note` 上送；手动添加前
   必须确认"交药剂科重点审核"。
4. **提交 vs 改写**：`editId` 存在走 `/rewrite`（不产生新处方），否则走新建；
   成功后清空表单、跳到「我的处方」第 1 页。
5. **离开保护**：病例或药单非空时，站内路由与关闭页面都要拦；清空是程序性
   变更，用 suppress 标记避免把 dirty 又置回。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
SRC = CONSOLE / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiRxView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_rx_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiRxSuggest", "aiRxCreate", "aiRxRewrite", "aiRxMine"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "'/medical/prescriptions/suggest'" in src
    assert "'/medical/prescriptions'" in src
    assert "/rewrite" in src
    assert "'/medical/prescriptions/mine'" in src
    assert "contraindication_reason" in src


def test_rx_wired_into_nav_and_router():
    assert "AiRxView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-rx'" in nav
    assert "path: '/rx'" in nav
    assert "group: '临床诊疗'" in nav
    block = nav.split("name: 'ai-rx'", 1)[1].split("},", 1)[0]
    assert "roles: ['DOCTOR']" in block, "开药工作台仅医生可见（后端 require_role doctor）"


def test_rx_view_suggestion_and_validation():
    src = VIEW.read_text(encoding="utf-8")
    assert "const CASE_MIN = 20" in src and "const CASE_MAX = 5000" in src
    assert "{ ...s, pick: true }" in src, "AI 推荐默认全选"
    assert "d.source !== 'ai'" in src, "新一轮推荐只替换上一轮 AI 药，保留手动/字典药"
    assert "addSuggestion(i)" in src
    assert "c.pair || []" in src and "names.has(n)" in src, "只约束仍留在药单内的高危组合"
    assert "liveConflicts.value.length > 0 || editForced.value" in src
    assert "ooIncomplete" not in src  # 防变量拼写回归
    assert "oodIncomplete" in src
    assert "d.ood.trim().length < 5" in src


def test_rx_view_dict_and_ood_flow():
    src = VIEW.read_text(encoding="utf-8")
    assert "d.name === key" in src
    assert "(d.aliases || []).includes(key)" in src
    assert "(d.brand_names || []).includes(key)" in src
    assert ".slice(0, 12)" in src, "字典搜索结果最多 12 条"
    assert "pendingAdd.value = row" in src, "字典外手动加药必须先确认"
    assert "aiRx.ood.title" in src
    assert "note: d.ood.trim() ? d.ood.trim() : d.note || ''" in src
    assert "aiRx.selected.oodOptional" in src, "字典未加载时仍要给字典外药留理由入口"
    assert "aiRx.add.dictUnknownToast" in src, "字典未加载不能假装能判定字典内外"


def test_rx_view_submit_and_mine():
    src = VIEW.read_text(encoding="utf-8")
    assert "if (editId.value) await aiRxRewrite(editId.value, body)" in src
    assert "else await aiRxCreate(body)" in src
    assert "tab.value = 'mine'" in src
    assert "minePage.value = 0" in src
    assert "const PER = 12" in src
    assert "offset: minePage.value * PER" in src
    assert "}, 300)" in src, "我的处方检索 300ms 防抖"
    assert "item.status === 'rejected'" in src
    assert "startRewrite(item)" in src
    assert "editForced.value = Boolean(item.forced_high_risk)" in src


def test_rx_view_leave_protection():
    src = VIEW.read_text(encoding="utf-8")
    assert "onBeforeRouteLeave" in src
    assert "beforeunload" in src
    assert "suppressDirty" in src
    assert "aiRx.leave.title" in src
    assert "aiRx.clear.title" in src
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_rx_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiRx\.[A-Za-z0-9_.]+)'", src))
    keys.update(k for k in re.findall(r"t\(`(aiRx\.[A-Za-z0-9_.]+)", src) if not k.endswith("."))
    keys.update({f"aiRx.status.{x}" for x in ("pending_pharm", "approved", "rejected", "draft", "unknown")})
    keys.update({"nav.ai-rx.title", "nav.ai-rx.hint"})

    assert len(keys) > 75, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiRx."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
