"""G89 联网来源白名单：只对接权威医疗学术/专业站点。

为什么不是"全网搜索"：临床辅助不能"谁的话都听都信"。联网检索只作为**补充证据**，
来源必须可追溯、可解释；因此默认白名单只包含：

- 国内监管/学会/期刊/数据库：国家卫健委、国家药监局、中华医学会、中华医学期刊网、
  中国疾控、知网、万方、医脉通；
- 国际权威学术来源：PubMed/NCBI、WHO、NICE、Cochrane、BMJ、NEJM、Lancet、JAMA。

运维可通过 ``WEB_SEARCH_ALLOWED_DOMAINS`` **追加**（逗号分隔），但不会放开全网；
代码内白名单始终生效（``allowed_domains`` 为并集）。前端展示白名单，联网开关仍由
``agent_web_search`` 控制、默认关闭。
"""
from __future__ import annotations

from backend.config import settings

DOMESTIC = (
    "nhc.gov.cn",              # 国家卫生健康委
    "nmpa.gov.cn",             # 国家药品监督管理局
    "cma.org.cn",              # 中华医学会
    "yiigle.com",              # 中华医学期刊网
    "chinacdc.cn",             # 中国疾控中心
    "cnki.net",                # 中国知网
    "wanfangdata.com.cn",      # 万方数据
    "medlive.cn",              # 医脉通
)

ACADEMIC = (
    "pubmed.ncbi.nlm.nih.gov",
    "ncbi.nlm.nih.gov",
    "who.int",
    "nice.org.uk",
    "cochranelibrary.com",
    "bmj.com",
    "nejm.org",
    "thelancet.com",
    "jamanetwork.com",
)

DEFAULT_DOMAINS = DOMESTIC + ACADEMIC


def extra_domains() -> tuple[str, ...]:
    raw = (getattr(settings, "web_search_allowed_domains", "") or "").strip()
    return tuple(d.strip().lower() for d in raw.split(",") if d.strip())


def allowed_domains() -> tuple[str, ...]:
    """代码白名单 ∪ 运维追加（去重、保持顺序）。"""
    out = list(DEFAULT_DOMAINS)
    for d in extra_domains():
        if d not in out:
            out.append(d)
    return tuple(out)


def describe() -> dict:
    return {
        "domestic": list(DOMESTIC),
        "academic": list(ACADEMIC),
        "extra": list(extra_domains()),
        "allowedDomains": list(allowed_domains()),
        "note": "联网检索只返回上述白名单站点的结果；开启开关不代表放开全网。",
    }
