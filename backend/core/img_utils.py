"""图片统一规整层（轮 A2）：全入口共用 normalize_data_url，「压缩替代拒绝」。

策略：
- 长边 >2000px → 等比缩到 2000；JPEG 质量 0.9 起步按 0.1 递减（下限 0.4）重编码，
  直到 ≤6MB；降到 0.4 仍超限 → 返回最低质量结果（由调用方校验后 422 拒绝）。
- 已合规（长边 ≤2000 且原图字节 ≤6MB）→ 原样返回（字节不变，避免无谓重编码损伤画质）。
- 非 data URL / base64 损坏 / PIL 无法解码 → 原样返回（防御，绝不抛错拖垮请求，
  交由下游既有丢弃逻辑处理）。

入口接入（轮 A2）：AskReq._cap_images（imaging/case/drug/literature/consult 全部 ask 类）；
MDT 传图（轮 B）接入同一函数。
"""
from __future__ import annotations

import base64
import io

from PIL import Image

MAX_EDGE = 2000               # 长边上限（等比缩放，不放大）
MAX_BYTES = 6 * 1024 * 1024   # 单张字节上限（base64 data URL ≈ 字节 × 4/3）
QUALITY_STEPS = (0.9, 0.8, 0.7, 0.6, 0.5, 0.4)  # JPEG 质量递减步长（下限 0.4）


def enforce_data_url(data_url: str) -> str:
    """SSRF 收口（终评 F1）：拒绝非 data:image/ 前缀的图片输入（HTTP 层 422 /
    MCP invalid params / MDT VL 层 ValueError），与产品语义一致——前端只发内嵌
    data URL，绝不向用户提供的外部 URL 发起服务端请求。"""
    if not isinstance(data_url, str) or not data_url.startswith("data:image/"):
        raise ValueError("仅支持内嵌图片(data URL)")
    return data_url


def normalize_data_url(data_url: str) -> str:
    """规整单张图片 data URL：超长边等比缩 + JPEG 质量递减重编码至 ≤6MB。

    - 已合规 → 原样返回（字节不变）；
    - 需处理 → 返回 data:image/jpeg;base64,...（统一转 RGB 编码）；
    - 输入非图/损坏/非字符串 → 原样返回（防御）。"""
    if not isinstance(data_url, str) or not data_url:
        return data_url
    try:
        _, _, b64 = data_url.partition(",")
        raw = base64.b64decode(b64) if b64 else b""
        img = Image.open(io.BytesIO(raw))
        img.load()
    except Exception:  # noqa: BLE001 —— 非图/损坏：原样返回，绝不抛错
        return data_url
    if max(img.size) <= MAX_EDGE and len(raw) <= MAX_BYTES:
        return data_url  # 已合规：原样返回（字节不变）
    if max(img.size) > MAX_EDGE:
        w, h = img.size
        scale = MAX_EDGE / max(img.size)
        img = img.resize((max(1, round(w * scale)), max(1, round(h * scale))))
    out = img.convert("RGB")  # JPEG 不支持透明通道，统一转 RGB
    best = b""
    for q in QUALITY_STEPS:  # 质量递减重编码，直至 ≤6MB（0.4 仍超限则取最低档交上层判定）
        buf = io.BytesIO()
        out.save(buf, format="JPEG", quality=int(q * 100))
        best = buf.getvalue()
        if len(best) <= MAX_BYTES:
            break
    return "data:image/jpeg;base64," + base64.b64encode(best).decode()
