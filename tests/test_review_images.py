"""F3 核对可见图片：submit 支持 images（Pillow 压缩长边 1280 JPEG q80）+ imaging 路由透传。

- 单元：submit 存压缩后 data URL；压缩失败原图兜底；不带 images 存 None；
- 路由：imaging/ask 高危入队后 review/pending 条目含 images（VL 用 Fake 捕获，不触网络）。
"""
import asyncio
import base64
import io

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


def test_submit_compression_failure_falls_back_to_original(monkeypatch, tmp_path):
    """非法图片（解码失败）→ 原样入库，不抛异常。"""
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    bad = "data:image/png;base64,@@@not-a-valid-image@@@"
    rid = review.submit(agent="imaging", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01", images=[bad])
    assert review.get(rid)["images"] == [bad]


def test_submit_without_images_stores_none(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    rid = review.submit(agent="drug", question="q", answer="a", confidence=0.6,
                        risk_reason="r", submitted_by="doctor01")
    item = review.get(rid)
    assert item.get("images") is None


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
