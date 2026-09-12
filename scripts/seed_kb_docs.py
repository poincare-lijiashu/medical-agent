"""国内·知识库指南语料摄取：两部分职责。

【v1 离线目录模式】把 data/kb_docs/ 下的指南文件摄取进 Milvus `medical_kb`。
支持 .pdf(PyMuPDF 抽取) / .md / .txt。全程不联网：文本抽取本地、编码走 embedder(GPU 若有否则 CPU)。
切块：句子边界友好、size≈600、overlap≈100；source_name 取文件名（去扩展名）；按 content-hash 去重。
幂等：document_id="kb_docs"，重跑先删该来源再插。

【v2 阶段5 真实指南要点】`python scripts/seed_kb_docs.py v2`：
入库"真实出处"的权威指南推荐意见条目（tag="clinical_guidelines_v2"）——
文本为脚本内嵌的要点摘编初稿，但每条均标注真实指南名称+年份（不虚构页码），
条目落盘 data/kb_guidelines_v2/*.md 供审计与随 repo 提交。
入库成功后把旧 demo 两条（local_corpus / sample_guideline）在 manifest 中保留数据、
描述追加"（已废弃，由 clinical_guidelines_v2 替换）"（不删保审计）。

用法：
    python scripts/seed_kb_docs.py        # v1 目录模式
    python scripts/seed_kb_docs.py v2     # 阶段5 真实指南要点
"""
from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

KB_DOCS_DIR = ROOT / "data" / "kb_docs"
KB_GUIDELINES_V2_DIR = ROOT / "data" / "kb_guidelines_v2"
DOC_TAG = "kb_docs"
DOC_TAG_V2 = "clinical_guidelines_v2"
SUPPORTED = (".pdf", ".md", ".markdown", ".txt")


def chunk_text(text: str, size: int = 600, overlap: int = 100) -> list[str]:
    """按句子边界聚合成 ~size 的块，块间带 overlap 个字符回退，避免切断语义。"""
    text = (text or "").strip()
    if not text:
        return []
    # 归一空白
    text = " ".join(text.split()) if "\n" not in text else text
    parts, buf = [], ""
    sentences = _split_sentences(text)
    for s in sentences:
        if len(buf) + len(s) > size and buf:
            parts.append(buf.strip())
            buf = buf[-overlap:] if overlap and len(buf) > overlap else ""
        buf += s
    if buf.strip():
        parts.append(buf.strip())
    return [p for p in (x.strip() for x in parts) if p]


def _split_sentences(text: str) -> list[str]:
    out, cur = [], ""
    for ch in text:
        cur += ch
        if ch in "。！？；.!?\n":
            out.append(cur)
            cur = ""
    if cur:
        out.append(cur)
    return out


def extract_text(path: Path) -> str:
    """pdf 用 PyMuPDF 取整页文本；md/txt 直接按 utf-8 读。"""
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        import fitz
        doc = fitz.open(str(path))
        return "".join(page.get_text() for page in doc)
    return path.read_text(encoding="utf-8", errors="replace")


def source_name_for(path: Path) -> str:
    return path.stem[:200]


def build_rows(path: Path) -> list[dict]:
    text = extract_text(path)
    src = source_name_for(path)
    return [{"content": c, "source": src} for c in chunk_text(text)]


def collect(files: list[Path]) -> list[dict]:
    rows, seen = [], set()
    for f in files:
        for r in build_rows(f):
            h = hashlib.sha1(r["content"].encode("utf-8")).hexdigest()
            if h in seen:
                continue
            seen.add(h)
            rows.append(r)
    return rows


def main() -> int:
    if not KB_DOCS_DIR.is_dir():
        print(f"NO_DIR {KB_DOCS_DIR}（把 .pdf/.md/.txt 指南放入该目录）")
        return 1
    files = sorted(p for p in KB_DOCS_DIR.iterdir() if p.suffix.lower() in SUPPORTED)
    if not files:
        print("NO_FILES 放几个指南文件到 data/kb_docs/ 再运行")
        return 1
    rows = collect(files)
    print(f"FILES={len(files)} CHUNKS={len(rows)} EMBEDDING...", flush=True)
    if not rows:
        print("NO_ROWS"); return 1
    from backend.core.embedder import embed_dense_sparse, device
    from backend.core.medical_kb import _collection
    vecs = []
    sps = []
    for j in range(0, len(rows), 32):
        d, s = embed_dense_sparse([r["content"] for r in rows[j:j + 32]], batch_size=32)  # 窗口走配置默认 1024（任务A）
        vecs += d; sps += s
    print(f"embedded {len(vecs)} device={device()}", flush=True)
    data = [{"embedding": v, "sparse": sp, "content": r["content"], "source_name": r["source"],
             "tenant_id": "tenant_default", "document_id": DOC_TAG}
            for r, v, sp in zip(rows, vecs, sps)]
    col = _collection()
    try:
        col.delete("medical_kb", filter=f'document_id == "{DOC_TAG}"')
    except Exception as e:  # noqa: BLE001
        print("clean skip", str(e)[:60], flush=True)
    col.insert("medical_kb", data)
    col.load_collection("medical_kb")
    print(f"INSERTED {len(data)} chunks from {len(files)} guideline file(s)", flush=True)
    return 0


