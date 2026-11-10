"""统一系统迁移（第 195 批）：药事配置进入控制台。

旧界面把「药品字典 / 相互作用规则」两个维护页塞在审核中心的 tab 里——那是药事主数据，
不是审核动作。本批把它们迁到「药事管理 → 药事配置」，并锁死以下约束：

1. **通道**：`/medical/drug/dict` 与 `/admin/drug/*` 都是 AI 中台裸 JSON，必须走
   `aiGet/aiPost`；混用 Java 信封通道会在运行期抛「响应格式不正确」。
2. **定位键不可变**：药品按规范名、规则按规范药对定位（与输入顺序无关）。
   编辑态必须锁定定位键，否则一次"改名"会变成新增 + 旧记录残留。
3. **审校语义**：批量标记的 key 是 `drug_a||drug_b`，审校人由后端取当前登录名
   （前端不传名字，防冒充）；撤销审校走同一端点 decision=unreviewed。
4. **高危语义**：severity 只接受「高危 / 中危」两档（后端 422 双保险），
   高危 = 开药强制阻断，中危 = 警示。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
AI_API = CONSOLE / "src/api/ai.ts"
VIEW = CONSOLE / "src/views/PharmacyConfigView.vue"
NAV = CONSOLE / "src/navigation.ts"
ROUTER = CONSOLE / "src/router/index.ts"
ZH = CONSOLE / "src/i18n/zh-CN.ts"
EN = CONSOLE / "src/i18n/en.ts"


def test_pharmacy_config_api_reuses_session_channel():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiDrugDict", "aiSaveDrugEntry", "aiSaveDrugRule", "aiMarkDrugRules"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "aiGet<AiDrugDict>('/medical/drug/dict'" in src
    assert "aiPost<{ ok?: boolean }>('/medical/admin/drug/dict'" in src
    assert "aiPost<{ ok?: boolean }>('/medical/admin/drug/rules'" in src
    assert "'/medical/admin/drug/rules/review'" in src
    # 裸 JSON 端点不得混用 Java 信封通道
    assert "get<AiDrugDict>" not in src
    assert "post<{ ok?: boolean }>" not in src


def test_pharmacy_config_wired_into_nav_and_router():
    assert "PharmacyConfigView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'pharmacy-config'" in nav
    assert "group: '药事管理'" in nav
    assert "roles: ['ADMIN', 'PHARMACIST']" in nav, "药事配置只对药剂科 / 管理员有意义"


def test_pharmacy_config_keeps_data_integrity_semantics():
    src = VIEW.read_text(encoding="utf-8")
    # 编辑态锁定定位键
    assert ':readonly="Boolean(editingDrugName)"' in src, "药品规范名编辑态必须锁定"
    assert ':readonly="Boolean(editingPair)"' in src, "药对编辑态必须锁定"
    # 严重度两档（后端 422 双保险）
    assert '<option value="高危">' in src and '<option value="中危">' in src
    # 批量审校键编码与两种 decision
    assert "`${r.drug_a}||${r.drug_b}`" in src
    assert "markRules('approved')" in src and "markRules('unreviewed')" in src
    # 删除必须走确认弹窗（不能一点就删）
    assert "askRemoveDrug" in src and "askRemoveRule" in src and "confirmRemove" in src
    assert "<AppModal" in src
    # 字典可达数千条：必须本地分页
    assert "const PER = 20" in src
    # 错误态 + 不引入 Element Plus / 不直连 fetch
    assert "loadErr" in src
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_pharmacy_config_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys = set(re.findall(r"t\('(pharmCfg\.[A-Za-z.]+)'", src))
    assert len(keys) > 80, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"
