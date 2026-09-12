"""影像描述：用 Qwen-VL 把医学图像转成结构化中文所见（强制人工复核，不确诊）。"""
from __future__ import annotations

from langchain_core.messages import HumanMessage

from backend.config import settings
from backend.core.llm_factory import get_vl_llm

_PROMPT = (
    "你是放射科辅助工具。请客观描述这张医学图像：\n"
    "1) 影像类型与部位；2) 主要所见；3) 可疑异常征象；4) 建议的下一步检查。\n"
    "严禁给出确诊结论或处方；信息不足时明确说明；用中文分点作答。\n"
    "任务3 空响应防线：必须输出至少包含「影像类型/所见」的结构化中文文本，"
    "即使图像无有效医学内容（如空白/非影像图），也要明确写「图像无有效医学内容」及原因，"
    "禁止返回空字符串或仅返回标点/符号。"
)

# 病例总结专用 prompt：与放射科阅片人设分离，供 case/ask 传入（F1）。
CASE_PROMPT = (
    "你是临床病例总结助手。结合用户提供的病史与图片（检查报告、影像、化验单等），输出："
    "1) 病例摘要；2) 关键发现；3) 鉴别诊断方向（列 2-4 个，标注支持/不支持点）；"
    "4) 建议的下一步检查或处理。禁止确诊结论与处方；信息不足时明确说明；用中文分点作答。\n"
    "任务3 空响应防线：必须输出结构化中文文本；图片无有效内容时也需明确说明「图片无有效"
    "信息」并给出补充建议，禁止返回空字符串。"
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


def _extract_reply(r) -> tuple[str, str | None]:
    """从 VL 响应提取（文本, stop_reason）。stop_reason 取 response_metadata/
    additional_kwargs 里的 finish_reason（OpenAI 兼容端点）等字段，供空响应审计诊断。"""
    out = r.content if hasattr(r, "content") else str(r)
    if isinstance(out, list):  # 某些 provider 返回分段
        out = "".join(p.get("text", "") if isinstance(p, dict) else str(p) for p in out)
    stop = None
    meta = getattr(r, "response_metadata", None)
    if isinstance(meta, dict):
        stop = meta.get("finish_reason") or meta.get("stop_reason")
    if not stop:
        ak = getattr(r, "additional_kwargs", None)
        if isinstance(ak, dict):
            stop = ak.get("finish_reason") or ak.get("stop_reason")
    return (out or ""), (str(stop) if stop else None)


# 任务4 截断提示文案：finish_reason=length（输出因 max_tokens 上限被截断）时追加到
# 所见末尾——用户此前看到"心脏及"式的半句还以为是 AI 结论残缺，明确告知是长度上限。
_VL_TRUNC_SUFFIX = "（输出因长度截断，已按最大长度返回）"


def _is_truncated(stop: str | None) -> bool:
    """stop_reason 是否为长度截断（openai finish_reason=length / anthropic stop_reason=max_tokens 等价形态）。"""
    return bool(stop) and str(stop).strip().lower() in ("length", "max_tokens", "max_output_tokens")


async def describe_images_detailed(images: list[str], question: str = "",
                                   system_prompt: str | None = None) -> dict:
    """支持多图（轮 A2 上限 10）：逐张描述并综合，返回 {"text","vl_raw_len","stop_reason"}。

    诊断1 稳定性：VL 偶发返回空内容（分段 list/超时截断/网关吞响应等）——首空自动换
    slightly 更高温度（0→0.2）重试一次（prompt 原样）；仍空则把末次原始响应长度与
    stop_reason 一并返回，供路由层写入 vl_empty 审计（为将来换稳定 vision 模型保留
    诊断线索）。system_prompt=None 时用默认放射科 _PROMPT；case/ask 传 CASE_PROMPT（F1）。
    任务4 截断修复：stop_reason=length（max_tokens 上限）→ 文本末尾补截断提示文案，
    路由层同时记 vl_truncated 审计（配合 llm_factory max_tokens=2000 根治截断）。
    """
    imgs = [x for x in (images or []) if x][:10]  # 轮 A2：与 medical_router.MAX_IMAGES 一致（6→10）
    if not imgs:
        return {"text": "", "vl_raw_len": 0, "stop_reason": None}
    base = _PROMPT if system_prompt is None else system_prompt
    prompt = base if len(imgs) == 1 else base.replace(
        "这张医学图像", f"这 {len(imgs)} 张医学图像（逐张描述后综合）")
    if question:
        prompt += f"\n用户关注点：{question}"
    content = [{"type": "image_url", "image_url": {"url": _to_data_url(x)}} for x in imgs]
    content.append({"type": "text", "text": prompt})
    text, raw_len, stop = "", 0, None
    for temp in (0, 0.2):  # 首试 temperature=0；空响应换 slightly 更高温度原样重试 1 次
        r = await get_vl_llm(temperature=temp).ainvoke([HumanMessage(content=content)])
        raw, stop = _extract_reply(r)
        text, raw_len = raw.strip(), len(raw)
        if text:
            break
    if text and _is_truncated(stop) and not text.endswith(_VL_TRUNC_SUFFIX):
        text += _VL_TRUNC_SUFFIX  # 任务4：截断文案补在所见末尾（末次重试结果仍截断才补）
    return {"text": text, "vl_raw_len": raw_len, "stop_reason": stop}


async def describe_images(images: list[str], question: str = "",
                          system_prompt: str | None = None) -> str:
    """describe_images_detailed 的字符串便捷封装（既有调用方/测试契约不变）。"""
    return (await describe_images_detailed(images, question, system_prompt))["text"]


async def describe_image(image_b64: str, question: str = "") -> str:
    return await describe_images([image_b64], question)
