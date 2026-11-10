"""统一系统迁移（第 196 批）：AI 数据面板 + 审计流进入控制台。

锁死的约束：

1. **通道**：`/medical/admin/*` 是 AI 中台裸 JSON；CSV 导出是字节流 + 响应头
   （X-Export-Rows / X-Export-Truncated）。前者走 `aiGet/aiPost`，后者必须走
   `aiDownload`——用信封通道解析二进制会在运行期直接炸。
2. **过滤必须下推**：操作者/事件/动作/日期区间由服务端精确过滤后再分页；
   只在前端过滤会把"总条数"和"下一页可用性"算错。
3. **截断必须显式告知**：全量导出上限 10000 行，超出时不能静默丢行。
4. **审计链校验**要展示 firstBroken（文件/行/seq），否则"链异常"无法定位。
5. **角色**：仅管理员可见；账号/角色/院区管理保留在控制台「系统管理」，不重复造入口。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
AI_API = CONSOLE / "src/api/ai.ts"
HTTP = CONSOLE / "src/api/http.ts"
VIEW = CONSOLE / "src/views/AiDataView.vue"
NAV = CONSOLE / "src/navigation.ts"
ROUTER = CONSOLE / "src/router/index.ts"
ZH = CONSOLE / "src/i18n/zh-CN.ts"
EN = CONSOLE / "src/i18n/en.ts"


def test_ai_data_api_channels():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiAdminData", "aiAuditPage", "aiAuditFacets", "aiAuditVerify", "aiToggleFlag", "aiAppLogs"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "'/medical/admin/data'" in src
    assert "'/medical/admin/audit'" in src
    assert "'/medical/admin/audit/facets'" in src
    assert "'/medical/admin/audit/verify'" in src
    assert "'/medical/admin/config-toggle'" in src
    assert "'/medical/admin/logs'" in src
    # 裸 JSON 端点不得混用 Java 信封通道
    assert "get<AiAdminData>" not in src
    assert "post<{ ok?: boolean; key?: string" not in src

    http_src = HTTP.read_text(encoding="utf-8")
    assert "export async function aiDownload(" in http_src, "导出必须走专用下载通道"
    assert "X-Export-Rows" in http_src and "X-Export-Truncated" in http_src, "导出行数/截断标记必须从响应头读取"


def test_ai_data_wired_into_nav_and_router():
    assert "AiDataView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-data'" in nav
    assert "group: 'AI 智能'" in nav
    assert "roles: ['ADMIN']" in nav, "数据面板仅管理员可见"


def test_ai_data_view_keeps_compliance_semantics():
    src = VIEW.read_text(encoding="utf-8")
    # 过滤下推到服务端，且导出复用同一套参数
    assert "aiAuditQuery(filter" in src
    assert "aiAuditPage(filter, auditOffset.value)" in src
    assert "aiDownload('/medical/admin/audit/export', aiAuditQuery(filter))" in src
    # 截断必须显式告知
    assert "res.truncated" in src and "exportTruncated" in src
    # 链校验定位信息
    assert "firstBroken" in src
    # 留痕开关必须二次确认（不能一点就切）
    assert "askToggleFlag" in src and "confirmToggleFlag" in src
    assert "aiToggleFlag('qc_auto_sign_full'" in src
    # 日志高亮（ERROR / WARNING）+ 只读
    assert "log-err" in src and "log-warn" in src
    assert "aiAppLogs" in src
    # kb_docs=-1 sentinel
    assert "Number(v) < 0 ? '…'" in src
    # 不引入 Element Plus / 不直连 fetch
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_ai_data_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys = set(re.findall(r"t\('(aiData\.[A-Za-z.]+)'", src))
    assert len(keys) > 70, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"
