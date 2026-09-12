"""知识库摄取 + 管理员端点角色门禁测试。"""
from fastapi.testclient import TestClient

from backend.core import kb_ingest
from backend.core.auth import seed_default_users
from backend.main import app


def test_chunk_text_splits_and_drops_tiny():
    text = "。".join(f"这是第{i}个足够长的句子用于测试切分逻辑是否生效" for i in range(40))
    chunks = kb_ingest.chunk_text(text)
    assert len(chunks) >= 2
    assert all(len(c) >= 20 for c in chunks)


def test_chunk_text_empty():
    assert kb_ingest.chunk_text("") == []
    assert kb_ingest.chunk_text("   ") == []


def test_admin_kb_endpoint_requires_admin():
    seed_default_users()
    c = TestClient(app)
    dtok = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).json()["access_token"]
    assert c.get("/api/v1/medical/admin/kb", headers={"Authorization": "Bearer " + dtok}).status_code == 403
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    r = c.get("/api/v1/medical/admin/kb", headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 200
    assert "total" in r.json() and "docs" in r.json()


def test_ingest_document_rejects_unsupported_type():
    """非文本/非图片类型必须明确拒绝（曾发生 jpg 被硬解为 1197 个乱码切片入库；
    任务C 后 png/jpg 已走 OCR 白名单，反例改用仍不支持的 gif/docx）。"""
    import pytest
    with pytest.raises(ValueError, match="不支持的文件类型"):
        kb_ingest.ingest_document("病例例图.gif", b"GIF89a-fake-gif-bytes")
    with pytest.raises(ValueError, match="不支持的文件类型"):
        kb_ingest.ingest_b64("x.docx", "aGk=")


def test_register_doc_and_list_kind(monkeypatch, tmp_path):
    """种子内置库必须登记进 manifest 并在列表中以 kind=builtin 呈现（此前内置库不可见不可管）。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    kb_ingest.register_doc("pubmed_seed", "PubMed 文献库（内置）", 1359)
    kb_ingest.register_doc("local_corpus", "本地指南要点（内置）", 21)
    d = kb_ingest.list_docs()
    kinds = {x["doc_tag"]: x["kind"] for x in d["docs"]}
    assert kinds["pubmed_seed"] == "builtin"
    assert kinds["local_corpus"] == "builtin"
    assert d["uploaded"] == 0  # 内置库不计入"在线上传"
    kb_ingest.register_doc("up_abc", "我传的.md", 3)
    d2 = kb_ingest.list_docs()
    kinds2 = {x["doc_tag"]: x["kind"] for x in d2["docs"]}
    assert kinds2["up_abc"] == "uploaded"
    assert d2["uploaded"] == 1


def test_preview_doc_returns_chunks_or_empty(monkeypatch, tmp_path):
    """预览接口：有 Milvus 返回切片，无数据/异常返回空列表，绝不抛错。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    out = kb_ingest.preview_doc("不存在的tag_xyz")
    assert out["tag"] == "不存在的tag_xyz"
    assert isinstance(out["chunks"], list)


# ---- 任务5：PDF 摄取诊断修复（扫描版明确报错 / 大文档上限 / 正常 PDF 成功入库）----

def _make_pdf(pages: int = 1, text: str = "") -> bytes:
    """用 PyMuPDF 构造内存 PDF：pages 页，每页写入 text（text 为空 → 无文本层，模拟扫描版）。"""
    import fitz
    doc = fitz.open()
    for _ in range(pages):
        page = doc.new_page()
        if text:
            page.insert_text((72, 72), text, fontname="china-s")
    data = doc.tobytes()
    doc.close()
    return data


def test_scanned_pdf_without_text_layer_gives_explicit_error():
    """扫描版 PDF（无文本层）→ 明确报错「扫描版PDF需OCR」，不再笼统"无有效内容"。"""
    import pytest
    raw = _make_pdf(2, text="")  # 两页空白页（无文本层）
    with pytest.raises(ValueError, match="扫描版PDF需OCR"):
        kb_ingest.ingest_document("扫描件.pdf", raw)


def test_oversized_pdf_pages_gives_explicit_error(monkeypatch):
    """页数超上限 → 明确报错「PDF 页数过多」，而非提取卡死或笼统失败。"""
    import pytest
    monkeypatch.setattr(kb_ingest, "PDF_MAX_PAGES", 2)
    raw = _make_pdf(3, text="正常文字内容用于触发页数上限判定逻辑")
    with pytest.raises(ValueError, match="PDF 页数过多"):
        kb_ingest.extract_text("big.pdf", raw)


def test_oversized_text_gives_explicit_error(monkeypatch):
    """提取文本超上限 → 明确报错「文档过大…请拆分后上传」。"""
    import pytest
    monkeypatch.setattr(kb_ingest, "TEXT_MAX_CHARS", 10)
    with pytest.raises(ValueError, match="文档过大"):
        kb_ingest.extract_text("long.md", "这是一段远超上限的中文文本内容，用于触发上限报错。".encode("utf-8"))


def test_normal_pdf_ingests_successfully(monkeypatch, tmp_path):
    """正常小 PDF（带文字层）→ 切分/嵌入/入库成功，manifest 登记（mock Milvus 不触网）。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    monkeypatch.setattr(kb_ingest, "embed_dense_sparse",
                        lambda chunks, batch_size=32, max_length=256:
                        ([[0.1, 0.2]] * len(chunks), [None] * len(chunks)))
    col = type("C", (), {"delete": lambda *a, **k: None,
                         "insert": lambda *a, **k: None,
                         "load_collection": lambda *a, **k: None})()
    monkeypatch.setattr(kb_ingest, "_collection", lambda: col)
    raw = _make_pdf(2, text="病理学是研究疾病的病因、发病机制与转归的基础医学学科，内容足够长以便切分。")
    out = kb_ingest.ingest_document("病理学要点.pdf", raw)
    assert out["chunks"] >= 1 and out["inserted"] == out["chunks"]
    assert out["doc_tag"].startswith("up_")
    m = kb_ingest._load_manifest()
    assert out["doc_tag"] in m and m[out["doc_tag"]]["name"] == "病理学要点.pdf"


# ---- 任务2：KB 上传异步化（阈值判定 / 后台任务状态流转 / 状态文件 / 路由分流）----

def _mock_embed_and_col(monkeypatch):
    """mock 向量化与 Milvus 集合：瞬时返回、不触网（后台任务测试共用）。"""
    monkeypatch.setattr(kb_ingest, "embed_dense_sparse",
                        lambda chunks, batch_size=32, max_length=256:
                        ([[0.1, 0.2]] * len(chunks), [None] * len(chunks)))
    col = type("C", (), {"delete": lambda *a, **k: None,
                         "insert": lambda *a, **k: None,
                         "load_collection": lambda *a, **k: None})()
    monkeypatch.setattr(kb_ingest, "_collection", lambda: col)


def _isolate_kb_dirs(monkeypatch, tmp_path):
    """隔离 manifest/uploads/tasks 目录（绝不触碰真实 data/kb*）。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    monkeypatch.setattr(kb_ingest, "KB_UPLOAD_DIR", str(tmp_path / "uploads"))
    monkeypatch.setattr(kb_ingest, "KB_TASKS_DIR", str(tmp_path / "tasks"))


def _wait_task_done(task_id: str, timeout: float = 5.0) -> dict:
    """轮询任务状态直至 done/error（后台线程异步完成）。"""
    import time
    deadline = time.time() + timeout
    while time.time() < deadline:
        st = kb_ingest.read_task(task_id)
        if st and st.get("status") in ("done", "error"):
            return st
        time.sleep(0.02)
    raise AssertionError("后台任务未在超时内完成")


def test_should_ingest_async_threshold(monkeypatch, tmp_path):
    """阈值判定（可测）：PDF 按页数（>20 页 → 异步）、文本按字符数（>20 万 → 异步）。"""
    small_md = "医学知识点。" * 100  # ~600 字符
    assert kb_ingest.should_ingest_async("指南.md", small_md.encode("utf-8")) is False
    big_md = "医学知识点内容。" * 30_000  # > 20 万字符
    assert kb_ingest.should_ingest_async("大教材.md", big_md.encode("utf-8")) is True
    small_pdf = _make_pdf(3, text="正常文字层内容，用于页数阈值判定测试。")
    assert kb_ingest.should_ingest_async("小册子.pdf", small_pdf) is False
    monkeypatch.setattr(kb_ingest, "SYNC_MAX_PAGES", 20)
    big_pdf = _make_pdf(21, text="第x页文字层。")
    assert kb_ingest.should_ingest_async("大教材.pdf", big_pdf) is True


def test_read_task_rejects_invalid_or_missing_id(monkeypatch, tmp_path):
    """状态读取：非法 task_id（路径穿越/乱格式）与不存在 id → None（端点转 404）。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    assert kb_ingest.read_task("../../secrets") is None
    assert kb_ingest.read_task("kbt-zzzz") is None
    assert kb_ingest.read_task("kbt-0123456789ab") is None  # 格式合法但文件不存在
    assert kb_ingest.read_task("") is None


def test_async_task_full_lifecycle_state_flow(monkeypatch, tmp_path):
    """大文件异步任务状态流转：processing(0/total) → 分批进度 → done + manifest 登记；
    embed 分批回调推进度（mock 快速返回，真实链路同构）。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    _mock_embed_and_col(monkeypatch)
    monkeypatch.setattr(kb_ingest, "SYNC_MAX_CHARS", 10)  # 强制走异步阈值
    raw = ("病理学是研究疾病的病因、发病机制与转归的基础医学学科。" * 50).encode("utf-8")
    task_id = kb_ingest.start_ingest_task("病理学要点.md", raw)
    assert task_id.startswith("kbt-")
    st0 = kb_ingest.read_task(task_id)
    assert st0 is not None and st0["status"] in ("processing", "done")  # 起点状态文件已落盘
    st = _wait_task_done(task_id)
    assert st["status"] == "done"
    assert st["result"]["inserted"] == st["result"]["chunks"] >= 1
    assert st["progress"] == st["total"] >= 1
    m = kb_ingest._load_manifest()
    assert st["result"]["doc_tag"] in m and m[st["result"]["doc_tag"]]["name"] == "病理学要点.md"


def test_async_task_error_state_carries_reason(monkeypatch, tmp_path):
    """后台任务失败 → status=error 且 error 携带原因（前端轮询可见，绝不静默）。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    monkeypatch.setattr(kb_ingest, "embed_dense_sparse",
                        lambda chunks, batch_size=32, max_length=256:
                        (_ for _ in ()).throw(RuntimeError("模型显存不足")))
    raw = "这是一段足够长会被切分的医学文本内容，用于触发向量化失败路径的测试。" * 20
    task_id = kb_ingest.start_ingest_task("失败用例.md", raw.encode("utf-8"))
    st = _wait_task_done(task_id)
    assert st["status"] == "error"
    assert "显存不足" in (st["error"] or "")


def test_async_scan_pdf_error_state(monkeypatch, tmp_path):
    """扫描版 PDF 走异步任务：error 原因与同步路径同文案（明确提示需 OCR）。
    任务C 后：OCR 已安装 → mock OCR 返回空文本（空白件），验证「OCR 后仍无文字」
    明确报错路径（不依赖真实 OCR 对 25 页的耗时，语义与同步路径一致）。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    _mock_embed_and_col(monkeypatch)
    monkeypatch.setattr(kb_ingest.kb_ocr, "ocr_image_bytes", lambda png: "")  # OCR 空结果（空白页）
    raw = _make_pdf(25, text="")  # >20 页且无文本层
    task_id = kb_ingest.start_ingest_task("扫描大书.pdf", raw)
    st = _wait_task_done(task_id)
    assert st["status"] == "error"
    assert "扫描版PDF需OCR" in (st["error"] or "")


def test_kb_upload_route_async_and_status_endpoint(monkeypatch, tmp_path):
    """路由分流：大文件 POST 立即返回 task_id（mode=async，不等待向量化）；状态端点
    可查询直至 done；非法 task_id → 404；doctor 无权限（admin 门禁）。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    _mock_embed_and_col(monkeypatch)
    monkeypatch.setattr(kb_ingest, "SYNC_MAX_CHARS", 10)  # 任意文本都走异步
    seed_default_users()
    c = TestClient(app)
    dtok = c.post("/api/v1/auth/login", json={"username": "doctor01", "password": "Med@2026"}).json()["access_token"]
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    big = ("医学内容。" * 5000).encode("utf-8")
    import base64 as b64
    r = c.post("/api/v1/medical/admin/kb/upload",
               json={"name": "大教材.md", "data_b64": b64.b64encode(big).decode()},
               headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 200
    d = r.json()
    assert d["mode"] == "async" and d["task_id"].startswith("kbt-")
    st = _wait_task_done(d["task_id"])
    assert st["status"] == "done"
    r2 = c.get(f"/api/v1/medical/admin/kb/upload/status/{d['task_id']}",
               headers={"Authorization": "Bearer " + atok})
    assert r2.status_code == 200 and r2.json()["status"] == "done"
    assert c.get("/api/v1/medical/admin/kb/upload/status/kbt-bad-id",
                 headers={"Authorization": "Bearer " + atok}).status_code == 404
    assert c.get(f"/api/v1/medical/admin/kb/upload/status/{d['task_id']}",
                 headers={"Authorization": "Bearer " + dtok}).status_code == 403


def test_kb_upload_route_small_file_stays_sync(monkeypatch, tmp_path):
    """小文件同步模式保留：POST 直接返回摄取结果（mode=sync），不产生任务文件。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    _mock_embed_and_col(monkeypatch)
    seed_default_users()
    c = TestClient(app)
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    import base64 as b64
    small = ("青霉素类抗生素主要用于敏感菌所致的感染，用药前需详细询问过敏史。"
             "青霉素皮试阳性者禁用该类药物。").encode("utf-8")
    r = c.post("/api/v1/medical/admin/kb/upload",
               json={"name": "用药要点.md", "data_b64": b64.b64encode(small).decode()},
               headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 200
    d = r.json()
    assert d["mode"] == "sync" and d["inserted"] >= 1 and "task_id" not in d


def test_kb_upload_route_rejects_unsupported_type(monkeypatch, tmp_path):
    """类型校验前置：非法扩展名（如 .gif）在同步/异步分流之前 400 拒绝
    （任务C 后 png/jpg 走 OCR 白名单，反例改用仍不支持的 gif）。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    seed_default_users()
    c = TestClient(app)
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    import base64 as b64
    r = c.post("/api/v1/medical/admin/kb/upload",
               json={"name": "例图.gif", "data_b64": b64.b64encode(b"GIF89afake").decode()},
               headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 400
    assert "不支持的文件类型" in r.json()["detail"]


# ---------- 本批次任务5：上传后列表/向量总数刷新链路回归锁 ----------

def test_uploaded_doc_appears_in_kb_list_after_ingest(monkeypatch, tmp_path):
    """任务5 回归锁：在线上传（同步路径）成功后，GET /admin/kb 立即可见该文档
    （manifest 来源，kind=uploaded），uploaded 计数与向量总数（doc_count）同步反映——
    前端 uploadKb 成功/轮询 done 后 loadKb() 重拉该端点即刷新列表与「向量总数」。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    _mock_embed_and_col(monkeypatch)
    seed_default_users()
    c = TestClient(app)
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}).json()["access_token"]
    import base64 as b64
    small = ("糖尿病饮食管理的核心是控制总热量与碳水分配，"
             "建议定时定量进餐并配合血糖监测。").encode("utf-8")
    r = c.post("/api/v1/medical/admin/kb/upload",
               json={"name": "糖尿病管理要点.md", "data_b64": b64.b64encode(small).decode()},
               headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 200 and r.json()["mode"] == "sync"
    # 上传后立即拉列表（前端 loadKb 的同一数据源）：文档出现 + 计数正确
    lst = c.get("/api/v1/medical/admin/kb", headers={"Authorization": "Bearer " + atok}).json()
    assert lst["uploaded"] == 1
    assert lst["docs"] and lst["docs"][0]["name"] == "糖尿病管理要点.md"
    assert lst["docs"][0]["kind"] == "uploaded"
    # 大文件异步路径同链路：轮询 done 后 manifest 已登记（列表同源可见）
    monkeypatch.setattr(kb_ingest, "SYNC_MAX_CHARS", 10)
    big = ("高血压生活方式干预包括限盐、减重、限酒与规律运动，"
           "贯穿全部危险分层管理。").encode("utf-8")
    r2 = c.post("/api/v1/medical/admin/kb/upload",
                json={"name": "高血压干预.md", "data_b64": b64.b64encode(big).decode()},
                headers={"Authorization": "Bearer " + atok})
    assert r2.json()["mode"] == "async"
    assert _wait_task_done(r2.json()["task_id"])["status"] == "done"
    lst2 = c.get("/api/v1/medical/admin/kb", headers={"Authorization": "Bearer " + atok}).json()
    assert lst2["uploaded"] == 2
    assert any(x["name"] == "高血压干预.md" for x in lst2["docs"])


# ---------- 轮 A1：内置库允许 admin 删除（此前 raise ValueError 致端点裸 500） ----------

def _fake_col(delete_count: int = 5):
    """delete_doc 单测替身：delete 返回 delete_count、flush 静默。"""
    return type("C", (), {"delete": lambda *a, **k: {"delete_count": delete_count},
                          "flush": lambda *a, **k: None})()


def test_delete_doc_allows_builtin_tag(monkeypatch, tmp_path):
    """轮 A1：delete_doc 对 BUILTIN_TAGS 不再 raise——admin 可删（审计区分在端点层）。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    kb_ingest.register_doc("pubmed_seed_v2", "PubMed 文献库（内置）", 1359)
    monkeypatch.setattr(kb_ingest, "_collection", lambda: _fake_col(7))
    assert kb_ingest.delete_doc("pubmed_seed_v2") == 7
    assert "pubmed_seed_v2" not in kb_ingest._load_manifest(), "删除成功应同步移出 manifest"


def test_doc_meta_reads_manifest(monkeypatch, tmp_path):
    """轮 A1：doc_meta 审计辅助——取 manifest 元数据；不存在返回空 dict（绝不抛错）。"""
    monkeypatch.setattr(kb_ingest, "MANIFEST", str(tmp_path / "kb_manifest.json"))
    kb_ingest.register_doc("up_x1", "我传的.md", 3)
    assert kb_ingest.doc_meta("up_x1")["name"] == "我传的.md"
    assert kb_ingest.doc_meta("nope") == {}


def test_kb_delete_builtin_200_and_audited(monkeypatch, tmp_path):
    """轮 A1：admin 删内置库 → 200 + 审计 kb.builtin_removed（tag+name），与普通 kb.delete 区分。"""
    from backend.core.medical_audit import AuditLog
    _isolate_kb_dirs(monkeypatch, tmp_path)
    kb_ingest.register_doc("pubmed_seed_v2", "PubMed 文献库（内置）", 1359)
    monkeypatch.setattr(kb_ingest, "_collection", lambda: _fake_col(5))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    c = TestClient(app)
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}
                  ).json()["access_token"]
    r = c.delete("/api/v1/medical/admin/kb/pubmed_seed_v2",
                 headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 200, r.text
    assert r.json() == {"deleted": 5}
    hits = [e for e in audit.entries if e["payload"].get("action") == "builtin_removed"]
    assert hits and hits[-1]["payload"]["tag"] == "pubmed_seed_v2"
    assert hits[-1]["payload"]["name"] == "PubMed 文献库（内置）"


def test_kb_delete_uploaded_audits_plain_delete(monkeypatch, tmp_path):
    """轮 A1：普通上传库删除仍记 kb.delete（与内置 builtin_removed 区分）。"""
    from backend.core.medical_audit import AuditLog
    _isolate_kb_dirs(monkeypatch, tmp_path)
    kb_ingest.register_doc("up_x1", "我传的.md", 3)
    monkeypatch.setattr(kb_ingest, "_collection", lambda: _fake_col(2))
    seed_default_users()
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    c = TestClient(app)
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}
                  ).json()["access_token"]
    r = c.delete("/api/v1/medical/admin/kb/up_x1", headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 200 and r.json() == {"deleted": 2}
    hits = [e for e in audit.entries if e["payload"].get("action") == "delete"]
    assert hits and hits[-1]["payload"]["tag"] == "up_x1"


def test_kb_delete_error_returns_json_not_bare_text(monkeypatch, tmp_path):
    """轮 A1：端点兜底——delete_doc 抛异常也必须返回 JSON（含 detail），永不裸文本 500
    （此前 ValueError 未捕获 → FastAPI 纯文本 500 → 前端 JSON 解析报 "Unexpected token 'I'"）。"""
    from backend.core.medical_audit import AuditLog
    _isolate_kb_dirs(monkeypatch, tmp_path)
    seed_default_users()
    monkeypatch.setattr(kb_ingest, "delete_doc",
                        lambda tag: (_ for _ in ()).throw(RuntimeError("Milvus 连接失败")))
    audit = AuditLog(path=str(tmp_path / "audit.jsonl"))
    monkeypatch.setattr("backend.api.v1.medical.medical_router.get_audit_logger", lambda: audit)
    c = TestClient(app)
    atok = c.post("/api/v1/auth/login", json={"username": "admin01", "password": "Med@2026"}
                  ).json()["access_token"]
    r = c.delete("/api/v1/medical/admin/kb/up_x1", headers={"Authorization": "Bearer " + atok})
    assert r.status_code == 500
    assert r.json()["detail"] == "Milvus 连接失败", "响应必须是可解析 JSON 且含 detail"
    assert [e for e in audit.entries if e["payload"].get("action") == "delete_failed"]


# ---- 终评 F5：doc_tag 清洗（与 delete/preview 同口径）+ 后台摄取并发上限 Semaphore(2) ----

def test_ingest_document_sanitizes_doc_tag(monkeypatch, tmp_path):
    """doc_tag 带引号/过滤字符 → ingest_document 过 _safe_tag 清洗（防 Milvus filter
    注入/孤儿 tag）；清洗后为空回落默认 up_ 前缀 tag。"""
    _isolate_kb_dirs(monkeypatch, tmp_path)
    _mock_embed_and_col(monkeypatch)
    raw = ("医学内容，足够长以便切分入库测试。" * 30).encode("utf-8")
    out = kb_ingest.ingest_document("指南.md", raw, doc_tag='ta"g<inject>')
    assert out["doc_tag"] == "taginject"
    assert out["doc_tag"] in kb_ingest._load_manifest()
    # 全为过滤字符 → 清洗为空 → 回落默认 tag
    out2 = kb_ingest.ingest_document("指南.md", raw, doc_tag='"\'')
    assert out2["doc_tag"].startswith("up_")
    # delete/preview 同口径：清洗后的 tag 可预览可删
    assert kb_ingest.preview_doc('ta"g<inject>')["tag"] == "taginject"


def test_ingest_semaphore_limits_concurrency(monkeypatch):
    """后台摄取全局并发 ≤2：前两个占满配额时第 3 个任务在 Semaphore 处排队
    （daemon 线程排队而非并发爆），释放后第 3 个才进入——峰值并发恒 ≤2。"""
    import threading
    import time

    active: list[str] = []
    entered: list[str] = []
    peak = [0]
    release = threading.Event()

    def fake_locked(task_id, *a, **k):
        active.append(task_id)
        peak[0] = max(peak[0], len(active))
        entered.append(task_id)
        release.wait(5)
        active.remove(task_id)

    monkeypatch.setattr(kb_ingest, "_run_ingest_task_locked", fake_locked)
    ts = [threading.Thread(target=kb_ingest.run_ingest_task,
                           args=(f"t{i}", "a.md", "p"), daemon=True) for i in range(3)]
    for t in ts:
        t.start()
    deadline = time.time() + 3
    while time.time() < deadline and len(active) < 2:
        time.sleep(0.01)
    time.sleep(0.15)  # 若无信号量，第 3 个此刻必然已进入 → 断言失败
    assert len(active) == 2, "第 3 个任务应被 Semaphore(2) 排队"
    assert peak[0] == 2
    release.set()  # 放行前两个 → 第 3 个进入
    for t in ts:
        t.join(timeout=5)
    assert sorted(entered) == ["t0", "t1", "t2"], "三个任务最终都应执行（排队而非丢弃）"
    assert peak[0] == 2, "任意时刻并发摄取不得超过 2"
