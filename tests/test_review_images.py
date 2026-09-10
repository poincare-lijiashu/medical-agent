"""F3 核对可见图片：submit 支持 images（Pillow 压缩长边 1280 JPEG q80）+ imaging 路由透传。

- 单元：submit 存压缩后 data URL；非法/超限图丢弃不入库（FIND-02）；不带 images 存 None；
  resolve 后历史清图（FIND-03a）；list_all 剥离 images（FIND-03b）；总字符 4M 截断（FIND-03c）；
  _load PermissionError 重试（FIND-04）；
- 路由：imaging/ask 高危入队后 review/pending 条目含 images（VL 用 Fake 捕获，不触网络）。
"""
import asyncio
import base64
import builtins
import io

import pytest

from fastapi.testclient import TestClient
from PIL import Image

from backend.core import medical_imaging as mi
from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.main import app


def _png_data_url(w: int = 2000, h: int = 800) -> str:
    """生成指定尺寸的纯色 PNG data URL（模拟前端上传的影像）。"""
    img = Image.new("RGB", (w, h), (180, 40, 40))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def _decode_image(data_url: str) -> Image.Image:
    b64 = data_url.split(",", 1)[1]
    return Image.open(io.BytesIO(base64.b64decode(b64)))


# ---- 单元：review.submit 的 images 行为 ----

def test_submit_with_images_compresses_to_jpeg_1280(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01",
                        images=[_png_data_url(2000, 800)])
    item = review.get(rid)
    assert isinstance(item.get("images"), list) and len(item["images"]) == 1
    url = item["images"][0]
    assert url.startswith("data:image/jpeg;base64,"), "应压缩为 JPEG data URL"
    im = _decode_image(url)
    assert max(im.size) <= 1280, f"长边应 ≤1280，实际 {im.size}"
    assert im.format == "JPEG"


def test_submit_small_image_keeps_size_no_upscale(monkeypatch, tmp_path):
    """小于 1280 的图不放大，仅转 JPEG。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01",
                        images=[_png_data_url(320, 200)])
    im = _decode_image(review.get(rid)["images"][0])
    assert im.size == (320, 200)


def test_submit_compression_failure_drops_image(monkeypatch, tmp_path):
    """FIND-02：非法图片（解码失败）→ 丢弃而非原样入库，不抛异常。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    bad = "data:image/png;base64,@@@not-a-valid-image@@@"
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01", images=[bad])
    assert review.get(rid)["images"] == []


def test_submit_oversized_pixel_image_dropped(monkeypatch, tmp_path):
    """FIND-02a：像素超限（>40M）的图解码前丢弃，不原样保留。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    img = Image.new("L", (6000, 7000), 0)  # 42M 像素 > 40M（L 模式仅 1 字节/像素，构造便宜）
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    huge = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01", images=[huge])
    assert review.get(rid)["images"] == []


def test_submit_without_images_stores_none(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    rid = review.submit(agent="drug", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01")
    item = review.get(rid)
    assert item.get("images") is None


# ---- FIND-03：队列体积控制 ----

def test_resolve_clears_images_for_history(monkeypatch, tmp_path):
    """FIND-03a：resolve 成功后清空已处理项 images（历史瘦身）；pending 阶段仍可见图。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01",
                        images=[_png_data_url(800, 600)])
    assert review.get(rid)["images"], "pending 阶段图片仍可见"
    review.resolve(rid, "approved", "pharm01", "ok")
    item = review.get(rid)
    assert item["status"] == "approved"
    assert item["images"] is None, "已处理历史不应再留存影像"


