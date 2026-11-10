"""审计哈希链（第 184 批）：追加式审计的防篡改与可验证性。

医疗合规要求审计记录「不可篡改、可证明完整」。此前只保证"没有删除/更新接口"，
但文件被手工改一行、删一行，系统完全无感。本文件锁定：

1. 每条记录带 `seq` / `prev` / `hash`，hash 覆盖内容 + 前序 hash；
2. 改内容 → `hash_mismatch`；删中间记录 → `prev_mismatch`，并给出行号/seq；
3. 进程重启后接着已有链写（seq 递增、prev 接得上），不重新起链；
4. 轮转跨文件仍然是一条链（prev 首尾相接）；
5. 升级前的无 hash 历史记录可容忍（计入 legacy），从该处重新起链；
6. 校验是只读操作，不会修改任何审计文件。
"""
from __future__ import annotations

import json

from backend.core import medical_audit as ma
from backend.core.medical_audit import AuditLog


def _log(tmp_path) -> AuditLog:
    return AuditLog(str(tmp_path / "audit.jsonl"))


def _records(tmp_path) -> list[dict]:
    path = tmp_path / "audit.jsonl"
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def _write_n(log: AuditLog, n: int) -> None:
    for i in range(n):
        log.write(event_type="qc", action="submit", actor="doctor01", payload={"i": i})


def test_chain_fields_and_verify_ok(tmp_path):
    log = _log(tmp_path)
    _write_n(log, 3)
    recs = _records(tmp_path)
    assert [r["seq"] for r in recs] == [1, 2, 3]
    assert recs[0]["prev"] == ""
    assert recs[1]["prev"] == recs[0]["hash"]
    assert recs[2]["prev"] == recs[1]["hash"]
    assert all(len(r["hash"]) == 64 for r in recs)

    result = log.verify_chain()
    assert result["ok"] is True and result["checked"] == 3 and result["legacy"] == 0
    assert result["firstBroken"] is None


def test_tampered_payload_detected(tmp_path):
    log = _log(tmp_path)
    _write_n(log, 3)
    path = tmp_path / "audit.jsonl"
    lines = path.read_text(encoding="utf-8").splitlines()
    rec = json.loads(lines[1])
    rec["payload"]["i"] = 999  # 篡改内容但保留旧 hash
    lines[1] = json.dumps(rec, ensure_ascii=False)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    result = log.verify_chain()
    assert result["ok"] is False
    assert result["firstBroken"]["reason"] == "hash_mismatch"
    assert result["firstBroken"]["seq"] == 2
    assert result["firstBroken"]["line"] == 2


def test_deleted_record_breaks_chain(tmp_path):
    log = _log(tmp_path)
    _write_n(log, 3)
    path = tmp_path / "audit.jsonl"
    lines = path.read_text(encoding="utf-8").splitlines()
    path.write_text(lines[0] + "\n" + lines[2] + "\n", encoding="utf-8")

    result = log.verify_chain()
    assert result["ok"] is False
    assert result["firstBroken"]["reason"] == "prev_mismatch"
    assert result["firstBroken"]["seq"] == 3


def test_restart_continues_existing_chain(tmp_path):
    path = tmp_path / "audit.jsonl"
    AuditLog(str(path)).write(event_type="qc", actor="doctor01", payload={})
    reopened = AuditLog(str(path))  # 模拟进程重启
    reopened.write(event_type="qc", actor="doctor01", payload={})

    recs = _records(tmp_path)
    assert recs[1]["seq"] == 2, "重启后 seq 必须接着已有链递增"
    assert recs[1]["prev"] == recs[0]["hash"]
    assert reopened.verify_chain()["ok"] is True


def test_chain_survives_rotation(tmp_path, monkeypatch):
    monkeypatch.setattr(ma, "_ROTATE_BYTES", 1)  # 每条写入后都触发轮转
    log = _log(tmp_path)
    _write_n(log, 3)

    files = log._chain_files()
    assert len(files) >= 2, "应产生至少一个轮转文件 + 主文件"
    result = log.verify_chain()
    assert result["ok"] is True and result["checked"] == 3 and result["files"] >= 2


def test_legacy_records_are_tolerated(tmp_path):
    path = tmp_path / "audit.jsonl"
    legacy = {"ts": "2025-01-01T00:00:00+00:00", "event_type": "old",
              "actor": "doctor01", "payload": {"legacy": True}}
    path.write_text(json.dumps(legacy, ensure_ascii=False) + "\n", encoding="utf-8")

    log = AuditLog(str(path))  # 升级后首次启动：历史记录无 hash
    log.write(event_type="qc", actor="doctor01", payload={})
    result = log.verify_chain()
    assert result["ok"] is True
    assert result["legacy"] == 1 and result["checked"] == 1, "历史段计入 legacy，新段重新起链"

    recs = _records(tmp_path)
    assert recs[1]["seq"] == 1 and recs[1]["prev"] == ""


def test_verify_is_read_only(tmp_path):
    log = _log(tmp_path)
    _write_n(log, 2)
    path = tmp_path / "audit.jsonl"
    before = path.read_bytes()
    log.verify_chain()
    assert path.read_bytes() == before, "校验不得写入/改写审计文件"


def test_max_records_truncates(tmp_path):
    log = _log(tmp_path)
    _write_n(log, 5)
    result = log.verify_chain(max_records=2)
    assert result["ok"] is True and result["checked"] == 2 and result["truncated"] is True
