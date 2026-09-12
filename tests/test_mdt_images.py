"""MDT 会诊传图（轮 B1）：带图会诊组队/定向意见自动切 VL 视觉模型 + 参与者看图。

覆盖：
- mdt.dept_briefs / pick_departments 带图时 get_vl_llm 被调用且消息含 image_url
  （意见 prompt 要求引用影像所见）；无图时文本路径零改变（VL 零调用）；
- VL 故障降级语义与既有一致（dept_briefs 逐科兜底 / pick_departments 重试后 ConsultError）；
- 端到端：create 200 + images 存储（非法图丢弃、大图压缩后长度减小）、参与者收件箱/
  mine 响应含 images、审计 consult_created 带 images_count、11 张 422；
- 前端标记锁（发起框上传组件 / 缩略图渲染 / showImg 放大 / esc 纪律）。

用 TestClient + 隔离 users/consults/departments 文件（与 test_consults 同模式，不触网）。
"""
from __future__ import annotations

import asyncio
import base64
import io
import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from backend.agents.medical import mdt as mdt_mod
from backend.core import auth as auth_mod
from backend.core import consults as consults_mod
from backend.core import departments as dept_mod
from backend.core.auth import create_user, seed_default_users
from backend.core.medical_audit import get_audit_logger
from backend.main import app


def _isolate(monkeypatch, tmp_path):
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(tmp_path / "departments.json"))
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    return TestClient(app)  # 不进 with，跳过 lifespan 的模型预加载


def _login(c, u, p="Med@2026"):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


def _make_img(side: int = 2500) -> str:
    """构造 side×side 的高质量 JPEG data URL（点阵噪声防压缩塌缩），用于压缩链路断言。"""
    import random

    from PIL import Image
    img = Image.new("RGB", (side, side))
    px = img.load()
    rng = random.Random(7)
    for y in range(0, side, 8):
        for x in range(0, side, 8):
            px[x, y] = (rng.randrange(256), rng.randrange(256), rng.randrange(256))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=95)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()


class _FakeVLText:
    """假 VL 模型（文本响应）：记录收到的消息 content，按 preset 返回响应。"""
    reply = ""

    def __init__(self, seen):
        self._seen = seen

    async def ainvoke(self, messages):
        self._seen.append(messages[-1].content)

        class _R:
            content = _FakeVLText.reply
        return _R()


class _FakeVLStructured:
    """假 VL 模型（结构化输出）：with_structured_output 校验 schema/method 后自返。"""

    def __init__(self, seen, departments_sel, reasoning="结合影像所见组队"):
        self._seen = seen
        self._preset = (list(departments_sel), reasoning)

    def with_structured_output(self, schema, method=None):
        assert schema is mdt_mod.ConsultTeam  # VL 路径结构化 schema 与文本路径一致
        assert method == "function_calling"
        return self

    async def ainvoke(self, messages):
        self._seen.append(messages[-1].content)

        class _D:
            departments = list(self._preset[0])
            reasoning = self._preset[1]
        return _D()


# ==================== dept_briefs：带图切 VL / 无图文本路径零改变 ====================

def test_dept_briefs_with_images_switches_to_vl(monkeypatch):
    """带图定向意见：get_vl_llm 被调用，消息为「image_url + text」多模态形态，
    文本含专科简报 prompt 与「引用影像所见」要求；意见正常解析产出。"""
    seen = []
    _FakeVLText.reply = json.dumps(
        {"opinions": [{"dept": "口腔科", "opinion": "影像示右下颌磨牙区低密度影，建议 CBCT 评估"}]},
        ensure_ascii=False)
    monkeypatch.setattr(mdt_mod, "get_vl_llm", lambda temp=0: _FakeVLText(seen))
    img = "data:image/png;base64,AAA"
    out = asyncio.run(mdt_mod.dept_briefs("右下后牙自发痛 3 天", ["口腔科"], [img]))
    assert out["口腔科"] == "影像示右下颌磨牙区低密度影，建议 CBCT 评估"
    assert len(seen) == 1
    content = seen[0]
    assert isinstance(content, list)  # 多模态消息（list 分段）
    kinds = [p["type"] for p in content]
    assert "image_url" in kinds and "text" in kinds
    assert content[0]["image_url"]["url"] == img  # 图在前
    text = content[-1]["text"]  # 文字在后
    assert "口腔科" in text and "影像所见" in text  # 专科简报 prompt + 引用影像要求


