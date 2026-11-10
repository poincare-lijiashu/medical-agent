"""统一系统迁移（第 203 批）：LLM 模型配置（仅管理员）。

锁死的约束：

1. **密钥只写不读**：GET 只返回服务端脱敏值；表单密钥默认 password，可显式显示，
   保存后不再回显。页面不出现任何明文密钥。
2. **激活/停用要立即生效**：服务端在 activate/deactivate 后重建模型缓存；
   前端成功后重新拉取 providers，不能只改本地状态。
3. **测试不落盘**：连通性测试走 `/admin/llm/test`，只有「保存配置」才
   `POST /admin/llm/providers`。
4. **厂商参数是逃生舱**：extra_params 非空必须是 JSON 对象（前端预检 + 服务端 422
   双保险）；`suggest-extra` 只作预填建议，500ms 防抖且不覆盖用户手改。
5. **SSRF 边界在服务端**：前端只校验 http(s):// 前缀，私网拒绝由后端统一处理。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
SRC = CONSOLE / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiLlmView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_llm_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in (
        "aiLlmProviders",
        "aiLlmAdd",
        "aiLlmDelete",
        "aiLlmActivate",
        "aiLlmDeactivate",
        "aiLlmTest",
        "aiLlmSuggestExtra",
        "aiLlmKeyInventory",
    ):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "'/medical/admin/llm/providers'" in src
    assert "/medical/admin/llm/providers/${encodeURIComponent(kind)}" in src
    assert "aiDelete<{ ok?: boolean }>" in src, "删除必须走 DELETE"
    assert "'/medical/admin/llm/activate'" in src
    assert "'/medical/admin/llm/deactivate'" in src
    assert "'/medical/admin/llm/test'" in src
    assert "'/medical/admin/llm/suggest-extra'" in src
    assert "'/medical/admin/llm/key-inventory'" in src
    assert "不是明文" in src, "GET 返回的 api_key 必须标注为脱敏值"


def test_llm_wired_into_nav_and_router():
    assert "AiLlmView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-llm'" in nav
    assert "path: '/ai/llm'" in nav
    assert "group: '系统管理'" in nav
    block = nav.split("name: 'ai-llm'", 1)[1].split("},", 1)[0]
    assert "roles: ['ADMIN']" in block, "模型配置仅管理员可见"


def test_llm_view_channels_and_actions():
    src = VIEW.read_text(encoding="utf-8")
    assert "const CHANNELS: Channel[] = ['chat', 'vision']" in src
    assert "aiLlmActivate(kind, provider.id)" in src
    assert "aiLlmDeactivate(kind)" in src
    assert "aiLlmDelete(target.kind, target.provider.id)" in src
    assert "activeProvider(ch) ? t('aiLlm.channel.custom')" in src
    assert "t('aiLlm.channel.useBuiltin')" in src
    assert "await loadAll()" in src, "激活/停用/删除后必须重新拉取，不能只改本地状态"


def test_llm_view_form_and_suggestion():
    src = VIEW.read_text(encoding="utf-8")
    assert "/^https?:\\/\\//i.test(url)" in src
    assert "t('aiLlm.form.needModel')" in src
    assert "Array.isArray(parsed)" in src, "extra_params 必须是 JSON 对象而不是数组"
    assert "setTimeout(() => void fetchSuggestion(), 500)" in src, "厂商预设 500ms 防抖"
    assert "current === lastSuggested" in src, "不覆盖用户手改的额外参数"
    assert "JSON.stringify(JSON.parse(text), null, 2)" in src, "格式化按钮"
    assert ":type=\"showKey ? 'text' : 'password'\"" in src
    assert "aiLlmTest(body)" in src
    assert "aiLlmAdd(body)" in src


def test_llm_view_key_inventory_and_security():
    src = VIEW.read_text(encoding="utf-8")
    assert "aiLlmKeyInventory()" in src
    assert "inventory.rotationReady" in src
    assert "inventory.plaintext" in src
    assert "inventory.legacyV1" in src
    assert "inventory.keysOutOfRing" in src
    assert "{{ p.api_key || '—' }}" in src, "列表只能显示脱敏密钥"
    assert "v-model=\"form.api_key\"" in src, "密钥只在新增表单中以密码框提交"
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_llm_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiLlm\.[A-Za-z0-9_.]+)'", src))
    keys.update(k for k in re.findall(r"t\(`(aiLlm\.[A-Za-z0-9_.]+)", src) if not k.endswith("."))
    keys.update({"aiLlm.channel.chat", "aiLlm.channel.vision"})
    keys.update({"nav.ai-llm.title", "nav.ai-llm.hint"})

    assert len(keys) > 60, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiLlm."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
