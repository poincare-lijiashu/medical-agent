"""任务3 全交互留痕：4 类 agent（literature/drug/imaging/case）+ MDT 的 ask 类响应
无条件入 review_queue（留痕模式自动签发）、待确认规则边界（conf 0.59/0.61）、
FULL 默认 on、imaging/case 空响应兜底（不入队）、sources 随条目存档。

不触网：literature 用替身图、VL/MDT 图用 monkeypatch 替身；队列/用户/审计隔离到 tmp_path。
"""
import base64
import io
import json

from fastapi.testclient import TestClient
from PIL import Image

from backend.config import settings
from backend.core import auth as auth_mod
from backend.core import medical_imaging as mi
from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.core.medical_audit import AuditLog
from backend.main import app


def _png_data_url() -> str:
    """生成合法纯色 PNG data URL（压缩管线可正常处理，模拟前端上传影像）。"""
    img = Image.new("RGB", (640, 480), (180, 40, 40))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    return TestClient(app), audit


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


class _CapMdtGraph:
    """替身 MDT 图：返回中等置信、无分歧的报告（非高危 → 纯留痕）。"""

    async def ainvoke(self, state, config=None):
        return {"report": {"headline": "会诊完成", "urgency": "中", "key_actions": ["观察"],
                           "text": "会诊小结：完善评估", "confidence": 0.75,
                           "opinions": [], "disagreements": "无",
                           "needs_human_review": False, "sources": ["KB:mdt"]}}


class _FakeVL:
    """假 VL：content 可定制（空串用于空响应兜底测试）。"""

    content = "影像示右肺纹理增粗，未见明确占位。"  # 无高危词 → 非高危纯留痕

    def __init__(self, content=None):
        if content is not None:
            self.content = content

    async def ainvoke(self, messages):
        class _R:
            pass
        _R.content = self.content
        return _R()


# ---------- FULL 默认 on ----------

def test_full_mode_default_on():
    """任务3：qc_auto_sign_full 默认 True（留痕模式默认开启，显式行为变更）。"""
    assert settings.qc_auto_sign_full is True


# ---------- 全 agent 入队（4 类各一 + MDT） ----------

def test_all_agents_enqueue_unconditionally(monkeypatch, tmp_path):
    """留痕模式（默认开）：literature/imaging/case/mdt 的常规（非高危）响应
    也无条件入队并自动签发（AI·留痕模式(自动)），question/answer/confidence/sources 全存档。
    本批次任务2 语义变更注记：drug 类豁免留痕模式（FULL 短路不适用）——drug「信息不足」
    （非高危）回落旧语义不入队，drug 高危恒人工由 test_drug_pharm_review 锁定。"""
    class _LitGraph:
        async def ainvoke(self, state, config=None):
            return {"answer": {"text": "常规循证回答", "confidence": 0.83,
                               "sources": ["KB:guideline-1"], "needs_human_review": False,
                               "verified": True, "evidence": []},
                    "refine_trace": []}

    monkeypatch.setattr("backend.api.v1.medical.medical_router._lit_graph", _LitGraph())
    monkeypatch.setattr("backend.api.v1.medical.medical_router._mdt_graph", _CapMdtGraph())
    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _FakeVL())
    c, audit = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")

    # literature：非 fallback、非低置信 → 纯留痕
    d1 = c.post("/api/v1/medical/literature/ask", headers=_h(doc),
                json={"question": "高血压一线用药原则"}).json()
    assert d1["review_id"]
    it1 = review.get(d1["review_id"])
    assert it1["agent"] == "literature" and it1["question"] == "高血压一线用药原则"
    assert it1["answer"] == "常规循证回答" and it1["confidence"] == 0.83
    assert it1["sources"] == ["KB:guideline-1"]  # 任务3：sources 存档
    assert it1["status"] == "approved" and it1["reviewed_by"] == "AI·留痕模式(自动)"
    assert it1["self_confirm_required"] is False  # conf≥0.6 且非高危 → 纯留痕

    # drug：本批次任务2 语义变更——drug 豁免留痕模式，「信息不足」（非高危）不入队
    d2 = c.post("/api/v1/medical/drug/ask", headers=_h(doc),
                json={"question": "布洛芬怎么用"}).json()
    assert d2["review_id"] is None, "drug 类豁免留痕模式：非高危不入队（三档旧语义）"

    # imaging：无高危词描述 → 纯留痕 + images 存档（合法 PNG，压缩管线可处理）
    img = _png_data_url()
    d3 = c.post("/api/v1/medical/imaging/ask", headers=_h(doc),
                json={"question": "这张胸片", "images": [img]}).json()
    it3 = review.get(d3["review_id"])
    assert it3["agent"] == "imaging" and it3["images"], "留痕条目应存档影像"
    assert it3["sources"] and it3["sources"][0].startswith("VL:")
    assert it3["self_confirm_required"] is False

    # case：同 VL 链路 → 纯留痕
    d4 = c.post("/api/v1/medical/case/ask", headers=_h(doc),
                json={"question": "总结该病例", "images": [img]}).json()
    it4 = review.get(d4["review_id"])
    assert it4["agent"] == "case" and it4["answer"].startswith("影像示右肺纹理增粗")
    assert it4["status"] == "approved"

    # mdt：报告全文入队（首次接入）
    d5 = c.post("/api/v1/medical/mdt/consult", headers=_h(doc),
                json={"case": "62 岁糖尿病合并冠心病病例"}).json()
    it5 = review.get(d5["review_id"])
    assert it5["agent"] == "mdt" and it5["answer"] == "会诊小结：完善评估"
    assert it5["sources"] == ["KB:mdt"]

    # 留痕签发保留影像（keep_images=True）；队列无 pending 积压
    assert it3["images"], "留痕模式自动签发应保留影像存档（keep_images）"
    assert review.pending() == []
    # 审计：每条都记 review_enqueued + auto_sign_full
    assert any(e["payload"].get("action") == "auto_sign_full" for e in audit.entries)


