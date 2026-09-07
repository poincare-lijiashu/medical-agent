from backend.core.medical_drug import check, detect_drugs


def test_detect_single_and_alias():
    assert detect_drugs("我在吃法华林") == ["华法林"]  # 别名归一
    assert "布洛芬" in detect_drugs("布洛芬和ibuprofen")


def test_high_risk_pair_detected():
    r = check("华法林和布洛芬能一起吃吗")
    assert "发现以下相互作用" in r["answer"]
    assert "高危" in r["answer"] and "华法林" in r["answer"] and "布洛芬" in r["answer"]
    assert r["sources"] == ["drug_rules:curated_v2"]


def test_contraindicated_pair():
    r = check("西地那非能和硝酸甘油一起用吗")
    assert "禁忌" in r["answer"]


def test_below_two_drugs_prompt():
    r = check("布洛芬怎么用")
    assert "≥2 种" in r["answer"]


def test_unknown_pair_no_false_claim():
    r = check("二甲双胍和别嘌醇能一起吃吗")  # 规则库未收录该对
    assert "未收录" in r["answer"]


def test_expanded_pairs():
    assert "INR" in check("华法林和胺碘酮")["answer"]  # 新增高危对
    assert "禁忌" in check("辛伐他汀 克拉霉素")["answer"]


def test_curated_v2_new_pairs():
    assert "高危" in check("碳酸锂和布洛芬同用会怎样")["answer"]  # v2 新增：锂+NSAID
    assert "INR" in check("华法林与克拉霉素联用")["answer"]      # v2 新增：大环内酯增强抗凝
    assert "阿托伐他汀" in check("atorvastatin 加克拉霉素")["answer"]  # 英文别名归一


def test_v2_negative_pair_still_unknown():
    r = check("二甲双胍和别嘌醇能一起吃吗")  # v2 扩充后仍未收录（防静默无据结论）
    assert "未收录" in r["answer"]
