"""任务C：非医学（技术领域）问题礼貌拒答——不检索、不入审核队列、不调 LLM。

判定必须保守：命中明确技术/编程词表 且 不含任何医学信号词 才拒答；
混合词（如「癌症的python教程」有医学信号）一律正常走检索流程。
审计记 offtopic_reject 事件。

任务1 追加两块「不入队」纪律锁：
- 代码特征词守门：纯代码片段（不含语言名字面词）同样拒答；
- fallback 兜底答案（sources 含 fallback: 前缀）不写审核队列，审计记 fallback_no_enqueue。
"""
from fastapi.testclient import TestClient

from backend.api.v1.medical import medical_router as mr
from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.core.intent import offtopic_tech_reply
from backend.main import app

# 与 medical_router 固定文案一致（契约锁定）
OFFTOPIC_REPLY = ("我是医学文献助手，只处理医学/药学/临床问题。"
                  "您的问题属于技术领域，请咨询对应专业渠道。")


class _FakeAudit:
    """内存假审计：捕获 write 调用；recent 支持 offset（与真实 AuditLog 语义一致）。"""

    def __init__(self):
        self.entries = []

    def write(self, *a, **k):
        self.entries.append({"event_type": k.get("event_type", ""),
                             "action": k.get("action", ""),
                             "payload": dict(k.get("payload") or {})})

    def recent(self, n=8, offset=0):
        end = max(0, len(self.entries) - offset)
        return list(self.entries[max(0, end - n):end])[::-1]


class _CapGraph:
    """替身文献图：正常产出答案，用于证明请求走完既有流程（未被守门拦截）。"""

    def __init__(self):
        self.calls = 0

    async def ainvoke(self, state, config=None):
        self.calls += 1
        return {"answer": {"text": "建议就医评估", "confidence": 0.9,
                           "sources": ["KB:x"], "needs_human_review": False,
                           "verified": True, "evidence": []},
                "refine_trace": []}


def _client(monkeypatch, tmp_path):
    seed_default_users()
    log = _FakeAudit()
    monkeypatch.setattr(mr, "get_audit_logger", lambda: log)
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    return TestClient(app), log


def _tok(c):
    r = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"})
    assert r.status_code == 200, r.text
    return {"Authorization": "Bearer " + r.json()["access_token"]}


# ---------- 单元层：守门函数保守性 ----------

def test_offtopic_guard_conservative():
    """技术词 + 零医学信号 → 拒答；其余一律 None（正常走流程）。"""
    assert offtopic_tech_reply("用python写一个快速排序算法") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("python 连接 mysql 数据库报错怎么排查") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("解释一下 JavaScript 的闭包") == OFFTOPIC_REPLY
    # 无技术词 → 不拦
    assert offtopic_tech_reply("胃疼怎么办") is None
    assert offtopic_tech_reply("") is None
    # 混合词：任何医学信号都优先 → 拿不准就正常走流程
    assert offtopic_tech_reply("癌症的python教程") is None
    assert offtopic_tech_reply("青霉素剂量计算的python脚本") is None
    assert offtopic_tech_reply("帮我检查这段python代码") is None  # “检查”按医学信号保守放行


def test_offtopic_guard_medical_research_stats_not_rejected():
    """误杀面回归（生产化后增量排查）：医学统计/科研分析场景曾因零医学信号被误拒。
    技术词命中但含医学统计信号 → 保守放行；纯技术题拒答面不得因词表扩充而放宽。"""
    assert offtopic_tech_reply("用python分析心电图数据") is None      # 原有心电图信号
    assert offtopic_tech_reply("用python绘制ROC曲线评估模型") is None
    assert offtopic_tech_reply("用python做生存分析") is None
    assert offtopic_tech_reply("用python计算样本量") is None
    assert offtopic_tech_reply("用python分析基因测序数据") is None
    assert offtopic_tech_reply("用python统计受试者入组情况") is None
    # 纯技术题仍拒答（词表扩充只放宽医学面）
    assert offtopic_tech_reply("用python写一个快速排序算法") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("python 连接 mysql 数据库报错怎么排查") == OFFTOPIC_REPLY


# ---------- 端点层：拒答不入队 ----------

