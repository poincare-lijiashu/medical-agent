"""W2.4 语料守护（评测 P0 建议落地）：把指南要点库的质量纪律变成回归锁。

① 规模锁：≥27 部指南 / ≥300 条条目（W2.4 扩容验收下限；核验修订允许小幅增删，
   跌破下限即红）；
② 结构锁：每条以「推荐意见N：」起始、来源前缀【指南名】入库形态、条目长度区间
   [30, 250] 字（过短=信息量不足，过长=应拆条）；
③ 内容卫生锁：零重复（跨指南 content-hash 去重语义在脚本内已有，此处锁条目文本）、
   禁生成残句模式（全角问号陈述句残留、“?——”拼接等——W2.4 编写时实测出现 3 处，
   已修复；本测试防复发）；
④ 落盘一致性：data/kb_guidelines_v2/ 的 md 文件集合与脚本 GUIDELINES_V2 一一对应
   （可审计文件不漂移）。
"""
from __future__ import annotations

import importlib.util
import re
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("seed_kb_docs", _ROOT / "scripts" / "seed_kb_docs.py")
skd = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(skd)

MIN_GUIDELINES = 27
MIN_ENTRIES = 300
LEN_MIN, LEN_MAX = 30, 250
_ENTRY_RE = re.compile(r"^推荐意见\d+：(.*)$")
# 生成残句/拼接痕迹模式（全角问号陈述句、"?——"半角拼接等；正常指南要点不应出现）
_ARTIFACT_PATTERNS = (re.compile(r"？"), re.compile(r"\?——"), re.compile(r"？？"))


def _all_entries() -> list[tuple[str, str, str]]:
    """[(文件名, 指南名, 条目正文), ...]——来自脚本内嵌语料（单一事实源）。"""
    out = []
    for fname, title, _year, items in skd.GUIDELINES_V2:
        for it in items:
            out.append((fname, title, it))
    return out


def test_corpus_scale_lock():
    """① 规模锁：≥27 部 / ≥300 条（W2.4 验收下限）。"""
    assert len(skd.GUIDELINES_V2) >= MIN_GUIDELINES, \
        f"指南部数 {len(skd.GUIDELINES_V2)} < {MIN_GUIDELINES}"
    entries = _all_entries()
    assert len(entries) >= MIN_ENTRIES, f"条目数 {len(entries)} < {MIN_ENTRIES}"


def test_entry_structure_and_length():
    """② 结构锁：脚本内嵌条目长度区间（「推荐意见N：」前缀由 write_v2_files 落盘时
    添加，属④落盘检查——不在脚本数据层断言）。"""
    bad_len = []
    for fname, _title, it in _all_entries():
        if not (LEN_MIN <= len(it) <= LEN_MAX):
            bad_len.append((fname, len(it), it[:40]))
    assert not bad_len, f"条目长度越界 [{LEN_MIN},{LEN_MAX}]：{bad_len[:5]}"


def test_no_duplicate_entries():
    """③a 零重复：跨指南条目文本不得完全相同（内容去重语义的上游锁）。"""
    seen: dict[str, str] = {}
    dups = []
    for fname, _title, it in _all_entries():
        if it in seen:
            dups.append((seen[it], fname, it[:40]))
        seen[it] = fname
    assert not dups, f"重复条目：{dups[:5]}"


def test_no_generation_artifacts():
    """③b 内容卫生：禁生成残句模式（？陈述句残留 / ?—— 拼接）。"""
    hits = []
    for fname, _title, it in _all_entries():
        for pat in _ARTIFACT_PATTERNS:
            if pat.search(it):
                hits.append((fname, pat.pattern, it[:50]))
    assert not hits, f"疑似生成残句（编写时自查修掉过 3 处，防复发）：{hits[:5]}"


def test_corpus_files_match_script():
    """④ 落盘一致性：data/kb_guidelines_v2/*.md 与脚本一一对应（可审计文件不漂移），
    且落盘条目均带「推荐意见N：」前缀（parse_v2_rows 入库形态的输入契约）。"""
    gdir = _ROOT / "data" / "kb_guidelines_v2"
    assert gdir.is_dir(), f"语料目录缺失：{gdir}"
    disk = {p.name for p in gdir.glob("*.md")}
    script = {fname for fname, *_ in skd.GUIDELINES_V2}
    assert disk == script, (
        f"落盘文件与脚本不一致 missing_on_disk={sorted(script - disk)} "
        f"stale_files={sorted(disk - script)}")
    missing_prefix = []
    for fname, _title, _year, items in skd.GUIDELINES_V2:
        text = (gdir / fname).read_text(encoding="utf-8")
        for i, item in enumerate(items, 1):
            if f"推荐意见{i}：{item}" not in text:
                missing_prefix.append((fname, i))
    assert not missing_prefix, f"落盘文件缺少带前缀条目：{missing_prefix[:5]}"