def test_dept_briefs_without_images_keeps_text_path(monkeypatch):
    """无图定向意见：走 _ask 文本路径（VL 零调用，文本 prompt 不带影像注记）——文本路径零改变。"""
    vl_seen = []
    monkeypatch.setattr(mdt_mod, "get_vl_llm", lambda temp=0: _FakeVLText(vl_seen))

    async def fake_ask(route, text):
        assert "影像" not in text  # 文本 prompt 不含影像注记（与既有形态一致）
        return json.dumps({"opinions": [{"dept": "口腔科", "opinion": "文本路径意见"}]},
                          ensure_ascii=False)
    monkeypatch.setattr(mdt_mod, "_ask", fake_ask)
    out = asyncio.run(mdt_mod.dept_briefs("q", ["口腔科"]))
    assert out["口腔科"] == "文本路径意见"
    assert vl_seen == []  # 无图 → VL 模型零调用


def test_dept_briefs_vl_failure_falls_back_per_dept(monkeypatch):
    """有图但 VL 故障：逐科兜底文案，不阻塞会诊发起（降级语义与既有 dept_briefs 一致）。"""

    class _Boom:
        async def ainvoke(self, messages):
            raise RuntimeError("vl down")

    monkeypatch.setattr(mdt_mod, "get_vl_llm", lambda temp=0: _Boom())
    out = asyncio.run(mdt_mod.dept_briefs("q", ["口腔科"], ["data:image/png;base64,AAA"]))
    assert "自行判断" in out["口腔科"]


# ==================== pick_departments：带图切 VL / 无图文本路径零改变 ====================

def test_pick_departments_with_images_switches_to_vl(monkeypatch):
    """带图组队：get_vl_llm（结构化输出同 schema）被调用，消息含 image_url 与
    「结合影像所见判断专科」要求；选科室护栏（过滤/急诊收口）不变。"""
    seen = []
    monkeypatch.setattr(mdt_mod, "get_vl_llm",
                        lambda temp=0: _FakeVLStructured(seen, ["口腔科", "内分泌科"]))
    monkeypatch.setattr(dept_mod, "list_departments",
                        lambda: ["内科", "口腔科", "内分泌科"])
    out = asyncio.run(mdt_mod.pick_departments(
        "牙痛合并糖尿病", ["data:image/png;base64,AAA"]))
    assert out["departments"] == ["口腔科", "内分泌科"]
    content = seen[0]
    assert isinstance(content, list)
    assert any(p["type"] == "image_url" for p in content)
    assert "影像所见" in content[-1]["text"]


def test_pick_departments_without_images_never_touches_vl(monkeypatch):
    """无图组队：仅走 get_structured_llm 文本路径（纯文本消息形态不变），
    get_vl_llm 被调用即失败——文本路径零改变锁。"""

    def _boom(temp=0):
        raise AssertionError("无图组队不得调用 VL 模型")

    monkeypatch.setattr(mdt_mod, "get_vl_llm", _boom)
    seen = []

    class _FakeStructured:
        async def ainvoke(self, messages):
            seen.append(messages[-1].content)

            class _D:
                departments = ["口腔科"]
                reasoning = "文本组队"
            return _D()

    monkeypatch.setattr(mdt_mod, "get_structured_llm", lambda *a, **k: _FakeStructured())
    monkeypatch.setattr(dept_mod, "list_departments", lambda: ["口腔科"])
    out = asyncio.run(mdt_mod.pick_departments("q"))
    assert out["departments"] == ["口腔科"]
    assert seen and isinstance(seen[0], str)  # 文本消息形态（str content）不变