# ======================================================================
# 阶段5 v2：真实出处指南要点（tag=clinical_guidelines_v2）
# ----------------------------------------------------------------------
# 编写纪律：条目为"要点摘编初稿"，但每条标注真实指南全名+年份，内容取该指南
# 公开可获取的核心推荐意见（广泛共识、保守表述），不虚构具体页码/条目编号。
# 条目以空行分段落盘 data/kb_guidelines_v2/*.md（可审计、随 repo 提交），
# 入库时每段为一个检索单元，source_name = 指南名称（年份）。
# ======================================================================

V2_DISCLAIMER = ("> 【阶段5 真实指南要点库：条目为指南核心推荐意见的要点摘编（非完整指南文本），"
                 "出处为真实指南名称与年份；临床决策以指南原文全文与医师判断为准。】")

# (文件名, 指南全称, 年份, [推荐意见条目...]) —— 8 部真实出处指南
GUIDELINES_V2: list[tuple[str, str, int, list[str]]] = [
    ("01_china_hypertension_2024.md", "中国高血压防治指南（2024年修订版）", 2024, [
        "高血压诊断：在未使用降压药物情况下，非同日 3 次测量诊室血压收缩压≥140 mmHg 和/或舒张压≥90 mmHg 即可诊断。",
        "家庭自测血压诊断阈值为≥135/85 mmHg；24 小时动态血压平均≥130/80 mmHg 支持诊断。",
        "一般降压目标为<140/90 mmHg；能耐受者多数可进一步降至<130/80 mmHg，并根据合并疾病个体化设定。",
        "诊室血压≥160/100 mmHg 或高于目标值 20/10 mmHg 以上时，可起始小剂量两种药物联合治疗。",
        "常用一线降压药五大类：钙通道阻滞剂（CCB）、血管紧张素转换酶抑制剂（ACEI）、血管紧张素Ⅱ受体拮抗剂（ARB）、噻嗪类利尿剂、β受体阻滞剂。",
        "合并糖尿病或蛋白尿性慢性肾脏病者优先选用 ACEI 或 ARB；妊娠期禁用 ACEI/ARB。",
        "高血压急症需静脉降压治疗，初始 1 小时内平均动脉压下降幅度不超过治疗前的 25%，随后逐步达标，避免降压过快导致灌注不足。",
        "生活方式干预是基础治疗：限盐（每日食盐摄入<5 g）、控制体重、规律运动、限制饮酒、戒烟、保持心理平衡。",
        "随访：血压未达标者每 2-4 周随访调整方案；达标且稳定者每 1-3 个月随访一次，并定期评估靶器官损害。",
        "≥80 岁高龄患者降压可适当放宽，可先以<150/90 mmHg 为目标，耐受后进一步下调。",
    ]),
    ("02_china_diabetes_2020.md", "中国2型糖尿病防治指南（2020年版）", 2020, [
        "糖尿病诊断标准：典型糖尿病症状加随机血糖≥11.1 mmol/L，或空腹血糖≥7.0 mmol/L，或 OGTT 2 小时血糖≥11.1 mmol/L，或 HbA1c≥6.5%。",
        "HbA1c 控制目标一般<7%；年轻、病程短、无并发症者可更严格（<6.5%）；老年及低血糖高危者可适当放宽至 7.5%-8.0%。",
        "二甲双胍是 2 型糖尿病控制高血糖的一线首选和全程基础用药，无禁忌证应一直保留在治疗方案中。",
        "合并动脉粥样硬化性心血管疾病、慢性肾脏病或心力衰竭者，无论 HbA1c 是否达标，优先联合具有心肾获益证据的 SGLT2 抑制剂或 GLP-1 受体激动剂。",
        "每年至少进行一次并发症筛查：糖尿病视网膜病变（眼底检查）、糖尿病肾病（UACR 与 eGFR）、糖尿病足（足部检查）。",
        "合并高血压者降压目标一般为<130/80 mmHg；按 ASCVD 风险分层启动他汀类调脂治疗。",
        "低血糖防治：加强血糖自我监测与患者教育，调整降糖方案时评估低血糖风险，老年患者避免使用低血糖风险高的方案。",
        "转诊指征：初发严重高血糖、急性并发症（酮症酸中毒、高渗状态）、血糖长期控制不佳或出现严重慢性并发症者应及时转上级医院。",
    ]),
    ("03_gold_copd_2024.md", "慢性阻塞性肺疾病全球倡议（GOLD 2024）", 2024, [
        "慢阻肺诊断：存在呼吸困难、慢性咳嗽、咳痰等症状及危险因素暴露史者，吸入支气管扩张剂后 FEV1/FVC<0.70 即可确诊（不完全可逆的气流受限）。",
        "稳定期初始药物治疗按症状负荷（mMRC 或 CAT 评分）与急性加重史分组（A/B/E）选择方案。",
        "B 组与 E 组患者推荐起始 LABA+LAMA 双支气管扩张剂联合；外周血嗜酸粒细胞计数≥300/μL 时可考虑联合 ICS（三联吸入）。",
        "急性加重期治疗以短效支气管扩张剂为基础；中重度加重者加用全身糖皮质激素，并按指征使用抗菌药物。",
        "急性加重伴高碳酸血症性呼吸衰竭者，无创通气可改善预后、降低气管插管率与病死率。",
        "戒烟是唯一能减缓 FEV1 下降进程的干预措施；所有患者应提供戒烟支持。",
        "肺康复（含运动训练与健康教育）可改善活动耐量与生活质量，推荐用于症状明显者。",
        "推荐接种流感疫苗与肺炎球菌疫苗，可降低急性加重与严重呼吸道感染风险。",
        "稳定期不推荐长期单一短效支气管扩张剂（SABA/SAMA）治疗。",
    ]),
    ("04_china_heart_failure_2024.md", "中国心力衰竭诊断和治疗指南（2024）", 2024, [
        "射血分数降低的心衰（HFrEF）基石药物治疗为四联：ARNI/ACEI、β受体阻滞剂、醛固酮受体拮抗剂（MRA）、SGLT2 抑制剂，均应尽早启动并逐步滴定至目标或最大耐受剂量。",
        "SGLT2 抑制剂（达格列净、恩格列净）无论射血分数高低均可降低心衰住院与心血管死亡风险（I 类推荐）。",
        "容量负荷过重伴淤血症状者使用利尿剂（首选袢利尿剂）缓解淤血、改善症状；长期利尿需监测电解质与肾功能。",
        "使用 ACEI/ARB/ARNI、MRA 与利尿剂期间需规律监测血压、血钾与肾功能，出现高钾血症或肾功能恶化时及时调整。",
        "射血分数保留的心衰（HFpEF）：SGLT2 抑制剂有循证获益；同时积极管理高血压、心房颤动、肥胖与糖尿病等合并症。",
        "铁缺乏（血清铁蛋白或转铁蛋白饱和度低下）的心衰患者建议静脉补铁，可改善症状与活动耐量。",
        "符合适应证的 HFrEF 患者应评估植入型心律转复除颤器（ICD）与心脏再同步化治疗（CRT）以预防猝死、改善预后。",
    ]),
    ("05_china_atrial_fibrillation_2023.md", "中国心房颤动管理指南（2023）", 2023, [
        "抗凝决策基于卒中风险：CHA2DS2-VASc 评分男性≥2 分、女性≥3 分一般推荐长期口服抗凝治疗。",
        "无机械瓣或中重度二尖瓣狭窄者，直接口服抗凝药（DOAC）优先于华法林，颅内出血风险更低且无需常规监测。",
        "使用华法林者维持 INR 2.0-3.0；TTR（治疗窗内时间）不佳者建议换用 DOAC。",
        "HAS-BLED 评分提示出血高风险者应识别并纠正可逆因素（血压控制、避免联用抗血小板药/NSAID、纠正贫血等），其本身不是抗凝禁忌。",
        "节律控制：症状明显或新发房颤可评估药物复律、电复律或导管消融；早期节律控制策略可改善部分患者预后。",
        "导管消融对阵发性房颤维持窦性心律优于药物治疗，症状性房颤药物治疗无效或不耐受者推荐。",
        "室率控制以宽松目标为起始（静息心率<110 次/分），症状不缓解再收紧；药物可选β受体阻滞剂、非二氢吡啶类 CCB 或地高辛。",
        "房颤合并肥厚型心肌病、机械瓣或中重度二尖瓣狭窄者应选用华法林而非 DOAC 抗凝。",
    ]),
    ("06_china_dyslipidemia_2023.md", "中国血脂管理指南（2023年）", 2023, [
        "低密度脂蛋白胆固醇（LDL-C）是血脂干预的首要靶点，动脉粥样硬化性心血管疾病（ASCVD）风险评估决定治疗目标与强度。",
        "ASCVD 患者属极高危：LDL-C 目标<1.4 mmol/L 且较基线降低≥50%。",
        "ASCVD 高危人群（如 LDL-C≥4.9 mmol/L 或糖尿病合并多重危险因素）LDL-C 目标<1.8 mmol/L。",
        "中危与低危人群 LDL-C 目标分别为<2.6 mmol/L 与<3.4 mmol/L，先以生活方式干预为基础。",
        "他汀类是降 LDL-C 的基石药物；单药不达标可联合依折麦布，仍不达标联合 PCSK9 抑制剂。",
        "他汀不耐受者可减量联合依折麦布/PCSK9 抑制剂等非他汀方案替代达标。",
        "脂蛋白(a)≥300 mg/L 属 ASCVD 危险增强因素，高危人群应检测并纳入总体风险评估。",
        "甘油三酯≥1.7 mmol/L 者首先生活方式干预；≥5.6 mmol/L 者需药物干预以预防急性胰腺炎。",
        "血脂筛查：40 岁以下成年人每 2-5 年检测一次血脂，40 岁及以上每年至少检测一次，ASCVD 患者及其高危人群更频繁。",
    ]),
    ("07_china_ischemic_stroke_2023.md", "中国急性缺血性卒中诊治指南（2023）", 2023, [
        "发病 4.5 小时内的急性缺血性卒中患者，无禁忌证时应给予静脉溶栓治疗（重组组织型纤溶酶原激活剂或替奈普酶）。",
        "发病 6 小时内的前循环大血管闭塞患者应尽快评估血管内机械取栓；经严格影像筛选（灌注/侧支评估）部分患者时间窗可扩展至 24 小时。",
        "静脉溶栓后 24 小时内血压应控制在<180/105 mmHg，之后启动或恢复抗血小板等药物治疗。",
        "未溶栓患者应在发病后尽早启动抗血小板治疗，首选阿司匹林；不推荐早期常规双重抗血小板长期使用（轻型卒中/高危 TIA 短程双抗例外）。",
        "血糖管理：持续血糖>10 mmol/L 时启动降糖治疗；血糖<2.8 mmol/L 时补充葡萄糖，避免低血糖加重脑损伤。",
        "血压管理：未溶栓且血压<220/120 mmHg 时不主张紧急降压，避免降压过快减少脑灌注。",
        "进食前应完成吞咽功能筛查（如饮水试验），降低吸入性肺炎风险；营养不足者早期肠内营养支持。",
        "卧床患者应尽早活动并采取物理预防措施，必要时药物预防深静脉血栓形成。",
    ]),
    ("08_china_sepsis_ed_2021.md", "中国脓毒症/脓毒性休克急诊治疗指南（2021）", 2021, [
        "脓毒性休克识别后 1 小时内完成复苏 bundle：测量乳酸、抗菌药物前留取血培养、尽快输注广谱抗菌药物、以 30 mL/kg 晶体液启动液体复苏、低血压或乳酸≥4 mmol/L 时应用血管活性药物维持平均动脉压≥65 mmHg。",
        "去甲肾上腺素是脓毒性休克的首选升压药；去甲肾上腺素效果不佳时可联合血管加压素。",
        "液体复苏应使用晶体液，并以动态指标（如被动抬腿、补液试验反应性、超声评估）指导后续补液，警惕液体过负荷。",
        "感染源控制应尽早明确并处置（引流、清创、移除感染导管等），与抗菌药物治疗协同。",
        "经验性抗菌药物按感染部位、当地耐药谱与宿主因素选择广谱方案；明确病原后降阶梯，每日评估停药指征。",
        "血管活性药物仍不能维持血压者建议静脉氢化可的松（约 200 mg/d）。",
        "合并 ARDS 者行保护性通气：小潮气量（约 6 mL/kg 预测体重）、平台压≤30 cmH2O，中重度 ARDS 推荐俯卧位通气。",
        "血糖控制目标≤10 mmol/L，避免严重低血糖；高危患者应采取措施预防静脉血栓与呼吸机相关性肺炎。",
    ]),
]


