"""G86 智能体目录（Agent Catalog）与 A2A Agent Card。

目录是「主路由智能体 + 专科子智能体」的唯一事实源：
- 每个 agent 声明职责、技能、可用工具、是否需要 HITL、使用哪类模型通道；
- 路由只按 ``id`` 分派，避免在路由代码里散落业务关键词与技能文案；
- A2A Card 由目录自动派生（技能 id / 描述 / 示例），保证"对外广告"与"内部实际能力"
  不漂移——新增一个技能只改目录一处。

A2A 规范采用 Linux Foundation 托管后的公开形态：``protocolVersion`` / ``skills`` /
``capabilities`` / ``securitySchemes``。本平台是辅助决策工具（非医疗器械），
因此额外在 ``x-medassist`` 扩展字段里声明 HITL 与非器械定位，供接入方合规判断。
"""
from __future__ import annotations

from dataclasses import dataclass

from backend.config import settings
from backend.core import agent_flags

# A2A 协议版本：与官方文档 v1.0 对齐；仅使用稳定字段（不使用 draft 特性）
A2A_PROTOCOL_VERSION = "1.0.0"


@dataclass(frozen=True)
class Skill:
    id: str
    name: str
    description: str
    tags: tuple[str, ...]
    examples: tuple[str, ...]


@dataclass(frozen=True)
class AgentSpec:
    id: str
    name: str
    description: str
    model: str  # chat | vision
    hitl: bool
    roles: tuple[str, ...]
    tools: tuple[str, ...]
    skills: tuple[Skill, ...]
    # 评审 01-S2：是否可被**自动路由**选中。``router`` 只做分派、不直接作答，
    # 必须显式置 False；其余能力域默认 True（目录与路由规则保持一致）。
    auto_routable: bool = True


AGENTS: tuple[AgentSpec, ...] = (
    AgentSpec(
        id="router",
        name="主路由智能体",
        description="判断问题类型并分派给专科子智能体；负责最大跳数与兜底，不直接作答。",
        model="chat",
        hitl=False,
        roles=("doctor", "pharmacist", "qc", "admin"),
        tools=(),
        skills=(Skill(
            id="triage-route",
            name="问题分诊与转交",
            description="把临床问题分类到文献、药物、影像、病例、质控或多学科会诊，并给出置信度与原因。",
            tags=("triage", "routing", "handoff"),
            examples=("这个患者的用药有相互作用吗", "帮我总结这份病例"),
        ),),
        auto_routable=False,  # 只做分派，不能作为答案能力域
    ),
    AgentSpec(
        id="literature",
        name="医学文献助手",
        description="院内知识库 + 外部文献的循证检索与带引用作答。",
        model="chat",
        hitl=True,
        roles=("doctor", "pharmacist", "qc", "admin"),
        tools=("kb_search", "web_search"),
        skills=(
            Skill(
                id="evidence-search",
                name="循证检索",
                description="混合检索院内知识库并返回可溯源片段，用于人工核对或后续生成。",
                tags=("evidence", "retrieval", "citation"),
                examples=("二甲双胍的一线治疗证据", "社区获得性肺炎经验性抗菌方案"),
            ),
            Skill(
                id="evidence-answer",
                name="循证问答",
                description="基于检索证据生成带引用与置信度的辅助答案，低置信/高危进入人工复核。",
                tags=("answer", "citation", "hitl"),
                examples=("这个方案是否符合指南", "帮我找支持该结论的文献"),
            ),
        ),
    ),
    AgentSpec(
        id="drug",
        name="药物信息助手",
        description="药物相互作用、禁忌与重复用药核对，强制药师复核。",
        model="chat",
        hitl=True,
        roles=("doctor", "pharmacist", "qc", "admin"),
        tools=("drug_check", "kb_search"),
        skills=(Skill(
            id="drug-interaction-check",
            name="相互作用与禁忌核对",
            description="按院内规则库识别相互作用/禁忌/重复用药，返回严重度与处理建议。",
            tags=("drug", "interaction", "contraindication"),
            examples=("华法林和阿司匹林能一起用吗", "这个患者用莫西沙星要注意什么"),
        ),),
    ),
    AgentSpec(
        id="kb",
        name="知识库检索助手",
        description="只做院内知识库片段检索，不生成结论，适合 RAG 前置与人工核对。",
        model="chat",
        hitl=False,
        roles=("doctor", "pharmacist", "qc", "admin"),
        tools=("kb_search",),
        skills=(Skill(
            id="kb-retrieval",
            name="院内知识库检索",
            description="返回指南/共识原文片段与来源，不下诊断结论。",
            tags=("kb", "retrieval", "rag"),
            examples=("院内成人退热流程", "围手术期抗凝管理共识"),
        ),),
    ),
    AgentSpec(
        id="imaging",
        name="影像辅助阅片",
        description="多模态影像的结构化初步所见，强制医师复核；明确非医学影像诊断。",
        model="vision",
        hitl=True,
        roles=("doctor", "admin"),
        tools=("kb_search",),
        skills=(Skill(
            id="imaging-describe",
            name="影像初步所见",
            description="对上传影像输出结构化所见与不确定性说明，不替代放射科诊断。",
            tags=("imaging", "multimodal", "hitl"),
            examples=("请描述这张胸部 CT 的可见征象",),
        ),),
    ),
    AgentSpec(
        id="case",
        name="病例总结助手",
        description="结构化病例要点与鉴别提示，强制复核。",
        model="vision",
        hitl=True,
        roles=("doctor", "admin"),
        tools=("kb_search",),
        skills=(Skill(
            id="case-summarize",
            name="病例摘要",
            description="提炼主诉/现病史/关键检查与鉴别方向，供医师快速回顾。",
            tags=("case", "summary"),
            examples=("总结这份住院病例的要点",),
        ),),
    ),
    AgentSpec(
        id="qc",
        name="病历质控助手",
        description="完整性规则 + 内涵质量 + ICD/危急值三轨质控，终审必须人工签字。",
        model="chat",
        hitl=True,
        roles=("qc", "admin", "doctor"),
        tools=("kb_search",),
        skills=(Skill(
            id="record-qc",
            name="病历质控",
            description="按规则与模型检查病历缺陷，输出可定位的整改建议，不自动归档。",
            tags=("qc", "record", "hitl"),
            examples=("检查这份病案首页的完整性",),
        ),),
    ),
    AgentSpec(
        id="mdt",
        name="多学科会诊助手",
        description="三专科并行意见 + 分歧/紧急度前置，最终由医师确认。",
        model="chat",
        hitl=True,
        roles=("doctor", "admin"),
        tools=("kb_search", "drug_check"),
        skills=(Skill(
            id="mdt-consult",
            name="多学科会诊",
            description="并行生成多个专科意见并汇总分歧，标注紧急度与建议行动。",
            tags=("mdt", "consult", "multi-agent"),
            examples=("这个复杂病例需要哪些科室会诊",),
        ),),
    ),
)

