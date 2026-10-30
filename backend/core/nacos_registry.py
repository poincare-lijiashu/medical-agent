"""Nacos 服务注册（Python AI 中台）—— 计划书 D6.1。

定位：Java 微服务（gateway/auth/empi）经 Spring Cloud Alibaba 注册到 Nacos；Python AI
中台作为独立进程，需要以**同样的服务名发现语义**注册进去，网关与后续 Java 服务才能
按服务名发现它，而不是硬编码 IP。

设计约束（生产口径，非演示）：
1. **默认关闭**：`NACOS_ENABLED=false`（默认）时本模块零副作用，未部署 Nacos 的
   单机/科室级部署完全不受影响；
2. **可用性优先**：注册失败不阻断 FastAPI 启动（AI 能力比"被发现"更重要），后台按
   指数退避重试并告警，成功即停；
3. **优雅上下线**：进程收到 SIGTERM 时先注销实例再关连接池，避免滚动更新期间请求被
   打到已停止的实例；临时实例（ephemeral）由 SDK 维持心跳，进程被 kill -9 时由
   Nacos 心跳超时自动剔除；
4. **注册地址可观测**：容器/多网卡环境必须显式指定 `NACOS_IP`，否则自动探测可能把
   回环地址注册上去（跨主机不可达），启动日志会打印实际注册的 ip:port 供核对。

版本口径：Nacos 2.5.1 服务端实测必须使用 nacos-sdk-python 3.x——2.0.11 的连接与注册
调用"成功"但服务端查不到实例（静默失败，2026-10-30 实测三次均复现），3.2.0 正常。
"""
from __future__ import annotations

import asyncio
import socket
from contextlib import suppress
from typing import Any, Optional

from backend.config import settings
from backend.core.logger import get_logger

logger = get_logger(__name__)


def resolve_registration_ip() -> str:
    """确定注册到 Nacos 的 IP：显式配置优先，否则取本机主机名解析结果。"""
    configured = (settings.nacos_ip or "").strip()
    if configured:
        return configured
    try:
        return socket.gethostbyname(socket.gethostname())
    except OSError:
        return "127.0.0.1"


def build_metadata() -> dict[str, str]:
    """实例元数据：供网关/控制台按版本、环境、语言做灰度与排障。"""
    metadata: dict[str, str] = {}
    for pair in (settings.nacos_metadata or "").split(","):
        if "=" not in pair:
            continue
        key, value = pair.split("=", 1)
        if key.strip():
            metadata[key.strip()] = value.strip()
    metadata.setdefault("version", settings.app_version)
    metadata.setdefault("env", settings.app_env)
    metadata.setdefault("lang", "python")
    return metadata


class NacosRegistry:
    """Nacos 注册生命周期管理器（一个进程一个实例）。"""

    def __init__(self) -> None:
        self._service: Optional[Any] = None      # v2.nacos.NacosNamingService（延迟导入）
        self._task: Optional[asyncio.Task] = None
        self._registered = False

    @property
    def registered(self) -> bool:
        return self._registered

    async def start(self) -> None:
        """启动注册（不阻塞启动流程；失败在后台重试）。"""
        if not settings.nacos_enabled:
            logger.info("nacos.register.skip", reason="NACOS_ENABLED=false")
            return
        self._task = asyncio.create_task(self._register_with_retry(), name="nacos-register")
        logger.info("nacos.register.scheduled",
                    server=settings.nacos_server_addresses,
                    service=settings.nacos_service_name,
                    group=settings.nacos_group,
                    namespace=settings.nacos_namespace or "public")

    async def stop(self) -> None:
        """注销实例并释放 SDK 连接（幂等；未注册时直接返回）。"""
        if self._task is not None and not self._task.done():
            self._task.cancel()
            with suppress(asyncio.CancelledError):
                await self._task
        self._task = None

        service = self._service
        if service is None:
            return
        try:
            if self._registered:
                from v2.nacos import DeregisterInstanceParam

                await service.deregister_instance(DeregisterInstanceParam(
                    service_name=settings.nacos_service_name,
                    group_name=settings.nacos_group,
                    ip=resolve_registration_ip(),
                    port=settings.server_port,
                    cluster_name=settings.nacos_cluster_name or "DEFAULT",
                    ephemeral=True,
                ))
                logger.info("nacos.deregister.ok", service=settings.nacos_service_name,
                            ip=resolve_registration_ip(), port=settings.server_port)
        except Exception as exc:  # noqa: BLE001 —— 注销失败不阻断停机
            logger.warning("nacos.deregister.failed", error=str(exc)[:200])
        finally:
            self._registered = False
            self._service = None
            with suppress(Exception):
                await service.shutdown()

    # ---------------------------------------------------------------- 内部实现

    async def _register_with_retry(self) -> None:
        delay = 2.0
        attempts = max(1, settings.nacos_register_attempts)
        for attempt in range(1, attempts + 1):
            try:
                await self._register_once()
                return
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 —— 网络/鉴权/服务端错误统一重试
                logger.warning("nacos.register.failed", attempt=attempt,
                               error=str(exc)[:200], retry_in_s=int(delay))
                await asyncio.sleep(delay)
                delay = min(delay * 2, 60.0)
        logger.error("nacos.register.give_up", attempts=attempts,
                     detail="服务继续运行，但未注册到 Nacos（网关将无法按服务名发现本实例）")

    async def _register_once(self) -> None:
        # 延迟导入：未启用 Nacos 的环境无需安装/加载 SDK
        from v2.nacos import ClientConfig, NacosNamingService, RegisterInstanceParam

        config = ClientConfig(
            server_addresses=settings.nacos_server_addresses,
            namespace_id=settings.nacos_namespace or "",
            username=(settings.nacos_username or None),
            password=(settings.nacos_password or None),
            log_level=settings.nacos_log_level,
            app_name="medassist-ai-core",
        )
        service = await NacosNamingService.create_naming_service(config)
        ip = resolve_registration_ip()
        await service.register_instance(RegisterInstanceParam(
            service_name=settings.nacos_service_name,
            group_name=settings.nacos_group,
            ip=ip,
            port=settings.server_port,
            weight=settings.nacos_weight,
            enabled=True,
            healthy=True,
            ephemeral=True,
            cluster_name=settings.nacos_cluster_name or "DEFAULT",
            metadata=build_metadata(),
        ))
        self._service = service
        self._registered = True
        logger.info("nacos.register.ok",
                    service=settings.nacos_service_name,
                    group=settings.nacos_group,
                    ip=ip,
                    port=settings.server_port,
                    metadata=build_metadata())


# 进程级单例（lifespan 中 start/stop）
registry = NacosRegistry()
