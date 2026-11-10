"""统一系统迁移（第 201 批）：AI 病历质控进入控制台。

锁死的约束：

1. **签名与科室由服务端绑定**：前端展示值只作提示；运行质控时不把签名当作「已填写内容」，
   避免空病历因为签名自动填充而被送进模型。
2. **拆分只覆盖非空字段**：`/medical/qc/parse` 返回的字段逐个覆盖，签名栏（第 7 栏）
   永远不覆盖；解析失败提示手动填写，不伪造内容。
3. **三档分流必须如实展示**：`auto_rejected` / `rejected_escalated` / 预审通过 / 人工复核
   四种状态对应不同颜色与解释，不能统一成一句"已完成"。
4. **重提链路**：`resubmit_of` 必须随下次运行质控提交；预填原病历与检验值，签名不回填；
   成功后清理重提上下文并刷新驳回列表。
5. **离开保护**：有未提交内容时，站内路由与关闭页面都要拦一下，不能静默丢 20 分钟输入。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
CONSOLE = REPO / "frontend-console"
SRC = CONSOLE / "src"
AI_API = SRC / "api/ai.ts"
VIEW = SRC / "views/AiQcView.vue"
REVIEW = SRC / "views/AiReviewView.vue"
NAV = SRC / "navigation.ts"
ROUTER = SRC / "router/index.ts"
ZH = SRC / "i18n/zh-CN.ts"
EN = SRC / "i18n/en.ts"


def test_qc_api_contract():
    src = AI_API.read_text(encoding="utf-8")
    for fn in ("aiMe", "aiQcParse", "aiQcRecord", "aiQcMyRejections", "aiCaseArchiveMine"):
        assert f"export function {fn}" in src, f"缺少接口函数 {fn}"
    assert "'/auth/me'" in src, "科室来自 AI 中台 /auth/me（不是 Java 的 /api/v2/auth/me）"
    assert "'/medical/qc/parse'" in src
    assert "'/medical/qc/record'" in src
    assert "resubmit_of" in src
    assert "'/medical/qc/my-rejections'" in src
    assert "'/medical/case-archive/mine'" in src


def test_qc_wired_into_nav_and_router():
    assert "AiQcView.vue" in ROUTER.read_text(encoding="utf-8"), "路由未注册"
    nav = NAV.read_text(encoding="utf-8")
    assert "name: 'ai-qc'" in nav
    assert "path: '/ai/qc'" in nav
    assert "group: 'AI 智能'" in nav
    block = nav.split("name: 'ai-qc'", 1)[1].split("},", 1)[0]
    assert "roles: ['DOCTOR', 'QC', 'ADMIN']" in block


def test_qc_view_field_and_signature_discipline():
    src = VIEW.read_text(encoding="utf-8")
    assert "const QC_LABELS = ['主诉', '现病史', '既往史', '体格检查', '辅助检查', '初步诊断', '医师签名']" in src
    # 拆分跳过签名栏
    assert "if (index === 6) return" in src
    # 运行质控只收集前 6 栏；签名由服务端覆盖
    assert "QC_LABELS.slice(0, 6)" in src
    assert "record[label] = value" in src
    assert ":readonly=\"i === 6\"" in src
    # 至少一项内容，避免签名自动填充造成"空病历可提交"
    assert "if (!Object.keys(record).length)" in src
    assert "aiQc.form.needContent" in src


def test_qc_view_result_and_resubmit():
    src = VIEW.read_text(encoding="utf-8")
    for status in ("auto_rejected", "rejected_escalated"):
        assert status in src, f"缺少分流状态 {status}"
    assert "aiQc.triage.autoRejected" in src
    assert "aiQc.triage.escalated" in src
    assert "aiQc.triage.pass" in src
    # 结构化缺陷按维度过滤 + 分组
    assert "qc_defects" in src
    assert "defectFilter" in src
    assert "groups = computed" in src
    # 重提：resubmit_of + 预填原病历与检验值 + 清理上下文
    assert "aiQcRecord(record, parsedLabs.value, resubmitOf.value || undefined)" in src
    assert "item.meta?.record" in src
    assert "item.meta?.labs" in src
    assert "resubmitAttempt.value = Number(item.attempt || 1)" in src
    assert "cancelResubmit()" in src
    # 医生看驳回/归档；qc/admin 走全局病例库
    assert "v-if=\"!isQcAdmin\"" in src
    assert 'to="/ai/review?tab=cases"' in src
    assert "aiCaseArchiveMine()" in src


def test_qc_view_leave_protection():
    src = VIEW.read_text(encoding="utf-8")
    assert "onBeforeRouteLeave" in src
    assert "beforeunload" in src
    assert "suppressDirty" in src, "清空是程序性变更，不能把 dirty 又置回 true"
    assert "aiQc.leave.title" in src
    assert "aiQc.clear.title" in src
    assert "<el-" not in src and "element-plus" not in src
    assert "fetch(" not in src


def test_review_center_supports_cases_query():
    src = REVIEW.read_text(encoding="utf-8")
    assert "value === 'cases' && !isMine.value" in src, "病例库 query 只对 qc/admin 生效"


def test_qc_copy_is_bilingual():
    src = VIEW.read_text(encoding="utf-8")
    keys: set[str] = set(re.findall(r"t\('(aiQc\.[A-Za-z0-9_.]+)'", src))
    keys.update(k for k in re.findall(r"t\(`(aiQc\.[A-Za-z0-9_.]+)", src) if not k.endswith("."))
    keys.update({f"aiQc.confidence.{x}" for x in ("high", "medium", "low")})
    keys.update({f"aiQc.triage.{x}Desc" for x in ("autoRejected", "escalated", "pass", "manual")})
    keys.update({"nav.ai-qc.title", "nav.ai-qc.hint"})

    assert len(keys) > 60, f"解析到的文案 key 过少（{len(keys)}）"
    zh = ZH.read_text(encoding="utf-8")
    en = EN.read_text(encoding="utf-8")
    missing_zh = sorted(k for k in keys if f"'{k}':" not in zh)
    missing_en = sorted(k for k in keys if f"'{k}':" not in en)
    assert not missing_zh, f"中文文案缺 key：{missing_zh}"
    assert not missing_en, f"英文文案缺 key：{missing_en}"

    for line in en.splitlines():
        if not line.strip().startswith("'aiQc."):
            continue
        value = line.split("':", 1)[-1]
        assert not re.search(r"[\u4e00-\u9fff]", value), f"英文文案混入中文：{line.strip()}"
