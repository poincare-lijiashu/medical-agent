"""任务3 轻量会话记忆：literature 多轮指代理解的进程内存态存储与 prompt 上下文构建。

设计边界（刻意保持轻量）：
- 存储：OrderedDict[session_id] → deque(maxlen=turns*2)，线程锁保护；**只存每轮问答的
  精简文本**（question 原文 + answer 前 500 字符），不存 sources/images——不扩大 PHI 暴露面。
- 生命周期：进程内存态，重启丢失可接受。医疗查证每问独立检索（记忆仅用于理解
  「它/该药」类指代），历史丢失不改变回答的检索与引用语义。
- 容量：settings.chat_memory_turns（默认 8，0=完全关闭）；deque 截断只保留最近 N 轮。
- 会话数上限（外部审查 M2）：MAX_SESSIONS（默认 200）——进程内存态 dict 若无上限，
  长会话/恶意刷 session_id 会持续增长；超限按 LRU 淘汰最久未写入的会话
  （活跃会话每次写入 move_to_end，不会被误淘汰）。
- 线程模型：写入/读取都是 O(1) 级 dict+deque 操作，锁粒度到方法级；ask 路径在
  响应完成后写入（asyncio.to_thread 中调用，多 worker 线程安全）。
"""
from __future__ import annotations

import threading
from collections import OrderedDict, deque

from backend.config import settings
from backend.core.stores import get_store

_ANSWER_SUMMARY_LEN = 500  # 每轮回答入存的截断长度（问题原文完整保留，见 redact 上游约束）

# 会话数上限（外部审查 M2）：_store 里最多保留多少个会话；超出淘汰最久未写入的
# 会话（LRU）。模块级常量便于测试 monkeypatch。
MAX_SESSIONS = 200

# 轮 B2 共享存储（REDIS_URL 配置后多实例互通会话）：JSON 列表形态存每会话问答轮；
# TTL 与令牌最大寿命对齐（内存模式下 TTL 同样生效，无额外成本）。last-write-wins
# （轻量记忆，仅用于指代理解，可接受）；本地 OrderedDict 为主真源、共享存储为互通层。
_CHAT_PREFIX = "chat:"
_CHAT_TTL = 8 * 86400

_store: OrderedDict[str, deque] = OrderedDict()
_lock = threading.Lock()


def _max_items() -> int:
    """deque 容量 = 轮数（每个 item 是一轮 (question, answer) 元组，按轮截断）。"""
    return max(0, int(settings.chat_memory_turns))


def _max_sessions() -> int:
    """会话数上限读取点（模块常量，测试可 monkeypatch）；下限 1 防误配置清空。"""
    return max(1, int(MAX_SESSIONS))


def remember(session_id: str, question: str, answer: str) -> None:
    """响应完成后写入本轮问答（answer 截断到 500 字符）。turns=0 或空 session 跳过。"""
    sid = (session_id or "").strip()
    if not sid or max(0, int(settings.chat_memory_turns)) <= 0:
        return
    item = (question, (answer or "")[:_ANSWER_SUMMARY_LEN])
    evicted: list[str] = []
    with _lock:
        d = _store.get(sid)
        if d is None:
            d = _store[sid] = deque(maxlen=_max_items())
        elif d.maxlen != _max_items():  # turns 运行时被调整 → 换新 deque 保留既有最近内容
            d = _store[sid] = deque(d, maxlen=_max_items())
        d.append(item)
        _store.move_to_end(sid)  # 活跃会话置最新（LRU）：淘汰只发生在最旧端，不误伤活跃会话
        while len(_store) > _max_sessions():  # 外部审查 M2：超限淘汰最久未写入的会话
            evicted.append(_store.popitem(last=False)[0])
        snapshot = list(d)  # 锁内快照，锁外写共享存储（避免持锁做 IO）
    # 共享存储同步（轮 B2）：淘汰会话同步删共享键——保证「淘汰后 recall 返回 []」语义不变
    store = get_store()
    for old in evicted:
        store.delete(_CHAT_PREFIX + old)
    store.set(_CHAT_PREFIX + sid, snapshot, ttl=_CHAT_TTL)


def recall(session_id: str) -> list[tuple[str, str]]:
    """返回该会话最近历史（旧→新）。无记录/关闭返回 []。

    本地命中优先（主真源）；本地未命中（多实例下其它实例写入的会话）查共享存储。"""
    sid = (session_id or "").strip()
    if not sid:
        return []
    with _lock:
        d = _store.get(sid)
        if d:
            return list(d)
    shared = get_store().get(_CHAT_PREFIX + sid)
    if isinstance(shared, list):
        return [(q, a) for q, a in shared]
    return []


def build_context(session_id: str) -> tuple[str, int]:
    """构建拼入 prompt 头部的「前文对话」块。返回 (块文本, 使用轮数)。

    无历史 / turns=0 / 空 session → ("", 0)。块内显式标注用途边界（仅指代理解，
    回答仍需独立检索与引用），防止模型把历史当成可直接复用的结论。"""
    turns = max(0, int(settings.chat_memory_turns))
    if turns <= 0:
        return "", 0
    hist = recall(session_id)[-turns:]
    if not hist:
        return "", 0
    lines = ["【前文对话】（仅用于理解指代，回答仍需独立检索与引用）"]
    for q, a in hist:
        lines.append(f"用户：{q}")
        lines.append(f"助手：{a}")
    return "\n".join(lines) + "\n", len(hist)


def reset() -> None:
    """清空全部会话（测试用；生产不暴露）。"""
    with _lock:
        _store.clear()
