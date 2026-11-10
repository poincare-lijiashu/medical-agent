"""控制台 API 403 受限态回归锁。

403 是"已登录但角色/权限不足"，不是服务故障。若把它塞进 `LoadError`，
用户会看到"数据加载失败 / 重试"，误以为是系统坏了而反复点击重试。
本文件锁死以下约束：

1. `http.ts` 提供 `isForbidden`，只认 `status === 403`（`ApiError` 与 `AiRequestError` 都带 status）；
2. `PermissionDenied.vue` 是独立受限态：role=alert、可返回上一页/驾驶舱，且不提供重试；
3. 13 个页面主数据加载在写 `loadErr` 前先分流 403，并渲染 `<PermissionDenied>`；
4. PageHeader 之后的**全部业务内容**（LoadError / KPI / 表格 / 弹窗）收在
   `<template v-else>` 内，与受限态互斥：403 时不能残留空表与 0 值 KPI 被误读为业务事实；
5. 中英文案 key 齐备，英文文案不含中文。

操作失败（提交/审批/生成）仍走 toast，不属于本文件约束范围。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
VIEWS = SRC / "views"
HTTP = SRC / "api/http.ts"
COMPONENT = SRC / "components/ui/PermissionDenied.vue"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"

PAGES = (
    "AiAgentView.vue",
    "AiSkillsView.vue",
    "AiBotsView.vue",
    "AiTeamsView.vue",
    "AiOpsView.vue",
    "AiLibraryView.vue",
    "AiLabView.vue",
    "AiDataView.vue",
    "AiKbView.vue",
    "AiLlmView.vue",
    "AiReviewView.vue",
    "SystemView.vue",
    "PharmacyConfigView.vue",
)

FORBIDDEN_KEYS = (
    "forbidden.title",
    "forbidden.desc",
    "forbidden.back",
    "forbidden.home",
)

CJK = re.compile(r"[\u4e00-\u9fff]")
# 并行加载页面用 `isForbidden(r.reason)`，串行加载页面用 `isForbidden(e)`。
IS_FORBIDDEN_CHECK = re.compile(r"isForbidden\((?:e|r\.reason)\)")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_is_forbidden_helper_only_matches_403():
    src = _read(HTTP)
    assert "export function isForbidden(e: unknown): boolean" in src, "http.ts 未导出 isForbidden"
    assert re.search(r"status === 403", src), "isForbidden 必须只认 HTTP 403"


def test_permission_denied_keeps_accessibility_and_actions():
    src = _read(COMPONENT)
    assert 'role="alert"' in src, "PermissionDenied 必须是 role=alert（读屏可播报）"
    assert "router.back()" in src, "返回上一页必须调用 router.back()"
    assert "history.length <= 1" in src, "无历史时需回退到驾驶舱，避免退出应用"
    assert 'to="/dashboard"' in src, "必须提供返回驾驶舱入口"
    for key in FORBIDDEN_KEYS:
        assert f"t('{key}')" in src, f"PermissionDenied 未使用 {key}"
    assert "LoadError" not in src, "受限态不能复用加载失败组件（会带出重试按钮）"


def test_pages_split_403_from_load_failure():
    for name in PAGES:
        src = _read(VIEWS / name)
        assert re.search(r"import \{[^}]*isForbidden[^}]*\} from '@/api/http'", src), (
            f"{name} 未从 '@/api/http' 引入 isForbidden"
        )
        assert "import PermissionDenied from '@/components/ui/PermissionDenied.vue'" in src, (
            f"{name} 未引入 PermissionDenied"
        )
        assert "<PermissionDenied" in src, f"{name} 模板缺少受限态"
        assert 'v-if="forbidden"' in src, f"{name} 的受限态没有绑定 forbidden"
        assert "const forbidden = ref(false)" in src, f"{name} 缺少 forbidden 状态"
        assert "forbidden.value = false" in src, f"{name} 重新加载时未清空受限态"
        assert "forbidden.value = true" in src or "forbidden.value = denied" in src, (
            f"{name} 的 403 分支从未置位"
        )
        assert IS_FORBIDDEN_CHECK.search(src), f"{name} 主数据加载未判断 403"


def test_pages_do_not_feed_403_into_load_error_first():
    """反向锁：LoadError 必须留在 v-else 分支内，403 先被 forbidden 截走。"""
    for name in PAGES:
        src = _read(VIEWS / name)
        denied = src.index('<PermissionDenied v-if="forbidden" />')
        assert src.index("<template v-else>", denied) < src.index("<LoadError", denied), (
            f"{name} 的 LoadError 没有被 v-else 分支挡在受限态之外"
        )


def test_pages_hide_all_business_content_when_forbidden():
    """403 时只剩标题栏与受限态：PermissionDenied 之后必须整体进 v-else。

    用 `<template v-else>`（而非 div）包裹：不引入额外盒子，`.page` 的 flex/gap 布局不变；
    AppModal 等 Teleport 组件也在同一分支内，避免结构分裂。
    """
    denied = '<PermissionDenied v-if="forbidden" />'
    for name in PAGES:
        src = _read(VIEWS / name)
        assert denied in src, f"{name} 的受限态写法不统一"
        start = src.index(denied)
        after = src[start + len(denied):].lstrip()
        assert after.startswith("<template v-else>"), f"{name} 的受限态之后没有紧跟 v-else 分支"
        assert "<template v-else>" in src[start:], f"{name} 的受限态之后缺少 v-else 包裹"
        template_src = src[: src.index("<script")]
        assert "!forbidden" not in template_src, f"{name} 仍残留 !forbidden 双重条件"


def test_forbidden_copy_is_bilingual_and_pure_english():
    zh = _read(ZH)
    en = _read(EN)
    for key in FORBIDDEN_KEYS:
        assert f"'{key}':" in zh, f"zh-CN 缺少 {key}"
        assert f"'{key}':" in en, f"en 缺少 {key}"

    assert "'forbidden.title': '无访问权限'" in zh
    assert "'forbidden.desc': '当前账号无权查看此页面。如岗位调整后仍无法访问，请联系系统管理员。'" in zh
    assert "'forbidden.back': '返回上一页'" in zh
    assert "'forbidden.home': '返回驾驶舱'" in zh

    assert "'forbidden.title': 'No access'" in en
    assert (
        "'forbidden.desc': 'Your account cannot view this page. "
        "Contact an administrator if your role recently changed.'" in en
    )
    assert "'forbidden.back': 'Go back'" in en
    assert "'forbidden.home': 'Back to dashboard'" in en

    for line in en.splitlines():
        if re.match(r"\s*'forbidden\.", line):
            assert not CJK.search(line), f"英文文案仍含中文：{line.strip()}"
