#!/bin/sh
# MedAssist AI 中台容器入口（P0-5）
#
# 职责：按环境决定是否挂载 SkyWalking Python agent，然后再启动 uvicorn。
# - SW_AGENT_COLLECTOR_BACKEND_SERVICES 非空 且 sw-python 可用 → sw-python run 包装（自动埋点）
# - 否则 → 原样执行 CMD（与未接入 SkyWalking 时行为完全一致，零副作用）
#
# 说明：sw-python 通过 PYTHONPATH 注入 bootstrap，对 FastAPI 的 ASGI 调用链自动埋点，
# 无需改动应用代码（非侵入式接入，官方推荐方式）。
set -eu

if [ -n "${SW_AGENT_COLLECTOR_BACKEND_SERVICES:-}" ] && command -v sw-python >/dev/null 2>&1; then
    echo "[entrypoint] SkyWalking Python agent enabled -> ${SW_AGENT_COLLECTOR_BACKEND_SERVICES} (service=${SW_AGENT_NAME:-unknown})"
    exec sw-python run "$@"
fi

if [ -n "${SW_AGENT_COLLECTOR_BACKEND_SERVICES:-}" ]; then
    echo "[entrypoint] WARN: SW_AGENT_COLLECTOR_BACKEND_SERVICES 已设置但未找到 sw-python，按未接入 SkyWalking 启动"
fi

exec "$@"
