"""E2E 限流兜底（第 114 批）：登录撞到 60 次/分钟限流时自动等窗口重试一次。

背景：服务端对每账号是 60 次/分钟**滑动窗口**限流；整套 e2e 顺序跑完会反复用同一批
演示账号登录与首刷，边缘情况下第 N 条用例的登录返回 429，页面停在登录页，
表现为 `.rail a.nav` 15s 超时——这是环境抖动，不是产品缺陷，但会让"全量全绿"每次
都要人工复跑。

判据（双保险，避免 toast 已消失导致误判）：
1. Playwright 捕获 `/auth/login` 的 HTTP 429；或
2. 等待失败时页面正文含"请求过于频繁"。

只有确认是限流才等待 61s 并重放一次登录；其他原因的超时原样抛出，绝不吞真实失败。
"""
from __future__ import annotations

from typing import Callable

RATE_LIMIT_MARK = "请求过于频繁"
WINDOW_WAIT_MS = 61_000  # 60 次/分钟滑动窗口 + 1s 余量
NAV_SELECTOR = ".rail a.nav"  # 布局壳侧边导航（登录成功的标志）


def login_with_rate_limit_retry(page, submit_fn: Callable[[], None],
                                timeout_ms: int = 15_000) -> None:
    """提交登录并等待侧边导航；确认是 429 限流时等窗口重置后重放一次。"""
    hit = {"rate_limited": False}

    def _on_response(resp) -> None:
        try:
            if "/auth/login" in resp.url and resp.status == 429:
                hit["rate_limited"] = True
        except Exception:  # noqa: BLE001 —— 监听器异常不影响测试主流程
            pass

    page.on("response", _on_response)
    try:
        submit_fn()
        try:
            page.wait_for_selector(NAV_SELECTOR, timeout=timeout_ms)
            return
        except Exception as first:  # noqa: BLE001
            body = ""
            try:
                body = page.inner_text("body")
            except Exception:  # noqa: BLE001
                pass
            if not hit["rate_limited"] and RATE_LIMIT_MARK not in body:
                raise first from None
        page.wait_for_timeout(WINDOW_WAIT_MS)
        hit["rate_limited"] = False
        submit_fn()
        page.wait_for_selector(NAV_SELECTOR, timeout=timeout_ms)
    finally:
        page.remove_listener("response", _on_response)
