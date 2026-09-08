"""F6 模型配置管理：对话（chat）与视觉（vision）双通道，OpenAI/Anthropic 双格式。

存储走 pg_store repo 层（B1 数据真源化）：PG 池可用 → PG 真源（llm_providers 表，
data 内嵌 _seq 还原 providers 顺序）；PG 不可用/异常/空表 → data/llm_providers.json
（现状行为完全等价，密钥严禁入库的 .gitignore 纪律不变）。结构：
{"chat": {"active": null, "providers": []}, "vision": {"active": null, "providers": []}}
provider：{"id","api_format","base_url","model_id","display_name","api_key"}
base_url 存原始值（不以 / 结尾）；openai 的 /chat/completions 语义由调用方处理
（httpx 直连时拼接，langchain ChatOpenAI 由 SDK 自动追加）。
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
import uuid

import httpx

from backend.core import pg_store

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LLM_CONFIG_FILE = os.path.join(BASE_DIR, "data", "llm_providers.json")
_lock = threading.Lock()

_KINDS = ("chat", "vision")
_API_FORMATS = ("openai", "anthropic")
_TEST_TIMEOUT = 15.0  # 连通性测试超时（秒）
_ANTHROPIC_VERSION = "2023-06-01"


def _default_config() -> dict:
    return {"chat": {"active": None, "providers": []},
            "vision": {"active": None, "providers": []}}


def _load_raw_json() -> dict:
    """llm_providers.json 现状读逻辑（损坏回落默认 + 通道补齐；JSON 兜底真源）。"""
    if not os.path.isfile(LLM_CONFIG_FILE):
        return _default_config()
    try:
        with open(LLM_CONFIG_FILE, "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:  # noqa: BLE001 —— 配置损坏时回落默认，不让坏文件拖垮服务
        return _default_config()
    for k in _KINDS:  # 容错补齐缺失的通道
        cfg.setdefault(k, {})
        cfg[k].setdefault("active", None)
        cfg[k].setdefault("providers", [])
    return cfg


def _load_raw() -> dict:
    # B1 数据真源化：PG 池可用 → PG 真源；不可用/异常/空表 → JSON（现状行为完全等价）
    return pg_store.load_llm_providers(LLM_CONFIG_FILE, _load_raw_json)


def _save_raw(cfg: dict) -> None:
    # B1：JSON 原子写兜底 + PG 全量同步（best-effort）；路径取本模块常量（测试 monkeypatch 生效）
    pg_store.save_llm_providers(cfg, LLM_CONFIG_FILE)


def load_config() -> dict:
    with _lock:
        return _load_raw()


def save_config(cfg: dict) -> None:
    with _lock:
        _save_raw(cfg)


def _validate(kind: str, api_format: str, base_url: str, model_id: str) -> None:
    if kind not in _KINDS:
        raise ValueError("kind 需为 chat 或 vision")
    if api_format not in _API_FORMATS:
        raise ValueError("api_format 需为 openai 或 anthropic")
    if not re.match(r"^https?://.+", base_url or ""):
        raise ValueError("base_url 需为 http(s):// 开头的完整 URL")
    if not (model_id or "").strip():
        raise ValueError("model_id 不能为空")


def add_provider(kind: str, api_format: str, base_url: str, model_id: str,
                 display_name: str, api_key: str) -> str:
    """新增 provider，返回 id（p-xxxx）。校验失败抛 ValueError。"""
    _validate(kind, api_format, base_url, model_id)
    pid = "p-" + uuid.uuid4().hex[:8]
    with _lock:
        cfg = _load_raw()
        cfg[kind]["providers"].append({
            "id": pid, "api_format": api_format,
            "base_url": (base_url or "").strip().rstrip("/"),
            "model_id": model_id.strip(),
            "display_name": (display_name or "").strip() or model_id.strip(),
            "api_key": api_key or "",
        })
        _save_raw(cfg)
    return pid


def remove_provider(kind: str, pid: str) -> bool:
    """删除 provider；若删除的是激活项则 active 置空。未找到返回 False。"""
    with _lock:
        cfg = _load_raw()
        if kind not in _KINDS:
            raise ValueError("kind 需为 chat 或 vision")
        before = len(cfg[kind]["providers"])
        cfg[kind]["providers"] = [p for p in cfg[kind]["providers"] if p.get("id") != pid]
        if len(cfg[kind]["providers"]) == before:
            return False
        if cfg[kind]["active"] == pid:
            cfg[kind]["active"] = None
        _save_raw(cfg)
        return True


def activate(kind: str, pid: str) -> dict:
    """激活 provider（active 指向其 id）。未找到抛 ValueError。"""
    with _lock:
        cfg = _load_raw()
        if kind not in _KINDS:
            raise ValueError("kind 需为 chat 或 vision")
        for p in cfg[kind]["providers"]:
            if p.get("id") == pid:
                cfg[kind]["active"] = pid
                _save_raw(cfg)
                return dict(p)
        raise ValueError("未找到该 provider")


def deactivate(kind: str) -> None:
    """停用激活 provider（active 置 None，回落 .env 内置配置）。kind 非法抛 ValueError。"""
    with _lock:
        cfg = _load_raw()
        if kind not in _KINDS:
            raise ValueError("kind 需为 chat 或 vision")
        cfg[kind]["active"] = None
        _save_raw(cfg)


def active_provider(kind: str) -> dict | None:
    """当前激活的 provider（含 api_key 原文，仅服务端内部使用）；无则 None。"""
    cfg = load_config()
    if kind not in _KINDS:
        return None
    aid = cfg[kind]["active"]
    for p in cfg[kind]["providers"]:
        if p.get("id") == aid:
            return p
    return None


def mask_key(key: str) -> str:
    """api_key 脱敏：前 6 后 4 中间 ***；过短（≤12 位，避免泄漏占比过高）仅返回 ***；空则空串。"""
    k = key or ""
    if not k:
        return ""
    if len(k) <= 12:
        return "***"
    return k[:6] + "***" + k[-4:]


def test_provider(api_format: str, base_url: str, model_id: str, api_key: str) -> dict:
    """连通性测试（不落盘）。返回 {"ok","latency_ms","detail"}；detail 不回显 key。"""
    t0 = time.perf_counter()
    url = (base_url or "").rstrip("/")
    headers: dict[str, str]
    payload: dict
    try:
        if api_format == "openai":
            url += "/chat/completions"
            headers = {"Authorization": "Bearer " + (api_key or ""),
                       "Content-Type": "application/json"}
            # FIND-05：与 llm_factory 真实请求对齐（extra_body.enable_thinking=False 落到 body 顶层），
            # 否则 Qwen3 等默认思考的网关会出现"测试通过、实跑 400"
            payload = {"model": model_id, "max_tokens": 8,
                       "messages": [{"role": "user", "content": "ping"}],
                       "enable_thinking": False}
        elif api_format == "anthropic":
            url += "/messages"
            headers = {"x-api-key": (api_key or ""), "anthropic-version": _ANTHROPIC_VERSION,
                       "Content-Type": "application/json"}
            payload = {"model": model_id, "max_tokens": 8,
                       "messages": [{"role": "user", "content": "ping"}]}
        else:
            return {"ok": False, "latency_ms": 0, "detail": "api_format 需为 openai 或 anthropic"}
        # FIND-06：与 llm_factory 纪律一致，走 Client(trust_env=False) 绕过系统代理
        with httpx.Client(trust_env=False, timeout=_TEST_TIMEOUT) as client:
            r = client.post(url, headers=headers, json=payload)
        latency_ms = int((time.perf_counter() - t0) * 1000)
        if r.status_code >= 400:
            return {"ok": False, "latency_ms": latency_ms,
                    "detail": f"HTTP {r.status_code}: {r.text[:120]}"}
        try:
            body = r.json()
        except ValueError:
            return {"ok": False, "latency_ms": latency_ms,
                    "detail": "响应非 JSON（网关/代理？）"}
        has_shape = (isinstance(body, dict)
                     and (("choices" in body) if api_format == "openai" else ("content" in body)))
        if not has_shape:  # 200 但结构不对：多半是网关/代理页或错误包装
            return {"ok": False, "latency_ms": latency_ms,
                    "detail": "响应结构异常：缺少预期回复字段（choices/content）"}
        return {"ok": True, "latency_ms": latency_ms, "detail": "连通正常"}
    except httpx.TimeoutException:
        return {"ok": False, "latency_ms": int((time.perf_counter() - t0) * 1000),
                "detail": f"连接超时（>{int(_TEST_TIMEOUT)}s）"}
    except Exception as e:  # noqa: BLE001 —— 网络类异常统一给可读摘要
        return {"ok": False, "latency_ms": int((time.perf_counter() - t0) * 1000),
                "detail": str(e)[:160]}