def test_tech_question_rejected_not_enqueued(monkeypatch, tmp_path):
    """编程题：固定文案秒答（不检索/不调 LLM/不入审核队列），审计记 offtopic_reject。"""
    c, log = _client(monkeypatch, tmp_path)
    h = _tok(c)
    r = c.post("/api/v1/medical/literature/ask", headers=h,
               json={"question": "用python写一个快速排序算法"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["answer"] == OFFTOPIC_REPLY            # 固定文案，非 LLM 生成
    assert d["sources"] == ["intent:offtopic"]
    assert d["needs_human_review"] is False
    assert d["review_id"] is None
    # 不入审核队列
    pend = c.get("/api/v1/medical/review/pending", headers=h).json()["pending"]
    assert pend == []
    # 审计记 offtopic_reject
    hits = [e for e in log.entries if e["action"] == "offtopic_reject"]
    assert hits and hits[0]["event_type"] == "literature"
    assert hits[0]["payload"].get("q")


def test_stream_tech_question_rejected(monkeypatch, tmp_path):
    """SSE 流式路径同样守门：直接发 result（拒答文案），不产生 progress 检索事件。"""
    c, log = _client(monkeypatch, tmp_path)
    h = _tok(c)
    r = c.post("/api/v1/medical/literature/stream", headers=h,
               json={"question": "怎么用git回滚代码"})
    assert r.status_code == 200, r.text
    assert OFFTOPIC_REPLY in r.text
    assert "event: result" in r.text
    assert "event: progress" not in r.text          # 未进入检索/生成节点
    assert any(e["action"] == "offtopic_reject" for e in log.entries)


def test_medical_question_walks_graph(monkeypatch, tmp_path):
    """「胃疼怎么办」正常走既有流程（替身图被调用，答案透传），无 offtopic_reject。"""
    g = _CapGraph()
    monkeypatch.setattr(mr, "_lit_graph", g)
    c, log = _client(monkeypatch, tmp_path)
    h = _tok(c)
    r = c.post("/api/v1/medical/literature/ask", headers=h, json={"question": "胃疼怎么办"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["answer"] == "建议就医评估" and d["sources"] == ["KB:x"]
    assert g.calls == 1                              # 确实进入检索/生成图
    assert not any(e["action"] == "offtopic_reject" for e in log.entries)


def test_mixed_medical_tech_walks_retrieval(monkeypatch, tmp_path):
    """「癌症的python教程」：技术词命中但含医学信号（癌）→ 保守放行，正常走检索。"""
    g = _CapGraph()
    monkeypatch.setattr(mr, "_lit_graph", g)
    c, log = _client(monkeypatch, tmp_path)
    h = _tok(c)
    r = c.post("/api/v1/medical/literature/ask", headers=h,
               json={"question": "癌症的python教程"})
    assert r.status_code == 200, r.text
    assert r.json()["answer"] == "建议就医评估"
    assert g.calls == 1
    assert not any(e["action"] == "offtopic_reject" for e in log.entries)


# ---------- 任务1：代码特征词守门（无语言名字面也能识别代码片段，大小写不敏感、保守） ----------

JAVA_SNIPPET = ('public class HelloWorld {\n'
                '    public static void main(String[] args) {\n'
                '        System.out.println("Hello, World!");\n'
                '    }\n'
                '}')


def test_offtopic_guard_code_features():
    """纯代码片段（不含 python/java 等语言名字面词）→ 拒答；医学信号仍最高优先。"""
    # 纯 Java 代码段（全文不含 "java" 字面）→ 拒答
    assert offtopic_tech_reply(JAVA_SNIPPET) == OFFTOPIC_REPLY
    # 常见代码特征（含大小写变体）
    assert offtopic_tech_reply("System.out.println(1)") == OFFTOPIC_REPLY
    assert offtopic_tech_reply('println("x")') == OFFTOPIC_REPLY
    assert offtopic_tech_reply("print('hello')") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("Console.Log('x')") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("<html><body>hi</body></html>") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("#!/bin/bash\necho hi") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("#!/usr/bin/env python3\nprint(1)") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("pip install requests 超时") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("npm run build 失败") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("import numpy as np 报 ModuleNotFound") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("def main():\n    pass") == OFFTOPIC_REPLY
    assert offtopic_tech_reply("class Foo { int x; }") == OFFTOPIC_REPLY
    # 无代码特征、无技术词 → 不拦（既有保守语义不回归）
    assert offtopic_tech_reply("胃疼怎么办") is None
    # 医学信号仍最高优先：代码特征命中但含医学词 → 放行
    assert offtopic_tech_reply("把患者的检验单 print(出来)") is None
    assert offtopic_tech_reply("心电图数据用 def 处理的脚本") is None


def test_java_snippet_rejected_endpoint(monkeypatch, tmp_path):
    """端点锁：纯 Java 代码段走 literature/ask → 固定拒答文案 + 不入审核队列。"""
    c, log = _client(monkeypatch, tmp_path)
    h = _tok(c)
    r = c.post("/api/v1/medical/literature/ask", headers=h, json={"question": JAVA_SNIPPET})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["answer"] == OFFTOPIC_REPLY
    assert d["sources"] == ["intent:offtopic"]
    assert d["review_id"] is None
    pend = c.get("/api/v1/medical/review/pending", headers=h).json()["pending"]
    assert pend == []
    assert any(e["action"] == "offtopic_reject" for e in log.entries)


# ---------- 任务1：fallback 兜底答案不入审核队列 ----------

def _fallback_answer():
    """fallback_node 产出契约形态（nodes.py：sources 恒以 fallback: 前缀 + 需人工复核）。"""
    return {"text": "⚠ 未检索到权威循证证据，以下为模型常识，仅供参考，请务必人工复核。",
            "confidence": 0.4, "sources": ["fallback:model-prior"],
            "needs_human_review": True, "verified": False, "evidence": []}


class _FbAskGraph:
    """替身文献图：ainvoke 直接返回 fallback 形态最终 state（不触真实 LLM/检索）。"""

    async def ainvoke(self, state, config=None):
        return {"answer": _fallback_answer(), "refine_trace": []}


class _FbStreamGraph:
    """替身文献图：astream 以 updates 形态 yield fallback 节点更新。"""

    def astream(self, state, config=None, stream_mode=None):
        async def _gen():
            yield {"fallback": {"answer": _fallback_answer()}}
        return _gen()


def test_literature_fallback_not_enqueued(monkeypatch, tmp_path):
    """任务1 测试锁：fallback 响应后 review_queue 不新增；审计记 fallback_no_enqueue。"""
    monkeypatch.setattr(mr, "_lit_graph", _FbAskGraph())
    c, log = _client(monkeypatch, tmp_path)
    h = _tok(c)
    r = c.post("/api/v1/medical/literature/ask", headers=h, json={"question": "罕见病治疗进展"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["review_id"] is None
    assert d["needs_human_review"] is True           # 需人工复核的语义不变（仅不写队列）
    assert d["sources"] == ["fallback:model-prior"]
    assert review.pending() == []                    # 测试锁：队列不新增
    hits = [e for e in log.entries if e["action"] == "fallback_no_enqueue"]
    assert hits and hits[0]["event_type"] == "literature"


def test_stream_fallback_not_enqueued(monkeypatch, tmp_path):
    """SSE 流式路径同样纪律：fallback 结果不入队（result 事件 review_id 为 null）。"""
    monkeypatch.setattr(mr, "_lit_graph", _FbStreamGraph())
    c, log = _client(monkeypatch, tmp_path)
    h = _tok(c)
    r = c.post("/api/v1/medical/literature/stream", headers=h,
               json={"question": "罕见病治疗进展"})
    assert r.status_code == 200, r.text
    assert "event: result" in r.text
    assert '"review_id": null' in r.text
    assert review.pending() == []
    assert any(e["action"] == "fallback_no_enqueue" for e in log.entries)


def test_non_fallback_low_confidence_still_enqueued(monkeypatch, tmp_path):
    """对照锁：非 fallback（KB 来源）的低置信答案仍照常入队（防一刀切全部豁免）。
    任务3 注记：显式关闭留痕模式以断言 pending 语义（默认 True 时入队即自动签发，
    条目不再 pending；留痕入队语义由 test_full_trace_mode.py 锁定）。"""
    from backend.config import settings
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)

    class _LowGraph:
        async def ainvoke(self, state, config=None):
            return {"answer": {"text": "不确定的回答", "confidence": 0.35,
                               "sources": ["KB:x"], "needs_human_review": True,
                               "verified": False, "evidence": []},
                    "refine_trace": []}

    monkeypatch.setattr(mr, "_lit_graph", _LowGraph())
    c, log = _client(monkeypatch, tmp_path)
    h = _tok(c)
    r = c.post("/api/v1/medical/literature/ask", headers=h, json={"question": "罕见病治疗进展"})
    d = r.json()
    assert d["review_id"]
    assert len(review.pending()) == 1
    assert not any(e["action"] == "fallback_no_enqueue" for e in log.entries)