def test_list_all_strips_images_only_for_human_signed(monkeypatch, tmp_path):
    """FIND-03b 语义更新（任务5 留痕记录 images 保留）：list_all 剥离规则由「全量剥离」
    调整为「仅人工签发的记录剥离 images」（合规初衷不变：人工复核后不留原图）；
    AI·留痕模式(自动) 签发的记录保留 images（用户自己的交互留痕，历史区可回溯）。
    既有测试 test_list_all_strips_images 按新语义重写并改名（非删除断言）。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    # AI 自动签发（留痕模式语义：resolve reviewer=AI·留痕模式(自动) + keep_images=True）
    rid_auto = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                             risk_reason="r", submitted_by="doctor01",
                             images=[_png_data_url(800, 600)])
    review.resolve(rid_auto, "approved", "AI·留痕模式(自动)", "留痕模式自动签发", True)
    # 人工签发（默认 keep_images=False，resolve 已把存储层 images 置 None）
    rid_manual = review.submit(agent="imaging", question="q2", answer="a2", confidence=0.6,
                               risk_reason="r2", submitted_by="doctor01",
                               images=[_png_data_url(800, 600)])
    review.resolve(rid_manual, "approved", "admin01", "人工核对通过")
    items = review.list_all()
    by_id = {i["id"]: i for i in items}
    assert by_id[rid_auto]["images"], "AI·留痕模式(自动) 签发的记录应保留 images"
    assert "images" not in by_id[rid_manual], "人工签发的记录在历史列表仍剥离 images"
    # with_images=False（/overview、/admin/data 统计调用方）：全量剥离控体积
    assert all("images" not in i for i in review.list_all(with_images=False))


def test_history_endpoint_images_visibility_boundary(monkeypatch, tmp_path):
    """任务5 可见性边界（服务端强制）：AI 留痕签发记录保留的 images 仅发起人本人/admin
    可见——其他医生（doctor02）经 /review/history 读取时 images 必须被剥离
    （此前仅靠前端按人过滤，接口层对多角色可见）。"""
    from backend.core import auth as auth_mod
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    assert auth_mod.create_user("doctor02", "Med@2026x", "doctor", False, "内科")
    c = TestClient(app)
    tok_doc1 = _login(c, "doctor01", "Med@2026")
    img = _png_data_url(800, 600)
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(tok_doc1),
               json={"question": "这张胸片", "images": [img]})
    assert r.status_code == 200, r.text
    rid = r.json()["review_id"]  # 留痕模式默认 on：入队即 AI·留痕模式(自动) 签发，images 存档
    # 发起人本人：images 可见
    items_self = c.get("/api/v1/medical/review/history", headers=_h(tok_doc1)).json()["items"]
    entry_self = next(i for i in items_self if i["id"] == rid)
    assert entry_self.get("images"), "发起人本人应能看到自己留痕记录的影像"
    # 其他医生：images 必须剥离
    tok_doc2 = _login(c, "doctor02", "Med@2026x")
    items_other = c.get("/api/v1/medical/review/history", headers=_h(tok_doc2)).json()["items"]
    entry_other = next(i for i in items_other if i["id"] == rid)
    assert "images" not in entry_other, "非发起人读取他人留痕记录不得携带 images"
    # admin：全可见（合规审计需要）
    tok_admin = _login(c, "admin01", "Med@2026")
    items_admin = c.get("/api/v1/medical/review/history", headers=_h(tok_admin)).json()["items"]
    entry_admin = next(i for i in items_admin if i["id"] == rid)
    assert entry_admin.get("images"), "admin 对留痕记录的影像全可见"


def test_submit_caps_total_image_chars_4m(monkeypatch, tmp_path):
    """FIND-03c：单条 images 总 base64 字符上限 4M，超限从尾部丢弃超额图（首图合规保留）。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    big = "data:image/jpeg;base64," + "A" * 3_000_000
    monkeypatch.setattr(review, "_compress_one", lambda src: big)
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01", images=["x1", "x2"])
    imgs = review.get(rid)["images"]
    assert imgs == [big], "两图共 6M 字符 > 4M，第二张应从尾部丢弃"
    assert sum(len(s) for s in imgs) <= 4_000_000