AGENT_INDEX = {a.id: a for a in AGENTS}


def get_agent(agent_id: str) -> AgentSpec | None:
    return AGENT_INDEX.get((agent_id or "").strip())


def catalog_payload(role: str) -> list[dict]:
    """按角色过滤的目录（不含工具执行细节；工具清单由 agent_tools 提供）。"""
    out = []
    for agent in AGENTS:
        if role not in agent.roles:
            continue
        out.append({
            "id": agent.id,
            "name": agent.name,
            "description": agent.description,
            "model": agent.model,
            "hitl": agent.hitl,
            "autoRoutable": agent.auto_routable,
            "roles": list(agent.roles),
            "tools": list(agent.tools),
            "skills": [
                {
                    "id": s.id,
                    "name": s.name,
                    "description": s.description,
                    "tags": list(s.tags),
                    "examples": list(s.examples),
                }
                for s in agent.skills
            ],
        })
    return out


def disclosed_agents() -> tuple[AgentSpec, ...]:
    """对外披露的能力域（评审 03-S7：只暴露白名单，不再广播全部内部拓扑）。

    白名单由 ``AGENT_CARD_DISCLOSED_AGENTS`` 配置；留空表示不披露任何能力域
    （fail-closed，能力卡只剩协议元数据）。
    """
    raw = getattr(settings, "agent_card_disclosed_agents", "") or ""
    wanted = [p.strip() for p in raw.split(",") if p.strip()]
    return tuple(a for a in AGENTS if a.id in wanted)


def card_base_url(fallback: str) -> str:
    """能力卡 ``url`` 的基址：优先可信配置，避免 Host 头注入（评审 03-S7）。"""
    base = getattr(settings, "agent_public_base_url", "") or fallback or ""
    return base.rstrip("/")


def agent_card(base_url: str) -> dict:
    """生成 A2A Agent Card（公开元数据，不含患者数据/密钥/内部地址）。"""
    base = (base_url or "").rstrip("/")
    agents = disclosed_agents()
    skills = []
    for agent in agents:
        for s in agent.skills:
            skills.append({
                "id": s.id,
                "name": s.name,
                "description": s.description,
                "tags": list(s.tags),
                "examples": list(s.examples),
                "inputModes": ["text/plain"],
                "outputModes": ["text/plain", "application/json"],
                "x-agent": agent.id,
                "x-hitl": agent.hitl,
            })
    return {
        "protocolVersion": A2A_PROTOCOL_VERSION,
        "name": "MedAssist 临床辅助智能体",
        "description": ("面向医师/药师的循证辅助与病历质控智能体集合；"
                        "非医疗器械，所有输出须经执业人员复核。"),
        "url": f"{base}/api/v1/agent/a2a",
        "preferredTransport": "JSONRPC",
        "version": getattr(settings, "app_version", "0.1.0"),
        "capabilities": {
            # 当前只实现 message/send（同步路由任务）；不广告未实现的 streaming/推送
            "streaming": False,
            "pushNotifications": False,
            "stateTransitionHistory": False,
        },
        "defaultInputModes": ["text/plain"],
        "defaultOutputModes": ["text/plain", "application/json"],
        "skills": skills,
        "securitySchemes": {
            "bearer": {"type": "http", "scheme": "bearer",
                       "description": "使用平台签发的访问令牌调用受保护接口"},
        },
        "security": [{"bearer": []}],
        "x-medassist": {
            "nonMedicalDevice": True,
            "hitlRequired": True,
            "a2aEnabled": agent_flags.get_flag("agent_a2a"),
            "multiAgentEnabled": agent_flags.get_flag("agent_multi_agent"),
            "webSearchEnabled": agent_flags.get_flag("agent_web_search"),
            # 评审 03-S7：只列对外披露的能力域；内部质控/影像/病例/会诊域不进能力卡
            "agents": [a.id for a in agents],
            "disclosure": "allowlist",
        },
    }
