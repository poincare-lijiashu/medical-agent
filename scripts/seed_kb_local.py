"""本地真实指南要点语料注入（不依赖网络，秒级）：多专科教学示例，每条标真实指南名。

定位：demo/教学语料，覆盖常见专科要点，用于让循证检索在离线/网络受限时可用作答与溯源。
demo 来源：全部条目标识 guideline_demo:*，属内置教学示例——待阶段 5（真实指南语料落地）替换，
届时以院方授权指南经 scripts/seed_kb_docs.py 重摄覆盖；替换前本语料保持检索可用。
生产应替换为院方授权的完整指南库（可配合 scripts/seed_kb_pubmed.py + NCBI API key 在线扩充）。
幂等：document_id="local_corpus"，重跑先按该 id 删除再插入；编码走 embedder（自动 GPU/CPU）批量。
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from backend.core.embedder import embed_dense_sparse  # noqa: E402
from backend.core.medical_kb import _collection  # noqa: E402

DOC_TAG = "local_corpus"
DISC = "【教学示例要点，非完整指南；临床决策以正式指南与医师判断为准】"

# (source_name, 正文) —— 均为广泛共识的教科书级要点，数值保守
DOCS = [
    ("guideline_demo:hypertension", DISC + " 高血压（中国高血压防治指南2024）：诊室血压≥140/90 mmHg 可诊断；一般降压目标<140/90，能耐受者多数可进一步<130/80。一线药物五大类：CCB、ACEI、ARB、利尿剂、β受体阻滞剂。合并糖尿病/蛋白尿/CKD 优先 ACEI 或 ARB。"),
    ("guideline_demo:dyslipidemia", DISC + " 血脂管理（中国血脂管理指南2023）：动脉粥样硬化性心血管病(ASCVD)为降 LDL-C 主要目标；极高危者 LDL-C 目标<1.4 mmol/L 且较基线降幅≥50%。他汀是基石，不耐受可联合依折麦布或 PCSK9 抑制剂。"),
    ("guideline_demo:diabetes2", DISC + " 2 型糖尿病：二甲双胍为一线，500mg 每日 2-3 次随餐服；一般 HbA1c 目标<7%，老年/低血糖风险高者可放宽至 7.5-8%。合并 ASCVD/CKD/心衰者优选加用 SGLT2i 或 GLP-1RA。"),
    ("guideline_demo:asthma", DISC + " 哮喘（GINA）：不推荐单用短效β2激动剂(SABA)；成人首选含 ICS 的控制治疗，轻度可用 ICS-福莫特罗按需，持续期按需低剂量 ICS-福莫特罗，加重则升级至每日维持 ICS-LABA。"),
    ("guideline_demo:copd", DISC + " 慢阻肺(COPD)稳定期（GOLD）：根据症状与急性加重风险分组选择 LAMA 或 LABA 支气管扩张剂，反复加重或血嗜酸细胞高者加 ICS（三联）；戒烟、肺康复、疫苗接种为基础。"),
    ("guideline_demo:af", DISC + " 心房颤动卒中预防：以 CHA2DS2-VASc 评分评估，男性≥2、女性≥3 一般推荐口服抗凝（优先 DOAC）；HAS-BLED 高非抗凝禁忌理由，需控危险因素并随访。"),
    ("guideline_demo:cap", DISC + " 社区获得性肺炎（门诊无合并症）常选阿莫西林或多西环素；住院非重症用β内酰胺联合大环内酯或呼吸喹诺酮；重症联合方案并覆盖常见病原，按当地耐药与培养调整。"),
    ("guideline_demo:stroke", DISC + " 急性缺血性卒中：发病 4.5 小时内、无禁忌者可静脉溶栓（阿替普酶/替奈普酶）；大血管闭塞在时间窗内评估血管内取栓。尽早头颅 CT 排除出血，管理血压、血糖。"),
    ("guideline_demo:vte", DISC + " 静脉血栓栓塞症(VTE)：非妊娠、无严重肾功能不全/抗凝禁忌者，多数优选 DOAC（阿哌沙班或利伐沙班）；抗凝疗程按诱因与复发风险，一般至少 3 个月。"),
    ("guideline_demo:ckd", DISC + " 慢性肾脏病：以 eGFR 与白蛋白尿(UACR)分期；合并高血压/蛋白尿优先 ACEI 或 ARB；SGLT2i 对部分 CKD（含糖尿病肾病）有肾脏与心血管获益；控制血压血糖、避肾毒性药物、随访电解质。"),
    ("guideline_demo:heartfailure", DISC + " 射血分数降低心衰(HFrEF)：四联基石——ARNI/ACEI、β受体阻滞剂、MRA、SGLT2i，逐步滴定并监测血压、钾、肾功能；容量负荷重用利尿剂缓解淤血。"),
    ("guideline_demo:pylori", DISC + " 幽门螺杆菌根除：常用含 PPI 与铋剂的四联疗法（PPI+铋剂+两种抗生素）10-14 天，方案按当地克拉霉素等耐药率选择；治疗后可用呼气试验复查（停 PPI≥2 周）。"),
    ("guideline_demo:gout", DISC + " 痛风：急性期以 NSAID、秋水仙碱或糖皮质激素抗炎镇痛；反复发作/痛风石/CKD 者缓解后启动降尿酸（别嘌醇或非布司他），起始小剂量并预防性抗炎，目标血尿酸达标。"),
    ("guideline_demo:commoncold", DISC + " 普通感冒/急性上呼吸道感染：多为病毒性、自限（约 7-10 天），以对症治疗为主；抗生素对感冒无效，不推荐常规使用。退热镇痛可选对乙酰氨基酚或布洛芬，注意补液休息；出现高热不退、呼吸困难、意识改变等警示征象应及时转诊评估。"),
    ("guideline_demo:influenza", DISC + " 流行性感冒：流感样症状（高热、肌痛、乏力）季节性流行；重症高危人群（孕妇、≥65 岁、慢性心肺疾病、免疫抑制）建议发病 48 小时内启动奥司他韦等神经氨酸酶抑制剂；每年接种流感疫苗是最有效预防手段。"),
    ("guideline_demo:gastroenteritis", DISC + " 急性胃肠炎：多数为病毒性、自限，治疗关键是口服补液盐防治脱水（少量多次）；抗生素仅用于特定细菌/寄生虫病因（如高热血便、旅行者腹泻重症）；止泻药慎用于发热伴血便者，警惕脱水电解质紊乱。"),
    ("guideline_demo:tensionheadache", DISC + " 紧张型头痛：双侧压迫/紧箍样、轻中度、不伴恶心呕吐与先兆；发作期一线对乙酰氨基酚或 NSAID 对症，频繁发作者考虑预防性阿米替林或行为放松治疗；突发雷击样剧痛、伴神经体征/发热/高血压急症等红旗征象需影像排除继发性头痛。"),
    ("guideline_demo:allergicrhinitis", DISC + " 过敏性鼻炎：阵发性喷嚏、清水样涕、鼻痒鼻塞；一线为鼻用糖皮质激素，可联合口服二代抗组胺药；配合规避过敏原与生理盐水鼻腔冲洗；常规治疗控制不佳的中重度患者可评估特异性免疫治疗。"),
    ("guideline_demo:acutebronchitis", DISC + " 急性支气管炎：多为病毒性，常规不推荐抗生素（无肺炎证据时）；以镇咳祛痰对症为主；咳嗽持续超过 3 周、反复发作或伴喘息者需排查肺炎、哮喘、COPD 等病因。"),
    ("guideline_demo:pedsfever", DISC + " 儿童发热：评估病情严重程度重于单纯退热；对乙酰氨基酚/布洛芬按体重规范给药以改善舒适度；<3 个月婴儿发热属急症须及时医院评估；出现嗜睡拒食、皮疹、呼吸急促、抽搐等警示征象立即急诊。"),
    ("guideline_demo:loweruti", DISC + " 女性单纯性下尿路感染：典型尿频尿急尿痛，一般无发热腰痛（上尿路征象需按肾盂肾炎处理）；一线短程方案为呋喃妥因或磷霉素或 SMZ-TMP（按当地耐药选择）；多饮水促进排尿；反复发作者需查找诱因并评估预防性方案。"),
]


def _chunk(text, size=300):
    return [text[i:i + size] for i in range(0, len(text), size)]


def main() -> int:
    rows = []
    for src, text in DOCS:
        for c in _chunk(text):
            if c.strip():
                rows.append({"content": c, "source": src})
    print(f"CHUNKS={len(rows)} EMBEDDING...", flush=True)
    dense, sparse = embed_dense_sparse([r["content"] for r in rows], batch_size=32)  # 窗口走配置默认 1024（任务A）
    col = _collection()
    try:
        col.delete("medical_kb", filter=f'document_id == "{DOC_TAG}"')
    except Exception as e:  # noqa: BLE001
        print("clean skip", str(e)[:60], flush=True)
    data = [{"embedding": v, "sparse": sp, "content": r["content"], "source_name": r["source"],
             "tenant_id": "tenant_default", "document_id": DOC_TAG}
            for r, v, sp in zip(rows, dense, sparse)]
    col.insert("medical_kb", data)
    col.load_collection("medical_kb")
    from backend.core.kb_ingest import register_doc
    register_doc(DOC_TAG, "本地指南要点（内置·含常见病）", len(data))
    print(f"INSERTED {len(data)} local guideline chunks", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
