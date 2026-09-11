"""DR 发现②加固回归锁：Milvus 检索线程级看门狗超时 + 客户端重建 + 既有降级语义。

背景：Milvus 停机后已缓存客户端的 RPC 挂起 ~7.5min（客户端 timeout=10 不生效，
asyncio.to_thread 侧无法中断工作线程）——检索调用统一套线程级看门狗（默认 20s，
settings.milvus_retrieve_timeout_s 可注入小值），超时抛 MilvusTimeout →
重建客户端重试一次 → 仍失败走既有降级语义（dense 兜底 / 异常上抛由调用方降级）。
测试注入 0.3s 短超时 + 1.2s 挂起模拟，避免真等 20s。
"""
import time

import pytest

from backend.core import medical_kb as kb


class _HangCol:
    """挂起客户端模拟：search/query 阻塞 1.2s（远超注入的 0.3s 看门狗超时）。"""

    def __init__(self, hang_s: float = 1.2):
        self._hang_s = hang_s
        self.rpc_calls = 0

    def _hang(self):
        self.rpc_calls += 1
        time.sleep(self._hang_s)
        return []

    def search(self, *a, **k):
        return self._hang()

    def query(self, *a, **k):
        return self._hang()


class _OkCol:
    """正常客户端模拟：search 返回一条命中（dense 路径 result 结构）。"""

    def __init__(self):
        self.rpc_calls = 0

    def search(self, *a, **k):
        self.rpc_calls += 1
        return [[{"distance": 0.9, "entity": {"content": "高血压防治要点", "source_name": "指南2024"}}]]


@pytest.fixture(autouse=True)
def _fast_timeout_and_clean_cache(monkeypatch):
    """注入 0.3s 短超时（避免真等 20s）；隔离模块级 _cache 防跨测试污染。"""
    monkeypatch.setattr(kb.settings, "milvus_retrieve_timeout_s", 0.3)
    monkeypatch.setattr(kb, "_cache", {})
    yield


@pytest.fixture(autouse=True)
def _stub_heavy_models(monkeypatch):
    """屏蔽 BGE/reranker 重模型：本文件只验证看门狗与重建编排，不加载本地权重。"""
    import backend.core.embedder as emb
    import backend.core.reranker as rr
    monkeypatch.setattr(kb, "get_bge_m3", lambda: None)
    monkeypatch.setattr(kb, "embed_text", lambda q: [0.0] * 8)
    monkeypatch.setattr(emb, "embed_sparse", lambda q: {1: 1.0})
    monkeypatch.setattr(rr, "rerank",
                        lambda q, docs, top_k=3: [(i, round(1.0 - i * 0.1, 2))
                                                  for i in range(min(top_k, len(docs)))])


def test_rpc_with_timeout_raises_milvus_timeout():
    """单次 RPC 挂起 → 看门狗在注入超时（0.3s）量级触发 MilvusTimeout（而非真等 20s/7.5min）。"""
    col = _HangCol(1.2)
    t0 = time.monotonic()
    with pytest.raises(kb.MilvusTimeout):
        kb._rpc_with_timeout(col.search, "medical_kb", data=[[0.0]], limit=3)
    assert time.monotonic() - t0 < 2.0, "看门狗必须按注入超时及时返回，不得等挂起线程结束"


def test_search_hybrid_timeout_rebuilds_client_and_recovers(monkeypatch):
    """超时 → _reset_client 重建 → 新客户端检索成功返回（重建路径触发 + 正常结果）。
    注意：_hybrid_candidates 与 search_dense 内部各调用一次 _collection，fake 必须按
    「重建状态」返回同一代客户端（与真实 _cache 缓存语义一致），而非逐次弹出。"""
    hang, ok = _HangCol(1.2), _OkCol()
    state = {"rebuilt": False}

    def fake_collection():
        return ok if state["rebuilt"] else hang

    def fake_reset():
        state["rebuilt"] = True
        resets.append(1)

    resets: list = []
    monkeypatch.setattr(kb, "_collection", fake_collection)
    monkeypatch.setattr(kb, "_has_sparse", lambda col: False)  # 走 dense 路径（search RPC）
    monkeypatch.setattr(kb, "_reset_client", fake_reset)
    out = kb.search_hybrid("高血压用药", top_k=3)
    assert resets, "超时后必须触发客户端重建（_reset_client）"
    assert hang.rpc_calls == 1 and ok.rpc_calls == 1
    assert out and out[0]["content"] == "高血压防治要点" and out[0]["source"] == "指南2024"
    assert "rerank" in out[0], "重建成功后照常走精排输出"


def test_search_hybrid_rebuild_failure_degrades(monkeypatch):
    """重建后仍失败 → 落既有降级语义：dense 兜底同样失败 → 异常上抛（与加固前行为一致，
    由调用方降级为空证据/降级标记）。本例重建后客户端直接不可达（RuntimeError）。"""
    hang = _HangCol(1.2)
    state = {"rebuilt": False}

    def fake_collection():
        if state["rebuilt"]:
            raise RuntimeError("Milvus 不可达：http://127.0.0.1:19531")
        return hang

    monkeypatch.setattr(kb, "_collection", fake_collection)
    monkeypatch.setattr(kb, "_has_sparse", lambda col: False)
    monkeypatch.setattr(kb, "_reset_client", lambda: state.__setitem__("rebuilt", True))
    with pytest.raises(RuntimeError, match="Milvus 不可达"):
        kb.search_hybrid("高血压用药", top_k=3)


def test_search_hybrid_timeout_then_empty_degrades_to_empty_list(monkeypatch):
    """超时 → 重建成功但检索无命中 → 降级返回 []（不抛异常，ask 走无据低置信分支）。"""
    hang, ok = _HangCol(1.2), _OkCol()
    ok.search = lambda *a, **k: []  # 重建后的客户端检索无命中
    state = {"rebuilt": False}

    def fake_collection():
        return ok if state["rebuilt"] else hang

    def fake_reset():
        state["rebuilt"] = True

    monkeypatch.setattr(kb, "_collection", fake_collection)
    monkeypatch.setattr(kb, "_has_sparse", lambda col: False)
    monkeypatch.setattr(kb, "_reset_client", fake_reset)
    assert kb.search_hybrid("高血压用药", top_k=3) == []


def test_doc_count_timeout_returns_negative(monkeypatch):
    """doc_count 检索挂起 → 看门狗超时 → 既有失败语义 -1（前端显示 … 而非假 0）。"""
    monkeypatch.setattr(kb, "_collection", lambda: _HangCol(1.2))
    assert kb.doc_count() == -1


def test_timeout_value_from_settings(monkeypatch):
    """看门狗超时值必须从 settings 读取：默认 20s；非法/非正值回落 20s（测试可注入小值）。"""
    monkeypatch.setattr(kb.settings, "milvus_retrieve_timeout_s", 20.0)
    assert kb._retrieve_timeout_s() == 20.0
    monkeypatch.setattr(kb.settings, "milvus_retrieve_timeout_s", 0.5)
    assert kb._retrieve_timeout_s() == 0.5
    monkeypatch.setattr(kb.settings, "milvus_retrieve_timeout_s", 0)
    assert kb._retrieve_timeout_s() == 20.0
    monkeypatch.setattr(kb.settings, "milvus_retrieve_timeout_s", "abc")
    assert kb._retrieve_timeout_s() == 20.0
