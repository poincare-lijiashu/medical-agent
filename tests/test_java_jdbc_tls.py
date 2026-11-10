"""Java 服务 JDBC 连接 TLS 静态锁（第 180 批，评测 04 P1-5 遗留）。

为什么需要：Python 侧（asyncpg）在 §180 已接 `PG_SSLMODE`，但 9 个 Java 服务的
`spring.datasource.url` 仍是裸 `jdbc:postgresql://...`——库间抓包能直接读到
`audit_log` / `review_queue` / 患者主索引。这条防线只存在于 YAML 与 Helm 模板里，
单测全绿也发现不了"新服务复制粘贴旧 URL"或"某环境忘了开 verify-full"，所以在这里锁死：

1. 每个有数据源的 Java 服务的 JDBC URL 都必须带 `sslmode=${PG_SSLMODE:prefer}`
   与 `sslrootcert=${PG_SSLROOTCERT:}`（可外部覆盖，本地默认 prefer 不阻断联调）；
2. 任何 `platform/**/application.yml` 都不得再出现不带 sslmode 的 JDBC URL；
3. Helm 的 `medassist.commonEnv` 必须把 `PG_SSLMODE`（默认 prefer）与可选的
   `PG_SSLROOTCERT` 注入到所有 Java 服务；
4. prod/staging 的 values 保持 `verify-full`，dev 保持 `prefer`；
5. AI 中台的 ConfigMap 也要同步 `PG_SSLROOTCERT`（Python 侧同一套变量名）。
"""
from __future__ import annotations

import os
import re

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLATFORM = os.path.join(REPO, "platform")
HELM = os.path.join(REPO, "deploy", "helm", "medassist-platform")
HELPERS = os.path.join(HELM, "templates", "_helpers.tpl")
CONFIGMAP_AI = os.path.join(HELM, "templates", "configmap-ai-core.yaml")

# 有 spring.datasource 的 Java 服务（gateway 无库，故不在列）
DB_SERVICES = ("auth", "clinical", "empi", "emr", "insurance", "kb", "material", "pharmacy", "tech")

TLS_PARAMS = ("sslmode=${PG_SSLMODE:prefer}", "sslrootcert=${PG_SSLROOTCERT:}")


def _read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def _app_yml(service: str) -> str:
    return os.path.join(PLATFORM, service, "src", "main", "resources", "application.yml")


def test_every_service_datasource_requests_tls():
    for service in DB_SERVICES:
        text = _read(_app_yml(service))
        m = re.search(r'url:\s*"(jdbc:postgresql://[^"]+)"', text)
        assert m, f"{service}: datasource.url 必须用引号包裹（参数里含 &）"
        url = m.group(1)
        for param in TLS_PARAMS:
            assert param in url, f"{service}: JDBC URL 缺少 {param}"


def test_no_service_left_with_plain_jdbc_url():
    """新增服务复制旧模板时立即失败：允许的唯一形态是带 sslmode 的 URL。"""
    offenders: list[str] = []
    for dirpath, _dirs, files in os.walk(PLATFORM):
        if os.sep + "target" + os.sep in dirpath + os.sep:
            continue
        if "application.yml" not in files:
            continue
        path = os.path.join(dirpath, "application.yml")
        for idx, line in enumerate(_read(path).splitlines(), 1):
            if "jdbc:postgresql" in line and "sslmode=" not in line:
                offenders.append(f"{os.path.relpath(path, REPO)}:{idx}")
    assert not offenders, f"以下 JDBC URL 未开启 TLS 参数：{offenders}"


def test_helm_injects_tls_env_into_common_java_env():
    tpl = _read(HELPERS)
    start = tpl.index('define "medassist.commonEnv"')
    common = tpl[start:]
    assert "- name: PG_SSLMODE" in common, "Java 服务公共环境必须注入 PG_SSLMODE"
    assert 'default "prefer"' in common, "未配置时必须回落 prefer（与 pgjdbc 默认一致）"
    assert "- name: PG_SSLROOTCERT" in common, "verify-full 需要 CA 路径，必须支持注入"
    assert "external.postgres.sslrootcert" in common


def test_environment_values_keep_expected_sslmode():
    expectations = {
        "values-prod.yaml": "verify-full",
        "values-staging.yaml": "verify-full",
        "values-dev.yaml": "prefer",
    }
    for name, mode in expectations.items():
        text = _read(os.path.join(HELM, name))
        assert re.search(rf"(?m)^\s+sslmode:\s*{re.escape(mode)}\s*$", text), \
            f"{name} 的 external.postgres.sslmode 必须为 {mode}"
    base = _read(os.path.join(HELM, "values.yaml"))
    assert re.search(r"(?m)^\s+sslmode:\s*\"\"\s*$", base), "base values 的 sslmode 默认应为空串"
    assert re.search(r"(?m)^\s+sslrootcert:\s*\"\"\s*$", base), "base values 必须声明 sslrootcert 键"


def test_ai_core_configmap_exposes_ssl_ca_path():
    cfg = _read(CONFIGMAP_AI)
    assert "PG_SSLMODE:" in cfg
    assert "PG_SSLROOTCERT" in cfg, "Python 侧 verify-full 同样需要 CA 路径"
