from typing import Optional, TypedDict


class LiteratureState(TypedDict, total=False):
    session_id: str
    question: str            # 原始问题
    query: str               # 当前检索用查询（可被改写）
    original_query: str      # 原始查询（图入口注入；refine 决策参考，缺省兜底=question）
    attempts: int            # 旧路径=已检索次数；agentic 路径=已消耗的 refine 决策轮数
    queries_tried: list      # 已尝试的检索查询（retrieve 登记；供 refine 去重护栏）
    memory_context: str      # 任务3 会话记忆块（【前文对话】…，ask/stream 入口按 session_id 注入；仅 generate 拼入 prompt 头部）
    agentic_action: str      # refine 节点决策结果（rewrite/giveup），供 refine 后条件边路由
    refine_trace: list       # refine 决策轨迹 [{attempt,action,old_query,new_query,reason}]，逐轮累积，供 ask/stream 返回前端展示改写过程
    pubmed_ids: list
    evidence: list           # 精排后的证据 [{'content','source','score','rerank'}]
    sufficient: bool         # 证据是否足以作答
    draft: Optional[dict]    # 生成的草稿 {answer, citations}
    answer: Optional[dict]   # 最终 {text, confidence, sources, needs_human_review, verified}
