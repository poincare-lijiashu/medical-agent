"""本批次任务3 覆盖回归锁：后端审计事件全集 vs 前端 humanizeAudit 映射表。

- 从 backend/**/*.py 提取全部审计 action 字面量：
  ① 显式 kwargs：action="xxx" / fail_action="xxx"（write/append/record 调用点）；
  ② f-string 动态 action/事件名（如 resolved_{body.decision}、{agent}.answer）按可取值展开；
  ③ pg_store.mirror_audit 点分事件名（如 "qc.qc_escalated"）取首个「.」后缀——
    admin 数据面板审计流优先取 PG 真源，前端 humanizeAudit 对点分事件按后缀匹配映射表。
- 从 frontend-vue/src/utils/audit.js 提取 humanizeAudit 内 MAP 映射表键集（轮4 起 Vue 源为权威全文）；
- 断言后端事件全集 ⊆ 前端映射键集：未来新增审计事件若不补人话映射，本测试即红。
- 另用 node 对代表性事件跑 humanizeAudit 输出断言（人话文案质量锁；node 不可用跳过）。
"""
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
BACKEND = ROOT / "backend"
FRONTEND = ROOT / "frontend" / "index.html"

# f-string 动态审计事件名的可取值展开（源码中仅此三族动态事件名；
# decision 取值约束见 ResolveReq/review.resolve：approved|rejected）
DYNAMIC = {
    "resolved_{body.decision}": {"resolved_approved", "resolved_rejected"},
    "{agent}.answer": {"answer"},
    "review.{body.decision}": {"approved", "rejected"},
}


def _backend_actions() -> set:
    """后端全部审计 action 键（显式字面量 + 动态展开 + PG 点分镜像事件后缀）。"""
    acts: set = set()
    for py in sorted(BACKEND.rglob("*.py")):
        src = py.read_text(encoding="utf-8")
        acts |= set(re.findall(r'\baction="([a-z_0-9]+)"', src))
        acts |= set(re.findall(r'\bfail_action="([a-z_0-9]+)"', src))
        for fmt in re.findall(r'\baction=f"([^"]+)"', src):
            acts |= DYNAMIC.get(fmt, set())
        for ev in re.findall(r'mirror_audit\("([a-z_0-9.]+)"', src):
            acts.add(ev.split(".", 1)[1] if "." in ev else ev)  # 点分事件取后缀
        for fmt in re.findall(r'mirror_audit\(f"([^"]+)"', src):
            acts |= DYNAMIC.get(fmt, set())
    return acts


def _humanize_map_keys() -> set:
    """前端 humanizeAudit 内 MAP 映射表键集（复用 test_frontend_syntax 的函数提取器；
    轮4 起 humanizeAudit 位于 frontend-vue/src/utils/audit.js，从拼接 JS 全文提取）。"""
    from tests.test_frontend_syntax import _all_frontend_js, _extract_fn
    fn = _extract_fn(_all_frontend_js(), "humanizeAudit")
    start = fn.index("const MAP = {")
    block = fn[start:fn.index("\n  }\n", start)]  # MAP 对象收口（两空格缩进，无分号）
    keys = set(re.findall(r"(?m)^\s{4}([a-z_][a-z_0-9]*):", block))
    assert keys, "未从前端提取到 humanizeAudit MAP 键（映射表结构被改动？）"
    return keys


def test_backend_events_all_have_humanize_mapping():
    """覆盖回归锁：后端审计事件全集必须全部有人话化映射（防未来新增事件漏人话）。"""
    acts = _backend_actions()
    keys = _humanize_map_keys()
    missing = sorted(acts - keys)
    assert acts, "后端应提取到审计 action 字面量"
    assert not missing, f"以下审计事件缺 humanize 映射（前端 humanizeAudit MAP 需补全）：{missing}"


def test_drug_answer_events_mapped():
    """核心事件抽查：drug.answered / qc.answer 等点分镜像事件键必须在映射表中。"""
    acts = _backend_actions()
    keys = _humanize_map_keys()
    for must in ("answered", "answer", "my_rejections", "auto_sign_full", "auto_sign",
                 "resolved_approved", "resolved_rejected", "qc_escalated", "config_toggle"):
        assert must in keys, f"映射表缺关键事件键：{must}"
        if must in ("answered", "my_rejections", "auto_sign_full", "auto_sign",
                    "resolved_approved", "resolved_rejected", "qc_escalated", "config_toggle"):
            assert must in acts, f"后端应存在事件 {must}（提取器回归？）"


