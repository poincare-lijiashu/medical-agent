"""第 158 批（评测 06 P1-6）：备份交付物静态门禁。

覆盖两处"看起来在备份、实际可能悄悄失效"的资产：
1. `deploy/backup/pg-backup-cronjob.yaml`——PG 每日逻辑备份：
   - 显式 `timeZone`（节点时区不可控，海外/混合集群会把 02:00 跑成业务高峰）；
   - Pod/容器安全上下文（非 root、只读根文件系统、无提权）、`/tmp` emptyDir；
   - `PGCONNECT_TIMEOUT`（连不上时快速失败，而不是在 2h 的 activeDeadline 里干等）；
   - 备份后立即 `pg_restore --list` 校验 + `sha256sum`，避免"半个文件也算成功"；
   - 过期清理只删本目录 `medagent-*.dump*`（`-maxdepth 1`）。
2. `scripts/backup_repo.ps1`——仓库跨盘备份：
   - **必须保持纯 ASCII**：PS 5.1 把无 BOM UTF-8 当 ANSI 解析，中文注释曾导致变量
     解析为 null 并走进删除分支（2026-10-30 事故，脚本头注释有完整复盘）；
   - 保留策略（`-Keep` / `-NoPrune`）只删 `medical_agent_YYYYMMDD.bundle`，永不动镜像、
     永不删刚写出的 bundle。
"""
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
PG_BACKUP = ROOT / "deploy" / "backup" / "pg-backup-cronjob.yaml"
BACKUP_PS1 = ROOT / "scripts" / "backup_repo.ps1"


def test_pg_backup_cronjob_is_production_ready():
    yaml = pytest.importorskip("yaml")
    docs = [d for d in yaml.safe_load_all(PG_BACKUP.read_text(encoding="utf-8")) if d]
    cj = next(d for d in docs if d.get("kind") == "CronJob")
    spec = cj["spec"]
    assert spec["schedule"] == "0 2 * * *"
    assert spec["timeZone"] == "Asia/Shanghai"
    assert spec["concurrencyPolicy"] == "Forbid"
    assert spec["startingDeadlineSeconds"] == 1800

    job = spec["jobTemplate"]["spec"]
    assert job["backoffLimit"] == 1
    assert job["activeDeadlineSeconds"] == 7200
    pod = job["template"]["spec"]
    assert pod["securityContext"]["runAsNonRoot"] is True
    assert pod["securityContext"]["seccompProfile"]["type"] == "RuntimeDefault"
    vol_names = {v["name"] for v in pod["volumes"]}
    assert {"backup", "tmp"} <= vol_names
    pvc = next(v for v in pod["volumes"] if v["name"] == "backup")
    assert pvc["persistentVolumeClaim"]["claimName"] == "medassist-pg-backup"

    c = pod["containers"][0]
    env = {e["name"]: e.get("value") for e in c["env"]}
    assert env["PGCONNECT_TIMEOUT"] == "10"
    assert env["RETENTION_DAYS"] == "14"
    assert "password" in str(c["env"])  # 口令来自 Secret，不硬编码
    assert c["securityContext"]["allowPrivilegeEscalation"] is False
    assert c["securityContext"]["readOnlyRootFilesystem"] is True
    assert c["securityContext"]["capabilities"]["drop"] == ["ALL"]
    mounts = {m["name"]: m["mountPath"] for m in c["volumeMounts"]}
    assert mounts == {"backup": "/backup", "tmp": "/tmp"}

    script = c["args"][0]
    assert "set -eu" in script
    assert "pg_dump -Fc" in script
    assert "pg_restore --list" in script  # 写完立即校验可读
    assert "sha256sum" in script
    assert "-maxdepth 1" in script  # 只清理备份目录本层


def test_backup_repo_ps1_stays_ascii():
    """事故复盘（脚本头 #3）：非 ASCII 会让 PS 5.1 解析错乱，必须逐字节守门。"""
    data = BACKUP_PS1.read_bytes()
    bad = [(i, b) for i, b in enumerate(data) if b > 127]
    assert not bad, f"backup_repo.ps1 含非 ASCII 字节（前 3 处：{bad[:3]}）"


def test_backup_repo_ps1_has_bounded_retention():
    text = BACKUP_PS1.read_text(encoding="ascii")
    assert "[int]$Keep = 14" in text
    assert "[switch]$NoPrune" in text
    # 只删精确命名的日期 bundle；镜像与临时目录永不参与清理
    assert r"^medical_agent_(\d{8})\.bundle$" in text
    assert "medical_agent_*.bundle" in text
    assert "Remove-Item -Recurse -Force $mirror" not in text
    assert "$keepNames += (Split-Path $bundle -Leaf)" in text  # 刚写出的 bundle 必保留
    assert 'BACKUP OK: mirror=$mirror bundle=$bundle HEAD=$head_src pruned=$pruned' in text
