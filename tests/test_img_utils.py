"""轮 A2：图片统一规整（img_utils.normalize_data_url）+ 全入口 ≤10 张/单张 ≤6MB。

- 单元：4000px 大图 → 压缩后 ≤6MB 且长边缩到 ≤2000；小图原样返回（字节不变）；
  损坏图原样返回（防御）。
- 路由（imaging/ask，AskReq._cap_images 统一入口）：10 张伪图全过；11 张 422；
  >6MB 伪图压缩后放行（「压缩替代拒绝」，压缩后仍 >6MB 才 422）。
"""
import base64
import io
import os

from fastapi.testclient import TestClient
from PIL import Image

from backend.core import img_utils
from backend.core import medical_imaging as mi
from backend.core import medical_review as review
from backend.core.auth import seed_default_users
from backend.main import app


def _png_data_url(w: int = 320, h: int = 200) -> str:
    """纯色 PNG data URL（小图基准）。"""
    img = Image.new("RGB", (w, h), (180, 40, 40))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def _noise_png_data_url(w: int = 2400, h: int = 2400) -> str:
    """随机噪声 PNG data URL：噪声不可压缩 → 二进制体积远超 6MB（>6MB 反例图）。"""
    raw = os.urandom(w * h * 3)
    img = Image.frombytes("RGB", (w, h), raw)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()


def _decode(data_url: str) -> Image.Image:
    return Image.open(io.BytesIO(base64.b64decode(data_url.split(",", 1)[1])))


# ---- 单元：normalize_data_url ----

def test_big_image_resized_and_within_6mb():
    """伪 4000px 大图：长边缩到 ≤2000、重编码 JPEG、≤6MB。"""
    url = _png_data_url(4000, 3000)
    out = img_utils.normalize_data_url(url)
    assert out.startswith("data:image/jpeg;base64,"), "应重编码为 JPEG data URL"
    assert len(out) <= 8_000_000, "压缩后不得超过 6MB（base64 ≤8M 字符）"
    im = _decode(out)
    assert max(im.size) <= img_utils.MAX_EDGE, f"长边应缩到 ≤2000，实际 {im.size}"
    assert max(im.size) < 4000, "尺寸必须缩了"


def test_small_image_returned_unchanged():
    """小图（长边 ≤2000 且 ≤6MB）原样返回，字节不变（不做无谓重编码）。"""
    url = _png_data_url(320, 200)
    assert img_utils.normalize_data_url(url) == url


def test_oversized_bytes_compressed_under_6mb():
    """>6MB 噪声图（2400×2400）→ 压缩后 ≤6MB（「压缩替代拒绝」核心场景）。"""
    url = _noise_png_data_url()
    assert len(base64.b64decode(url.split(",", 1)[1])) > img_utils.MAX_BYTES, "构造前提：原图 >6MB"
    out = img_utils.normalize_data_url(url)
    assert len(base64.b64decode(out.split(",", 1)[1])) <= img_utils.MAX_BYTES
    assert out.startswith("data:image/jpeg;base64,")


def test_corrupt_input_returned_as_is():
    """非图/损坏输入原样返回（防御，绝不抛错）。"""
    bad = "data:image/png;base64,@@@not-a-valid-image@@@"
    assert img_utils.normalize_data_url(bad) == bad
    assert img_utils.normalize_data_url("") == ""
    assert img_utils.normalize_data_url("x" * 8_000_000) == "x" * 8_000_000


# ---- 路由：AskReq._cap_images 统一入口（imaging/ask） ----

class _FakeVL:
    async def ainvoke(self, messages):
        class _R:
            content = "影像所见描述文本"
        return _R()


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def _client(monkeypatch, tmp_path):
    monkeypatch.setattr(review, "QUEUE_FILE", str(tmp_path / "queue.json"))
    seed_default_users()
    monkeypatch.setattr(mi, "get_vl_llm", lambda temperature=0: _FakeVL())
    return TestClient(app)


def test_route_accepts_ten_images(monkeypatch, tmp_path):
    """10 张伪图全部通过（轮 A2 上限 6→10）。"""
    c = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01")
    imgs = [_png_data_url(640, 480) for _ in range(10)]
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(tok),
               json={"question": "这张胸片有什么异常", "images": imgs})
    assert r.status_code == 200, r.text
    assert r.json()["answer"]


def test_route_rejects_eleven_images(monkeypatch, tmp_path):
    """11 张 → 422（张数超上限仍然拒绝）。"""
    c = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01")
    imgs = [_png_data_url(320, 200) for _ in range(11)]
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(tok),
               json={"question": "这张胸片有什么异常", "images": imgs})
    assert r.status_code == 422
    assert "最多 10 张" in r.text


def test_route_oversized_image_compressed_and_accepted(monkeypatch, tmp_path):
    """>6MB 伪图 → 入口自动压缩放行（压缩替代拒绝；压缩后仍 >6MB 才 422）。"""
    c = _client(monkeypatch, tmp_path)
    tok = _login(c, "doctor01")
    big = _noise_png_data_url(2400, 2400)
    assert len(big) > 8_000_000, "构造前提：原始 data URL 超 8M 字符（旧版会 422）"
    r = c.post("/api/v1/medical/imaging/ask", headers=_h(tok),
               json={"question": "这张胸片有什么异常", "images": [big]})
    assert r.status_code == 200, r.text
    # 入队留痕里的图应是压缩后的 JPEG（≤6MB），而非原样大图
    rid = r.json().get("review_id")
    assert rid
    url = review.get(rid)["images"][0]
    assert url.startswith("data:image/jpeg;base64,")
    assert len(base64.b64decode(url.split(",", 1)[1])) <= img_utils.MAX_BYTES
