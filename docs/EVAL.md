# Medical Agent Evaluation Protocol

## Confidence / HITL gate

- `confidence < 0.70` (model self-eval) → `needs_human_review=True`.
- Imaging / Drug / Case: `needs_human_review=True` always (forced).
- PHI regex (CN): phone `1[3-9]\d{9}`, email, 18-digit ID-card → placeholder.

## Literature agent test cases

| Q | Expect keywords | Source |
|---|---|---|
| 二甲双胍是一线治疗吗 | 二甲双胍 / 首选 | KB seeded guideline |
| 2 型糖尿病 HbA1c 控制目标 | <7% / 7.5-8% (老年) | KB |
| 华法林 布洛芬 相互作用 | TODO drug scaffold | drug |
| 头颅 CT 影像判读 | TODO imaging scaffold (BiomedCLIP) | imaging |
| 多模态病例摘要 | TODO case scaffold | case |

## Run

```powershell
# 单元/集成回归（离线）
python -m pytest tests/ -v

# 可执行评测基线（需后端在 127.0.0.1:8001 运行）
python scripts\run_eval.py
# 输出 hit_rate 摘要 + data/eval/eval_result.json；退出码 0 当 hit_rate>=0.8
```

## Production criteria (not yet met)

- ≥20 real clinical cases per agent with ground-truth labels
- Red-team test set (adversarial medical queries)
- Offline eval harness on a held-out set with Cohen's κ between AI and clinician
- Latency SLO p95 < 8 s per query on CPU

## Commercial-readiness backlog

- [x] C1 executable eval baseline + hardened unit tests (drug rules, HITL gate) — `scripts/run_eval.py` 9/9, 19 pytest
- [x] Self-contained: local bge-m3 weights + own docker-compose; `medical_kb` now uses `embedder`/settings (no EduAgent fallback)
- [x] C2 JWT auth (PyJWT HS256 + pbkdf2) + role-based Bearer gate on all `/ask` endpoints — verified 401 (no/bad token) vs 200
- [x] C3 real KB: 30 PubMed abstracts across 10 topics (`scripts/seed_kb_pubmed.py`), source=`PMID:xxx`; cross-lingual retrieval
- [x] C4 imaging/case agent: Qwen-VL (`qwen3-vl-plus`) image→structured findings, forced HITL; drug DB expanded to 15 pairs

## Remaining for regulated production (honest gaps)

- Replace PubMed-seeded demo corpus with the hospital's licensed authoritative guideline library.
- Refresh demo credentials / move user store from JSON file to DB; add rate limiting + TLS + audit retention.
- Regulatory: this is an assistive tool, not a cleared medical device; clinical validation + sign-off workflow required.
- Full drug DB (drugbank/OpenFDA) for exhaustive interaction coverage; BiomedCLIP for content-based image retrieval.
