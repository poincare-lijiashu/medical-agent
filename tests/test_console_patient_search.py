"""全局患者检索 + 深链打开医嘱上下文（第 219 批）。

商业系统的“全局搜索”不能只搜菜单。本批把患者检索并入命令面板（Ctrl/Cmd+K）：
2 字起、300ms 防抖、按病案号/姓名分别走对应接口；选中后深链到医嘱工作台并
自动打开该患者上下文。

锁死：
1. 权限门：没有 `clinical:visit:read` 的账号不发请求、不出现患者分组；
2. 两种检索：纯数字走病案号，其余走姓名；
3. 结果只来自服务端，不做本地二次过滤（服务端已匹配）；
4. 深链：`/orders?patientId=...`，医嘱页必须真正读取并选中；`visitId` 可选；
5. 失败/检索中不能显示“没有匹配的命令”，要有独立状态。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
PALETTE = SRC / "components/ui/CommandPalette.vue"
ORDERS = SRC / "views/OrdersWorkbenchView.vue"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_palette_searches_patients_with_permission_gate():
    src = _read(PALETTE)
    assert "empiApi" in src and "searchByNo" in src and "searchByName" in src
    assert "hasPermission('clinical:visit:read')" in src, "患者检索必须做权限门"
    assert "keyword.length < 2" in src, "不足 2 字不应请求"
    assert "setTimeout(() => void searchPatients(value.trim()), 300)" in src, "300ms 防抖"
    assert "/^\\d+$/.test(keyword)" in src, "纯数字走病案号检索"


def test_palette_deep_links_to_orders():
    src = _read(PALETTE)
    assert "path: '/orders', query: { patientId: p.id }" in src
    assert "t('palette.groupPatient')" in src
    assert "patientStatus" in src, "检索中/失败要有独立状态"
    assert "!patientStatus" in src, "检索中不能显示“没有匹配的命令”"
    assert "patientSearching" in src and "patientError" in src


def test_orders_consumes_patient_deep_link():
    src = _read(ORDERS)
    assert "useRoute" in src
    assert "route.query.patientId" in src
    assert "await empiApi.patient(patientId)" in src
    assert "await pickPatient(patient)" in src, "必须真正打开患者上下文，而不是只填搜索框"
    assert "route.query.visitId" in src and "pickVisit(wanted)" in src


def test_patient_search_copy_is_bilingual():
    zh, en = _read(ZH), _read(EN)
    for key in ("palette.groupPatient", "palette.patients.searching", "palette.patients.error"):
        assert f"'{key}':" in zh, f"中文缺 {key}"
        assert f"'{key}':" in en, f"英文缺 {key}"
    for line in en.splitlines():
        if line.strip().startswith("'palette.patients."):
            value = line.split("':", 1)[-1]
            assert not re.search(r"[\u4e00-\u9fff]", value), f"英文混入中文：{line.strip()}"
