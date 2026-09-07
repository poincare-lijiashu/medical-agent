"""影像描述：用 Qwen-VL 把医学图像转成结构化中文所见（强制人工复核，不确诊）。"""
from __future__ import annotations

from langchain_core.messages import HumanMessage

from backend.config import settings
from backend.core.llm_factory import get_vl_llm

_PROMPT = (
    "你是放射科辅助工具。请客观描述这张医学图像：\n"
    "1) 影像类型与部位；2) 主要所见；3) 可疑异常征象；4) 建议的下一步检查。\n"
    "严禁给出确诊结论或处方；信息不足时明确说明；用中文分点作答。"
)

# 病例总结专用 prompt：与放射科阅片人设分离，供 case/ask 传入（F1）。
CASE_PROMPT = (
    "你是临床病例总结助手。结合用户提供的病史与图片（检查报告、影像、化验单等），输出："
    "1) 病例摘要；2) 关键发现；3) 鉴别诊断方向（列 2-4 个，标注支持/不支持点）；"
    "4) 建议的下一步检查或处理。禁止确诊结论与处方；信息不足时明确说明；用中文分点作答。"
)


def _to_data_url(image: str) -> str:
    if image.startswith("data:"):
        return image
    return "data:image/jpeg;base64," + image


def vl_model_name() -> str:
    """FIND-15：优先返回管理端激活 vision provider 的 model_id（与实跑一致），
    无配置/读配置失败回落 .env 值。"""
    from backend.core.llm_config import active_provider
    try:
        p = active_provider("vision")
    except Exception:  # noqa: BLE001 —— 配置文件损坏等不阻断回答，回落 .env
        p = None
    return (p or {}).get("model_id") or settings.qwen_model_vl


async def describe_images(images: list[str], question: str = "",
                          system_prompt: str | None = None) -> str:
    """支持多图（≤N6）：逐张描述并综合。单图时与旧行为一致。

    system_prompt=None 时用默认放射科 _PROMPT；case/ask 传 CASE_PROMPT（F1 人设分离）。
    """
    imgs = [x for x in (images or []) if x][:6]
    if not imgs:
        return ""
    llm = get_vl_llm(temperature=0)
    base = _PROMPT if system_prompt is None else system_prompt
    prompt = base if len(imgs) == 1 else base.replace(
        "这张医学图像", f"这 {len(imgs)} 张医学图像（逐张描述后综合）")
    if question:
        prompt += f"\n用户关注点：{question}"
    content = [{"type": "image_url", "image_url": {"url": _to_data_url(x)}} for x in imgs]
    content.append({"type": "text", "text": prompt})
    r = await llm.ainvoke([HumanMessage(content=content)])
    out = r.content if hasattr(r, "content") else str(r)
    if isinstance(out, list):  # 某些 provider 返回分段
        out = "".join(p.get("text", "") if isinstance(p, dict) else str(p) for p in out)
    return (out or "").strip()


async def describe_image(image_b64: str, question: str = "") -> str:
    return await describe_images([image_b64], question)
