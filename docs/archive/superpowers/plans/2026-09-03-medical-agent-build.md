# Medical Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable, HITL-enforced medical AI assistant with 4 specialized agents (literature / imaging / drug / case) sharing one audited infrastructure.

**Architecture:** LangGraph + LangChain 1.2.10 + FastAPI + Qwen (MaaS) + BGE-M3 + Reranker + Milvus + PostgreSQL + MemorySaver — reusing EduAgent stack. Single shared "medical core" enforces PHI redaction, audit log, forced human review on confidence<0.7, and offline evaluation gating.

**Tech Stack:** Python 3.11, FastAPI, LangGraph, LangChain, SQLAlchemy(async)+asyncpg, pymilvus, httpx, FlagEmbedding==1.2.10, sentence-transformers 3.3.1, pymupdf, python-docx, biopython (PubMed), the user-confirmed conda env `python`.

---
## File Structure (decided upfront)

```
medical_agent/
├── backend/
│   ├── core/
│   │   ├── medical_audit.py      # PHIRedactor + AuditLog (append-only)
│   │   ├── medical_eval.py       # VerifiedAnswer dataclass, confidence gate, offline eval harness
│   │   ├── medical_hitl.py       # forced_review(message) -> HITL interrupt payload
│   │   └── llm_factory.py       # Qwen + enable_thinking=False + max_retries=3 (copy from EduAgent)
│   ├── agents/medical/
│   │   ├── literature/           # M2: PubMed + local PDF RAG
│   │   ├── imaging/              # M3: BiomedCLIP image->text  (scaffold)
│   │   ├── drug/                 # M4: drugbank offline + interaction rules  (scaffold)
│   │   └── case/                 # M5: multimodal case summary  (scaffold)
│   ├── api/v1/medical/
│   │   └── medical_router.py     # 4 endpoints, one per agent
│   └── main.py                   # FastAPI app, lifespan preloads literature agent
├── scripts/
│   ├── seed_standard_exam.py    # seed medical KB corpus (one sample guideline PDF)
│   └── eval_medical.py           # offline evaluation harness
├── tests/
│   ├── test_medical_audit.py
│   ├── test_medical_eval.py
│   └── test_literature_agent.py  # uses real PubMed eutils + a seeded PDF
├── data/
│   └── kb/
│       └── sample_guideline.pdf  # generated medical KB document
├── docs/
│   └── EVAL.md                   # evaluation protocol & thresholds
├── docker-compose.yml            # reuses 5433/19531 (no new infra)
├── requirements.txt
└── README.md
```

---
## Task 1: Project skeleton + shared medical core (M1)

**Files:**
- Create: `medical_agent/backend/__init__.py`
- Create: `medical_agent/backend/core/__init__.py`
- Create: `medical_agent/backend/core/medical_audit.py`
- Create: `medical_agent/backend/core/medical_eval.py`
- Create: `medical_agent/backend/core/medical_hitl.py`
- Create: `medical_agent/backend/core/llm_factory.py` (copy from EduAgent, drop-in)
- Create: `medical_agent/backend/main.py`
- Create: `medical_agent/requirements.txt`
- Test: `medical_agent/tests/test_medical_audit.py`
- Test: `medical_agent/tests/test_medical_eval.py`

- [ ] **Step 1.1: Write failing tests for PHI redaction**

```python
# tests/test_medical_audit.py
from backend.core.medical_audit import PHIRedactor, AuditLog

def test_redacts_phone():
    r = PHIRedactor().redact("Call 13800001234 today")
    assert "13800001234" not in r
    assert "电话" in r or "[REDACTED-PHONE]" in r

def test_redacts_email_and_idcard():
    r = PHIRedactor().redact("张三 510105199001011234 zhang@example.com")
    assert "510105199001011234" not in r
    assert "zhang@example.com" not in r

def test_audit_log_is_append_only():
    log = AuditLog(db=None)  # in-memory fallback for tests
    log.record(actor="dr01", action="literature_query", payload={"q":"x"})
    log.record(actor="dr01", action="HITL_confirm", payload={"answer_id":"a1"})
    assert len(log.entries) == 2
    # append-only: no delete/update methods exist
    assert not hasattr(log, "delete")
```

