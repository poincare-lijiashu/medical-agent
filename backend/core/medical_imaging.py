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


def _to_data_url(image: str) -> str:
    if image.startswith("data:"):
        return image
    return "data:image/jpeg;base64," + image


def vl_model_name() -> str:
    return settings.qwen_model_vl


async def describe_images(images: list[str], question: str = "") -> str:
    """支持多图（≤N6）：逐张描述并综合。单图时与旧行为一致。"""
    imgs = [x for x in (images or []) if x][:6]
    if not imgs:
        return ""
    llm = get_vl_llm(temperature=0)
    prompt = _PROMPT if len(imgs) == 1 else _PROMPT.replace(
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