# ---------- 待确认规则边界（conf 0.59 / 0.61） ----------

def test_self_confirm_threshold_boundary(monkeypatch, tmp_path):
    """任务3 待确认规则：conf<0.6 → 提交医生待确认；conf≥0.6（非高危）→ 纯留痕。"""
    def _graph(conf):
        class _G:
            async def ainvoke(self, state, config=None):
                return {"answer": {"text": "低置信回答", "confidence": conf,
                                   "sources": ["KB:x"], "needs_human_review": conf < 0.6,
                                   "verified": True, "evidence": []},
                        "refine_trace": []}
        return _G()

    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    # 0.59 → 待确认
    monkeypatch.setattr("backend.api.v1.medical.medical_router._lit_graph", _graph(0.59))
    d59 = c.post("/api/v1/medical/literature/ask", headers=_h(doc),
                 json={"question": "低置信问题甲"}).json()
    it59 = review.get(d59["review_id"])
    assert it59["confidence"] == 0.59 and it59["self_confirm_required"] is True
    # 0.61 → 纯留痕（无待确认）
    monkeypatch.setattr("backend.api.v1.medical.medical_router._lit_graph", _graph(0.61))
    d61 = c.post("/api/v1/medical/literature/ask", headers=_h(doc),
                 json={"question": "低置信问题乙"}).json()
    it61 = review.get(d61["review_id"])
    assert it61["confidence"] == 0.61 and it61["self_confirm_required"] is False
    # 待确认列表只含 0.59 一条
    mine = c.get("/api/v1/medical/review/my-pending-confirm", headers=_h(doc)).json()["items"]
    assert [i["id"] for i in mine] == [it59["id"]]


# ---------- imaging/case 空响应兜底 ----------

def test_imaging_empty_vl_response_bailout(monkeypatch, tmp_path):
    """任务3 imaging 空响应兜底：VL 返回空内容 → 明确提示重试（status=vl_empty）、
    不入队、审计记 vl_empty。"""
    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _FakeVL(content="   "))
    c, audit = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    img = "data:image/png;base64," + "B" * 64
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(doc),
               json={"question": "看看", "images": [img]})
    assert r.status_code == 200, r.text
    d = r.json()
    assert "AI 未生成所见" in d["answer"]
    assert d["status"] == "vl_empty" and d["review_id"] is None
    assert d["confidence"] == 0.0 and d["needs_human_review"] is False
    assert review.pending() == [] and review.list_all() == []  # 不入队
    assert any(e["payload"].get("action") == "vl_empty" for e in audit.entries)


def test_case_empty_vl_response_bailout(monkeypatch, tmp_path):
    """case 同纪律：VL 空内容 → 明确提示、不入队。"""
    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _FakeVL(content=""))
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    img = _png_data_url()
    d = c.post("/api/v1/medical/case/ask", headers=_h(doc),
               json={"question": "总结", "images": [img]}).json()
    assert "AI 未生成所见" in d["answer"] and d["status"] == "vl_empty" and d["review_id"] is None
    assert review.list_all() == []


def test_vl_prompt_forbids_empty_output(monkeypatch):
    """任务3 prompt 空响应防线：放射科/病例 prompt 均要求结构化输出、禁止空内容。"""
    assert "禁止返回空字符串" in mi._PROMPT
    assert "禁止返回空字符串" in mi.CASE_PROMPT


# ---------- 诊断1：VL 空响应自动重试 + 审计诊断字段 ----------

