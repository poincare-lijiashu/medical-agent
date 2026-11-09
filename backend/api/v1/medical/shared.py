"""医疗 API 共享运行时（W4.3 router 拆分）：审计接入口 + PHI 脱敏 + 跨域行为助手。

单点接入口设计（测试兼容）：
- get_audit_logger()：动态转发到 core 层。各域端点在请求时经本模块属性调用
  （shared.get_audit_logger()），测试统一 monkeypatch「backend.api.v1.medical.shared.
  get_audit_logger」即可对全部域生效（此前 patch 点为 medical_router 模块符号）。
- redactor：全域共用 PHI 脱敏器实例。

跨域行为助手：_enqueue_if_risk（审核入队/留痕自动签发）、_has_risk/RISK_WORDS
（影像/病例高危征象判定）、_ask_prefilter（imaging/case 意图预检）、
_vl_describe_flow（imaging/case 共用 VL 描述流程）、_sse（SSE 帧）、
_is_fallback_answer（fallback 兜底判定）。
"""
from __future__ import annotations

import asyncio
import json
import re

from fastapi import HTTPException

from backend.config import settings
from backend.core import medical_review as review
from backend.core import pg_store, runtime_flags
from backend.core.intent import prefilter_reply
from backend.core.medical_audit import PHIRedactor, current_channel

from backend.api.v1.medical.schemas import AUDIT_Q_SNIPPET, AskResp


def get_audit_logger():
    """审计器动态接入口：转发到 backend.core.medical_audit（每次调用时解析，
    测试 monkeypatch 本模块符号即可全局替换；不要改为 from-import 绑定）。"""
    from backend.core.medical_audit import get_audit_logger as _real
    return _real()


redactor = PHIRedactor()

# 影像/病例高危征象词表：默认覆盖常见危急值类征象；院方可经 IMAGING_RISK_WORDS（逗号分隔）覆盖
_DEFAULT_RISK_WORDS = ("恶性", "肿瘤", "癌", "转移", "占位", "出血", "梗死", "栓塞", "血栓",
                       "穿孔", "坏死", "气胸", "骨折", "梗阻", "破裂", "脓肿", "卒中", "中风",
                       "夹层", "积液", "禁忌", "可疑")
RISK_WORDS = (tuple(w.strip() for w in (settings.imaging_risk_words or "").split(",") if w.strip())
              or _DEFAULT_RISK_WORDS)
_NEGATED = re.compile(r"(?:无|未见|未发现|未提示|未见明确|排除)[^，。；,;]{0,6}")


def _has_risk(desc: str) -> bool:
    """高危词判定：先剔除“无/未见…”等否定短语（≤6 字），避免“未见可疑征象”误报复核。"""
    d = _NEGATED.sub("", desc or "")
    return any(w in d for w in RISK_WORDS)


def _is_fallback_answer(a: dict | None) -> bool:
    """任务1：fallback 兜底答案判定（fallback_node 契约：sources 含 fallback: 前缀）。"""
    return any(str(s).startswith("fallback:") for s in ((a or {}).get("sources") or []))