def write_v2_files() -> list[Path]:
    """把 8 部指南条目落盘为 md（幂等：内容由脚本生成，重写覆盖，可审计可提交）。"""
    KB_GUIDELINES_V2_DIR.mkdir(parents=True, exist_ok=True)
    paths = []
    for fname, title, year, items in GUIDELINES_V2:
        lines = [f"# {title}", "", V2_DISCLAIMER, ""]
        for i, item in enumerate(items, 1):
            lines.append(f"推荐意见{i}：{item}")
            lines.append("")
        p = KB_GUIDELINES_V2_DIR / fname
        p.write_text("\n".join(lines), encoding="utf-8")
        paths.append(p)
    return paths


def parse_v2_rows(paths: list[Path]) -> list[dict]:
    """解析指南 md → 条目级检索单元：每段一条，content 前缀【指南名·年份】，去重。"""
    rows: list[dict] = []
    seen: set[str] = set()
    for p in paths:
        text = p.read_text(encoding="utf-8", errors="replace")
        # 指南全称取 md 首个一级标题（落盘格式由 write_v2_files 保证；外部文件按同样约定）
        title = ""
        for ln in text.splitlines():
            if ln.startswith("# "):
                title = ln[2:].strip()
                break
        paras = [seg.strip() for seg in re.split(r"\n\s*\n", text) if seg.strip()]
        for seg in paras:
            if seg.startswith("#") or seg.startswith(">"):  # 标题/警示行不单独入库
                continue
            content = f"【{title}】{seg}" if title else seg
            if len(content) < 20:  # 与 kb_ingest 切分下限一致，避免过短噪声
                continue
            h = hashlib.sha1(content.encode("utf-8")).hexdigest()
            if h in seen:
                continue
            seen.add(h)
            rows.append({"content": content, "source": f"{title}"})
    return rows