def test_representative_mappings_node():
    """代表性映射断言（node 直跑 humanizeAudit 纯函数）：药物问答/质控驳回/留痕签发/
    点分镜像事件等产出人话文案；任何输出不得含 undefined/null 字样。"""
    node = shutil.which("node")
    if not node:
        pytest.skip("node 不可用，跳过 humanizeAudit 单测")
    from tests.test_frontend_syntax import _all_frontend_js, _extract_fn
    fn = _extract_fn(_all_frontend_js(), "humanizeAudit")
    cases = [
        # [输入事件, 期望人话化输出]
        [{"event": "drug", "action": "answered", "payload": {"conf": 0.86, "review": True}},
         "药物问答（置信 0.86；需药剂科复核）"],
        [{"event": "drug", "action": "answered", "payload": {"conf": 0.80, "review": False}},
         "药物问答（置信 0.80）"],
        [{"event": "qc", "action": "my_rejections", "payload": {"n": 3}},
         "查看了我的质控驳回（3 条）"],
        [{"event": "drug", "action": "auto_sign_full", "payload": {"rid": "rev-1", "reason": "x"}},
         "留痕模式自动签发（#rev-1）"],
        [{"event": "drug", "action": "auto_sign", "payload": {"rid": "rev-2", "sev": "中危"}},
         "中危规则库提示自动签发（#rev-2）"],
        [{"event": "review", "action": "resolved_approved",
          "payload": {"rid": "rev-3", "role": "pharmacist"}}, "对 #rev-3 执行了签发"],
        [{"event": "review", "action": "resolved_rejected", "payload": {"rid": "rev-4"}},
         "对 #rev-4 执行了驳回"],
        [{"event": "imaging", "action": "vl_empty",
          "payload": {"imgs": 1, "vl_raw_len": 0, "stop_reason": "length"}},
         "AI 未生成影像所见（已提示重试）"],
        [{"event": "admin", "action": "config_toggle",
          "payload": {"key": "qc_auto_sign_full", "from": True, "to": False}},
         "切换了运行时开关 qc_auto_sign_full（开 → 关）"],
        # PG 点分镜像事件（action 为空 → 取 event 后缀匹配）
        [{"event": "qc.answer", "action": "", "payload": {"conf": 0.75, "review": True}},
         "提交了质控结论（置信 0.75 · 需复核）"],
        [{"event": "drug.answer", "action": "", "payload": {"conf": 0.9, "review": True}},
         "药物问答（置信 0.90；需药剂科复核）"],
        [{"event": "literature.answer", "action": "", "payload": {"conf": 0.83, "review": False}},
         "AI 问答留痕（置信 0.83）"],
        [{"event": "review.approved", "action": "", "payload": {"rid": "rev-5"}},
         "对 #rev-5 执行了签发"],
        # 病历质控三档 / 重提链
        [{"event": "qc", "action": "auto_reject", "payload": {"hard": ["主诉缺失", "签名缺失"]}},
         "AI 自动驳回质控病历（硬伤 2 项）"],
        [{"event": "qc", "action": "auto_pass", "payload": {"conf": 0.86}},
         "AI 预审通过并归档（置信 0.86）"],
        [{"event": "qc", "action": "resubmit", "payload": {"resubmit_of": "rev-9", "attempt": 2}},
         "重新提交了质控病历（第 2 次）"],
        [{"event": "qc", "action": "qc_escalated", "payload": {"attempt": 2, "resubmit_of": "rev-9"}},
         "质控重提自动升级病案科复核（第 2 次）"],
        # 认证 / 知识库 / HIS
        [{"event": "auth", "action": "password_changed", "payload": {"role": "doctor"}},
         "修改了自己的登录密码"],
        [{"event": "auth", "action": "password_reset", "payload": {"user": "doctor01", "role": "admin"}},
         "重置了用户密码（doctor01）"],
        [{"event": "kb", "action": "search", "payload": {"q": "高血压", "top_k": 3}},
         "检索了知识库（top 3）"],
        [{"event": "his", "action": "pushed",
          "payload": {"record_id": "r1", "adapter": "DemoAdapter", "status": "enqueued"}},
         "质控结论已推送 HIS（enqueued）"],
        [{"event": "literature", "action": "agentic_refine",
          "payload": {"attempt": 2, "action": "rewrite", "reason": "召回不足"}},
         "检索改写（第 2 轮）"],
    ]
    runner = (fn + "\nconst CASES=" + json.dumps(cases, ensure_ascii=False) + ";\n"
              "for(const [e,want] of CASES){const got=humanizeAudit(e);"
              "if(got!==want){console.error('FAIL ev='+e.event+' act='+e.action+' got=['+got+'] want=['+want+']');"
              "process.exit(1);}"
              "if(/undefined|NaN|null/.test(got)){console.error('BAD TOKEN in ['+got+']');process.exit(1);}}"
              "console.log('HUMANIZE_OK '+CASES.length);")
    fd = tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8")
    fd.write(runner)
    fd.close()
    try:
        r = subprocess.run([node, fd.name], capture_output=True, text=True, timeout=60)
    finally:
        Path(fd.name).unlink(missing_ok=True)
    assert r.returncode == 0, f"humanizeAudit 代表性断言失败：{r.stderr[:800]}"
    assert "HUMANIZE_OK" in r.stdout