def test_vl_empty_response_retries_once_then_succeeds(monkeypatch, tmp_path):
    """诊断1：VL 首次空响应 → 内部换 slightly 更高温度（0→0.2）自动重试 1 次并成功，
    路由正常返回所见（不触发 vl_empty 兜底）。"""
    temps, seq = [], iter(["   ", "影像示右肺纹理增粗，未见明确占位。"])

    def _factory(temperature=0):
        temps.append(temperature)
        content = next(seq)

        class _VL:
            async def ainvoke(self, messages):
                class _R:
                    def __init__(self, c):
                        self.content = c
                return _R(content)
        return _VL()

    monkeypatch.setattr(mi, "get_vl_llm", _factory)
    c, audit = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    img = _png_data_url()
    d = c.post("/api/v1/medical/imaging/ask", headers=_h(doc),
               json={"question": "看看", "images": [img]}).json()
    assert d["answer"].startswith("影像示右肺纹理增粗"), d
    assert d["review_id"]  # 重试成功 → 正常入队留痕
    assert temps == [0, 0.2]  # 首试 0，空响应后换 0.2 重试 1 次
    assert not any(e["payload"].get("action") == "vl_empty" for e in audit.entries)


def test_vl_empty_twice_audits_raw_len_and_stop_reason(monkeypatch, tmp_path):
    """诊断1：两次均空 → vl_empty 兜底（不入队），审计携带 vl_raw_len/stop_reason
    诊断线索（为换稳定 vision 模型留证据）。"""
    class _EmptyVL:
        async def ainvoke(self, messages):
            class _R:
                content = ""
                response_metadata = {"finish_reason": "length"}
            return _R()

    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _EmptyVL())
    c, audit = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    img = "data:image/png;base64," + "B" * 64
    d = c.post("/api/v1/medical/imaging/ask", headers=_h(doc),
               json={"question": "看看", "images": [img]}).json()
    assert d["status"] == "vl_empty" and d["review_id"] is None
    assert review.list_all() == []  # 不入队
    ev = [e for e in audit.entries if e["payload"].get("action") == "vl_empty"]
    assert ev and ev[-1]["payload"]["vl_raw_len"] == 0
    assert ev[-1]["payload"]["stop_reason"] == "length"


# ---------- 诊断2：stream 入队补传 sources ----------

class _StreamGraph:
    """替身文献图（流式）：单个 verify 节点更新即终态。"""

    def __init__(self, answer):
        self.answer = answer

    def astream(self, state, config=None, stream_mode=None):
        async def _gen():
            yield {"verify": {"answer": self.answer}}
        return _gen()


def test_stream_enqueue_carries_sources(monkeypatch, tmp_path):
    """诊断2：SSE 流式路径 _enqueue_if_risk 与非流式 ask 同纪律补传 sources——
    留痕条目 sources 随条目存档（此前漏传，队内溯源为空）。"""
    monkeypatch.setattr("backend.api.v1.medical.medical_router._lit_graph", _StreamGraph({
        "text": "常规循证回答", "confidence": 0.83, "sources": ["KB:guideline-stream"],
        "needs_human_review": False, "verified": True, "evidence": []}))
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    r = c.post("/api/v1/medical/literature/stream", headers=_h(doc),
               json={"question": "高血压一线用药原则"})
    assert r.status_code == 200, r.text
    data = [ln[5:].strip() for ln in r.text.splitlines() if ln.startswith("data:")][-1]
    payload = json.loads(data)
    assert payload["review_id"]
    it = review.get(payload["review_id"])
    assert it["agent"] == "literature"
    assert it["sources"] == ["KB:guideline-stream"]  # 诊断2：stream 入队 sources 存档


# ---------- 留痕模式关闭 → 回落旧语义（仅高危入队） ----------

def test_full_mode_off_restores_legacy_enqueue(monkeypatch, tmp_path):
    """admin 关闭留痕模式后：常规（非高危）响应不入队，高危才入队（旧语义）。"""
    from backend.core import runtime_flags
    monkeypatch.setattr(runtime_flags, "FLAGS_FILE", str(tmp_path / "runtime_flags.json"))
    runtime_flags.set_flag("qc_auto_sign_full", False)
    monkeypatch.setattr("backend.api.v1.medical.medical_router._lit_graph",
                        _LitGraphForOff())
    c, _ = _client(monkeypatch, tmp_path)
    doc = _login(c, "doctor01")
    d = c.post("/api/v1/medical/literature/ask", headers=_h(doc),
               json={"question": "常规问题"}).json()
    assert d["review_id"] is None  # 非高危不入队（旧语义）
    assert review.list_all() == []


class _LitGraphForOff:
    async def ainvoke(self, state, config=None):
        return {"answer": {"text": "常规回答", "confidence": 0.85,
                           "sources": ["KB:x"], "needs_human_review": False,
                           "verified": True, "evidence": []},
                "refine_trace": []}
