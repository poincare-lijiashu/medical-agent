"""规则驱动的药物相互作用/禁忌查询（可溯源，循证）。商用增强。

阶段1.1/1.2 完成：查询函数改读 drug_dict 域 repo（PG 字典表真源 + data/drug_dict.json /
data/drug_rules.json 兜底 + 进程内缓存 + invalidate 失效钩子，供阶段1.5 管理页变更后刷新）。
legacy ALIAS/INTERACTIONS 硬编码已作种子迁移（curated_v2 条目进 data/drug_seed.json，
经 scripts/seed_drug_dict.py 导入字典表/兜底文件）——别名/规则语义保持一致：
- 别名图 = 各药品条目的规范名+别名+商品名（单映射，同名后写覆盖）；
- 规则图 = 药对 frozenset → (severity, mechanism, management)，JSON 路径保留 legacy
  「禁忌」原文，PG 路径按表 CHECK 归一为高危（上轮已定）。
"""
from __future__ import annotations

from backend.core import drug_dict

# 阶段1.1：药品字典/规则 JSON 兜底文件路径（repo 层使用；由 scripts/seed_drug_dict.py 生成）。
# 此处 re-export 保持既有 import 面（pg_store 迁移/测试 monkeypatch 兼容）。
DRUG_DICT_FILE = drug_dict.DRUG_DICT_FILE
DRUG_RULES_FILE = drug_dict.DRUG_RULES_FILE


def detect_drugs(text: str) -> list[str]:
    """文本 → 识别到的规范药名列表（别名/商品名归一；保持出现顺序去重）。"""
    found = []
    low = text.lower()
    for alias, norm in drug_dict.alias_map().items():
        if (alias in text or alias.lower() in low) and norm not in found:
            found.append(norm)
    return found


_SEV_RANK = {"中危": 1, "高危": 2, "禁忌": 3}  # 严重度排序：禁忌 > 高危 > 中危


def check(question: str) -> dict:
    """返回 {answer, sources, drugs, findings, max_severity}。严重度越高越需复核。

    findings 为结构化命中列表（供调用方按 max_severity 做显式置信映射，替代对 answer 文本的子串猜测）：
      [{"pair": "华法林+布洛芬", "severity": "高危", "note": "..."}]
    max_severity ∈ {"禁忌","高危","中危","无"}（无命中为 "无"）。answer 文本格式保持不变。
    备注文本 = mechanism（机制）+ management（处置建议，非空时以「；」连接；
    legacy 迁移条目 management 为空 → 备注与 curated_v2 原文逐字一致）。"""
    drugs = detect_drugs(question)
    src = ["drug_rules:curated_v2"]
    if len(drugs) < 2:
        ans = (f"未在问题中识别到 ≥2 种可核查药物（识别到：{drugs or '无'}）。"
               "请同时给出两种药物名称以检查相互作用。本结果为规则库初筛，需药师/医师复核。")
        return {"answer": ans, "sources": src, "drugs": drugs, "findings": [], "max_severity": "无"}
    hits = []
    findings = []
    for i in range(len(drugs)):
        for j in range(i + 1, len(drugs)):
            key = frozenset([drugs[i], drugs[j]])
            rule = drug_dict.rule_map().get(key)
            if rule:
                sev, mech, mgmt = rule
                note = mech + (f"；{mgmt}" if mgmt else "")
                hits.append(f"【{drugs[i]} + {drugs[j]}｜{sev}】{note}")
                findings.append({"pair": [drugs[i], drugs[j]], "severity": sev, "note": note})
    if not hits:
        ans = f"规则库中未收录「{'、'.join(drugs)}」之间的已知相互作用；不排除未知风险，仍需药师/医师复核。"
    else:
        ans = "发现以下相互作用（循证规则库）：\n- " + "\n- ".join(hits) + "\n\n⚠️ 以上为规则库初筛，用药调整必须由执业医师/药师决定。"
    max_sev = max((f["severity"] for f in findings),
                  key=lambda s: _SEV_RANK.get(s, 0), default="无")
    return {"answer": ans, "sources": src, "drugs": drugs,
            "findings": findings, "max_severity": max_sev}
