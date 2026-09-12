"""MDT 多学科会诊 Agent 图：循证 + 药学 + 临床 三专科并行给出意见 → 主持人综合并标注分歧。

体现多智能体编排：三个专科节点 fan-out 并行执行（循证检索与临床推理互不阻塞，缩短会诊耗时），
各专科节点产出结构化意见（opinions 用 operator.add 归并），synthesis 节点汇总共识/分歧；
循证意见的引用经 _ground_citations 落地校验（与主文献链路同护栏），
整体置信度由专科置信与分歧情况校准；分歧或高危进入双人核对。
"""
from __future__ import annotations

import asyncio
import json
import operator
import re
from typing import Annotated, Optional, TypedDict

from langchain_core.messages import HumanMessage
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, Field

from backend.agents.medical.literature.nodes import (
    _ask,
    _evidence_brief,
    _ground_citations,
    _parse_json,
)
from backend.core.llm_factory import get_llm, get_structured_llm, get_vl_llm, structured_with_fallback
from backend.core.logger import get_logger
from backend.core.medical_drug import check as drug_check
from backend.core.medical_kb import search_hybrid

logger = get_logger(__name__)


class MdtState(TypedDict, total=False):
    session_id: str
    case: str
    opinions: Annotated[list, operator.add]  # 并行专科节点各自追加，reducer 归并
    report: Optional[dict]


_SYS = "你是严谨的临床多学科会诊(MDT)助手，只依据给定信息与循证证据，坦诚标注不确定与分歧，禁止编造。"


async def literature_op(state: MdtState) -> dict:
    case = state["case"]
    try:
        ev = await asyncio.to_thread(search_hybrid, case, 3) or []
    except Exception:  # noqa: BLE001
        ev = []
    prompt = ("针对该临床问题给出循证意见(2-3 句)并引用来源编号。\n"
              f"证据：{json.dumps(_evidence_brief(ev), ensure_ascii=False)}\n问题：{case}\n"
              '返回 JSON：{"opinion":"...","citations":["..."]}')
    d = _parse_json(await _ask("medical_literature", _SYS + "\n" + prompt), {"opinion": "", "citations": []})
    # claim 溯源护栏：只保留能在证据集核验的引用（防幻觉），有剔除则降置信
    kept, dropped = _ground_citations(d.get("citations", []), ev, [])
    conf = 0.85 if ev else 0.4
    if dropped:
        conf = min(conf, 0.6)
    op = {"specialty": "循证医学", "opinion": d.get("opinion", ""),
          "sources": kept, "confidence": conf}
    return {"opinions": [op]}


async def drug_op(state: MdtState) -> dict:
    r = drug_check(state["case"])
    # 结构化严重度（check() 返回 max_severity ∈ {"禁忌","高危","中危","无"}），与旧子串判定产出一致
    sev = r.get("max_severity", "无")
    conf = 0.9 if r.get("findings") else 0.5
    op = {"specialty": "药学", "opinion": r["answer"], "sources": r["sources"],
          "confidence": conf, "severity": sev}
    return {"opinions": [op]}


async def case_op(state: MdtState) -> dict:
    prompt = ("作为临床医师，基于病例信息给出：主要问题概括 + 待鉴别方向(要点式)。"
              "不下确诊结论。\n病例：" + state["case"] +
              '\n返回 JSON：{"opinion":"..."}')
    d = _parse_json(await _ask("medical_case", _SYS + "\n" + prompt), {"opinion": ""})
    op = {"specialty": "临床诊断", "opinion": d.get("opinion", ""), "sources": [], "confidence": 0.6}
    return {"opinions": [op]}