def test_pick_departments_vl_failure_retries_then_raises(monkeypatch):
    """有图但 VL 故障：与文本路径同语义——重试 1 次后抛 ConsultError（路由转 400）。"""
    from backend.core.consults import ConsultError
    calls = {"n": 0}

    class _Boom:
        def with_structured_output(self, schema, method=None):
            return self

        async def ainvoke(self, messages):
            calls["n"] += 1
            raise RuntimeError("vl down")

    monkeypatch.setattr(mdt_mod, "get_vl_llm", lambda temp=0: _Boom())
    monkeypatch.setattr(dept_mod, "list_departments", lambda: ["口腔科"])
    with pytest.raises(ConsultError):
        asyncio.run(mdt_mod.pick_departments("q", ["data:image/png;base64,AAA"]))
    assert calls["n"] == 2  # 恰好重试 1 次


# ==================== 路由端到端（TestClient）：存图 / 看图 / 审计 / 上限 ====================

def _mock_team_capture(monkeypatch, departments_sel):
    """mock Agent 决策点并捕获路由传给组队节点的 images（校验入口已压缩）。"""
    cap = {"pick_images": None}

    async def fake_pick(question, images=None):
        cap["pick_images"] = images
        return {"departments": list(departments_sel), "reasoning": "结合影像组队"}

    async def fake_briefs(question, depts, images=None):
        return {d: f"{d}定向意见" for d in depts}

    monkeypatch.setattr(mdt_mod, "pick_departments", fake_pick)
    monkeypatch.setattr(mdt_mod, "dept_briefs", fake_briefs)
    return cap


def test_consult_create_with_images_end_to_end(monkeypatch, tmp_path):
    """带图会诊端到端：create 200 + images 存储（非法图丢弃、大图压缩后长度减小）；
    组队节点收到入口压缩后的图；参与者收件箱与发起者 mine 响应均含 images；
    审计 consult_created payload 带 images_count（入库张数）。"""
    c = _isolate(monkeypatch, tmp_path)
    create_user("dent2", "Med@2026", "doctor", dept="口腔科")
    doc1 = _login(c, "doctor01")
    dent2 = _login(c, "dent2")
    cap = _mock_team_capture(monkeypatch, ["口腔科"])

    big = _make_img(2500)  # 2500px 大图：入口 normalize（≤2000）→ 存储（≤1280）双重压缩
    # SSRF 收口（终评 F1）：非 data:image/ 入口 422 拒绝（存储层丢弃兜底由 test_consults 锁定）
    assert c.post("/api/v1/medical/consults", headers=_h(doc1),
                  json={"question": "带外部 URL 的请求", "images": ["http://evil/x.png"]}
                  ).status_code == 422
    r = c.post("/api/v1/medical/consults", headers=_h(doc1),
               json={"question": "右下后牙自发痛 3 天，既往 2 型糖尿病",
                     "images": [big]})
    assert r.status_code == 200, r.text
    d = r.json()
    assert len(d["images"]) == 1
    stored = d["images"][0]
    assert len(stored) < len(big), "存储图应为压缩后结果（长度显著减小）"
    assert stored.startswith("data:image/jpeg;base64,")
    # 组队节点吃到的是入口压缩后的图（轮 A2 AskReq 校验器 normalize 输出）
    assert cap["pick_images"] and len(cap["pick_images"][0]) < len(big)

    # 参与者（受邀科室医生）收件箱可见原图；发起者 mine 同样可见
    inbox = c.get("/api/v1/medical/consults/inbox", headers=_h(dent2)).json()
    assert [i["images"] for i in inbox["items"]] == [[stored]]
    mine = c.get("/api/v1/medical/consults/mine", headers=_h(doc1)).json()
    assert [i["images"] for i in mine["initiated"]] == [[stored]]

    # 审计 consult_created：images_count = 实际入库张数
    cid = d["id"]
    ev = [e for e in get_audit_logger().entries
          if e["event_type"] == "consult"
          and e["payload"].get("action") == "consult_created"
          and e["payload"].get("cid") == cid]
    assert ev and ev[-1]["payload"]["images_count"] == 1


