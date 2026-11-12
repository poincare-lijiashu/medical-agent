"""AI 模型调用台账与授权对账上报（§289 / §290）。

为什么需要本模块
----------------
授权里的"AI 日调用上限"只有在**每天真的有人把用量送到授权服务**时才有意义。
此前用量快照只能人工 `POST /license/usage`：合同签了 6000 次/日，实际用了多少没人知道，
超限告警也永远不会触发。

本模块把**模型调用次数**做成可核对的日台账，并由 AI 中台定时推给授权服务：

- **计费口径（§290 起）**：每一次**成功的模型 HTTP 调用** = 1 次 AI 调用，
  计量点在 `llm_factory` 的共享 httpx 客户端响应钩子——问答、影像、结构化、审方、
  摘要压缩、意图识别等所有走模型的路径都被覆盖；
  失败/被拒（非 2xx）不计；本地嵌入模型（BGE）不产生 HTTP、不占 AI 配额。
  前置闲聊（prefilter）不调用模型，因此天然不计。
- **耐久**：进程内按日累计 + 每次落盘写一行 ``agent_usage_daily``
  （id = ``<UTC 日期>#<进程实例>``，PG 真源 + JSON 镜像）。
  **重启不丢**：新进程用新的实例 id，旧行仍在，合计=所有行之和；
  因此同一天重启两次也不会重复计数或回退。
- **上报**：POST ``{PLATFORM_LICENSE_BASE_URL}/internal/license/usage/ai``，
  带 ``X-Internal-Token``（与 Java 侧同一个 Secret）。授权服务只接受当日/前一日，
  重复上报幂等；AI 中台只在"计数变化或心跳超时"时推送，避免每分钟写库。

边界
----
- 未配置 ``PLATFORM_LICENSE_BASE_URL`` / ``INTERNAL_API_TOKEN`` → 不同步，行为与历史一致；
- 授权服务不可达**不阻断业务**：计数留在本地台账，下一轮继续推送；
- 多 worker 时各进程各自累计、合计上报；``workerCount`` 只用于排障提示，
  进程重启型计数不能当作严格计费凭证（以授权服务侧日快照为准）；
- 台账只保留最近 ``MAX_KEEP_DAYS`` 天的进程行（幂等清理，不影响授权侧已落库数据）。
"""
from __future__ import annotations

import json
import os
import threading
import time
import uuid
from datetime import date, datetime, timedelta, timezone
from typing import Optional

import httpx

from backend.config import settings
from backend.core import ai_metering, pg_store
from backend.core.logger import get_logger

logger = get_logger(__name__)

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEDGER_FILE = os.path.join(BASE_DIR, "data", "ai_usage_daily.json")
DOMAIN = "agent_usage_daily"
MAX_KEEP_DAYS = 30
# 心跳：计数没变化时也用一次上报把 captured_at 刷新，让授权侧"用量陈旧"告警有意义
HEARTBEAT_SECONDS = 900.0
_REPORT_PATH = "/internal/license/usage/ai"

# 进程实例 id：同一天重启会生成新 id，旧行保留，合计不丢也不重复
WORKER_ID = uuid.uuid4().hex[:12]

_lock = threading.Lock()
_persisted: dict[str, int] = {}              # 日期 → 已落盘值（相同则不重复写库）
_last_push: dict = {"at": 0.0, "ok": False, "date": "", "calls": 0, "error": ""}
_push_failures = 0
_last_prune = ""                              # 最近一次清理日期（一天只清一次，避免每分钟全表读）
_started = False
_stop = threading.Event()
_thread: Optional[threading.Thread] = None


# --------------------------------------------------------------------------- 配置

def is_configured() -> bool:
    """是否具备上报条件（授权服务地址与内部令牌都配了）。"""
    return bool((getattr(settings, "platform_license_base_url", "") or "").strip()
                and (settings.internal_api_token or "").strip())


def _interval() -> float:
    try:
        return max(15.0, float(getattr(settings, "ai_usage_flush_seconds", 60) or 60))
    except (TypeError, ValueError):
        return 60.0


# --------------------------------------------------------------------------- 计数

