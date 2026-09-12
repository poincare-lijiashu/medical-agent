LITERATURE_SYSTEM = (
    "你是循证医学文献助手。所有结论必须可溯源到给定的文献；"
    "证据不足时必须明确说明并降低置信度，禁止编造文献或臆测结论。"
)

# 生成草稿：只依据证据作答，逐条引用
LITERATURE_ANSWER = (
    "基于以下证据回答问题，必须给出引用且只引用提供的来源编号。\n"
    "证据(含来源)：{local}\n"
    "PubMed IDs：{pubmed}\n"
    "问题：{question}\n\n"
    "严格返回 JSON（不要 markdown 包裹）：\n"
    '{{"answer":"<2-5 句、可核验的中文回答>",'
    '"citations":["来源编号，如 KB:sample_guideline 或 PMID:123"]}}'
)

# 充分性打分器：判断证据是否足以回答，不足则给改写查询
LITERATURE_GRADE = (
    "判断下列证据是否足以严谨回答该临床问题。若关键信息缺失，给出更精准的检索查询。\n"
    "注意：仅当证据完全无法支撑问题的核心意图（证据未涉及问题所问的机制/标准/因果关系，"
    "属答非所问）时才判不充分；证据主题相关且包含可回答问题的具体信息时应当判充分，"
    "不要因证据未覆盖问题的所有细节而保守判否。\n"
    "问题：{question}\n"
    "证据摘要：{evidence}\n\n"
    "严格返回 JSON：\n"
    '{{"sufficient": true/false, "missing":"<缺什么>", "rewrite_query":"<不足时的改写查询，否则留空>"}}'
)

# claim 溯源校验器：逐条核对回答中的论断是否被证据支持，用于校准置信度
LITERATURE_VERIFY = (
    "核对回答中的每个论断是否被给定证据支持。统计被支持的比例。\n"
    "证据：{evidence}\n"
    "回答：{answer}\n\n"
    "严格返回 JSON：\n"
    '{{"supported_ratio":0.0-1.0, "unsupported_claims":["<未被证据支持的论断>"], '
    '"high_risk": true/false}}'
)

# agentic 改写决策器：grade 零命中后决策「改写重检」或「放弃」（语料缺失时及时止损）
LITERATURE_REFINE = (
    "知识库检索未命中任何可用证据，请你作为检索查询优化器做决策。\n"
    "原问题：{question}\n"
    "已尝试过的查询（你的新查询不要与之重复）：{tried}\n"
    "检索分数分布（rerank 归一化，越低越不相关）：{scores}\n"
    "{corpus_hint}"
    "请决策 action：rewrite（给出一个新的、更可能命中的检索查询，"
    "可换用规范医学术语/同义词/疾病学名）或 giveup（知识库很可能缺少相关语料，放弃重检）。\n"
    '严格返回 JSON：{{"action":"rewrite或giveup", '
    '"new_query":"<rewrite时的新查询，giveup留空>", "reason":"<简短理由>"}}'
)