async def synthesize_node(state: MdtState) -> dict:
    ops = state.get("opinions", [])
    prompt = ("下面是 MDT 各专科意见，请综合并【重点前置】：1) headline 一句话会诊结论(不超过40字，点明最关键判断) "
              "2) urgency 紧急度(高/中/低，按急腹症/危重症风险判断) 3) key_actions 医生最该先做的3-5条行动 "
              "4) 会诊小结 5) 共识 6) 分歧点(若无写\'无\') 7) 下一步建议。\n"
              f"意见：{json.dumps(ops, ensure_ascii=False)}\n问题：{state['case']}\n"
              '返回 JSON：{"headline":"...","urgency":"高|中|低","key_actions":["..."],'
              '"summary":"...","consensus":"...","disagreements":"...","plan":"..."}')
    d = _parse_json(await _ask("medical_literature", _SYS + "\n" + prompt),
                    {"headline": "", "urgency": "中", "key_actions": [],
                     "summary": "", "consensus": "", "disagreements": "无", "plan": ""})
    dis = d.get("disagreements", "无")
    has_dis = dis and dis not in ("无", "无。", "None")
    confs = [o.get("confidence", 0.5) for o in ops] or [0.5]
    overall = round(min(0.95, sum(confs) / len(confs) * (0.85 if has_dis else 1.0)), 2)
    risky = any(o.get("severity") in ("禁忌", "高危") for o in ops)
    need_review = overall < 0.6 or has_dis or risky
    urgency = d.get("urgency") if d.get("urgency") in ("高", "中", "低") else "中"
    headline = (d.get("headline") or "").strip() or (d.get("summary", "")[:40] or "会诊完成")
    key_actions = [str(a) for a in (d.get("key_actions") or []) if str(a).strip()][:5]
    lines = [f"【{o['specialty']}】(置信 {o.get('confidence'):.2f}) {o.get('opinion','')}" for o in ops]
    report_text = ("会诊小结：" + d.get("summary", "") +
                   "\n共识：" + d.get("consensus", "") +
                   "\n分歧：" + dis + "\n建议：" + d.get("plan", "") +
                   "\n\n—— 各专科意见 ——\n" + "\n".join(lines))
    return {"report": {
        "headline": headline, "urgency": urgency, "key_actions": key_actions,
        "text": report_text, "confidence": overall, "opinions": ops,
        "disagreements": dis, "needs_human_review": need_review,
        "sources": sorted({s for o in ops for s in o.get("sources", [])}),
    }}


def build_mdt_graph(checkpointer=None):
    """默认不挂 checkpointer（与 literature 同纪律）：thread_id 每请求唯一、无断点续跑需求，
    MemorySaver 会按唯一 thread_id 无界累积会话快照（长期运行 OOM）。"""
    g = StateGraph(MdtState)
    g.add_node("literature", literature_op)
    g.add_node("drug", drug_op)
    g.add_node("case", case_op)
    g.add_node("synthesize", synthesize_node)
    # 三专科并行 fan-out：START → {literature, drug, case} → synthesize（join）
    g.add_edge(START, "literature")
    g.add_edge(START, "drug")
    g.add_edge(START, "case")
    g.add_edge("literature", "synthesize")
    g.add_edge("drug", "synthesize")
    g.add_edge("case", "synthesize")
    g.add_edge("synthesize", END)
    return g.compile(checkpointer=checkpointer)


# ==================== 跨科室会诊 · Agent 动态组队（真实会诊流转） ====================
# 继文献 refine 循环后第二个 LLM 自主决策点：读病例 + 实时科室清单，自主选择召集哪些专科
# （如「牙痛+糖尿病」→ 口腔科+内分泌科），再按科室生成定向初步意见随单分发。

class ConsultTeam(BaseModel):
    """跨科室会诊动态组队决策（function calling 结构化输出，经 pydantic 校验）。"""
    departments: list[str] = Field(default_factory=list,
                                   description="从可选科室清单中选出的专科名（必须与清单完全一致）")
    reasoning: str = Field(default="", description="组队理由（结合病例，简述）")
    # 分科理由（一句话：基于症状/影像所见判断为何召这些科；带图时优先引用影像所见）。
    # 与既有 reasoning 并存（schema 加字段向后兼容：旧 mock/调用方无 reason 键不破坏）。
    reason: str = Field(default="", description="分科理由（一句话：基于症状/影像所见判断为何召这些科；带图时优先引用影像所见）")


_TEAM_MAX = 6  # 单次会诊召集科室上限（代码强制截断，防 LLM 全选导致分发面过大）

_CONSULT_TEAM_PROMPT = (
    "你是跨科室会诊发起助手。阅读下面的病例/会诊问题，从【可选科室清单】中自主选出"
    "最需要参与会诊的专科（1-{maxn} 个，按病例实际需要与下方分科规则）。\n"
    "硬性规则：只能从清单中选择，科室名必须与清单完全一致，不得编造清单外的科室。\n"
    # 分科规则一：分科理由必出（一句话；带图优先引用影像所见——受邀医生要看得懂为何这样定向）
    "分科规则：必须输出分科理由（一句话：基于症状/影像所见判断为何召这些科；"
    "附病例影像图片时优先引用影像所见）。\n"
    # 分科规则二：无明确指向最少 2 科（主科+鉴别科，宁多勿漏）；仅明确影像急症指征允许单科
    "组队下限：无明确影像/症状指向时最少召 2 科（主科+鉴别科，宁多勿漏）；"
    "有明确影像急症指征（如靶征/游离气体）才允许单科。\n"
    "任务4 急诊科收口：急诊科仅在存在急危重征象（生命体征不稳/气道受压风险/"
    "怀疑急性冠脉综合征等）时才召集；普通专科症状优先相应专科而非急诊。\n"
    "可选科室清单：{depts}\n病例：{case}\n"
)

