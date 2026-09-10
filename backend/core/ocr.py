"""扫描版 PDF / 图片 OCR（任务C）：rapidocr-onnxruntime，CPU onnx 推理，模型随包内置。

选型事实（环境核查结论）：edu_agent 环境原无任何 OCR 包（无 paddleocr/easyocr/onnxruntime）；
按「已有 paddleocr→直接用，否则优先 rapidocr-onnxruntime（轻量 onnx、中文优、无 paddle 大
依赖）」规则，已安装 rapidocr-onnxruntime 1.4.4（onnxruntime+opencv，wheel 约 60MB）。
失败降级：组件未安装/推理失败 → 抛 RuntimeError（kb_ingest 转 ValueError 透传明确报错，
绝不静默产生空文本污染知识库）。
"""
from __future__ import annotations

import threading

from backend.core.logger import get_logger

logger = get_logger(__name__)

_lock = threading.Lock()
_ocr = None  # RapidOCR 单例（进程内懒加载；首次调用加载 onnx 模型约 1-2s）


def ocr_available() -> bool:
    """检测 OCR 组件是否可导入（供诊断/状态展示；不触发模型加载）。"""
    try:
        import rapidocr_onnxruntime  # noqa: F401
        return True
    except ImportError:
        return False


def get_ocr():
    """RapidOCR 单例。未安装 → RuntimeError（明确报错文案，含安装指引）。"""
    global _ocr
    if _ocr is not None:
        return _ocr
    with _lock:
        if _ocr is not None:
            return _ocr
        try:
            from rapidocr_onnxruntime import RapidOCR
        except ImportError as e:
            raise RuntimeError("扫描版PDF需OCR（未安装 rapidocr）：请在服务环境执行 "
                               "pip install rapidocr-onnxruntime 后重试") from e
        logger.info("ocr.rapidocr_loading")
        _ocr = RapidOCR()
        logger.info("ocr.rapidocr_loaded")
        return _ocr


def ocr_image_bytes(img: bytes) -> str:
    """单页/单图字节（png/jpg）→ 识别文本（按检测框纵向排序拼接，行间 \\n）。
    无文字 → 空串（调用方决定后续语义：PDF 空白页正常，图片空文本按无有效内容报错）。"""
    ocr = get_ocr()
    result, _elapsed = ocr(img)
    if not result:
        return ""
    # RapidOCR 结果元素为 [box(4点坐标), text, score]；保险按框顶 y 排序保证阅读序
    rows = []
    for item in result:
        try:
            box, text = item[0], str(item[1]).strip()
        except Exception:  # noqa: BLE001 —— 结构异常行跳过，不拖垮整页
            continue
        if text:
            try:
                rows.append((float(box[0][1]), text))
            except Exception:  # noqa: BLE001 —— 无坐标时按原序附加
                rows.append((0.0, text))
    rows.sort(key=lambda x: x[0])
    return "\n".join(t for _, t in rows)
