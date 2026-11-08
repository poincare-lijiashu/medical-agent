"""E2E 限流兜底（第 114–115 批）：登录与首屏数据撞到 60 次/分钟限流时自动等窗口恢复。

背景：服务端对每账号是 60 次/分钟**滑动窗口**限流；整套 e2e 顺序跑完会反复用同一批
演示账号，边缘情况下出现两类 429：
1. 登录请求 429 → 页面停在登录页，`.rail a.nav` 15s 超时；
2. **登录成功后的首屏并发请求**（overview / pending / config / kb…）中某一条 429 →
   页面某个数据区为空，后续断言假红（第 118 批遗留边界）。

做法：
- 监听 `/auth/login` 429 → 等窗口（61s）重放登录一次；
- 登录成功后开一个**首刷观察窗**（约 3s）：期间任何 `/api/v1/` 请求 429 → 等窗口后
  reload 一次，最多 2 轮；
- 只对**确认是限流**的情况等待重试，其他失败原样抛出（不吞真实回归）。
"""
from __future__ import annotations

from typing import Callable

RATE_LIMIT_MARK = "请求过于频繁"
WINDOW_WAIT_MS = 61_000  # 60 次/分钟滑动窗口 + 1s 余量
NAV_SELECTOR = ".rail a.nav"  # 布局壳侧边导航（登录成功的标志）
SETTLE_ROUNDS = 2
# 首刷观察窗：登录成功后并发首屏请求通常在 1s 内返回；取 1.5s 兼顾"不漏 429"与
# "每条用例多等 1.5s"（16 条 ≈ +24s，仅在真实限流时才额外等 61s）
SETTLE_CHECK_MS = 1_500
_POLL_STEP_MS = 250


def _banner_says_rate_limited(page) -> bool:
    """页面正文是否显示限流提示（toast 会消失，调用方需在观察窗内轮询）。"""
    try:
        return RATE_LIMIT_MARK in page.inner_text("body")
    except Exception:  # noqa: BLE001
        return False


def _watch_window_has_429(page, hits: dict, total_ms: int = SETTLE_CHECK_MS) -> bool:
    """在观察窗内轮询：响应监听计数或页面提示任一命中即视为限流。"""
    waited = 0
    while waited < total_ms:
        if hits["n"] > 0 or _banner_says_rate_limited(page):
            return True
        page.wait_for_timeout(_POLL_STEP_MS)
        waited += _POLL_STEP_MS
    return hits["n"] > 0 or _banner_says_rate_limited(page)


def login_with_rate_limit_retry(page, submit_fn: Callable[[], None],
                                timeout_ms: int = 15_000) -> None:
    """提交登录 → 等导航 → 等首屏稳定；确认 429 时限流窗口后重放/刷新（有限次）。"""
    hits = {"n": 0}

    def _on_response(resp) -> None:
        try:
            if "/api/v1/" in resp.url and resp.status == 429:
                hits["n"] += 1
        except Exception:  # noqa: BLE001 —— 监听器异常不影响测试主流程
            pass

    page.on("response", _on_response)
    try:
        submit_fn()
        try:
            page.wait_for_selector(NAV_SELECTOR, timeout=timeout_ms)
        except Exception as first:  # noqa: BLE001
            if not _watch_window_has_429(page, hits, total_ms=1_000):
                raise first from None
            # 登录阶段限流：等一个窗口后重放登录
            page.wait_for_timeout(WINDOW_WAIT_MS)
            hits["n"] = 0
            submit_fn()
            page.wait_for_selector(NAV_SELECTOR, timeout=timeout_ms)

        # 首刷观察窗：登录成功不代表首屏数据都拿到了（并发请求可能 429）
        for _ in range(SETTLE_ROUNDS):
            if not _watch_window_has_429(page, hits):
                break
            page.wait_for_timeout(WINDOW_WAIT_MS)
            hits["n"] = 0
            page.reload()
            page.wait_for_selector(NAV_SELECTOR, timeout=timeout_ms)
    finally:
        page.remove_listener("response", _on_response)