# 任务4 急诊科收口（代码强制后过滤，不信任 LLM 自律）：病例不含急危重征象词时
# 从组队结果剔除急诊科——普通专科症状优先专科，防止 LLM 习惯性把急诊科拉进队。
EMERGENCY_DEPT = "急诊科"
_EMERGENCY_SIGNS = re.compile(
    r"(生命体征不稳|生命体征不平稳|气道受压|气道梗阻|急性冠脉|心肌梗死|心梗|"
    r"休克|昏迷|意识丧失|意识障碍|呼吸衰竭|呼吸骤停|心跳骤停|心脏骤停|猝死|"
    r"大出血|活动性出血|张力性气胸|脑疝|急危重|危重|濒死|抽搐不止|癫痫持续|"
    r"严重过敏|过敏性休克|窒息)")


def _filter_emergency(question: str, departments: list[str]) -> list[str]:
    """急诊科后过滤：病例无急危重征象词 → 剔除急诊科（有征象词则保留，不动其余科室）。"""
    if _EMERGENCY_SIGNS.search(question or ""):
        return departments
    return [d for d in departments if d != EMERGENCY_DEPT]

_DEPT_BRIEF_PROMPT = (
    "你是跨科室会诊发起助手。请为下列每个受邀专科，结合病例给出该专科视角的定向初步意见"
    "（每条 2-3 句：该科最需要关注的问题、建议补充的检查或处置方向），不下确诊结论。\n"
    "受邀专科：{depts}\n病例：{case}\n"
    '返回 JSON：{{"opinions":[{{"dept":"科室名","opinion":"..."}}]}}'
)

# 轮 B1（MDT 传图）：有图时附加提示——要求意见引用影像所见（意见里能看到关键影像发现，
# 受邀科室医生才读得懂 AI 为什么这样定向）。
_VL_BRIEF_NOTE = (
    "\n附病例影像图片：请结合影像所见给出意见，意见中引用关键影像发现（如部位/形态/密度等）。"
)
_VL_TEAM_NOTE = "\n附病例影像图片：请结合影像所见判断最需要召集的专科。"

_MDT_IMG_CAP = 10  # 与 medical_router.MAX_IMAGES 一致（轮 A2 上限 6→10）；防御性再截断


def _vl_content(images: list[str], prompt: str) -> list[dict]:
    """多模态消息 content：图片在前、文字在后（与 medical_imaging.describe_images 同构）。
    SSRF 收口（终评 F1）：逐张 enforce_data_url——非 data:image/ 抛 ValueError
    （用户字符串原样进 image_url 会被服务端当 URL 请求，一律拒绝）。"""
    from backend.core.img_utils import enforce_data_url
    return ([{"type": "image_url", "image_url": {"url": enforce_data_url(u)}} for u in images]
            + [{"type": "text", "text": prompt}])


def _extract_text(r) -> str:
    """从 LLM 响应提取纯文本（content 可能为分段 list，与 medical_imaging._extract_reply 同语义）。"""
    out = r.content if hasattr(r, "content") else str(r)
    if isinstance(out, list):  # 某些 provider 返回分段
        out = "".join(p.get("text", "") if isinstance(p, dict) else str(p) for p in out)
    return out or ""


