"""第 118 批 + 评测 06 P1-2：审计归档 CronJob（Helm）静态交付锁。

锁定：
- 模板受 `auditArchive.enabled` 开关控制，默认关闭（归档会删热表数据）；
- 定时、保留期、输出目录、PVC/emptyDir 两个分支、env 注入都在；
- `concurrencyPolicy: Forbid`（避免两次归档并发互抢行）；
- 剥掉 Helm 指令后仍是合法 YAML，且 cron 的 args 真的带 `--apply`（不是 dry-run）；
- 第 157 批（评测 06 P1-2）：显式 timeZone、Pod/容器安全上下文、`--before-days`
  （不依赖 GNU date）、未配 PVC 时渲染失败（emptyDir 会丢归档）。
"""
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
CHART = ROOT / "deploy" / "helm" / "medassist-platform"
TPL = CHART / "templates" / "audit-archive-cronjob.yaml"
VALUES = CHART / "values.yaml"


def _strip_helm(text: str) -> str:
    """去掉 Helm 注释块与指令行（含 `{{` 的行），剩余部分应为合法 YAML。"""
    out, in_comment = [], False
    for ln in text.splitlines():
        s = ln.strip()
        if s.startswith("{{- /*") or s.startswith("{{/*"):
            in_comment = True
            continue
        if in_comment:
            if "*/}}" in ln:
                in_comment = False
            continue
        if "{{" in ln:
            continue
        out.append(ln)
    return "\n".join(out)


def test_cronjob_template_markers():
    text = TPL.read_text(encoding="utf-8")
    for marker in (
        "if .Values.auditArchive.enabled",
        "apiVersion: batch/v1",
        "kind: CronJob",
        ".Values.auditArchive.schedule | quote",
        "timeZone: {{ .Values.auditArchive.timeZone",
        "startingDeadlineSeconds: 1800",
        "concurrencyPolicy: Forbid",
        "successfulJobsHistoryLimit: 3",
        "--before-days {{ .Values.auditArchive.beforeDays }}",
        "--apply --out",
        ".Values.auditArchive.outDir",
        "if .Values.auditArchive.pvc",
        "persistentVolumeClaim:",
        "emptyDir: {}",
        "with .Values.auditArchive.env",
        "toYaml . | nindent 16",
        'image: "{{ .Values.auditArchive.image.repository }}'
        ':{{ .Values.auditArchive.image.tag | default .Chart.AppVersion }}"',
    ):
        assert marker in text, f"缺少 CronJob 标记：{marker}"
    # 评测 06 P1-2：容器镜像可能只有 busybox date，`date -d` 会让归档 Job 直接失败
    # （注释里会提到这件事，这里只看真正执行的命令行）
    assert '--before "$(date' not in text


def test_cronjob_is_hardened_and_timezone_explicit():
    """评测 06 P1-2：时区显式 + 非 root/只读根文件系统/无提权。"""
    text = TPL.read_text(encoding="utf-8")
    for marker in (
        "runAsNonRoot: true",
        "runAsUser: 10001",
        "seccompProfile:",
        "allowPrivilegeEscalation: false",
        "readOnlyRootFilesystem: true",
        'drop: ["ALL"]',
        "activeDeadlineSeconds: 3600",
        "PYTHONDONTWRITEBYTECODE",
        "mountPath: /tmp",
    ):
        assert marker in text, f"缺少加固标记：{marker}"
    values = VALUES.read_text(encoding="utf-8")
    seg = values.split("auditArchive:")[1]
    assert "timeZone: Asia/Shanghai" in seg
    assert "allowEmptyDir: false" in seg


def test_cronjob_refuses_ephemeral_archive_dir():
    """未配 PVC 且未显式允许 emptyDir 时必须渲染失败（否则"删了热表却没留冷存"）。"""
    text = TPL.read_text(encoding="utf-8")
    assert "else if .Values.auditArchive.allowEmptyDir" in text
    assert "{{- fail" in text
    assert "allowEmptyDir=true" in text


def test_values_default_off_and_flags():
    text = VALUES.read_text(encoding="utf-8")
    seg = text.split("auditArchive:")[1]
    assert "enabled: false" in seg
    for key in ("schedule:", "beforeDays: 365", "outDir:", "pvc:", "env: []"):
        assert key in seg, f"values 缺少 {key}"


def test_cronjob_parses_as_yaml():
    yaml = pytest.importorskip("yaml")
    doc = yaml.safe_load(_strip_helm(TPL.read_text(encoding="utf-8")))
    assert doc["kind"] == "CronJob"
    assert doc["apiVersion"] == "batch/v1"
    spec = doc["spec"]
    assert spec["concurrencyPolicy"] == "Forbid"
    c = spec["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]
    assert c["name"] == "archive"
    assert c["workingDir"] == "/app"
    assert "archive_audit_log.py" in c["args"][0]
    # 剥离 Helm 指令会把插值行（--before/--out）整行去掉，故 --apply 在模板原文断言
    assert "--apply" in TPL.read_text(encoding="utf-8")
