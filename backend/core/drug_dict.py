"""药品字典/相互作用规则域模块（阶段1.1/1.2）。

分层：pg_store.repo（PG 真源 + JSON 兜底读写，全量同步模式）之上提供：
- 进程内缓存：load_dict/load_rules 及其派生图 alias_map/rule_map 首次调用构建后缓存，
  查询热路径（detect_drugs/check）不再逐次触达 PG/JSON 或重建映射；
- 失效钩子 invalidate()：阶段1.5 管理页增删改后调用即可让本进程缓存生效；
- save_dict/save_rules：写透 pg_store（JSON 原子写兜底 + PG 全量同步）并刷新缓存
  （派生图一并失效，防止读到旧映射）；
- 统计 stats()：数量与分类分布（管理页/运维报告用）。

数据文件（JSON 兜底真源，与 users/review_queue 同 repo 语义）：
- data/drug_dict.json / data/drug_rules.json：运行时兜底文件（seed 导入产物，随库提交）；
- data/drug_seed.json：种子源文件（curated_v2 迁移条目 + AI 生成初稿，便于人工审阅）。

legacy「禁忌」严重度语义：JSON 兜底文件保留原文（行为不变）；PG 落库按 pg_store
_norm_severity 归一为「高危」（表 CHECK 枚举 高危|中危，上轮已定）。本模块不做二次归一。
"""
from __future__ import annotations

import os
import threading

from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# JSON 兜底文件（repo 层 PG 不可用/空表时回落；测试可 monkeypatch 本模块属性隔离）
DRUG_DICT_FILE = os.path.join(BASE_DIR, "data", "drug_dict.json")
DRUG_RULES_FILE = os.path.join(BASE_DIR, "data", "drug_rules.json")
# 种子源文件（审阅用；scripts/seed_drug_dict.py 导入 PG/JSON）
DRUG_SEED_FILE = os.path.join(BASE_DIR, "data", "drug_seed.json")

_lock = threading.RLock()  # 可重入锁：alias_map/rule_map 持锁内调用 load_dict/load_rules
_cache: dict = {"dict": None, "rules": None, "alias_map": None, "rule_map": None}

# 免责声明（阶段1.4）：随字典管理 list 接口下发，前端字典/开药界面必须可见
DISCLAIMER = ("药品数据由 AI 辅助生成，临床使用前必须经执业药师核对；"
              "本系统仅供辅助决策，不构成医疗建议。")


def invalidate() -> None:
    """失效钩子（阶段1.5 管理页变更后调用）：清空进程内缓存与派生图，下次查询重读。"""
    with _lock:
        for k in _cache:
            _cache[k] = None


def load_dict() -> list[dict]:
    """药品字典全量读（带进程内缓存）：PG 可用 → PG 真源；空表/异常 → JSON 兜底。"""
    with _lock:
        if _cache["dict"] is None:
            _cache["dict"] = pg_store.load_drug_dict(DRUG_DICT_FILE)
        return list(_cache["dict"] or [])


def load_rules() -> list[dict]:
    """相互作用规则全量读（带进程内缓存），语义同 load_dict。"""
    with _lock:
        if _cache["rules"] is None:
            _cache["rules"] = pg_store.load_drug_rules(DRUG_RULES_FILE)
        return list(_cache["rules"] or [])


def _dedup(items: list[dict], key_of) -> list[dict]:
    """按业务键去重（后写生效，保留首次出现顺序）：JSON 兜底文件保持规范无重复，
    与 PG UPSERT（同键后写覆盖）语义对齐。"""
    seen: dict = {}
    order: list = []
    for i in (items or []):
        if not isinstance(i, dict):
            continue
        k = key_of(i)
        if k not in seen:
            order.append(k)
        seen[k] = i
    return [seen[k] for k in order]


def save_dict(items: list[dict]) -> None:
    """药品字典全量写（按规范名去重，后写生效）：JSON 原子写兜底 + PG 全量同步
    （不在清单中的规范名一并清除），写后刷新进程内缓存并失效别名图（阶段1.5 管理页保存路径）。"""
    with _lock:
        cleaned = _dedup(items, lambda i: str(i.get("name") or "").strip())
        pg_store.save_drug_dict(cleaned, DRUG_DICT_FILE)
        _cache["dict"] = cleaned
        _cache["alias_map"] = None


def save_rules(items: list[dict]) -> None:
    """相互作用规则全量写（按规范药对去重，后写生效）：语义同 save_dict，规则图一并失效。"""
    with _lock:
        cleaned = _dedup(items, lambda i: pg_store._norm_pair(i.get("drug_a"), i.get("drug_b")))
        pg_store.save_drug_rules(cleaned, DRUG_RULES_FILE)
        _cache["rules"] = cleaned
        _cache["rule_map"] = None


def alias_map() -> dict[str, str]:
    """别名图：规范名/别名/商品名 → 规范名（detect_drugs 用的归一映射，带缓存）。
    同名键后写覆盖（字典清单中靠后者优先），语义与 legacy ALIAS 单映射一致。"""
    with _lock:
        if _cache["alias_map"] is None:
            out: dict[str, str] = {}
            for i in load_dict():
                name = str(i.get("name") or "").strip()
                if not name:
                    continue
                out[name] = name
                for a in (i.get("aliases") or []):
                    a = str(a).strip()
                    if a:
                        out[a] = name
                for b in (i.get("brand_names") or []):
                    b = str(b).strip()
                    if b:
                        out[b] = name
            _cache["alias_map"] = out
        return dict(_cache["alias_map"])


def rule_map() -> dict[frozenset, tuple[str, str, str]]:
    """规则图：frozenset(规范药对) → (severity, mechanism, management)（带缓存）。
    键与 legacy INTERACTIONS 同构（frozenset 药对，与输入顺序无关）；
    同药对重复时后写覆盖。severity 保留存储原文（JSON 路径含 legacy「禁忌」）。"""
    with _lock:
        if _cache["rule_map"] is None:
            out: dict[frozenset, tuple[str, str, str]] = {}
            for r in load_rules():
                a, b = str(r.get("drug_a") or "").strip(), str(r.get("drug_b") or "").strip()
                if not a or not b:
                    continue
                out[frozenset((a, b))] = (
                    str(r.get("severity") or "中危"),
                    str(r.get("mechanism") or ""),
                    str(r.get("management") or ""),
                )
            _cache["rule_map"] = out
        return dict(_cache["rule_map"])


def stats() -> dict:
    """字典/规则规模与分类分布（管理页/运维报告用）：
    {drugs: n, rules: n, high: n, mid: n, categories: {类: n}, ai_marked: n}。"""
    drugs = load_dict()
    rules = load_rules()
    cats: dict[str, int] = {}
    for i in drugs:
        c = str(i.get("category") or "其他")
        cats[c] = cats.get(c, 0) + 1
    high = sum(1 for r in rules if r.get("severity") == "高危")
    ai = sum(1 for r in rules
             if "AI" in str(r.get("source") or "") or "待药师核对" in str(r.get("source") or ""))
    return {"drugs": len(drugs), "rules": len(rules), "high": high,
            "mid": len(rules) - high, "categories": cats, "ai_marked": ai}