async def pick_departments(question: str, images: list[str] | None = None) -> dict:
    """Agent 决策点：LLM 读病例 + 实时科室清单，自主选择召集哪些专科。

    科室同步（关键基建）：科室清单每次实时调 departments.list_departments()（绝不缓存）——
    管理端新增科室后，下一次会诊的组队 prompt 立即包含新科室、分发即可达新科室医生。
    护栏（代码强制，不信任 LLM 自律）：非清单内科室名过滤；去重保序；[:_TEAM_MAX] 截断；
    任务4 急诊科收口：病例无急危重征象词时代码强制剔除急诊科（prompt 约束 + 后过滤双保险）。
    诊断4 兜底语义变更：选空/LLM 异常先原样重试 1 次，仍失败 → 抛 ConsultError
    （不再全量召集兜底——全量分发既打扰无关科室也放大误召集面，宁失败不乱召集）。
    轮 B1（MDT 传图）：带图时切 VL 视觉模型（get_vl_llm + 结构化输出同 schema），
    让组队决策参考影像所见；无图维持 get_structured_llm 文本路径零改变。
    """
    from backend.core import departments  # 延迟导入避免环；每次实时读（科室同步纪律）
    from backend.core.consults import ConsultError  # 延迟导入避免环（诊断4 兜底语义）
    depts = await asyncio.to_thread(departments.list_departments)
    if not depts:
        return {"departments": [], "reasoning": "科室清单为空，无法组队",
                "reason": "科室清单为空，无法组队"}
    prompt = _CONSULT_TEAM_PROMPT.format(maxn=_TEAM_MAX,
                                         depts=json.dumps(depts, ensure_ascii=False),
                                         case=(question or "")[:4000])
    imgs = [x for x in (images or []) if x][:_MDT_IMG_CAP]
    reasoning = ""
    reason = ""
    for attempt in (1, 2):  # LLM 选空/异常 → 原样重试 1 次（诊断4）
        raw: list[str] = []
        try:
            if imgs:
                # 有图：VL 视觉模型读影像 + 组队 prompt（结构化输出 schema 与文本路径一致）
                # F3：经 structured_with_fallback 包装——GLM thinking 模型 400
                # 'Thinking mode does not support this tool_choice' 时自动降级 json_mode 重试
                vl = get_vl_llm()
                d = await structured_with_fallback(
                    vl.with_structured_output(ConsultTeam, method="function_calling"),
                    ConsultTeam, None, base_llm_fn=lambda: vl,
                    messages=[HumanMessage(content=_vl_content(
                        imgs, _SYS + "\n" + prompt + _VL_TEAM_NOTE))])
            else:
                # F3：同上——文本路径结构化输出统一走 thinking-400 降级兜底
                d = await structured_with_fallback(
                    get_structured_llm("medical_literature", ConsultTeam), ConsultTeam,
                    _SYS + "\n" + prompt, base_llm_fn=lambda: get_llm("medical_literature"))
            raw = [str(x).strip() for x in (getattr(d, "departments", None) or []) if str(x).strip()]
            reasoning = (getattr(d, "reasoning", "") or "")[:200]
            # 分科理由解析（旧 mock 对象无 reason 属性 → 回退 reasoning，向后兼容）
            reason = (getattr(d, "reason", "") or getattr(d, "reasoning", "") or "")[:200]
        except Exception as exc:  # noqa: BLE001 —— 单次决策异常不终局，重试一次再定
            logger.warning("mdt.pick_departments_llm_failed", attempt=attempt, error=str(exc)[:120])
        # 非法科室名过滤（以实时清单为准）+ 去重保序 + [:_TEAM_MAX] 截断
        valid = list(dict.fromkeys(x for x in raw if x in depts))[:_TEAM_MAX]
        if valid:
            return {"departments": _filter_emergency(question, valid),
                    "reasoning": reasoning or "（未给出理由）",
                    "reason": reason or "（未给出理由）"}
    raise ConsultError("未能确定召集科室，请补充病例信息后重试")  # 诊断4：不再全量召集兜底


async def dept_briefs(question: str, depts: list[str],
                      images: list[str] | None = None) -> dict:
    """按科室生成定向初步意见（一次 LLM 调用产出全部目标科室；单科缺失/解析失败逐科
    兜底文案，绝不阻塞会诊发起）。返回 {dept: 意见文本}。
    轮 B1（MDT 传图）：带图时切 VL 视觉模型（get_vl_llm 多模态消息）并要求意见引用
    影像所见；无图维持 _ask 文本路径零改变。"""
    depts = [d for d in (depts or []) if d]
    out: dict[str, str] = {}
    if depts:
        prompt = _DEPT_BRIEF_PROMPT.format(depts=json.dumps(depts, ensure_ascii=False),
                                           case=(question or "")[:4000])
        imgs = [x for x in (images or []) if x][:_MDT_IMG_CAP]
        try:
            if imgs:
                # 有图：VL 视觉模型读影像 + 专科简报 prompt（图在前文在后）
                r = await get_vl_llm().ainvoke([HumanMessage(content=_vl_content(
                    imgs, _SYS + "\n" + prompt + _VL_BRIEF_NOTE))])
                d = _parse_json(_extract_text(r), {})
            else:
                d = _parse_json(await _ask("medical_literature", _SYS + "\n" + prompt), {})
            for item in (d.get("opinions") or []):
                if isinstance(item, dict) and item.get("dept") in depts:
                    out[str(item["dept"])] = str(item.get("opinion", "")).strip()[:800]
        except Exception as exc:  # noqa: BLE001 —— 意见生成失败逐科兜底，不阻塞发起
            logger.warning("mdt.dept_briefs_llm_failed", error=str(exc)[:120])
    for dep in depts:
        out.setdefault(dep, "（AI 定向初步意见生成失败，请受邀医师结合病例自行判断）")
    return out
