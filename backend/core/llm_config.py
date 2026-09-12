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

import ipaddress
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


def _invalidate_llm_factory_cache() -> None:
    """任务3 评测行动项：provider 增删/激活/停用后立即失效 LLMFactory 客户端缓存。

    此前失效钩子只在 admin 路由层（medical_router._reset_llm_cache）——绕过路由
    直接调用本模块更新函数的路径（脚本/内部调用）不会失效，get_llm 仍复用旧
    api_key 的实例，key 热更新对已构造客户端不生效。函数内延迟导入避免与
    llm_factory 的模块级循环导入（llm_factory 顶部 import 本模块的 active_provider）。"""
    from backend.core.llm_factory import LLMFactory
    LLMFactory._instances.clear()  # 幂等：路由层 _reset_llm_cache 再次清空为 no-op


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
    _reject_private_host(base_url)


def _reject_private_host(base_url: str) -> None:
    """终评 F4 SSRF 收口：base_url 指向内网/回环地址时拒绝（保存/连通性测试同一防线）。

    覆盖 localhost、127.x、10.x、172.16-31.x、192.168.x、169.254.x（云元数据）、
    0.0.0.0、::1。development 本地网关经 settings.llm_allow_private_hosts 显式放行。
    仅拦新增/更新：既有存量 provider 不受影响（读取侧不校验）。"""
    from urllib.parse import urlparse

    from backend.config import settings
    if settings.llm_allow_private_hosts:
        return
    host = (urlparse(base_url or "").hostname or "").strip().strip("[]").lower()
    if host == "localhost":
        raise ValueError("LLM base_url 不允许指向内网/回环地址")
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return  # 普通域名（公网 MaaS 端点）放行——不做 DNS 解析拦截（职责在网关侧）
    if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_unspecified:
        raise ValueError("LLM base_url 不允许指向内网/回环地址")


# ---------- 任务2 额外参数逃生舱：解析 / 厂商自动预设 ----------

def parse_extra_params(raw: str | None) -> dict:
    """extra_params JSON 字符串 → dict（空/None/非法/非 object 一律返回 {}）。

    非法 JSON 在保存层（路由 422 / add_provider ValueError）已拦截；本函数是
    读取侧防御：历史数据或手改文件不至于让模型构造路径炸掉。"""
    s = (raw or "").strip()
    if not s:
        return {}
    try:
        parsed = json.loads(s)
    except ValueError:
        return {}
    return dict(parsed) if isinstance(parsed, dict) else {}


def suggest_extra_params(base_url: str, model_id: str) -> str:
    """按 base_url+model_id 匹配已知厂商，返回建议的 extra_params（JSON 字符串或空串）。

    仅作建议（前端预填可改），不强制落库。已知预设：
    - 智谱（open.bigmodel.cn 或 model 前缀 glm-）：GLM-5.3 强制深度思考（thinking.type
      仅接受 enabled/disabled，关闭即 400「该模型始终思考」）；报错文案里的 low/high/max
      指的是 reasoning_effort（思考程度）参数——依据官方迁移文档（docs.bigmodel.cn
      migrate-to-glm-new），正确组合为 thinking.type=enabled + reasoning_effort=high。
    - deepseek / moonshot / minimax / anthropic（非 thinking 配置）：无预设（空串）。
    """
    u = (base_url or "").lower()
    m = (model_id or "").strip().lower()
    if "open.bigmodel.cn" in u or m.startswith("glm-") or m.startswith("glm_") or m == "glm":
        return json.dumps({"thinking": {"type": "enabled"}, "reasoning_effort": "high"},
                          ensure_ascii=False)
    return ""


def add_provider(kind: str, api_format: str, base_url: str, model_id: str,
                 display_name: str, api_key: str, extra_params: str = "") -> str:
    """新增 provider，返回 id（p-xxxx）。校验失败抛 ValueError。

    extra_params：可选 JSON 字符串（厂商特殊参数逃生舱，如 thinking 配置），
    保存前校验——空串合法（=无额外参数），非空必须是 JSON object，否则 ValueError
    （路由层经 pydantic 校验先行拦截为 422）。"""
    _validate(kind, api_format, base_url, model_id)
    if (extra_params or "").strip():
        parsed = parse_extra_params(extra_params)
        if not parsed:  # 空 dict = 非法 JSON 或解析结果非 object
            raise ValueError("extra_params 需为合法的 JSON 对象字符串，如 {\"thinking\":{\"type\":\"high\"}}")
    pid = "p-" + uuid.uuid4().hex[:8]
    with _lock:
        cfg = _load_raw()
        cfg[kind]["providers"].append({
            "id": pid, "api_format": api_format,
            "base_url": (base_url or "").strip().rstrip("/"),
            "model_id": model_id.strip(),
            "display_name": (display_name or "").strip() or model_id.strip(),
            "api_key": api_key or "",
            "extra_params": (extra_params or "").strip(),
        })
        _save_raw(cfg)
        _invalidate_llm_factory_cache()  # 任务3：新增 provider 后旧客户端缓存立即失效
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
        _invalidate_llm_factory_cache()  # 任务3：删除（含激活项被删）后旧客户端缓存立即失效
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
                _invalidate_llm_factory_cache()  # 任务3：切换激活后旧模型实例立即失效
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
        _invalidate_llm_factory_cache()  # 任务3：停用（回落 .env 内置配置）后旧实例立即失效


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


def test_provider(api_format: str, base_url: str, model_id: str, api_key: str,
                  extra_params: str = "") -> dict:
    """连通性测试（不落盘）。返回 {"ok","latency_ms","detail"}；detail 不回显 key。

    extra_params（任务2）：用户填写的厂商特殊参数合并进请求 payload —— 真实验证
    用户参数可用性（而非仅验证基础连通）；显式思考参数（thinking/enable_thinking）
    存在时默认的 enable_thinking=False 让位（GLM 5.x「始终思考」模型对关闭参数 400）。"""
    t0 = time.perf_counter()
    url = (base_url or "").rstrip("/")
    user_extra = parse_extra_params(extra_params)
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
        if user_extra:
            if "thinking" in user_extra or "enable_thinking" in user_extra:
                payload.pop("enable_thinking", None)  # 用户显式管理思考模式 → 默认关闭参数让位
            payload.update(user_extra)  # 用户参数覆盖默认（max_tokens 等显式配置优先）
        # FIND-06：与 llm_factory 纪律一致，走 Client(trust_env=False) 绕过系统代理
        with httpx.Client(trust_env=False, timeout=_TEST_TIMEOUT) as client:
            r = client.post(url, headers=headers, json=payload)
        latency_ms = int((time.perf_counter() - t0) * 1000)
        if r.status_code >= 400:
            detail = f"HTTP {r.status_code}: {r.text[:120]}"
            # 4xx 且用户未填 extra_params 时附厂商建议（如 GLM 强制思考模型的 thinking 参数）
            if 400 <= r.status_code < 500 and not user_extra:
                sug = suggest_extra_params(base_url, model_id)
                if sug:
                    detail += f"；建议额外参数：{sug}（在「额外参数」框填入后重试）"
            return {"ok": False, "latency_ms": latency_ms,
                    "detail": detail}
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