def record_model_call() -> None:
    """记录一次**成功**的模型 HTTP 调用（由 ``llm_factory`` 响应钩子调用）。

    为什么放在 provider 调用层而不是问答层：问答只是模型调用的一种消费方，
    影像/结构化/审方/摘要压缩同样消耗模型额度；按"答案次数"计费会低估，
    按"HTTP 调用次数"计费与供应商账单口径一致（重试/降级各算一次，
    因为它们各自产生了一次真实的模型请求）。

    计数本体在 ``backend.core.ai_metering``（core 不得依赖 agents），
    本函数是编排层对外入口，便于测试固定业务日。
    """
    ai_metering.record(_today())


def _today() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def _today_offset(day: str, offset: int) -> str:
    return (date.fromisoformat(day) + timedelta(days=offset)).isoformat()


# --------------------------------------------------------------------------- 台账

def _load_json() -> list:
    if not os.path.isfile(LEDGER_FILE):
        return []
    try:
        with open(LEDGER_FILE, encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, list) else []
    except Exception:  # noqa: BLE001 —— 台账损坏按空处理，后续写入会重建
        return []


def _load_rows() -> list[dict]:
    try:
        rows = pg_store.load_json_list(DOMAIN, LEDGER_FILE, _load_json)
    except Exception:  # noqa: BLE001 —— PG 异常时读 JSON 镜像
        rows = []
    return [r for r in (rows or []) if isinstance(r, dict)]


def totals(day: str) -> int:
    """某日全部进程行之和（重启/多 worker 均不重复）。

    ``max(台账, 本进程累计)``：PG 写失败的窗口里台账可能暂时读不到本进程增量，
    但上报值绝不能比本进程已经计过的数更小（否则授权侧会看到"用量下降"）；
    重启后本进程累计归零，不影响合计。
    """
    total = 0
    for row in _load_rows():
        if str(row.get("date") or "") != day:
            continue
        try:
            total += max(0, int(row.get("aiCalls") or 0))
        except (TypeError, ValueError):
            continue
    return max(total, ai_metering.count(day))


def _persist_own() -> None:
    """把本进程累计按日写入台账（幂等：同 id 同值重写）。"""
    today = _today()
    snapshot = ai_metering.counts()
    for day, calls in snapshot.items():
        if calls <= 0 or _persisted.get(day) == calls:
            continue
        row = {
            "id": f"{day}#{WORKER_ID}",
            "date": day,
            "aiCalls": calls,
            "workerId": WORKER_ID,
            "status": "ACTIVE",
            "updatedAt": datetime.now(timezone.utc).isoformat(),
        }
        try:
            pg_store.upsert_json_item(DOMAIN, LEDGER_FILE, row)
        except Exception as exc:  # noqa: BLE001 —— 落盘失败下轮重试，不影响问答
            logger.warning("ai_usage.persist_failed", day=day, error=str(exc)[:120])
            continue
        _persisted[day] = calls
    _prune_ledger(today)


def _prune_ledger(today: str) -> None:
    """清理超过保留期的旧进程行（只删行，不删授权侧历史；一天只做一次）。"""
    global _last_prune
    if _last_prune == today:
        return
    _last_prune = today
    cutoff = _today_offset(today, -MAX_KEEP_DAYS)
    rows = _load_rows()
    stale = [r for r in rows if isinstance(r, dict) and str(r.get("date") or "") < cutoff]
    if not stale:
        return
    keep = [r for r in rows if r not in stale]
    try:
        pg_store.save_json_list(DOMAIN, LEDGER_FILE, keep)
    except Exception as exc:  # noqa: BLE001
        logger.warning("ai_usage.prune_failed", error=str(exc)[:120])


def flush() -> dict:
    """落盘并返回当日合计（可单独调用，便于测试与运维诊断）。"""
    _persist_own()
    day = _today()
    return {"date": day, "aiCalls": totals(day)}


# --------------------------------------------------------------------------- 上报

def _should_push(calls: int) -> bool:
    if not _last_push.get("ok"):
        return True
    if _last_push.get("date") != _today() or int(_last_push.get("calls") or 0) != calls:
        return True
    return time.time() - float(_last_push.get("at") or 0) >= HEARTBEAT_SECONDS


