"""商用化阶段6 配置默认值锁（防回退）：全新部署必须零演示足迹。

默认值纪律：
- auth_seed_demo 必须 False：演示账号（doctor01 等）仅开发/演示环境显式
  AUTH_SEED_DEMO=true 时注入（backend/main.py 启动分支，seed_skipped 日志）；
- his_adapter 必须 "none"：生产默认不连任何 HIS 适配器（含 demo 内存假数据）。

注意：测试不受 .env.local 影响——显式 _env_file=None + 清同名环境变量后取纯默认值。
"""
from backend.config import Settings


def test_auth_seed_demo_default_off(monkeypatch):
    """AUTH_SEED_DEMO 默认必须为 False（阶段6 去demo：演示账号不随默认配置注入）。"""
    monkeypatch.delenv("AUTH_SEED_DEMO", raising=False)
    s = Settings(_env_file=None)
    assert s.auth_seed_demo is False


def test_production_env_aliases_are_unified():
    """评审 S6：production / prod / staging 必须同一口径判定。"""
    from backend.config import is_production_env
    assert is_production_env("production")
    assert is_production_env("prod")
    assert is_production_env("STAGING")
    assert not is_production_env("development")


def test_his_adapter_default_none(monkeypatch):
    """HIS_ADAPTER 默认必须为 "none"（生产默认未对接；demo 适配器仅供集成开发参考）。"""
    monkeypatch.delenv("HIS_ADAPTER", raising=False)
    s = Settings(_env_file=None)
    assert s.his_adapter == "none"


def test_auth_enabled_default_on(monkeypatch):
    """鉴权默认必须开启（商用底线：关闭鉴权须显式且生产拒启）。"""
    monkeypatch.delenv("AUTH_ENABLED", raising=False)
    s = Settings(_env_file=None)
    assert s.auth_enabled is True
