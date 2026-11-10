"""第 173 批：`/internal/**` 对外暴露策略回归锁（评测「HIS 深度集成」第 ② 步）。

背景：HIS → AI 的反向调用（`/internal/ai/*`）是**服务间通道**，任何情况下都不能
从公网/IP 入口直达；而 HIS 工作台需要一条经网关鉴权的外部通道。三者必须同时成立：

1. **网关 denylist** 覆盖 `/internal/**` 与 `/api/v2/*/internal/**`——鉴权开关关闭时
   也照拒（`AuthGlobalFilter` 里 deny 检查在 `enabled` 之前），并防止未来新增
   catch-all 路由后内部接口裸奔；
2. **Ingress** 把 `/internal` 显式路由到网关（fail-closed 404），而不是被 `/` 兜底
   路由转给 ai-core——内部请求永远不落到任何业务进程；
3. **HIS 工作台**走独立外部通道 `/api/v2/clinical/ai-assist/**`（JWT + 独立限流桶），
   不复用 `/internal` 前缀。

删掉任一层都不会有明显报错，所以用配置级测试锁住（与 GatewayRouteConfigTest 互补：
那边锁 Java 配置结构，这里锁"三环境 values + 网关 yml 的实际取值"）。
"""
from __future__ import annotations

import os

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHART = os.path.join(REPO, "deploy", "helm", "medassist-platform")
GATEWAY_YML = os.path.join(REPO, "platform", "gateway", "src", "main",
                           "resources", "application.yml")

VALUES_FILES = ["values.yaml", "values-dev.yaml", "values-staging.yaml", "values-prod.yaml"]


def _load(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def _ingress_paths(doc: dict) -> list[dict]:
    paths = []
    for host in (doc.get("ingress") or {}).get("hosts") or []:
        paths.extend(host.get("paths") or [])
    return paths


def test_all_environments_route_internal_to_gateway():
    """Ingress 的 /internal 必须指向网关；/api/v1、/api/v2 也必须是网关。"""
    for fn in VALUES_FILES:
        doc = _load(os.path.join(CHART, fn))
        paths = {p["path"]: p["service"] for p in _ingress_paths(doc)}
        assert paths.get("/internal") == "gateway", f"{fn}：/internal 未指向网关（会落到 / 的 ai-core）"
        assert paths.get("/api/v1") == "gateway", fn
        assert paths.get("/api/v2") == "gateway", fn
        # "/" 只能兜底前端静态资源与 SSE，不能承接内部前缀
        assert paths.get("/") == "aiCore", fn


def test_ingress_paths_are_longest_prefix_first():
    """显式路径必须排在 "/" 兜底之前（阅读顺序即 nginx 生成顺序，便于评审）。"""
    for fn in VALUES_FILES:
        paths = [p["path"] for p in _ingress_paths(_load(os.path.join(CHART, fn)))]
        assert paths.index("/internal") < paths.index("/"), f"{fn}：/internal 必须在 / 之前"
        assert paths.index("/api/v1") < paths.index("/"), fn
        assert paths.index("/api/v2") < paths.index("/"), fn


def test_gateway_denylist_covers_both_internal_forms():
    """网关 denylist：根级 /internal/** 与 /api/v2/*/internal/** 都要在（缺一即裸奔）。"""
    doc = _load(GATEWAY_YML)
    deny = doc["medassist"]["gateway"]["auth"]["deny-paths"]
    assert "/internal/**" in deny
    assert "/api/v2/*/internal/**" in deny


def test_his_assist_has_own_rate_limit_before_clinical_catch_all():
    """HIS 工作台外部通道：独立限流桶，且必须排在 /api/v2/clinical 兜底之前。"""
    doc = _load(GATEWAY_YML)
    rules = doc["medassist"]["gateway"]["sentinel"]["flow-rules"]
    paths = [r["path"] for r in rules]
    assert "/api/v2/clinical/ai-assist" in paths, "缺少 HIS → AI 外部通道限流规则"
    assert paths.index("/api/v2/clinical/ai-assist") < paths.index("/api/v2/clinical")
