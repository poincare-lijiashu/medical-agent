"""商用化阶段6 配置默认值锁（防回退）：全新部署零演示足迹（零内置假数据/默认不注入种子账号）。

默认值纪律：
- auth_seed_demo 必须 False：种子账号（doctor01 等）仅本地试用显式
  AUTH_SEED_DEMO=true 时注入（backend/main.py 启动分支，seed_skipped 日志）；
- his_adapter 必须 "none"：生产默认不连任何 HIS 适配器（内置仅 NullAdapter 空转）。

注意：测试不受 .env.local 影响——显式 _env_file=None + 清同名环境变量后取纯默认值。
"""
from backend.config import Settings


def test_auth_seed_demo_default_off(monkeypatch):
    """AUTH_SEED_DEMO 默认必须为 False（阶段6 去demo：种子账号不随默认配置注入）。"""
    monkeypatch.delenv("AUTH_SEED_DEMO", raising=False)
    s = Settings(_env_file=None)
    assert s.auth_seed_demo is False


def test_his_adapter_default_none(monkeypatch):
    """HIS_ADAPTER 默认必须为 "none"（生产默认未对接；内置仅 NullAdapter 空转）。"""
    monkeypatch.delenv("HIS_ADAPTER", raising=False)
    s = Settings(_env_file=None)
    assert s.his_adapter == "none"


def test_auth_enabled_default_on(monkeypatch):
    """鉴权默认必须开启（商用底线：关闭鉴权须显式且生产拒启）。"""
    monkeypatch.delenv("AUTH_ENABLED", raising=False)
    s = Settings(_env_file=None)
    assert s.auth_enabled is True
