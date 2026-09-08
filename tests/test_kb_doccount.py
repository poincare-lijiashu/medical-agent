"""doc_count 失败区分「真 0」：Milvus 异常时返回 -1（前端显示 … 而非假 0）。"""
from backend.core import medical_kb as kb


def test_doc_count_returns_negative_on_failure(monkeypatch):
    def boom():
        raise RuntimeError("Milvus 不可达")

    monkeypatch.setattr(kb, "_collection", boom)
    assert kb.doc_count() == -1


def test_doc_count_zero_is_real_zero(monkeypatch):
    class _Col:
        def query(self, *a, **k):
            return [{"count(*)": 0}]

    monkeypatch.setattr(kb, "_collection", lambda: _Col())
    assert kb.doc_count() == 0


# ---------- milvus_status：URI 凭据脱敏（防 /config、/admin/data 下发泄露密码） ----------

def test_milvus_status_masks_uri_credentials(monkeypatch):
    """milvus_uri 是标准 URL，院方部署可能把凭据写进 URI（user:pass@host）。
    该值经 GET /config（全部登录用户）与 GET /admin/data infra 运维卡片下发，
    必须在状态源头脱敏：密码不可见，host:port 保留（运维展示价值不受损）。"""
    monkeypatch.setattr(kb, "_milvus_reachable", lambda: True)
    monkeypatch.setattr(kb.settings, "milvus_uri", "http://root:s3cret@10.0.0.8:19530")
    st = kb.milvus_status()
    assert st["connected"] is True
    assert "s3cret" not in st["uri"], "密码绝不能出现在状态接口响应中"
    assert st["uri"] == "http://root:***@10.0.0.8:19530"


def test_milvus_status_plain_uri_untouched(monkeypatch):
    """无凭据的 URI 原样返回（既有部署与前端运维卡片展示不受影响）。"""
    monkeypatch.setattr(kb, "_milvus_reachable", lambda: False)
    monkeypatch.setattr(kb.settings, "milvus_uri", "http://127.0.0.1:19531")
    assert kb.milvus_status() == {"connected": False, "uri": "http://127.0.0.1:19531"}


def test_milvus_status_empty_uri_safe(monkeypatch):
    monkeypatch.setattr(kb, "_milvus_reachable", lambda: False)
    monkeypatch.setattr(kb.settings, "milvus_uri", "")
    st = kb.milvus_status()
    assert st == {"connected": False, "uri": ""}
