"""§296 AI 治理公共层：存储、文本校验、时间与并发锁。

为什么要单独抽公共层：
- 版本治理、临床验证、漂移监测、误报闭环四块共享同一套"拒绝 PHI、CAS 版本号、
  JSON+PG 双写"的规则；分散实现必然出现某个入口忘了拒绝 PHI。
- 数据域与表名集中登记，新增域时只改一处，PG 建表白名单不会漏。

边界：
- 只在 AI 中台单实例内保证串行（``RLock``）；多实例部署时 JSON/PG 全量写存在
  互相覆盖风险，与既有技能库同一约束（生产建议单 worker，见 AGENT-RUNTIME）。
- 不记录问题、回答、患者信息；所有自由文本入口统一经 ``require_no_phi``。
"""
from __future__ import annotations

import json
import os
import re
import threading
import uuid
from datetime import datetime, timedelta, timezone

from backend.core import pg_store, phi

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.dirname(os.path.abspath(__file__)))))
DATA_DIR = os.path.join(BASE_DIR, "data")

# 域 → (PG 表名, JSON 文件名)
DOMAINS = {
    "versions": ("agent_model_versions", "ai_model_versions.json"),
    "studies": ("agent_validation_studies", "ai_validation_studies.json"),
    "snapshots": ("agent_drift_snapshots", "ai_drift_snapshots.json"),
    "baselines": ("agent_drift_baselines", "ai_drift_baselines.json"),
    "incidents": ("agent_incidents", "ai_incidents.json"),
}

lock = threading.RLock()

_COMPONENT = re.compile(r"^[a-z][a-z0-9_-]{1,31}$")
_VERSION = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$")
_CODE = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$")
_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


class GovernanceError(RuntimeError):
    code = "AI_GOVERNANCE_ERROR"


class GovernanceInvalid(GovernanceError):
    code = "AI_GOVERNANCE_INVALID"


class GovernanceNotFound(GovernanceError):
    code = "AI_GOVERNANCE_NOT_FOUND"


class GovernanceConflict(GovernanceError):
    code = "AI_GOVERNANCE_CONFLICT"


class GovernanceForbidden(GovernanceError):
    code = "AI_GOVERNANCE_FORBIDDEN"


def now() -> datetime:
    return datetime.now(timezone.utc).astimezone()


def now_iso() -> str:
    return now().isoformat(timespec="seconds")


def new_id(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:12]}"


def _json_loader(path: str):
    def _load() -> list:
        if not os.path.isfile(path):
            return []
        try:
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            return data if isinstance(data, list) else []
        except Exception:  # noqa: BLE001
            return []
    return _load


def load(domain: str) -> list:
    table, filename = DOMAINS[domain]
    path = os.path.join(DATA_DIR, filename)
    try:
        return pg_store.load_json_list(table, path, _json_loader(path))
    except Exception:  # noqa: BLE001 —— PG 不可用时回落 JSON，治理功能不应整体不可用
        return _json_loader(path)()


def save(domain: str, items: list) -> None:
    table, filename = DOMAINS[domain]
    path = os.path.join(DATA_DIR, filename)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    pg_store.save_json_list(table, path, items)


def require_text(value, field: str, minimum: int = 1, maximum: int = 1000) -> str:
    text = str(value or "").strip()
    if len(text) < minimum:
        raise GovernanceInvalid(f"{field}至少 {minimum} 个字符")
    if len(text) > maximum:
        raise GovernanceInvalid(f"{field}不能超过 {maximum} 个字符")
    return text


def optional_text(value, field: str, maximum: int = 1000) -> str | None:
    if value is None or str(value).strip() == "":
        return None
    return require_text(value, field, 1, maximum)


def require_no_phi(value, field: str) -> str:
    text = str(value or "").strip()
    if phi.has_phi(text) or phi.has_direct_identifier(text):
        raise GovernanceInvalid(f"{field}包含患者身份信息，请脱敏后再提交")
    return text


def require_choice(value, field: str, allowed) -> str:
    text = str(value or "").strip().upper()
    if text not in {str(a).upper() for a in allowed}:
        raise GovernanceInvalid(f"{field}只能是 " + " / ".join(sorted(allowed)))
    return text


def require_component(value) -> str:
    text = str(value or "").strip().lower()
    if not _COMPONENT.match(text):
        raise GovernanceInvalid("component 格式不合法（小写字母开头，允许数字/下划线/连字符）")
    return text


def require_version(value) -> str:
    text = str(value or "").strip()
    if not _VERSION.match(text):
        raise GovernanceInvalid("version 格式不合法（字母数字开头，允许 . _ -）")
    return text


def require_code(value, field: str) -> str:
    text = str(value or "").strip()
    if not _CODE.match(text):
        raise GovernanceInvalid(f"{field}格式不合法")
    return text


def require_sha256(value, field: str) -> str:
    text = str(value or "").strip().lower()
    if not _SHA256.match(text):
        raise GovernanceInvalid(f"{field}必须是 64 位十六进制 SHA-256")
    return text


def require_bool(value, field: str) -> bool:
    if not isinstance(value, bool):
        raise GovernanceInvalid(f"{field}必须是布尔值")
    return value


def require_ratio(value, field: str, default: float) -> float:
    if value is None:
        return default
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise GovernanceInvalid(f"{field}必须是 0–1 之间的小数") from None
    if number < 0 or number > 1:
        raise GovernanceInvalid(f"{field}必须在 0–1 之间")
    return round(number, 4)


def parse_time(value, field: str = "时间", default: datetime | None = None) -> datetime:
    if value is None or str(value).strip() == "":
        if default is not None:
            return default
        raise GovernanceInvalid(f"{field}不能为空")
    text = str(value).strip()
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        raise GovernanceInvalid(f"{field}必须是 ISO-8601 时间") from None
    if parsed.tzinfo is None:
        raise GovernanceInvalid(f"{field}必须带时区")
    return parsed


def iso(value: datetime | None) -> str | None:
    return value.isoformat(timespec="seconds") if value else None


def days_from_now(days: int) -> str:
    return (now() + timedelta(days=days)).isoformat(timespec="seconds")


def require_row_version(item: dict, expected, field: str = "rowVersion") -> None:
    if expected is None:
        raise GovernanceInvalid(f"{field}不能为空（用于并发校验）")
    try:
        expected_int = int(expected)
    except (TypeError, ValueError):
        raise GovernanceInvalid(f"{field}必须是整数") from None
    if int(item.get("rowVersion") or 0) != expected_int:
        raise GovernanceConflict("数据已被他人修改，请刷新后重试")


def bump(item: dict) -> None:
    item["rowVersion"] = int(item.get("rowVersion") or 1) + 1
    item["updatedAt"] = now_iso()
