"""第 79 批：知识库只读完整性检查（脏 tag / 零切片 / 重名 / 零向量）与 admin 端点。"""
import json

from fastapi.testclient import TestClient

from backend.core import kb_ingest as kb
from backend.core.auth import create_access_token
from backend.core.medical_audit import AuditLog
from backend.main import app

MANIFEST = {
    "good_tag": {"name": "正常.pdf", "chunks": 5, "ts": "t"},
    "old-tag.v1": {"name": "旧版.pdf", "chunks": 3, "ts": "t"},
    "empty1": {"name": "空文档.pdf", "chunks": 0, "ts": "t"},
    "dup_a": {"name": "指南.pdf", "chunks": 2, "ts": "t"},
    "dup_b": {"name": "指南.pdf", "chunks": 4, "ts": "t"},
    'bad"tag': {"name": "坏键.pdf", "chunks": 1, "ts": "t"},
}


class _Col:
    def __init__(self, zero=("empty1",)):
        self.zero = zero
        self.filters = []

    def query(self, coll, filter=None, output_fields=None):
        self.filters.append(filter)
        return [{"count(*)": 0 if any(f'"{z}"' in (filter or "") for z in self.zero) else 5}]


def _manifest(monkeypatch, tmp_path):
    p = tmp_path / "kb_manifest.json"
    p.write_text(json.dumps(MANIFEST, ensure_ascii=False), encoding="utf-8")
    monkeypatch.setattr(kb, "MANIFEST", str(p))


def test_integrity_report_flags_all_issue_kinds(monkeypatch, tmp_path):
    _manifest(monkeypatch, tmp_path)
    col = _Col()
    monkeypatch.setattr(kb, "_collection", lambda: col)
    rep = kb.integrity_report()
    s = rep["summary"]
    assert s["scanned"] == 6
    # old-tag.v1 与 bad"tag 都是未清洗旧键 → 2 个 legacy
    assert s["legacyTag"] == 2 and s["emptyChunks"] == 1
    assert s["duplicateName"] == 2 and s["zeroVectors"] == 1 and s["unqueryableTag"] == 1
    assert rep["vectorCheck"] is True
    by_tag = {i["doc_tag"]: i["issues"] for i in rep["issues"]}
    assert "legacy_tag" in by_tag["old-tag.v1"]
    assert "empty_chunks" in by_tag["empty1"] and "zero_vectors" in by_tag["empty1"]
    assert "duplicate_name" in by_tag["dup_a"] and "duplicate_name" in by_tag["dup_b"]
    assert "unqueryable_tag" in by_tag['bad"tag']
    assert 'bad"tag' not in "".join(col.filters), "坏键绝不能拼进 filter"


def test_integrity_report_can_skip_vectors(monkeypatch, tmp_path):
    _manifest(monkeypatch, tmp_path)
    called = []
    monkeypatch.setattr(kb, "_collection", lambda: called.append(1) or _Col())
    rep = kb.integrity_report(check_vectors=False)
    assert rep["vectorCheck"] is False
    assert rep["summary"]["zeroVectors"] == 0 and not called
    # 非向量问题照常发现
    assert rep["summary"]["legacyTag"] == 2 and rep["summary"]["emptyChunks"] == 1


def test_integrity_report_survives_milvus_down(monkeypatch, tmp_path):
    _manifest(monkeypatch, tmp_path)

    def boom():
        raise RuntimeError("milvus down")

    monkeypatch.setattr(kb, "_collection", boom)
    rep = kb.integrity_report()
    assert rep["vectorCheck"] is False
    assert rep["summary"]["legacyTag"] == 2 and rep["summary"]["duplicateName"] == 2


def _client(monkeypatch, tmp_path):
    log = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.shared.get_audit_logger", lambda: log)
    monkeypatch.setattr(kb, "integrity_report", lambda check_vectors=True: {
        "vectorCheck": False, "vectorCheckedLimit": 50,
        "summary": {"scanned": 2, "legacyTag": 0, "emptyChunks": 0,
                    "duplicateName": 0, "zeroVectors": 0, "unqueryableTag": 0},
        "issues": [],
    })
    monkeypatch.setattr("backend.api.deps.get_user", lambda u: {"role": "", "dept": ""})
    return TestClient(app), log


def _h(role):
    return {"Authorization": "Bearer " + create_access_token("admin01", role)}


def test_integrity_endpoint_admin_only_and_audited(monkeypatch, tmp_path):
    c, log = _client(monkeypatch, tmp_path)
    assert c.get("/api/v1/medical/admin/kb/integrity",
                 headers=_h("doctor")).status_code == 403
    r = c.get("/api/v1/medical/admin/kb/integrity", headers=_h("admin"))
    assert r.status_code == 200 and r.json()["summary"]["scanned"] == 2
    ev = [e for e in log.entries if e["payload"].get("action") == "integrity_scan"]
    assert ev and ev[-1]["payload"]["scanned"] == 2
    assert "content" not in ev[-1]["payload"], "审计不记文档正文"