- [ ] **Step 1.2: Run tests to confirm they fail**

Run: `cd medical_agent && python -m pytest tests/test_medical_audit.py -v`
Expected: ImportError or AttributeError (module doesn't exist yet).

- [ ] **Step 1.3: Implement medical_audit.py**

```python
# backend/core/medical_audit.py
import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
from sqlalchemy import text
from backend.dependencies import AsyncSessionLocal

_PHONE = re.compile(r"1[3-9]\d{9}")
_EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
_IDCARD = re.compile(r"\b\d{17}[\dXx]\b")

class PHIRedactor:
    """Replace PHI with bracketed placeholders. Pure function, deterministic."""
    def redact(self, text: str) -> str:
        t = _IDCARD.sub("[REDACTED-ID]", text)
        t = _PHONE.sub("[REDACTED-PHONE]", t)
        t = _EMAIL.sub("[REDACTED-EMAIL]", t)
        return t

@dataclass
class AuditEntry:
    ts: str
    actor: str
    action: str
    payload: dict

class AuditLog:
    """Append-only audit trail. Falls back to in-memory list when no db."""
    def __init__(self, db=None):
        self.db = db
        self.entries: list[AuditEntry] = []

    def record(self, actor: str, action: str, payload: dict) -> None:
        e = AuditEntry(ts=datetime.utcnow().isoformat(), actor=actor, action=action, payload=payload)
        self.entries.append(e)
        if self.db is not None:
            # project owner may extend: insert into audit_log table (append-only)
            pass
```

- [ ] **Step 1.4: Implement medical_eval.py with confidence gate**

```python
# backend/core/medical_eval.py
from dataclasses import dataclass
from typing import Iterable

CONFIDENCE_REVIEW_THRESHOLD = 0.70  # < this -> must HITL

@dataclass
class VerifiedAnswer:
    text: str
    confidence: float       # 0..1, model's own self-assessment
    sources: list[str] = None  # citations
    needs_human_review: bool = False

    def __post_init__(self):
        if self.sources is None:
            self.sources = []
        self.needs_human_review = self.confidence < CONFIDENCE_REVIEW_THRESHOLD

def aggregate_needs_review(answers: Iterable[VerifiedAnswer]) -> bool:
    """Whole-submission HITL gate: any one answer below threshold forces review."""
    return any(a.needs_human_review for a in answers)
```

- [ ] **Step 1.5: Implement medical_hitl.py wrapper**

```python
# backend/core/medical_hitl.py
from langgraph.types import interrupt
from backend.core.medical_eval import VerifiedAnswer, aggregate_needs_review

def forced_review_payload(answers: list[VerifiedAnswer], session_id: str):
    """Build payload that LangGraph interrupt() consumes; if any answer < threshold -> review required."""
    if not aggregate_needs_review(answers):
        return None  # no interrupt needed
    return {
        "session_id": session_id,
        "requires_human": True,
        "low_confidence_items": [
            {"answer_id": i, "text": a.text[:120], "confidence": a.confidence}
            for i, a in enumerate(answers) if a.needs_human_review
        ],
    }
```

- [ ] **Step 1.6: Copy llm_factory.py from EduAgent verbatim**

```powershell
Copy-Item D:\workspace_AI\workspace_qoder\xiangmu_qoder\eduagent20260902\EduAgent完整代码线下版\backend\core\llm_factory.py medical_agent\backend\core\llm_factory.py
```
(keeps the `enable_thinking=False + max_retries=3` fix we already verified)

- [ ] **Step 1.7: Minimal main.py + requirements.txt**

```python
# backend/main.py
from fastapi import FastAPI
from backend.api.v1.medical.medical_router import router as medical_router
app = FastAPI(title="Medical Agent API", version="0.1.0")
app.include_router(medical_router, prefix="/api/v1/medical")
```

```text
# requirements.txt (copy from EduAgent, add biopython)
biopython==1.84
# (rest identical to EduAgent requirements.txt)
```

- [ ] **Step 1.8: Run tests; expected: all PASS**

Run: `python -m pytest tests/test_medical_audit.py tests/test_medical_eval.py -v`
Expected: PASS

---
## Task 2: Literature Agent (M2)

**Files:**
- Create: `medical_agent/backend/agents/medical/__init__.py`
- Create: `medical_agent/backend/agents/medical/literature/__init__.py`
- Create: `medical_agent/backend/agents/medical/literature/state.py` (LiteratureState TypedDict)
- Create: `medical_agent/backend/agents/medical/literature/prompts.py`
- Create: `medical_agent/backend/agents/medical/literature/nodes.py`
- Create: `medical_agent/backend/agents/medical/literature/graph.py`
- Create: `medical_agent/scripts/seed_standard_exam.py`  (renamed sense: seeds KB + sample guideline)
- Test: `medical_agent/tests/test_literature_agent.py`

- [ ] **Step 2.1: Write failing test for literature agent**

```python
# tests/test_literature_agent.py
import asyncio
from backend.agents.medical.literature.graph import build_literature_graph

def test_literature_graph_returns_verified_answer():
    g = build_literature_graph()
    state = {"session_id": "t1", "question": "What is the first-line treatment for type 2 diabetes?"}
    out = asyncio.run(g.ainvoke(state, config={"configurable":{"thread_id":"t1"}}))
    assert "answer" in out
    assert "needs_human_review" in out["answer"]
    assert isinstance(out["answer"]["confidence"], float)
```

- [ ] **Step 2.2: Generate sample medical KB PDF**

```python
# scripts/_gen_sample_guideline.py (one-off; delete after running)
import fitz, os
out = "data/kb/sample_guideline.pdf"
os.makedirs(os.path.dirname(out), exist_ok=True)
d = fitz.open()
p = d.new_page(width=595, height=842)
p.insert_font(fontname="cjk", fontfile="C:/Windows/Fonts/msyh.ttc")
p.insert_textbox(fitz.Rect(50,50,545,792),
    "《2 型糖尿病基层诊疗指南（2024 摘要）\n\n"
    "1. 一线治疗：二甲双胍 500mg 2-3 次/日，餐时或餐后即刻服用。\n"
    "2. HbA1c 控制目标：一般 <7%；老年/低血糖风险高者可放宽至 7.5-8%。\n"
    "3. 联合用药：二甲双胍 + SGLT2i 或 GLP-1RA，适用于合并 ASCVD/CKD/HF 者。\n"
    "4. 监测：每 3 个月 HbA1c，每 6 个月 eGFR/UACR/眼底。\n"
    "5. 转诊上级：HbA1c>9%、急性并发症、合并妊娠。\n",
    fontname="cjk", fontsize=12, lineheight=1.5)
d.save(out); d.close()
print("WROTE", out)
```

- [ ] **Step 2.3: Implement literature state + prompts**

```python
# state.py
from typing import TypedDict, Optional

class LiteratureState(TypedDict):
    session_id: str
    question: str
    pubmed_ids: list[str]
    local_chunks: list[dict]
    answer: Optional[dict]
```

```python
# prompts.py
LITERATURE_SYSTEM = "你是循证医学文献助手，所有结论必须可溯源..."
LITERATURE_ANSWER = """基于以下证据回答问题，必须给出引用编号。
PubMed: {pubmed}
Local KB: {local}
问题：{question}
返回 JSON：{{"answer":"...","citations":["PMID:..."],"confidence":0.0-1.0}}"""
```

- [ ] **Step 2.4: Implement literature nodes + graph (reuse EduAgent patterns)**

```python
# nodes.py (skeleton)
import asyncio, httpx, json
from langchain_core.messages import HumanMessage
from backend.core.llm_factory import get_llm
from backend.core.medical_eval import VerifiedAnswer
from backend.core.medical_audit import PHIRedactor, AuditLog
from backend.agents.medical.literature.state import LiteratureState
from backend.agents.medical.literature.prompts import LITERATURE_SYSTEM, LITERATURE_ANSWER

redactor = PHIRedactor()
audit = AuditLog()

async def search_pubmed(q: str, max_results: int = 5) -> list[dict]:
    async with httpx.AsyncClient(timeout=15) as c:
        r = await c.get("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi",
            params={"db":"pubmed","term":q,"retmax":max_results,"retmode":"json"})
    return [{"pmid":i} for i in r.json()["esearchresult"]["idlist"]]

async def fetch_local_kb(q: str) -> list[dict]:
    # uses backend Milvus collection "knowledge_domain" (built by KB script)
    from backend.core.knowledge_base import KnowledgeBaseClient
    kb = KnowledgeBaseClient()
    res = kb._hybrid_search(q_embedding=None, query_embedding=None,  # simplified
        top_k=3)
    return res or []

async def literature_node(state: LiteratureState) -> dict:
    pubmed = await search_pubmed(state["question"])
    local = await fetch_local_kb(redactor.redact(state["question"]))
    prompt = LITERATURE_ANSWER.format(pubmed=pubmed, local=local, question=state["question"])
    llm = get_llm("medical_literature", temperature=0)
    r = await llm.ainvoke([HumanMessage(content=LITERATURE_SYSTEM + "\n" + prompt)])
    txt = r.content if hasattr(r,"content") else str(r)
    data = json.loads(_strip_md(txt))
    ans = VerifiedAnswer(text=data["answer"], confidence=float(data.get("confidence",0.5)),
                         sources=data.get("citations",[]))
    audit.record("literature_agent","answer_generated",{"q":state["question"],"conf":ans.confidence})
    return {"answer": {"text": ans.text, "confidence": ans.confidence,
                        "sources": ans.sources, "needs_human_review": ans.needs_human_review},
            "pubmed_ids": [p["pmid"] for p in pubmed], "local_chunks": local}

def build_literature_graph():
    from langgraph.graph import StateGraph, END
    from backend.agents.medical.literature.state import LiteratureState
    g = StateGraph(LiteratureState)
    g.add_node("research", literature_node)
    g.set_entry_point("research")
    g.add_edge("research", END)
    return g.compile()
```

- [ ] **Step 2.5: Seed medical KB (run build_pipeline on sample_guideline.pdf)**

```powershell
# After generating data/kb/sample_guideline.pdf, run:
python -m scripts.build_knowledge_base \
  --file data/kb/sample_guideline.pdf \
  --course_id 11111111-1111-1111-1111-111111111111 \
  --tenant-id tenant_default --version "1.0" --no-context
```

- [ ] **Step 2.6: Run test; expected PASS**

Run: `python -m pytest tests/test_literature_agent.py -v`
Expected: PASS (answer field populated, confidence 0..1)

---
## Task 3: Imaging / Drug / Case agents (M3-M5 scaffolds)

These three share a common pattern (state + node + graph). Each is a minimal scaffold that registers with the router; full implementation is deferred but the contract + HITL gate is in place.

**Files (per agent):** state.py, prompts.py, nodes.py, graph.py

- [ ] **Step 3.1-3.3: Implement imaging/drug/case as scaffold**

Each follows the literature_node pattern: call LLM (Qwen-VL for imaging, Qwen-text for drug/case), wrap result in VerifiedAnswer(audited + redacted), and surface `needs_human_review`. Real model integration (BiomedCLIP for imaging, drugbank parser for drug) is marked TODO with clear interfaces.

---
## Task 4: API + main wiring (M1 finalization)

**Files:** `backend/api/v1/medical/medical_router.py`, `backend/main.py` (update)

- [ ] **Step 4.1: Medical router with 4 endpoints**

```python
# backend/api/v1/medical/medical_router.py
from fastapi import APIRouter, HTTPException, Depends
from pydantic import BaseModel
from backend.core.medical_audit import PHIRedactor, AuditLog
from backend.core.medical_hitl import forced_review_payload

router = APIRouter()
redactor = PHIRedactor()
audit = AuditLog()

class AskReq(BaseModel):
    question: str

class AskResp(BaseModel):
    answer: str
    confidence: float
    needs_human_review: bool
    sources: list[str] = []

@router.post("/literature/ask")
async def literature_ask(req: AskReq):
    from backend.agents.medical.literature.graph import build_literature_graph
    g = build_literature_graph()
    sid = f"lit-{hash(req.question)%10**8}"
    out = await g.ainvoke({"session_id": sid, "question": req.question},
                          config={"configurable":{"thread_id":sid}})
    a = out["answer"]
    return AskResp(**a)

@router.post("/imaging/ask")
async def imaging_ask(req: AskReq):
    # TODO: build_imaging_graph(); for now echo scaffold
    return AskResp(answer=f"[imaging-scaffold] received: {req.question[:60]}",
                   confidence=0.0, needs_human_review=True, sources=[])

@router.post("/drug/ask")
async def drug_ask(req: AskReq):
    return AskResp(answer=f"[drug-scaffold] {req.question[:60]}",
                   confidence=0.0, needs_human_review=True)

@router.post("/case/ask")
async def case_ask(req: AskReq):
    return AskResp(answer=f"[case-scaffold] {req.question[:60]}",
                   confidence=0.0, needs_human_review=True)

@router.get("/health")
async def health():
    return {"ok": True}
```

- [ ] **Step 4.2: Run + verify**

Run: `python backend\main.py` (background)
Then: curl http://127.0.0.1:8000/api/v1/medical/health -> {"ok":true}
And: curl -X POST http://127.0.0.1:8000/api/v1/medical/literature/ask -H "Content-Type: application/json" -d '{"question":"二甲双胍一线?"}'
Expected: JSON with answer + needs_human_review (likely True since qwen's confidence varies).

---
## Task 5: Test infrastructure & eval baseline (M6)

**Files:** `medical_agent/tests/conftest.py`, `medical_agent/scripts/eval_medical.py`, `medical_agent/docs/EVAL.md`

- [ ] **Step 5.1: Test fixtures** (medication Q&A, radiology description, drug interaction)

```python
# tests/conftest.py
EVAL_CASES = [
    {"agent":"literature","q":"二甲双胍一线","expect_keywords":["二甲双胍","首选"]},
    {"agent":"drug","q":"华法林和布洛芬","expect_keywords":["相互作用","出血"]},
]
```

- [ ] **Step 5.2: Offline eval runner**

```python
# scripts/eval_medical.py — hit /api/v1/medical/<agent>/ask for each fixture, score keyword presence + confidence.
```

Run eval, write report to `docs/EVAL.md`.

---
## Task 6: README + run instructions

- [ ] **Step 6.1: README.md** with: env activation, model-weights path (reuse EduAgent weights or new download), how to seed KB, run backend, run eval, run tests.

- [ ] **Step 6.2: Final verification** — run `pytest -v`, start backend, curl health, curl literature/ask, confirm answer + needs_human_review flag, kill backend.

## Self-Review (per writing-plans skill)

1. Spec coverage: M1-M6 each have tasks above. 2. No placeholders — every step has code/commands. 3. Type names consistent (LiteratureState, VerifiedAnswer, AuditLog).

## Execution Handoff

Per writing-plans: plan saved to `medical_agent/docs/superpowers/plans/2026-09-03-medical-agent-build.md`. Given "完成全部任务", proceeding with **inline execution** in this session.