def report_once(timeout_s: float = 5.0) -> bool:
    """执行一轮"落盘 + 上报"；返回是否推送成功。异常由调用方决定是否告警。"""
    global _push_failures
    if not is_configured():
        return False
    day = _today()
    calls = int(flush().get("aiCalls") or 0)
    if not _should_push(calls):
        return True

    base = (settings.platform_license_base_url or "").strip().rstrip("/")
    headers = {"X-Internal-Token": (settings.internal_api_token or "").strip()}
    payload = {
        "date": day,
        "aiCalls": calls,
        "source": "AI_CORE",
        "workerId": WORKER_ID,
        "workerCount": _worker_count(),
    }
    try:
        with _new_client(timeout_s) as client:
            resp = client.post(f"{base}{_REPORT_PATH}", json=payload, headers=headers)
            resp.raise_for_status()
            _unwrap(resp.json())
    except Exception as exc:  # noqa: BLE001 —— 上报失败保留台账，下一轮继续
        _push_failures += 1
        _last_push.update({"at": time.time(), "ok": False, "date": day,
                           "calls": calls, "error": f"{type(exc).__name__}: {str(exc)[:120]}"})
        raise
    _last_push.update({"at": time.time(), "ok": True, "date": day,
                       "calls": calls, "error": ""})
    logger.info("ai_usage.reported", date=day, ai_calls=calls, worker=WORKER_ID,
                worker_count=payload["workerCount"])
    return True


# --------------------------------------------------------------------------- 生命周期

def start() -> bool:
    """启动后台上报线程（幂等）。未配置返回 False，不产生副作用。"""
    global _started, _thread
    if not is_configured():
        return False
    with _lock:
        if _started:
            return True
        _started = True
        _stop.clear()
    _thread = threading.Thread(target=_loop, name="ai-usage-reporter", daemon=True)
    _thread.start()
    logger.info("ai_usage.reporter_started",
                base_url=settings.platform_license_base_url,
                interval_s=_interval(), worker=WORKER_ID)
    return True


def stop() -> None:
    """停止上报线程（下线时先 stop 再 flush，确保最后一批计数落盘）。"""
    _stop.set()


def _loop() -> None:
    warned = False
    interval = _interval()
    while not _stop.is_set():
        try:
            report_once()
            warned = False
        except Exception as exc:  # noqa: BLE001 —— 授权服务抖动不能影响 AI 能力
            if not warned:
                warned = True
                logger.warning("ai_usage.report_failed", error=str(exc)[:160],
                               detail="台账保留，下一轮重试；仅首次/恢复时记录，避免刷日志")
        _stop.wait(interval)


def _new_client(timeout_s: float) -> httpx.Client:
    """HTTP 客户端工厂（测试通过替换本函数注入 MockTransport）。"""
    return httpx.Client(timeout=timeout_s)


def _unwrap(body) -> dict | list:
    """兼容 Java 统一响应包装 {code,message,data}。"""
    if isinstance(body, dict) and "data" in body and isinstance(body.get("data"), (dict, list)):
        return body["data"]
    return body


def _worker_count() -> int:
    try:
        from backend.agents import consult_metrics

        return int(consult_metrics.worker_count())
    except Exception:  # noqa: BLE001
        return 1


# --------------------------------------------------------------------------- 观测

def snapshot() -> dict:
    """上报状态快照（健康检查/排障用）。"""
    return {
        "configured": is_configured(),
        "started": _started,
        "workerId": WORKER_ID,
        "today": _today(),
        "todayCalls": totals(_today()),
        "lastPush": dict(_last_push),
        "failures": _push_failures,
    }


def prometheus_text() -> str:
    today_calls = totals(_today())
    return "\n".join([
        "# HELP medassist_agent_model_calls_today 今日成功的模型调用次数（授权计费口径，UTC 日切）",
        "# TYPE medassist_agent_model_calls_today gauge",
        f"medassist_agent_model_calls_today {today_calls}",
        "# HELP medassist_agent_ai_usage_push_timestamp_seconds 最近一次用量上报时刻（Unix 秒）",
        "# TYPE medassist_agent_ai_usage_push_timestamp_seconds gauge",
        f"medassist_agent_ai_usage_push_timestamp_seconds {float(_last_push.get('at') or 0):.0f}",
        "# HELP medassist_agent_ai_usage_push_failures_total 用量上报失败次数",
        "# TYPE medassist_agent_ai_usage_push_failures_total counter",
        f"medassist_agent_ai_usage_push_failures_total {_push_failures}",
    ]) + "\n"


def _reset_for_tests() -> None:
    """测试专用：清空内存累计与上报状态（不停止线程）。"""
    global _push_failures, _last_prune
    ai_metering.reset_for_tests()
    _persisted.clear()
    _last_prune = ""
    _last_push.update({"at": 0.0, "ok": False, "date": "", "calls": 0, "error": ""})
    _push_failures = 0