def _mark_v2_replaced() -> None:
    """旧 guideline_demo 两条（local_corpus / sample_guideline）manifest 描述标废弃：
    保留数据与条目不动（保审计），仅追加"（已废弃，由 clinical_guidelines_v2 替换）"。幂等。"""
    from backend.core.kb_ingest import _load_manifest, _save_manifest
    m = _load_manifest()
    changed = False
    for tag in ("local_corpus", "sample_guideline"):
        info = m.get(tag)
        if info and "已废弃" not in str(info.get("name", "")):
            info["name"] = f"{info.get('name', tag)}（已废弃，由 clinical_guidelines_v2 替换）"
            changed = True
    if changed:
        _save_manifest(m)


def main_v2() -> int:
    """真实指南要点入库（幂等：重跑重写文件、按 content-hash 去重、同 tag 先删后插）。"""
    from backend.core.embedder import embed_dense_sparse, device
    from backend.core.medical_kb import _collection
    from backend.core.kb_ingest import register_doc

    paths = write_v2_files()
    rows = parse_v2_rows(paths)
    print(f"V2 FILES={len(paths)} ENTRIES={len(rows)} EMBEDDING...", flush=True)
    if not rows:
        print("NO_ROWS"); return 1
    dense, sparse = embed_dense_sparse([r["content"] for r in rows], batch_size=32)
    print(f"embedded {len(dense)} device={device()}", flush=True)
    data = [{"embedding": v, "sparse": sp, "content": r["content"][:4000],
             "source_name": r["source"][:250], "tenant_id": "tenant_default",
             "document_id": DOC_TAG_V2}
            for r, v, sp in zip(rows, dense, sparse)]
    col = _collection()
    try:
        col.delete("medical_kb", filter=f'document_id == "{DOC_TAG_V2}"')
    except Exception as e:  # noqa: BLE001
        print("clean skip", str(e)[:60], flush=True)
    col.insert("medical_kb", data)
    col.load_collection("medical_kb")
    register_doc(DOC_TAG_V2, "临床指南要点库（真实出处）", len(data))
    _mark_v2_replaced()
    print(f"INSERTED {len(data)} real-guideline entries (tag={DOC_TAG_V2})", flush=True)
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "v2":
        sys.exit(main_v2())
    sys.exit(main())
