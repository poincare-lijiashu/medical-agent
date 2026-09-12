"""Seed Milvus `medical_kb` with a sample 2 型糖尿病 guideline (本地指南样例).

自包含：向量走 backend.core.kb_ingest（BGE-M3 dense + sparse，混合检索 schema 一致），
Milvus 走 settings.milvus_uri + db_base.get_milvus_token。不依赖 EduAgent。
重跑会按 document_id="sample_guideline" 幂等刷新样例，不动 pubmed_seed 行。

⚠️ demo 来源：sample_guideline 为内置样例指南（教学/联调用），待阶段 5 替换为
院方授权的真实指南语料（scripts/seed_kb_docs.py 重摄覆盖）；替换前检索仍可用。

用法：
    python scripts/seed_kb.py
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


def _cjk_font() -> tuple[str, str]:
    """任务6 可移植性小修：跨平台中文字体选择（此前硬编码 C:/Windows/Fonts/msyh.ttc，
    Linux/macOS 上种子脚本直接失败）。优先系统常见 CJK 字体（Windows/Linux/macOS 各候选），
    全部缺失时回退 PyMuPDF 内置 CJK（china-s，无外部文件依赖）。"""
    import os
    windir = os.environ.get("WINDIR") or r"C:\Windows"
    candidates = [
        os.path.join(windir, "Fonts", "msyh.ttc"),          # Windows 微软雅黑
        os.path.join(windir, "Fonts", "simhei.ttf"),        # Windows 黑体
        "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",   # Linux 文泉驿
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",  # Linux Noto
        "/System/Library/Fonts/PingFang.ttc",               # macOS 苹方
    ]
    for p in candidates:
        if os.path.isfile(p):
            return "cjk", p
    return "china-s", ""  # PyMuPDF 内置 CJK 字体名（fontfile 留空）


def ensure_pdf():
    GUIDE.parent.mkdir(parents=True, exist_ok=True)
    if GUIDE.exists():
        return
    d = fitz.open()
    p = d.new_page(width=595, height=842)
    fname, ffile = _cjk_font()
    if ffile:
        p.insert_font(fontname=fname, fontfile=ffile)
    p.insert_textbox(fitz.Rect(50, 50, 545, 792), GUIDE_TEXT,
                     fontname=fname, fontsize=12, lineheight=1.5)
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
