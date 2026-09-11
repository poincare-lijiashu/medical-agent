# -*- coding: utf-8 -*-
"""合并中断子代理生成的分片数据（data/_exp_dict_p*.json / _exp_rules_p*.json）
到正式 drug_dict.json / drug_rules.json。幂等：按 name / (drug_a,drug_b) 去重。
运行后删除临时分片。"""
import json, io, os, glob

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DICT = os.path.join(BASE, "data", "drug_dict.json")
RULES = os.path.join(BASE, "data", "drug_rules.json")


def main():
    d = json.load(io.open(DICT, encoding="utf-8"))
    names = {x["name"] for x in d}
    d_add = 0
    for f in sorted(glob.glob(os.path.join(BASE, "data", "_exp_dict_p*.json"))):
        part = json.load(io.open(f, encoding="utf-8"))
        for x in part:
            if x["name"] in names:
                continue
            d.append(x)
            names.add(x["name"])
            d_add += 1
    json.dump(d, io.open(DICT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    r = json.load(io.open(RULES, encoding="utf-8"))
    keys = {(x["drug_a"], x["drug_b"]) for x in r}
    r_add = 0
    for f in sorted(glob.glob(os.path.join(BASE, "data", "_exp_rules_p*.json"))):
        part = json.load(io.open(f, encoding="utf-8"))
        for x in part:
            if (x["drug_a"], x["drug_b"]) in keys:
                continue
            r.append(x)
            keys.add((x["drug_a"], x["drug_b"]))
            r_add += 1
    json.dump(r, io.open(RULES, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    for f in glob.glob(os.path.join(BASE, "data", "_exp_*.json")):
        os.remove(f)
    print(f"drugs +{d_add} (total {len(d)}), rules +{r_add} (total {len(r)})")


if __name__ == "__main__":
    main()
