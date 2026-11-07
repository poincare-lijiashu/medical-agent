"""Agent 域跨链路共享的输入上限（评审 01-S03 整改）。

此前 ``MAX_QUESTION = 2000`` 在 ``consult`` / ``bot_runtime`` / ``tool_loop`` /
``second_opinion`` 四处各写一遍，``MAX_INPUT = 2000`` 在 ``skills`` 与 ``guardrails``
又各有一份。调整一次要改六处，漏改即产生"同一问题在两条链路上一条过一条拒"的
静默不一致。

这里只收敛**跨链路共享**的数值；模块专属上限（记忆条数、工具循环步数、提示预算
的装配规则等）仍留在各自模块，避免把不同语义硬塞进一个常量表。
"""
from __future__ import annotations

# 单次提问的最大字符数（问答 / Bot 运行时 / 工具循环 / 第二意见 共用）
MAX_QUESTION = 2000
# 技能卡护栏 maxInputChars 的允许区间（与 guardrails.effective 的下限一致）
MIN_MAX_INPUT = 200
MAX_INPUT = 2000
# 多轮上下文：条数 / 单条 / 合计 三重上限
MAX_HISTORY_TURNS = 6
MAX_HISTORY_ITEM = 2000
MAX_HISTORY_CHARS = 6000
# 单次回答的落库/返回上限（流式累积与同步返回共用）
MAX_ANSWER_CHARS = 4000