async def _enqueue_if_risk(agent: str, q: str, answer: str, conf: float, review_flag: bool,
                           risk_reason: str, actor: str,
                           images: list[str] | None = None,
                           self_confirm: bool | None = None,
                           resubmit_of: str | None = None,
                           attempt: int = 1,
                           meta: dict | None = None,
                           sources: list | None = None) -> str | None:
    """任务3 全交互留痕：runtime_flags.full_mode()=True（默认开）时**全部 ask 类响应无条件
    入队**并立即自动签发（reviewed_by=「AI·留痕模式(自动)」，影像存档保留 keep_images），
    医生侧不阻塞；full=False 时回落旧语义（仅高危 review_flag 入队 + 人工双控）。
    待确认规则（任务3）：conf<0.6 或高危（review_flag）→ self_confirm_required=True
    （提交医生本人在「我的待确认」补知情确认）；否则纯留痕。显式传 self_confirm=True
    时强制待确认（drug 高危语义）。
    fallback/offtopic 拒答不入队（调用方维持既有分支，不进本函数）。
    sources：响应溯源来源随条目存档。resubmit_of/attempt/meta 透传（任务6）。"""
    # 评测 A3：答案事件先写 JSONL（权威），PG 镜像由 AuditLog._emit 统一挂钩。
    # 此前只 mirror 到 PG（event_type=literature.answer）→ JSONL 缺该事件、且两侧命名不同构。
    await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="answered",
                            actor=actor, payload={"conf": conf, "review": review_flag})
    if current_channel() == "mcp":
        # 批2 任务1 安全边界（docs/MCP.md）：MCP 外部 AI 客户端的调用【不写审核队列】——
        # 结果由外部客户端消费自担（免责声明见 MCP 工具 docstring），避免外部批量调用
        # 污染审核流；审计留痕照常（各端点 query/answered 事件 + channel/tool 标记）。
        # 与 web 流量唯一语义差异：不入队、无 review_id；答案正文/置信度/来源照常返回。
        return None
    full = runtime_flags.full_mode()  # runtime_flags.json 优先（admin 运行时切换即时生效）
    # 本批次任务2（drug 类豁免留痕模式）：FULL 短路（入队即自动签发）只适用于非 drug
    # agent——drug 类无论 QC_AUTO_SIGN_FULL 如何都走既有三档（QC_AUTO_SIGN=on 时中危
    # 规则库提示自动签发、高危恒人工、两开关均 off 时全部人工），保证药剂科始终有
    # drug 高危待核对项（配合 resolve 端点的药剂科一票）。实现上仅令 drug 视角
    # full=False：入队条件回落旧语义（review_flag 才入队）、无「AI·留痕模式(自动)」
    # 短路、无待确认强制（人工路径由药剂科复核，不吃知情确认流程）。
    if agent == "drug":
        # 问题2 注记：两药快查已下线，drug 项不再入队（开药走 prescriptions）。
        # 端点仅为 API 兼容保留；此分支仅存档语义（full=False 豁免留痕模式），行为不变——
        # 正常流量不会到达（前端无 drug 视图入口），遗留历史由 scripts/purge_drug_review_items.py 清理。
        full = False
    if not review_flag and not full:
        return None  # 非留痕模式维持旧语义：仅高危入队
    if self_confirm is True:
        need_self = full  # 显式高危标记：留痕模式下强制待确认
    elif self_confirm is False:
        # drug 显式传 False（中危规则库留痕/信息不足非高危）：待确认仅按 conf<0.6 判，
        # 不吃入队用的 review_flag（旧语义里 review_flag or mid_risk_rule 只决定"入队"）
        need_self = full and conf < 0.6
    else:
        need_self = full and (bool(review_flag) or conf < 0.6)  # 任务3 待确认规则
    rid = await asyncio.to_thread(review.submit, agent=agent, question=q, answer=answer,
                                  confidence=conf, risk_reason=risk_reason, submitted_by=actor,
                                  images=images, sources=sources,
                                  self_confirm_required=need_self,
                                  resubmit_of=resubmit_of, attempt=attempt, meta=meta)
    await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="review_enqueued",
                            actor=actor, payload={"rid": rid, "reason": risk_reason,
                                                  "trace_mode": full})
    await pg_store.mirror_review(await asyncio.to_thread(review.get, rid))
    if full:
        # 任务3：留痕模式——入队即自动签发（不等人工），全程 AI 留痕；医生侧 AskResp 不变。
        # keep_images=True：签发后保留影像存档（留痕可回溯；人工签发路径仍清空）。
        try:
            item = await asyncio.to_thread(review.resolve, rid, "approved", "AI·留痕模式(自动)",
                                           "QC_AUTO_SIGN_FULL：留痕模式自动签发（全交互留痕）",
                                           True)
            await pg_store.mirror_review(item)
        except review.ReviewError:  # pragma: no cover —— 刚入队必 pending，reviewer 亦非提交人
            pass
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="auto_sign_full",
                                actor=actor, payload={"rid": rid, "reason": risk_reason})
    return rid


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


