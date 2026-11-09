"""把 PDF/图片解析放进子进程（评测 04 P1-10 第二步）：硬超时 + 超时 kill。

为什么需要：PyMuPDF 的 `get_text()/get_pixmap()` 与 OCR 推理是 C/原生调用，**线程无法打断**。
前一版只在页间检查 deadline（能挡住"上千页扫描件"），但单页畸形 PDF（递归对象、超大图像）
仍可能卡死线程并占满 CPU。子进程可以把这种调用整块 kill 掉。

实现取舍：
- Linux 优先 `fork`（零重导入、快）；Windows/受限环境回落 `spawn`；
- 子进程只负责"解析文本"，不加载 embedding 模型（kb_ingest 的 embedder 是函数内懒加载）；
- 子进程通过 Queue 回报进度 `("page", done, total)` / 结果 `("ok", text)` / 错误 `("err", msg)`；
- 启动失败（受限环境/沙箱）抛 `IsolationUnavailable`，调用方回落线程内解析（仍受页数/时限约束）。
"""
from __future__ import annotations

import multiprocessing as mp
import os
import queue as _queue
import tempfile
import time

from backend.core.logger import get_logger

logger = get_logger(__name__)

_CTX = None


class IsolationUnavailable(RuntimeError):
    """子进程不可用（受限环境/沙箱）：调用方应回落线程内解析。"""


def _ctx():
    global _CTX
    if _CTX is None:
        methods = mp.get_all_start_methods()
        _CTX = mp.get_context("fork" if "fork" in methods else "spawn")
    return _CTX


def _worker(kind: str, payload, q) -> None:
    """子进程入口：只做解析，结果/进度/错误全部经 Queue 回报。"""
    try:
        if kind == "extract":
            from backend.core import kb_ingest  # 子进程内导入，避免主进程循环依赖

            filename, path = payload
            with open(path, "rb") as f:
                raw = f.read()
            text = kb_ingest._extract_text_raw(
                filename, raw, on_ocr_page=lambda done, total: q.put(("page", done, total)))
            q.put(("ok", text))
        elif kind == "echo":
            q.put(("ok", payload))
        elif kind == "sleep":
            time.sleep(float(payload))
            q.put(("ok", "done"))
        else:  # pragma: no cover —— 内部调用只会用上面三种
            q.put(("err", f"unknown kind {kind}"))
    except Exception as e:  # noqa: BLE001 —— 子进程异常必须回传，不能让父进程干等
        q.put(("err", (str(e) or type(e).__name__)[:300]))


def _kill(p) -> None:
    if p is None:
        return
    if p.is_alive():
        p.terminate()
        p.join(1.0)
    if p.is_alive():  # terminate 无效（卡在不可中断的系统调用）→ kill
        p.kill()
        p.join(1.0)


def _run(kind: str, payload, timeout_s: float, on_page=None) -> str:
    """启动子进程并等结果：超时/异常退出 → ValueError（调用方转 400/任务 error）。"""
    try:
        ctx = _ctx()
        q = ctx.Queue(maxsize=256)
        p = ctx.Process(target=_worker, args=(kind, payload, q), daemon=True)
        p.start()
    except Exception as e:  # noqa: BLE001 —— 受限环境（无 /dev/shm、被禁 fork 等）
        raise IsolationUnavailable(str(e)[:160]) from e
    deadline = time.monotonic() + max(0.1, float(timeout_s))
    try:
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                raise ValueError(f"文档解析超时（子进程已终止，>{timeout_s:g}s），请拆分后上传")
            try:
                msg = q.get(timeout=min(0.2, left))
            except _queue.Empty:
                if not p.is_alive():
                    raise ValueError("文档解析进程异常退出（可能内存不足），请重试或拆分文件")
                continue
            tag, val = msg[0], msg[1:]
            if tag == "page":
                if on_page:
                    on_page(int(val[0]), int(val[1]))
                continue
            if tag == "ok":
                return val[0]
            raise ValueError(str(val[0]))
    finally:
        _kill(p)
        try:
            q.close()
        except Exception:  # noqa: BLE001
            pass


def extract_text_isolated(filename: str, raw: bytes, timeout_s: float,
                          on_page=None) -> str:
    """在子进程里跑 `kb_ingest._extract_text_raw`（写入临时文件传参，避免 pickle 大 bytes）。"""
    ext = os.path.splitext(filename)[1].lower() or ".bin"
    fd, path = tempfile.mkstemp(prefix="kb_parse_", suffix=ext)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(raw)
        return _run("extract", (filename, path), timeout_s, on_page=on_page)
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def selfcheck(timeout_s: float = 5.0) -> bool:
    """环境自检：能否起子进程并回传结果（部署后可用于 readiness/排障）。"""
    try:
        return _run("echo", "ok", timeout_s) == "ok"
    except (IsolationUnavailable, ValueError):
        return False
