"""任务6 部署可移植性：embedder device 选择与回退。

- 默认 auto：有 CUDA 用 GPU，否则 CPU（无 N 卡部署机零配置可跑）；
- 显式 cpu：强制 CPU（即使 CUDA 可用）；
- 显式 cuda：CUDA 不可用/驱动异常 → 回落 CPU 并告警（GPU 是加速项，不是硬依赖）。

torch 经 sys.modules 替身注入（不依赖本机是否装 torch/CUDA）。
"""
import logging
import sys
import types

import pytest

from backend.config import settings
from backend.core import embedder


class _FakeCuda:
    def __init__(self, ok: bool):
        self._ok = ok

    def is_available(self) -> bool:
        return self._ok


def _install_fake_torch(monkeypatch, cuda_ok: bool) -> None:
    fake = types.ModuleType("torch")
    fake.cuda = _FakeCuda(cuda_ok)
    monkeypatch.setitem(sys.modules, "torch", fake)


@pytest.fixture(autouse=True)
def _reset_device_cache(monkeypatch):
    """device() 结果进程内缓存（_dev），每个用例复位并恢复现场。"""
    monkeypatch.setattr(embedder, "_dev", None)
    yield
    monkeypatch.setattr(embedder, "_dev", None)


def test_device_auto_uses_cpu_without_cuda(monkeypatch):
    """mock 无 CUDA（torch.cuda.is_available()=False）→ auto 回落 CPU。"""
    _install_fake_torch(monkeypatch, cuda_ok=False)
    monkeypatch.setattr(settings, "embed_device", "auto")
    assert embedder.device() == "cpu"


def test_device_auto_uses_cuda_when_available(monkeypatch):
    _install_fake_torch(monkeypatch, cuda_ok=True)
    monkeypatch.setattr(settings, "embed_device", "auto")
    assert embedder.device() == "cuda"


def test_device_cpu_forced_even_with_cuda(monkeypatch):
    """显式 EMBED_DEVICE=cpu：即使 CUDA 可用也强制 CPU（显存紧张部署选择）。"""
    _install_fake_torch(monkeypatch, cuda_ok=True)
    monkeypatch.setattr(settings, "embed_device", "cpu")
    assert embedder.device() == "cpu"


def test_device_cuda_requested_falls_back_to_cpu_with_warning(monkeypatch, caplog):
    """显式 EMBED_DEVICE=cuda 但 CUDA 不可用 → 回落 CPU 并告警（绝不拒绝启动）。"""
    _install_fake_torch(monkeypatch, cuda_ok=False)
    monkeypatch.setattr(settings, "embed_device", "cuda")
    with caplog.at_level(logging.WARNING, logger="backend.core.embedder"):
        dev = embedder.device()
    assert dev == "cpu"
    assert any("device_cuda_fallback" in r.getMessage() for r in caplog.records)


def test_device_defaults_auto_when_torch_missing(monkeypatch):
    """无 torch（CPU-only 精简环境）→ auto/缺省一律 CPU，不抛异常。"""
    monkeypatch.setitem(sys.modules, "torch", None)  # import torch → ImportError
    monkeypatch.setattr(settings, "embed_device", "auto")
    assert embedder.device() == "cpu"


def test_embed_device_setting_default_is_auto():
    """settings.embed_device 默认 auto（部署清单口径：GPU 可选、CPU 回退）。"""
    assert (settings.embed_device or "auto") == "auto"