async def _ask_prefilter(agent: str, q: str, actor: str) -> AskResp | None:
    """imaging/case 共用预检（任务C 意图前置）：命中闲聊/身份 → 审计 prefilter 并返回
    免 LLM 固定兜底响应；未命中返回 None（继续走端点主流程）。"""
    pre = prefilter_reply(q)
    if pre:
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="prefilter",
                                actor=actor, payload={"q": q[:AUDIT_Q_SNIPPET]})
        return AskResp(answer=pre, confidence=0.95, needs_human_review=False,
                       sources=["intent:prefilter"], review_id=None)
    return None


async def _vl_describe_flow(agent: str, imgs: list[str], q: str, actor: str,
                            question: str, system_prompt: str | None = None,
                            fail_action: str = "describe_failed",
                            fail_msg: str = "影像描述服务暂时不可用，请稍后重试",
                            conf_ok: float = 0.72,
                            risk_reason: str = "影像可疑高危征象",
                            suffix_risky: str = "（已标记为需放射科医师双人核对）",
                            suffix_ok: str = "\n\n以上为 AI 初步所见，供医师参考。",
                            audit_truncation: bool = True) -> AskResp:
    """imaging/case 共用 VL 描述流程（外部审查重构：两端点此前为近乎复制的
    「describe→空响应兜底→截断审计→入队→vl_described 留痕」流程）。

    两端点各自语义经参数化保留（行为完全等价）：
    - question/system_prompt：imaging 传原问题 + 默认放射科 prompt；case 传病例前缀 +
      CASE_PROMPT（F1 人设分离）；
    - fail_action/fail_msg：describe_failed vs vl_failed 及各自 500 文案；
    - conf_ok（非高危置信 0.72 vs 0.70）、risk_reason、响应 suffix；
    - audit_truncation：vl_truncated 审计此前仅 imaging 记录（case 保持现状不记），
      显式参数区分，不静默改变任一端点行为。
    """
    try:
        from backend.core.medical_imaging import describe_images_detailed, vl_model_name
        # 诊断1：空响应自动重试 1 次（内部换温度），并带回 vl_raw_len/stop_reason 诊断线索
        d = await describe_images_detailed(imgs, question, system_prompt=system_prompt)
        desc = d["text"]
    except Exception as e:  # noqa: BLE001
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action=fail_action,
                                actor=actor, payload={"err": str(e)[:120]})
        raise HTTPException(500, fail_msg)
    desc = (desc or "").strip()
    # 任务3 空响应兜底：VL 空内容（重试后仍空）→ 明确提示重试，不入队不留档
    # （与 fallback/offtopic 拒答同纪律），审计记 vl_empty + 诊断1
    # vl_raw_len/stop_reason（原始响应长度/停止原因，为换稳定 vision 模型留线索）。
    if not desc:
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="vl_empty",
                                actor=actor, payload={"imgs": len(imgs),
                                                      "vl_raw_len": d["vl_raw_len"],
                                                      "stop_reason": d["stop_reason"]})
        return AskResp(answer="AI 未生成所见，请重新上传或补充描述。",
                       confidence=0.0, needs_human_review=False,
                       sources=[f"{agent}:vl_empty"], review_id=None, status="vl_empty")
    risky = _has_risk(desc)
    conf = 0.60 if risky else conf_ok
    src = ["VL:" + vl_model_name()]
    # 任务4：finish_reason=length（max_tokens 上限截断）→ 审计记 vl_truncated
    # （文案提示由 medical_imaging 统一追加"（输出因长度截断，已按最大长度返回）"）。
    if audit_truncation and (d.get("stop_reason") or "").strip().lower() in (
            "length", "max_tokens", "max_output_tokens"):
        await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="vl_truncated",
                                actor=actor, payload={"vl_raw_len": d["vl_raw_len"],
                                                      "stop_reason": d["stop_reason"]})
    rid = await _enqueue_if_risk(agent, q, desc, conf, risky, risk_reason, actor,
                                 images=imgs, sources=src)
    suffix = suffix_risky if risky else suffix_ok
    await asyncio.to_thread(get_audit_logger().write, event_type=agent, action="vl_described",
                            actor=actor, payload={"len": len(desc), "risky": risky})
    return AskResp(answer=desc + suffix, confidence=conf, needs_human_review=risky,
                   sources=src, review_id=rid)
