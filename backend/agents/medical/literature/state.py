from typing import Optional, TypedDict


class LiteratureState(TypedDict, total=False):
    session_id: str
    question: str            # 原始问题
    query: str               # 当前检索用查询（可被改写）
    attempts: int            # 检索-打分重试次数
    pubmed_ids: list
    evidence: list           # 精排后的证据 [{'content','source','score','rerank'}]
    sufficient: bool         # 证据是否足以作答
    draft: Optional[dict]    # 生成的草稿 {answer, citations}
    answer: Optional[dict]   # 最终 {text, confidence, sources, needs_human_review, verified}
