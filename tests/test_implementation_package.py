"""实施交付包完整性校验（§274）。

培训材料最容易腐烂：题库答案与选项漂移、检查单被删章节、方法论只剩标题。
把这些约束做成测试，CI 失败比"上线前才发现材料不完整"便宜得多。
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT / "docs" / "implementation"

DOCS = ["README.md", "training-matrix.md", "go-live-checklist.md", "rollback-drill.md"]
BANK = "certification-bank.json"

REQUIRED_ROLES = {
    "DOCTOR", "NURSE", "PHARMACIST", "CASHIER", "TECH", "CODER",
    "INSURANCE", "OPS", "RELEASE", "DATA_STEWARD", "IMPLEMENTATION",
}
DIFFICULTIES = {"BASIC", "INTERMEDIATE", "ADVANCED"}


def _text(name: str) -> str:
    return (PKG / name).read_text(encoding="utf-8")


def test_required_documents_exist_and_not_placeholders() -> None:
    for name in DOCS:
        path = PKG / name
        assert path.is_file(), f"缺少实施交付文档：{name}"
        content = path.read_text(encoding="utf-8")
        assert len(content) >= 800, f"{name} 内容过短，疑似占位"
        for placeholder in ("TODO", "TBD", "待补充", "待完善"):
            assert placeholder not in content, f"{name} 仍含占位词 {placeholder}"


def test_methodology_covers_five_phases_and_gates() -> None:
    content = _text("README.md")
    for phase in ("S1 准备", "S2 蓝图", "S3 构建与配置", "S4 迁移与演练", "S5 上线与稳定"):
        assert phase in content, f"实施方法论缺少阶段：{phase}"
    for keyword in ("门禁", "RACI", "回滚", "交付物", "风险"):
        assert keyword in content, f"实施方法论缺少关键章节：{keyword}"


def test_training_matrix_has_roles_and_certification_rules() -> None:
    content = _text("training-matrix.md")
    for role in REQUIRED_ROLES:
        assert role in content, f"培训矩阵缺少角色：{role}"
    for keyword in ("及格线", "认证", "有效期", "补考", "课程"):
        assert keyword in content, f"培训矩阵缺少：{keyword}"


def test_go_live_checklist_has_blocking_and_categories() -> None:
    content = _text("go-live-checklist.md")
    assert "阻塞" in content
    for category in ("数据", "接口", "安全", "性能", "培训", "回滚", "值班"):
        assert category in content, f"Go/No-Go 检查单缺少分类：{category}"
    for keyword in ("一票否决", "GO", "NO-GO", "签署"):
        assert keyword in content, f"Go/No-Go 检查单缺少：{keyword}"


def test_rollback_drill_has_decision_and_verification() -> None:
    content = _text("rollback-drill.md")
    for keyword in ("触发条件", "决策", "验证", "复盘", "L1", "L2", "L4"):
        assert keyword in content, f"回滚演练脚本缺少：{keyword}"


def test_certification_bank_schema_and_coverage() -> None:
    data = json.loads((PKG / BANK).read_text(encoding="utf-8"))

    assert data["passScore"] == 80
    assert data["highRiskPassScore"] == 90
    roles = set(data["roles"])
    assert REQUIRED_ROLES <= roles, f"题库角色缺失：{REQUIRED_ROLES - roles}"
    assert set(data["highRiskRoles"]) <= roles

    questions = data["questions"]
    assert len(questions) >= 33, "题库题量不足以支撑认证抽题"
    ids: set[str] = set()
    counts: dict[str, int] = {role: 0 for role in roles}

    for q in questions:
        qid = q["id"]
        assert re.fullmatch(r"[A-Z]{2,4}-\d{3}", qid), f"题号格式不合法：{qid}"
        assert qid not in ids, f"题号重复：{qid}"
        ids.add(qid)

        role = q["role"]
        assert role in roles, f"{qid} 角色不在角色清单：{role}"
        counts[role] += 1

        assert q["module"].strip(), f"{qid} 缺少模块"
        assert q["difficulty"] in DIFFICULTIES, f"{qid} 难度不合法"
        assert q["question"].strip(), f"{qid} 缺少题干"

        options = q["options"]
        assert isinstance(options, list) and len(options) == 4, f"{qid} 必须恰好 4 个选项"
        assert len({o.strip() for o in options}) == 4, f"{qid} 选项重复"
        assert all(str(o).strip() for o in options), f"{qid} 存在空选项"

        answer = q["answer"]
        assert isinstance(answer, int) and 0 <= answer < 4, f"{qid} 答案下标不合法"
        explanation = q["explanation"].strip()
        assert len(explanation) >= 10, f"{qid} 解析过短，无法用于培训"

    for role, count in counts.items():
        assert count >= 3, f"角色 {role} 题量不足（至少 3 题）"
    for role in data["highRiskRoles"]:
        assert counts[role] >= 4, f"高风险角色 {role} 至少需要 4 题"


def test_local_markdown_links_resolve() -> None:
    link = re.compile(r"\]\(([^)#]+\.md)\)")
    for name in DOCS:
        content = _text(name)
        for target in link.findall(content):
            if target.startswith("http"):
                continue
            assert (PKG / target).is_file(), f"{name} 中的链接不存在：{target}"
