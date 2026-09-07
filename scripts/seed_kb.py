"""Seed Milvus `medical_kb` with a sample 2 型糖尿病 guideline (本地指南样例).

自包含：向量走 backend.core.kb_ingest（BGE-M3 dense + sparse，混合检索 schema 一致），
Milvus 走 settings.milvus_uri + db_base.get_milvus_token。不依赖 EduAgent。
重跑会按 document_id="sample_guideline" 幂等刷新样例，不动 pubmed_seed 行。

用法：
    D:\\software_work\\conda_envs\\edu_agent\\python.exe scripts/seed_kb.py
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

import fitz  # PyMuPDF

from backend.config import settings

GUIDE = ROOT / "data" / "kb" / "sample_guideline.pdf"
DOC_TAG = "sample_guideline"

GUIDE_TEXT = (
    "《2 型糖尿病基层诊疗指南（2024 摘要）》\n\n"
    "1. 一线治疗：二甲双胍 500mg 2-3 次/日，餐时或餐后即刻服用。\n"
    "2. HbA1c 控制目标：一般 <7%；老年/低血糖风险高者可放宽至 7.5-8%。\n"
    "3. 联合用药：二甲双胍 + SGLT2i 或 GLP-1RA，适用于合并 ASCVD/CKD/HF 者。\n"
    "4. 监测：每 3 个月 HbA1c，每 6 个月 eGFR/UACR/眼底。\n"
    "5. 转诊上级：HbA1c>9%、急性并发症、合并妊娠。\n"
)


def ensure_pdf():
    GUIDE.parent.mkdir(parents=True, exist_ok=True)
    if GUIDE.exists():
        return
    d = fitz.open()
    p = d.new_page(width=595, height=842)
    p.insert_font(fontname="cjk", fontfile="C:/Windows/Fonts/msyh.ttc")
    p.insert_textbox(fitz.Rect(50, 50, 545, 792), GUIDE_TEXT,
                     fontname="cjk", fontsize=12, lineheight=1.5)
    d.save(str(GUIDE))
    d.close()
    print("WROTE", GUIDE)


def main():
    ensure_pdf()
    # 复用与 seed_kb_docs/admin 上传一致的混合摄取管道（dense+sparse、幂等、写 manifest），
    # 避免旧版只写 dense 与混合检索 schema 不一致的问题。
    from backend.core.kb_ingest import ingest_document
    res = ingest_document("sample_guideline.pdf", GUIDE.read_bytes(), doc_tag=DOC_TAG)
    print(f"INSERTED {res['inserted']} sample chunks (weights={settings.bge_m3_path})")


if __name__ == "__main__":
    main()