def test_consult_create_without_images_no_images_key_noise(monkeypatch, tmp_path):
    """无图会诊：响应 images 为空列表/None（不误带图），审计 images_count=0。"""
    c = _isolate(monkeypatch, tmp_path)
    doc1 = _login(c, "doctor01")
    _mock_team_capture(monkeypatch, ["口腔科"])
    r = c.post("/api/v1/medical/consults", headers=_h(doc1),
               json={"question": "右下后牙自发痛 3 天，既往 2 型糖尿病"})
    assert r.status_code == 200, r.text
    d = r.json()
    assert not (d.get("images") or [])  # 无图 → 空存储
    ev = [e for e in get_audit_logger().entries
          if e["event_type"] == "consult"
          and e["payload"].get("action") == "consult_created"
          and e["payload"].get("cid") == d["id"]]
    assert ev and ev[-1]["payload"]["images_count"] == 0


def test_consult_images_cap_eleven_rejected(monkeypatch, tmp_path):
    """11 张 → 422（轮 A2 白名单上限 10；AskReq 校验器先于业务逻辑拦截）。"""
    c = _isolate(monkeypatch, tmp_path)
    doc1 = _login(c, "doctor01")
    _mock_team_capture(monkeypatch, ["口腔科"])
    r = c.post("/api/v1/medical/consults", headers=_h(doc1),
               json={"question": "右下后牙自发痛 3 天需要会诊",
                     "images": ["data:image/jpeg;base64,AAAA"] * 11})
    assert r.status_code == 422, r.text


# ==================== 前端标记锁（防回退） ====================

def test_mdt_images_frontend_markers_present():
    """轮 B1 前端标记锁（Vue 语义版）：会诊发起框图片上传（复用 compressImage + ≤10 计数）、
    发起请求随单带图并在成功后清空、「我的会诊」详情缩略图渲染（data:image 白名单）。
    轮4 起断言源 = frontend-vue/src（ConsultView.vue + utils/image.js）；缩略图经 Vue 模板
    el-image 组件渲染（模板插值 + preview-src-list 点击放大接管 legacy esc/showImg 语义）。"""
    from tests.test_frontend_syntax import _vue_source
    html = _vue_source()
    for marker in (
        # 发起框上传组件（独立 ref 上传，appendImages 压缩入列）
        "const consultImages = ref([])", "async function onFiles(",
        "function clearConsultImgs(", "appendImages(consultImages.value",
        "(await Promise.all(add.map((f) => compressImage(f))))",  # 复用 imaging 压缩函数
        "最多 ${MAX_IMG} 张，已忽略超出部分",        # ≤10 张计数提示
        # 发起请求随单带图 + 成功清空
        "images: consultImages.value.length ? consultImages.value : undefined",
        "consultImages.value = [] // 图已随单发出，清空待附影像",
        # 「我的会诊」详情缩略图（data:image 白名单过滤）
        "function consultThumbs(",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # 「我的会诊」详情缩略图条（v-if + v-for + preview-src-list 三处消费 consultThumbs(c)）
    assert html.count("consultThumbs(c)") >= 2, "「我的会诊」详情应渲染缩略图条（含点击放大预览列表）"
    thumbs = html[html.index("function consultThumbs("):]
    thumbs = thumbs[:thumbs.index("\n}", 10)]
    assert "startsWith('data:image/')" in thumbs, "缩略图缺少 data:image/ 前缀白名单过滤"
