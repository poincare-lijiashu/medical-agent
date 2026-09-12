"""C3/d7 真实知识库注入：PubMed 拉取大量真实摘要 → 批量编码 → 原子写入 Milvus `medical_kb`。

- 真实可溯源：content = 某 PMID 摘要原文，source_name = "PMID:xxx"。
- 自包含：编码走 backend.core.embedder（自动 GPU/CPU，本地 bge-m3 权重）。
- 快而稳：批量前向 + max_length=256 截断；上限 TOTAL_CAP；先编码成功再删旧插新（避免空窗）。
- 幂等：所有行 document_id="pubmed_seed"，不碰 sample_guideline；跨主题按 PMID 去重。

用法（需 Milvus 在 19531 运行）：
    python scripts/seed_kb_pubmed.py
可选：MEDICAL_TOTAL_CAP(默认900) / MEDICAL_PER_TOPIC(默认20)。
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from backend.core.embedder import embed_dense_sparse  # noqa: E402
from backend.core.medical_kb import _collection  # noqa: E402

EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"
DOC_TAG = "pubmed_seed"
PER_TOPIC = int(os.environ.get("MEDICAL_PER_TOPIC", "20"))
TOTAL_CAP = int(os.environ.get("MEDICAL_TOTAL_CAP", "1500"))

# 覆盖多专科的真实文献检索词（英文，BGE-M3 跨语言可被中文问题召回）
TOPICS = [
    "metformin type 2 diabetes first-line therapy", "HbA1c glycemic target diabetes guideline",
    "SGLT2 inhibitor cardiorenal benefit", "GLP-1 receptor agonist obesity diabetes",
    "insulin therapy type 1 diabetes", "diabetic ketoacidosis management",
    "hypothyroidism levothyroxine treatment", "thyroid nodule evaluation",
    "statin LDL-C target prevention", "hypertension diagnosis treatment guideline",
    "aspirin acute coronary syndrome", "heart failure guideline therapy",
    "atrial fibrillation anticoagulation", "ACS antiplatelet P2Y12",
    "ACE inhibitor heart failure", "beta blocker post MI",
    "asthma inhaled corticosteroid GINA", "COPD exacerbation management",
    "community-acquired pneumonia antibiotic", "pulmonary embolism anticoagulation",
    "lung cancer screening CT", "H pylori eradication therapy",
    "GERD proton pump inhibitor", "cirrhosis ascites management",
    "acute pancreatitis treatment", "IBS diagnosis management",
    "chronic kidney disease management", "AKI fluid management",
    "UTI antibiotic treatment", "benign prostatic hyperplasia treatment",
    "nephrolithiasis management", "acute ischemic stroke thrombolysis",
    "epilepsy treatment guideline", "migraine prophylaxis",
    "Parkinson disease management", "major depression SSRI treatment",
    "generalized anxiety treatment", "Alzheimer disease management",
    "sepsis bundle management", "influenza antiviral treatment",
    "HIV antiretroviral initiation", "urinary tract infection resistance",
    "antimicrobial stewardship", "iron deficiency anemia treatment",
    "venous thromboembolism DOAC", "deep vein thrombosis treatment",
    "rheumatoid arthritis methotrexate", "systemic lupus erythematosus management",
    "gout treatment guideline", "preeclampsia management",
    "gestational diabetes screening", "pediatric fever management",
    "childhood asthma step therapy", "cancer immunotherapy checkpoint inhibitor",
    "chemotherapy-induced nausea prevention", "cancer pain management opioid",
    "tumor lysis syndrome prevention", "cardiac arrest ACLS",
    "anaphylaxis epinephrine", "diabetic hyperglycemic emergency",
    "septic shock vasopressor", "trauma massive transfusion",
    "atopic dermatitis treatment", "cellulitis antibiotic therapy",
    "diabetic retinopathy screening", "age-related macular degeneration treatment",
    "osteoporosis bisphosphonate therapy", "low back pain management",
    "warfarin bleeding risk interaction", "drug-induced liver injury",
    "polypharmacy older adults deprescribing", "QT prolongation drug risk",
    "contrast-induced nephropathy prevention",
    # 常见病/门诊高频（提升普通诉求召回率）
    "common cold symptomatic treatment zinc", "influenza vaccination effectiveness",
    "oseltamivir early treatment influenza", "acute gastroenteritis oral rehydration",
    "tension type headache management", "allergic rhinitis intranasal corticosteroid",
    "acute bronchitis antibiotic overuse", "uncomplicated cystitis short course antibiotics",
    "pediatric fever antipyretic guideline", "functional dyspepsia management",
    "insomnia cognitive behavioral therapy", "dysmenorrhea NSAID treatment",
]


def _get(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "MedAssist-seed/1.0"})
    return urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")


def search(term: str, retmax: int) -> list[str]:
    u = f"{EUTILS}/esearch.fcgi?db=pubmed&term={urllib.parse.quote(term)}&retmax={retmax}&retmode=json"
    return json.loads(_get(u)).get("esearchresult", {}).get("idlist", [])


def abstract(pmid: str) -> str:
    u = f"{EUTILS}/efetch.fcgi?db=pubmed&id={pmid}&rettype=abstract&retmode=text"
    return _get(u).strip()[:1200]


def main() -> int:
    seen: set[str] = set()
    rows: list[dict] = []
    for i, term in enumerate(TOPICS, 1):
        if len(rows) >= TOTAL_CAP:
            break
        try:
            ids = search(term, PER_TOPIC)
        except Exception as e:  # noqa: BLE001
            print("search FAIL", term[:36], str(e)[:40], flush=True); continue
        added = 0
        for pid in ids:
            if len(rows) >= TOTAL_CAP or pid in seen:
                continue
            try:
                a = abstract(pid)
            except Exception:  # noqa: BLE001
                continue
            seen.add(pid)
            if len(a) < 60:
                continue
            rows.append({"content": a, "source": f"PMID:{pid}"})
            added += 1
            time.sleep(0.28)
        print(f"[{i}/{len(TOPICS)}] {term[:38]:38} +{added} (total {len(rows)})", flush=True)

    if not rows:
        print("NO_ROWS_FETCHED", flush=True); return 1

    print(f"EMBEDDING {len(rows)} batched ...", flush=True)
    data = []
    B = 64
    for j in range(0, len(rows), B):
        chunk = rows[j:j + B]
        try:
            dense, sparse = embed_dense_sparse([r["content"] for r in chunk], batch_size=32)  # 窗口走配置默认 1024（任务A）
        except Exception as e:  # noqa: BLE001
            print("embed chunk fail", j, str(e)[:60], flush=True); continue
        for r, v, sp in zip(chunk, dense, sparse):
            data.append({"embedding": v, "sparse": sp, "content": r["content"], "source_name": r["source"],
                         "tenant_id": "tenant_default", "document_id": DOC_TAG})
        print(f"  embedded {min(j+B, len(rows))}/{len(rows)}", flush=True)

    if not data:
        print("NO_EMBEDDED", flush=True); return 1

    col = _collection()
    try:
        col.delete("medical_kb", filter=f'document_id == "{DOC_TAG}"')
    except Exception as e:  # noqa: BLE001
        print("clean skip", str(e)[:60], flush=True)
    col.insert("medical_kb", data)
    col.load_collection("medical_kb")
    from backend.core.kb_ingest import register_doc
    register_doc(DOC_TAG, "PubMed 真实文献库（内置·85 主题）", len(data))
    print(f"INSERTED {len(data)} real PubMed abstracts", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