def test_submit_drops_first_image_when_itself_over_cap(monkeypatch, tmp_path):
    """FIND-03c：首图本身超 4M 时丢弃（不合规首图不得保留），后续合规图保留。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    huge = "data:image/jpeg;base64," + "A" * 4_500_000
    small = "data:image/jpeg;base64," + "A" * 10
    monkeypatch.setattr(review, "_compress_one", lambda src: huge if src == "x1" else small)
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01", images=["x1", "x2"])
    assert review.get(rid)["images"] == [small]


# ---- FIND-04：_load Windows 并发防护 ----

class _NoSleep:
    """替身 time 模块：只提供 sleep（测试提速，不真实等待 50ms）。"""

    @staticmethod
    def sleep(_):
        pass


def _patch_flaky_read_open(monkeypatch, queue_path, fail_times):
    """让 QUEUE_FILE 的读模式 open 前 fail_times 次抛 PermissionError。"""
    real_open = builtins.open
    state = {"n": 0}

    def flaky(file, mode="r", *a, **k):
        if "r" in str(mode) and str(file) == str(queue_path):
            state["n"] += 1
            if state["n"] <= fail_times:
                raise PermissionError(13, "simulated windows file lock")
        return real_open(file, mode, *a, **k)

    monkeypatch.setattr(builtins, "open", flaky)
    return state


def test_load_retries_on_permission_error_then_succeeds(monkeypatch, tmp_path):
    """FIND-04：open 抛两次 PermissionError，第三次成功 → _load 返回数据。"""
    queue_path = tmp_path / "queue.json"
    monkeypatch.setattr(review, "QUEUE_FILE", str(queue_path))
    monkeypatch.setattr(review, "time", _NoSleep)
    review._save([{"id": "rev-1", "status": "pending"}])
    state = _patch_flaky_read_open(monkeypatch, queue_path, fail_times=2)
    assert review._load() == [{"id": "rev-1", "status": "pending"}]
    assert state["n"] == 3, "应重试到第三次成功"


def test_load_permission_error_exhausted_raises(monkeypatch, tmp_path):
    """FIND-04：重试次数耗尽仍 PermissionError → 抛原异常。"""
    queue_path = tmp_path / "queue.json"
    monkeypatch.setattr(review, "QUEUE_FILE", str(queue_path))
    monkeypatch.setattr(review, "time", _NoSleep)
    review._save([{"id": "rev-1", "status": "pending"}])
    _patch_flaky_read_open(monkeypatch, queue_path, fail_times=99)
    with pytest.raises(PermissionError):
        review._load()


# ---- 路由：imaging/ask 高危入队后条目含 images ----

class _FakeVL:
    def __init__(self):
        pass

    async def ainvoke(self, messages):
        class _R:
            content = "影像示右肺占位，考虑恶性肿瘤可能，建议进一步检查"  # 含高危词 → 必入队
        return _R()


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def test_imaging_route_enqueues_review_with_images(monkeypatch, tmp_path):
    """任务3 注记：本测试锁定「高危入队且条目含压缩 images（pending 可见）」语义，
    显式关闭留痕模式（默认 True 时入队即自动签发、条目不再处于 pending）。"""
    from backend.config import settings
    monkeypatch.setattr(settings, "qc_auto_sign_full", False)
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _FakeVL())
    c = TestClient(app)
    tok = _login(c, "doctor01", "Med@2026")
    img = _png_data_url(1600, 900)
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(tok),
               json={"question": "这张胸片有什么异常", "images": [img]})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["needs_human_review"] is True and d["review_id"]
    r2 = c.get("/api/v1/medical/review/pending", headers=_h(tok))
    assert r2.status_code == 200
    entry = next(i for i in r2.json()["pending"] if i["id"] == d["review_id"])
    assert isinstance(entry.get("images"), list) and len(entry["images"]) == 1
    url = entry["images"][0]
    assert url.startswith("data:image/jpeg;base64,"), "入队前应压缩为 JPEG"
    assert max(_decode_image(url).size) <= 1280
    # 压缩后不再等于原图（1600 宽 PNG 已被缩到 ≤1280）
    assert url != img


def test_case_route_enqueues_review_with_images(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _FakeVL())
    c = TestClient(app)
    tok = _login(c, "doctor01", "Med@2026")
    r = c.post("/api/v1/medical/case/ask", headers=_h(tok),
               json={"question": "总结该病例", "images": [_png_data_url(1500, 700)]})
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["review_id"]
    entry = review.get(d["review_id"])
    assert entry["images"] and entry["images"][0].startswith("data:image/jpeg;base64,")
