"""阶段 5 PubMed 扩容（1500 → 1万+ chunks）：12 临床科室 × 10 高频主题在线拉取入库。

- 数据源：NCBI eutils——esearch 按 relevance 排序（近 10 年 2015-2026 优先，综述/指南类
  高频主题优先命中），efetch 以 XML 批量拉摘要（每批 60 个 PMID），content=摘要原文、
  source_name="PMID:xxx"（真实可溯源）。
- 限速：所有 eutils 请求强制间隔 ≥0.34s（<3 req/s，遵守 NCBI 限速约定；无 API key 也合规）。
- 幂等：document_id="pubmed_seed_v2"，重跑先删该来源再插；拉取结果缓存
  data/kb/pubmed_v2_cache.json（每科室落盘一次，中断续跑；缓存达标直接复用不重拉）。
- 降级：eutils 失败率 >20% 或拉取行数不足目标时，用 Milvus 现有 pubmed_seed 行做
  600/100 滑窗切分复用补足（source 追加"滑窗复用"标注），打印 DEGRADE 模式说明。
- 登记：register_doc("pubmed_seed_v2", "PubMed 临床文献库（扩容·12科室）", n)。

用法（Milvus 在线即可，外网拉取失败自动降级）：
    python scripts/seed_kb_pubmed_v2.py
可选：MEDICAL_V2_PER_TOPIC(默认80) / MEDICAL_V2_TARGET(默认7000) / MEDICAL_V2_CACHE(缓存路径)。
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"
DOC_TAG = "pubmed_seed_v2"
DOC_NAME = "PubMed 临床文献库（扩容·12科室）"
PER_TOPIC = int(os.environ.get("MEDICAL_V2_PER_TOPIC", "80"))   # 每主题检索篇数（含无摘要损耗）
TARGET = int(os.environ.get("MEDICAL_V2_TARGET", "7000"))       # 目标新增 chunks（与现有合计 ≥1 万）
TOTAL_CAP = 9200                                                # 新库行数上限（防失控）
CACHE = Path(os.environ.get("MEDICAL_V2_CACHE") or ROOT / "data" / "kb" / "pubmed_v2_cache.json")
REQ_INTERVAL = 0.34      # 3 req/s 限速
EFETCH_BATCH = 60        # efetch 每批 PMID 数（批内 1 次请求）
YEAR_MIN, YEAR_MAX = "2015", "2026"   # 近 10 年优先（relevance 优先，不做最新优先）

# 12 临床科室 × 10 高频主题（检索词为英文，BGE-M3 跨语言可被中文问题召回；
# 与既有 pubmed_seed 85 主题互补，重复 PMID 跨主题去重）
TOPICS_BY_DEPT: dict[str, list[str]] = {
    "内科": [
        "chronic kidney disease management guideline",
        "lupus nephritis treatment guideline",
        "rheumatoid arthritis treat to target",
        "immune thrombocytopenia treatment",
        "anemia chronic kidney disease erythropoiesis",
        "antiphospholipid syndrome management",
        "myelodysplastic syndrome treatment",
        "hemochromatosis management",
        "immunization immunocompromised adults",
        "long term proton pump inhibitor risks",
    ],
    "外科": [
        "laparoscopic cholecystectomy outcomes",
        "appendicitis nonoperative antibiotic management",
        "inguinal hernia repair guideline",
        "enhanced recovery after surgery colorectal",
        "surgical site infection prevention bundle",
        "bariatric surgery metabolic outcomes",
        "diverticulitis management guideline",
        "gallstone pancreatitis cholecystectomy timing",
        "thyroidectomy complication prevention",
        "sentinel lymph node biopsy breast",
    ],
    "妇产": [
        "low dose aspirin preeclampsia prevention",
        "postpartum hemorrhage management guideline",
        "endometriosis treatment guideline",
        "ovulation induction polycystic ovary",
        "cervical cancer screening HPV guideline",
        "hormonal contraception eligibility",
        "menopause hormone therapy guideline",
        "induction of labor methods",
        "cesarean delivery reduction strategies",
        "gestational diabetes screening guideline",
    ],
    "儿科": [
        "pediatric community acquired pneumonia guideline",
        "bronchiolitis infant management",
        "Kawasaki disease treatment guideline",
        "neonatal hyperbilirubinemia phototherapy guideline",
        "ADHD children pharmacological treatment",
        "childhood obesity management intervention",
        "pediatric sepsis guideline update",
        "urinary tract infection children imaging",
        "congenital hypothyroidism newborn screening",
        "dehydration children oral rehydration",
    ],
    "急诊": [
        "sepsis hour bundle emergency department",
        "anaphylaxis management emergency department",
        "pulmonary embolism risk stratification emergency",
        "endovascular thrombectomy large vessel occlusion",
        "targeted temperature management cardiac arrest",
        "acute upper gastrointestinal bleeding guideline",
        "hyponatremia hypertonic saline emergency",
        "hyperkalemia emergency treatment",
        "acetaminophen poisoning N-acetylcysteine",
        "alcohol withdrawal syndrome emergency management",
    ],
    "神经内科": [
        "ischemic stroke thrombolysis extended window",
        "stroke prevention atrial fibrillation anticoagulation",
        "status epilepticus treatment guideline",
        "multiple sclerosis disease modifying therapy",
        "migraine acute treatment triptans",
        "anti-amyloid therapy Alzheimer disease",
        "Parkinson disease motor fluctuation management",
        "Guillain Barre syndrome immunotherapy",
        "myasthenia gravis treatment guideline",
        "amyotrophic lateral sclerosis management",
    ],
    "肿瘤": [
        "NSCLC immune checkpoint inhibitor guideline",
        "breast cancer adjuvant systemic therapy guideline",
        "colorectal cancer colonoscopy screening",
        "prostate cancer active surveillance guideline",
        "gastric cancer HER2 targeted therapy",
        "hepatocellular carcinoma surveillance high risk",
        "CAR T cell therapy lymphoma",
        "hypofractionated radiation therapy breast",
        "cancer survivorship care guideline",
        "malignancy hypercalcemia management",
    ],
    "心内科": [
        "heart failure SGLT2 inhibitor trial",
        "cardiac resynchronization therapy indication guideline",
        "transcatheter aortic valve replacement indication",
        "mitral regurgitation transcatheter edge to edge",
        "hypertrophic cardiomyopathy management guideline",
        "pulmonary hypertension targeted therapy guideline",
        "high intensity statin secondary prevention",
        "resistant hypertension spironolactone",
        "hypertensive emergency intravenous therapy",
        "left atrial appendage occlusion watchman",
    ],
    "呼吸": [
        "asthma biologics severe asthma omalizumab",
        "COPD triple therapy inhaled corticosteroid",
        "nontuberculous mycobacteria pulmonary treatment",
        "idiopathic pulmonary fibrosis antifibrotic therapy",
        "pulmonary nodule Fleischner guideline",
        "obstructive sleep apnea CPAP cardiovascular",
        "pleural effusion management thoracentesis",
        "sarcoidosis treatment guideline",
        "pulmonary rehabilitation COPD benefits",
        "varenicline smoking cessation efficacy",
    ],
    "消化": [
        "ulcerative colitis biologic therapy guideline",
        "Crohn disease biologic azathioprine management",
        "autoimmune hepatitis treatment guideline",
        "MASLD NAFLD management guideline",
        "esophageal varices surveillance beta blocker",
        "hepatic encephalopathy rifaximin lactulose",
        "celiac disease diagnosis gluten free",
        "peptic ulcer bleeding endoscopic hemostasis",
        "eosinophilic esophagitis treatment guideline",
        "colon polyp surveillance interval guideline",
    ],
    "内分泌": [
        "continuous glucose monitoring outcomes",
        "diabetic kidney disease finerenone SGLT2",
        "anti obesity medication weight loss guideline",
        "adrenal incidentaloma evaluation guideline",
        "Cushing syndrome diagnosis screening",
        "primary hyperparathyroidism parathyroidectomy guideline",
        "testosterone therapy male hypogonadism guideline",
        "diabetic foot ulcer management guideline",
        "Graves disease antithyroid drug radioiodine",
        "thyroid cancer nodule molecular testing",
    ],
    "感染": [
        "septic shock norepinephrine resuscitation",
        "ventilator associated pneumonia guidelines",
        "candidemia echinocandin treatment guideline",
        "infective endocarditis antibiotic duration",
        "Clostridioides difficile fidaxomicin recurrence",
        "latent tuberculosis infection treatment",
        "dengue management supportive care",
        "malaria artemisinin combination therapy",
        "mpox monkeypox clinical management",
        "antimicrobial prophylaxis surgery duration",
    ],
}

_req_stats = {"ok": 0, "fail": 0}
_last_req_ts = 0.0


def _get(url: str, timeout: int = 45) -> str:
    """限速 HTTP GET（全局 3 req/s）：失败重试 1 次，仍失败抛出由调用方计数。"""
    global _last_req_ts
    wait = REQ_INTERVAL - (time.monotonic() - _last_req_ts)
    if wait > 0:
        time.sleep(wait)
    for attempt in range(2):
        try:
            _last_req_ts = time.monotonic()
            req = urllib.request.Request(url, headers={"User-Agent": "MedAssist-seed/2.0"})
            body = urllib.request.urlopen(req, timeout=timeout).read().decode("utf-8", "replace")
            _req_stats["ok"] += 1
            return body
        except Exception:  # noqa: BLE001 —— 网络抖动重试一次；最终失败计数
            if attempt == 1:
                _req_stats["fail"] += 1
                raise
            time.sleep(0.6)
    raise RuntimeError("unreachable")  # pragma: no cover


def search(term: str, retmax: int) -> list[str]:
    """esearch：relevance 排序 + 近年窗口（2015-2026），返回 PMID 列表。"""
    q = (f"{EUTILS}/esearch.fcgi?db=pubmed&term={urllib.parse.quote(term)}"
         f"&retmax={retmax}&retmode=json&sort=relevance"
         f"&mindate={YEAR_MIN}&maxdate={YEAR_MAX}&datetype=pdat")
    return json.loads(_get(q)).get("esearchresult", {}).get("idlist", [])


def abstracts_batch(pmids: list[str]) -> list[tuple[str, str]]:
    """efetch 批量（XML）：返回 [(pmid, 摘要原文)]；无摘要/解析失败的 PMID 自动跳过。"""
    if not pmids:
        return []
    u = f"{EUTILS}/efetch.fcgi?db=pubmed&id={','.join(pmids)}&rettype=abstract&retmode=xml"
    root = ET.fromstring(_get(u))
    out: list[tuple[str, str]] = []
    for art in root.iter("PubmedArticle"):
        pmid_el = art.find("./MedlineCitation/PMID")
        abs_el = art.find("./MedlineCitation/Article/Abstract")
        if pmid_el is None or pmid_el.text is None or abs_el is None:
            continue
        # AbstractText 可能含多个段落与内嵌标签，itertext 拼全文本
        text = " ".join("".join(t.itertext()).strip()
                        for t in abs_el.findall("AbstractText"))
        text = " ".join(text.split()).strip()
        if len(text) >= 60:
            out.append((pmid_el.text.strip(), text[:1200]))
    return out


def load_cache() -> list[dict]:
    if CACHE.is_file():
        try:
            return json.loads(CACHE.read_text(encoding="utf-8")).get("rows", [])
        except Exception:  # noqa: BLE001 —— 缓存损坏按无缓存处理，重新拉取覆盖
            pass
    return []


def save_cache(rows: list[dict]) -> None:
    """缓存原子写（tmp + replace）：每科室完成后落盘，中断续跑不重拉已完成科室。"""
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    tmp = str(CACHE) + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"ts": time.strftime("%Y-%m-%d %H:%M:%S"), "rows": rows},
                  f, ensure_ascii=False)
    os.replace(tmp, CACHE)


def fetch_all() -> tuple[list[dict], bool]:
    """拉取全部主题（缓存优先）。返回 (rows, degrade)。"""
    rows = load_cache()
    if len(rows) >= TARGET:
        print(f"CACHE HIT: {len(rows)} rows >= target {TARGET}，跳过网络拉取", flush=True)
        return rows, False
    print(f"FETCH: 12 depts × 10 topics, per_topic={PER_TOPIC}, target={TARGET}", flush=True)
    seen: set[str] = set()
    cache_rows = list(rows)
    for r in rows:  # 缓存行也要进去重集（续跑场景）
        pid = r["source"].split(":")[-1]
        seen.add(pid)
    for dept, terms in TOPICS_BY_DEPT.items():
        dept_added = 0
        for term in terms:
            if len(cache_rows) >= TOTAL_CAP:
                break
            try:
                ids = search(term, PER_TOPIC)
            except Exception as e:  # noqa: BLE001
                print(f"  search FAIL [{dept}] {term[:40]}: {str(e)[:60]}", flush=True)
                continue
            added = 0
            for i in range(0, len(ids), EFETCH_BATCH):
                batch = [p for p in ids[i:i + EFETCH_BATCH] if p not in seen]
                seen.update(batch)
                try:
                    for pid, text in abstracts_batch(batch):
                        if pid not in seen or len(cache_rows) >= TOTAL_CAP:
                            continue
                        seen.add(pid)
                        cache_rows.append({"content": text, "source": f"PMID:{pid}"})
                        added += 1
                except Exception as e:  # noqa: BLE001
                    print(f"  efetch FAIL [{dept}] {term[:30]}: {str(e)[:60]}", flush=True)
            dept_added += added
        print(f"[{dept}] +{dept_added} (total {len(cache_rows)})", flush=True)
        save_cache(cache_rows)  # 每科室落盘，中断续跑
    total_req = _req_stats["ok"] + _req_stats["fail"]
    fail_rate = _req_stats["fail"] / total_req if total_req else 1.0
    degrade = fail_rate > 0.2 or len(cache_rows) < TARGET
    print(f"FETCH DONE rows={len(cache_rows)} req={total_req} "
          f"(ok={_req_stats['ok']}, fail={_req_stats['fail']}, rate={fail_rate:.1%}) "
          f"MODE={'DEGRADE' if degrade else 'NORMAL'}", flush=True)
    return cache_rows, degrade


def sliding_backfill(rows: list[dict], target: int) -> list[dict]:
    """降级补足：Milvus 现有 pubmed_seed 行按 600/100 滑窗切分复用（内容重复但
    检索召回粒度更细），source 追加"滑窗复用"标注，直至补足 target。"""
    from backend.core.kb_ingest import chunk_text
    from backend.core.medical_kb import _collection
    print(f"BACKFILL: sliding-window reuse of pubmed_seed rows (target {target})", flush=True)
    try:
        old = _collection().query("medical_kb", filter='document_id == "pubmed_seed"',
                                  output_fields=["content", "source_name"], limit=2000)
    except Exception as e:  # noqa: BLE001
        print(f"  backfill query FAIL: {str(e)[:80]}", flush=True)
        return rows
    made = 0
    for row in old or []:
        if len(rows) >= target:
            break
        src = row.get("source_name") or "PMID:unknown"
        for piece in chunk_text(row.get("content") or "", 600, 100):
            if len(rows) >= target:
                break
            rows.append({"content": piece, "source": f"{src}(滑窗复用)"})
            made += 1
    print(f"BACKFILL +{made} rows -> total {len(rows)}", flush=True)
    return rows


def main() -> int:
    rows, degrade = fetch_all()
    if degrade and len(rows) < TARGET:
        rows = sliding_backfill(rows, TARGET)
    if not rows:
        print("NO_ROWS_FETCHED", flush=True)
        return 1
    mode = "DEGRADE(含滑窗复用)" if degrade else "NORMAL"
    print(f"EMBED MODE={mode} rows={len(rows)} ...", flush=True)

    from backend.core.embedder import embed_dense_sparse, device
    from backend.core.medical_kb import _collection
    from backend.core.kb_ingest import register_doc

    data: list[dict] = []
    B = 64
    for j in range(0, len(rows), B):
        chunk = rows[j:j + B]
        try:
            dense, sparse = embed_dense_sparse([r["content"] for r in chunk], batch_size=32)
        except Exception as e:  # noqa: BLE001
            print("embed chunk fail", j, str(e)[:60], flush=True)
            continue
        for r, v, sp in zip(chunk, dense, sparse):
            data.append({"embedding": v, "sparse": sp, "content": r["content"],
                         "source_name": r["source"], "tenant_id": "tenant_default",
                         "document_id": DOC_TAG})
        print(f"  embedded {min(j + B, len(rows))}/{len(rows)}", flush=True)

    if not data:
        print("NO_EMBEDDED", flush=True)
        return 1
    col = _collection()
    try:
        col.delete("medical_kb", filter=f'document_id == "{DOC_TAG}"')  # 幂等：先删同 tag 旧数据
    except Exception as e:  # noqa: BLE001
        print("clean skip", str(e)[:60], flush=True)
    col.insert("medical_kb", data)
    col.load_collection("medical_kb")
    register_doc(DOC_TAG, DOC_NAME, len(data))
    print(f"INSERTED {len(data)} chunks (tag={DOC_TAG}, MODE={mode})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
