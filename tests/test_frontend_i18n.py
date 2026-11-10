"""前端 i18n 静态锁（第 178 批）：文案字典与导航必须始终对齐。

为什么需要：`en.ts` 虽然有 `Record<MessageKey, string>` 约束（漏翻会在 `npm run typecheck` 报错），
但「导航条目必须被字典覆盖」这件事 typecheck 管不到——`t()` 找不到 key 时会静默回落中文，
英文环境里就会冒出中文标题，且没有任何报错。本文件把这条约定变成可执行的断言：

1. `navigation.ts` 里每个导航条目都有 `nav.<name>.title` / `nav.<name>.hint`；
2. 每个导航分组都有 `nav.group.<分组名>`；
3. 中英字典 key 集合完全一致，且各自没有重复 key（重复会让后一条静默覆盖前一条）；
4. 英文字典的值里不得残留中文（个别有意保留中文的 key 见 `CJK_ALLOWED`）。
"""
from __future__ import annotations

import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAVIGATION = os.path.join(REPO, "frontend-console", "src", "navigation.ts")
ZH = os.path.join(REPO, "frontend-console", "src", "i18n", "zh-CN.ts")
EN = os.path.join(REPO, "frontend-console", "src", "i18n", "en.ts")

# 语言切换项在英文界面下本来就要显示「中文」，是唯一允许出现中文字面量的 key
CJK_ALLOWED = {"shell.language"}

CJK = re.compile(r"[\u4e00-\u9fff]")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def _nav_items() -> list[str]:
    src = _read(NAVIGATION)
    return re.findall(r"^\s{4}name: '([^']+)',", src, re.M)


def _nav_groups() -> list[str]:
    src = _read(NAVIGATION)
    return re.findall(r"^\s{4}group: '([^']+)',", src, re.M)


def _dict_entries(path: str) -> dict[str, str]:
    """极简解析：字典是扁平对象，key 统一缩进两格并带单引号。"""
    src = _read(path)
    return dict(re.findall(r"^\s{2}'([^']+)':\s*(?:'((?:[^'\\]|\\.)*)')?,", src, re.M))


def test_navigation_entries_have_copy():
    names = _nav_items()
    assert len(names) >= 40, f"导航条目数异常（{len(names)}），解析规则可能已失效"
    zh, en = _dict_entries(ZH), _dict_entries(EN)
    missing = [
        f"{prefix}{name}"
        for name in names
        for prefix in (f"nav.{name}.title", f"nav.{name}.hint")
        if prefix not in zh or prefix not in en
    ]
    assert not missing, f"导航文案缺少中/英文 key：{sorted(set(missing))}"


def test_navigation_groups_have_copy():
    groups = sorted(set(_nav_groups()))
    zh, en = _dict_entries(ZH), _dict_entries(EN)
    missing = [f"nav.group.{g}" for g in groups if f"nav.group.{g}" not in zh or f"nav.group.{g}" not in en]
    assert not missing, f"导航分组缺少中/英文 key：{missing}"


def test_locale_dicts_have_identical_keys():
    zh, en = _dict_entries(ZH), _dict_entries(EN)
    assert zh, "中文字典解析为空，正则可能与文件结构脱节"
    assert set(zh) == set(en), (
        f"中英字典 key 不一致：仅中文 {sorted(set(zh) - set(en))}；仅英文 {sorted(set(en) - set(zh))}"
    )


def test_locale_dicts_have_no_duplicate_keys():
    for path in (ZH, EN):
        keys = re.findall(r"^\s{2}'([^']+)':", _read(path), re.M)
        dupes = sorted({k for k in keys if keys.count(k) > 1})
        assert not dupes, f"{os.path.basename(path)} 存在重复 key（后者会静默覆盖前者）：{dupes}"


def test_english_dict_is_actually_translated():
    en = _dict_entries(EN)
    leftovers = [k for k, v in en.items() if k not in CJK_ALLOWED and CJK.search(v or "")]
    assert not leftovers, f"英文字典里仍有中文字面量（漏翻）：{leftovers}"
