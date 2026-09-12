"""跨科室会诊协助专项：动态组队 mock、科室同步锁、分发按 dept 多医生、意见权限矩阵、
close 仅发起者、汇总内容完整、my/inbox 隔离、审计 consult_dispatch。

用 TestClient + 隔离 users/consults/departments 文件（不污染真实数据）；
Agent 决策点 monkeypatch mdt.pick_departments / mdt.dept_briefs（不触网）；
pg 池不可用走 JSON 真源（与现状等价；PG 真源路径见 test_pg_repo.py）。
"""
from __future__ import annotations

import asyncio
import json

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


def _mock_team(monkeypatch, departments_sel, reasoning="按病例需要选择", briefs=None):
    """mock Agent 决策点（组队 + 定向意见），不触网。
    轮 B1：路由带 images 调用（签名扩参，无图/有图均兼容；既有断言不变）。"""
    async def fake_pick(question, images=None):
        return {"departments": list(departments_sel), "reasoning": reasoning}
    async def fake_briefs(question, depts, images=None):
        if briefs is not None:
            return dict(briefs)
        return {d: f"{d}定向意见" for d in depts}
    monkeypatch.setattr(mdt_mod, "pick_departments", fake_pick)
    monkeypatch.setattr(mdt_mod, "dept_briefs", fake_briefs)


# ==================== Agent 动态组队（LLM 结构化输出 + 护栏） ====================

class _FakeStructured:
    """假结构化 LLM：记录收到的 prompt，按 preset 返回 ConsultTeam 形态对象。"""
    preset = ([], "")

    def __init__(self, seen):
        self._seen = seen

    async def ainvoke(self, messages):
        self._seen.append(messages[-1].content)

        class _D:
            departments = list(_FakeStructured.preset[0])
            reasoning = _FakeStructured.preset[1]
        return _D()


def _patch_structured(monkeypatch, seen, departments_sel, reasoning="r"):
    _FakeStructured.preset = (list(departments_sel), reasoning)
    monkeypatch.setattr(mdt_mod, "get_structured_llm",
                        lambda *a, **k: _FakeStructured(seen))


def test_pick_departments_selects_subset_and_filters_invalid(monkeypatch):
    """LLM 选 4 科室（含清单外非法名）：非法科室被过滤、合法保序去重。"""
    seen = []
    _patch_structured(monkeypatch, seen,
                      ["口腔科", "内分泌科", "不存在的科室", "内科", "口腔科"])
    monkeypatch.setattr(dept_mod, "list_departments",
                        lambda: ["内科", "外科", "口腔科", "内分泌科"])
    out = asyncio.run(mdt_mod.pick_departments("牙痛合并糖尿病"))
    assert out["departments"] == ["口腔科", "内分泌科", "内科"]  # 非法过滤 + 去重保序
    assert out["reasoning"] == "r"
    # 组队 prompt 注入实时科室清单（LLM 决策的输入）
    assert "口腔科" in seen[0] and "内分泌科" in seen[0]


def test_pick_departments_empty_selection_retries_then_raises(monkeypatch):
    """诊断4：LLM 选空 → 原样重试 1 次；两轮仍空 → 抛 ConsultError（不再全量召集兜底）。"""
    from backend.core.consults import ConsultError
    seen = []
    _patch_structured(monkeypatch, seen, [])
    monkeypatch.setattr(dept_mod, "list_departments",
                        lambda: ["内科", "外科", "口腔科", "内分泌科"])
    with pytest.raises(ConsultError, match="未能确定召集科室"):
        asyncio.run(mdt_mod.pick_departments("牙痛合并糖尿病需多科会诊"))
    assert len(seen) == 2  # 恰好重试 1 次（共 2 轮决策）


def test_pick_departments_llm_failure_retries_then_raises(monkeypatch):
    """诊断4：LLM 异常 → 原样重试 1 次；仍异常 → 抛 ConsultError（不再全部科室兜底）。"""
    from backend.core.consults import ConsultError
    calls = {"n": 0}

    class _Boom:
        async def ainvoke(self, messages):
            calls["n"] += 1
            raise RuntimeError("llm down")

    monkeypatch.setattr(mdt_mod, "get_structured_llm", lambda *a, **k: _Boom())
    monkeypatch.setattr(dept_mod, "list_departments", lambda: ["内科", "口腔科"])
    with pytest.raises(ConsultError):
        asyncio.run(mdt_mod.pick_departments("q"))
    assert calls["n"] == 2  # 恰好重试 1 次


def test_pick_departments_reads_department_list_live(monkeypatch):
    """科室同步锁：组队 prompt 的科室清单每次实时读 list_departments()——
    管理端新增科室后，下一次会诊的 prompt 立即包含新科室（绝不缓存）。"""
    seen = []
    _patch_structured(monkeypatch, seen, ["口腔科"])
    holder = {"list": ["内科", "外科", "口腔科"]}
    monkeypatch.setattr(dept_mod, "list_departments", lambda: list(holder["list"]))
    asyncio.run(mdt_mod.pick_departments("q"))
    assert "康复科" not in seen[0]
    holder["list"] = ["内科", "外科", "口腔科", "康复科"]  # 模拟 admin 新增科室
    out = asyncio.run(mdt_mod.pick_departments("q"))
    assert "康复科" in seen[1], "新增科室必须实时出现在组队 prompt 中"
    assert out["departments"] == ["口腔科"]


def test_pick_departments_emergency_dept_requires_critical_signs(monkeypatch):
    """任务4 急诊科收口（代码强制后过滤）：普通病例（无急危重征象词）即使 LLM 选了
    急诊科也被剔除；含急危重征象词则保留。prompt同时含新约束（双保险）。"""
    seen = []
    _patch_structured(monkeypatch, seen, ["急诊科", "口腔科"])
    monkeypatch.setattr(dept_mod, "list_departments",
                        lambda: ["内科", "口腔科", "急诊科"])
    # 普通牙痛病例：LLM 输出含急诊科 → 后过滤剔除（代码强制）
    out = asyncio.run(mdt_mod.pick_departments("右下后牙自发痛 3 天"))
    assert out["departments"] == ["口腔科"]
    # 组队 prompt 含急诊科收口约束（提示层）
    assert "急诊科仅在存在急危重征象" in seen[0]
    # 含急危重征象词的病例：急诊科保留
    out2 = asyncio.run(mdt_mod.pick_departments("车祸伤，血压 80/50，休克，怀疑腹腔大出血"))
    assert out2["departments"] == ["急诊科", "口腔科"]


def test_pick_departments_retry_recovers_and_keeps_emergency_filter(monkeypatch):
    """诊断4：首轮选空、重试后选出有效科室 → 成功组队，且急诊科收口仍生效（普通病例剔急诊）。"""
    calls = {"n": 0}

    class _Retry:
        async def ainvoke(self, messages):
            calls["n"] += 1

            class _D:
                departments = [] if calls["n"] == 1 else ["急诊科", "口腔科"]
                reasoning = "第二轮选出"
            return _D()

    monkeypatch.setattr(mdt_mod, "get_structured_llm", lambda *a, **k: _Retry())
    monkeypatch.setattr(dept_mod, "list_departments",
                        lambda: ["内科", "口腔科", "急诊科"])
    out = asyncio.run(mdt_mod.pick_departments("常规体检咨询"))
    assert calls["n"] == 2  # 首轮空 → 重试 1 次后成功
    assert out["departments"] == ["口腔科"]  # 急诊科收口仍生效
    assert out["reasoning"] == "第二轮选出"


def test_pick_departments_truncates_to_team_max(monkeypatch):
    """诊断4：LLM 选出超过上限的科室 → 代码强制截断到 _TEAM_MAX（防分发面过大）。"""
    seen = []
    lst = ["内科", "外科", "口腔科", "内分泌科", "急诊科", "骨科", "眼科", "皮肤科"]
    _patch_structured(monkeypatch, seen, lst)  # 8 个合法科室 > 上限 6
    monkeypatch.setattr(dept_mod, "list_departments", lambda: list(lst))
    out = asyncio.run(mdt_mod.pick_departments("车祸伤，血压 80/50，休克，怀疑腹腔大出血"))
    assert len(out["departments"]) == mdt_mod._TEAM_MAX
    assert out["departments"] == lst[:mdt_mod._TEAM_MAX]


def test_dept_briefs_covers_all_and_falls_back(monkeypatch):
    """按科室定向初步意见：LLM 正常时逐科产出；解析失败逐科兜底文案。"""
    async def fake_ask(route, text):
        return json.dumps({"opinions": [{"dept": "口腔科", "opinion": "关注牙源性感染"}]},
                          ensure_ascii=False)
    monkeypatch.setattr(mdt_mod, "_ask", fake_ask)
    out = asyncio.run(mdt_mod.dept_briefs("q", ["口腔科", "内分泌科"]))
    assert out["口腔科"] == "关注牙源性感染"
    assert "自行判断" in out["内分泌科"]  # 缺失科室兜底

    async def boom(route, text):
        raise RuntimeError("x")
    monkeypatch.setattr(mdt_mod, "_ask", boom)
    out2 = asyncio.run(mdt_mod.dept_briefs("q", ["口腔科"]))
    assert "自行判断" in out2["口腔科"]


# ==================== core：模型/队列语义（JSON 真源） ====================

def test_consult_create_and_get_roundtrip(monkeypatch, tmp_path):
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    cid = consults_mod.create(initiator="doctor01", question="牙痛合并糖尿病如何处置",
                              ai_analysis="【口腔科】…", target_depts=["口腔科", "内分泌科"],
                              team={"departments": ["口腔科", "内分泌科"], "reasoning": "多学科"},
                              images=["not-a-data-url"], initiator_dept="口腔科")
    item = consults_mod.get(cid)
    assert item["status"] == "open"
    assert item["initiator"] == "doctor01"
    assert item["target_depts"] == ["口腔科", "内分泌科"]
    assert item["opinions"] == [] and item["summary"] is None
    assert item["team"]["reasoning"] == "多学科"
    assert item["images"] == []  # 非法图片丢弃不入库（与 review 同纪律）
    on_disk = json.loads((tmp_path / "consults.json").read_text(encoding="utf-8"))
    assert [i["id"] for i in on_disk] == [cid]


def test_consult_create_storage_filters_non_data_url(monkeypatch, tmp_path):
    """SSRF 收口（终评 F1）存储层兜底：绕过 HTTP 校验（MCP/内部调用）时，
    非 data:image/ 前缀图在 create 内被丢弃不入库；合法 data URL 原样保留。"""
    import base64
    import io

    from PIL import Image
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    buf = io.BytesIO()
    Image.new("RGB", (8, 8), (10, 20, 30)).save(buf, format="PNG")
    good = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
    cid = consults_mod.create(initiator="doctor01", question="q", ai_analysis="a",
                              target_depts=["口腔科"],
                              images=[good, "http://evil/x.png"])
    item = consults_mod.get(cid)
    assert len(item["images"]) == 1 and item["images"][0].startswith("data:image/")
    cid2 = consults_mod.create(initiator="doctor01", question="q2", ai_analysis="a",
                               target_depts=["口腔科"], images=["http://evil/x.png"])
    assert consults_mod.get(cid2)["images"] == []


def test_mdt_vl_content_rejects_non_data_url():
    """SSRF 收口（终评 F1）MDT VL 层锁：_vl_content 对每张图 enforce_data_url——
    非 data:image/ 抛 ValueError（防用户 URL 原样进 image_url 被服务端请求）。"""
    ok = mdt_mod._vl_content(["data:image/png;base64,AAA"], "prompt")
    assert ok[0]["image_url"]["url"] == "data:image/png;base64,AAA"
    for bad in ("http://evil/x.png", "AAAAnakedb64", "data:text/html;base64,AAA"):
        with pytest.raises(ValueError, match="data URL"):
            mdt_mod._vl_content([bad], "prompt")


def test_consult_inbox_dept_isolation_and_closed_excluded(monkeypatch, tmp_path):
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    cid = consults_mod.create(initiator="doctor01", question="q", ai_analysis="a",
                              target_depts=["口腔科", "内分泌科"])
    assert len(consults_mod.inbox_for("口腔科")) == 1
    assert len(consults_mod.inbox_for("内分泌科")) == 1
    assert consults_mod.inbox_for("内科") == []
    assert consults_mod.inbox_for("") == []  # 未设置科室恒为空
    consults_mod.close(cid, "doctor01")
    assert consults_mod.inbox_for("口腔科") == []  # 已结束不再进入收件箱


def test_consult_opinion_permission_matrix(monkeypatch, tmp_path):
    """意见权限矩阵：受邀科室可填（含发起者本人在自己科室名单内）、每人一票、
    任务4 科室一票（同科室第二名医生不可再填 → 400「该科室已完成意见」）、
    非受邀 403、会诊结束 400、未找到 404。"""
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    cid = consults_mod.create(initiator="doctor01", question="q", ai_analysis="a",
                              target_depts=["口腔科", "内分泌科"])
    # 受邀科室医生正常填写
    item = consults_mod.add_opinion(cid, "dent2", "口腔科", "建议根管治疗")
    assert item["opinions"][0]["doctor"] == "dent2"
    assert item["opinions"][0]["dept"] == "口腔科" and item["opinions"][0]["ts"]
    # 重复填写 → 400（每人一票）
    with pytest.raises(consults_mod.ConsultError):
        consults_mod.add_opinion(cid, "dent2", "口腔科", "再填一次")
    # 任务4 科室一票：同科室第二名医生再填 → 400「该科室已完成意见」
    with pytest.raises(consults_mod.ConsultError, match="该科室已完成意见"):
        consults_mod.add_opinion(cid, "dent3", "口腔科", "同科室另一票")
    # 非受邀科室 → 403
    with pytest.raises(consults_mod.ConsultPermissionError):
        consults_mod.add_opinion(cid, "doc3", "内科", "我不该被邀请")
    # 未设置科室 → 403
    with pytest.raises(consults_mod.ConsultPermissionError):
        consults_mod.add_opinion(cid, "doc4", "", "没有科室")
    # 空内容 → 400
    with pytest.raises(consults_mod.ConsultError):
        consults_mod.add_opinion(cid, "endo1", "内分泌科", "  ")
    # 未找到 → 404
    with pytest.raises(consults_mod.ConsultNotFoundError):
        consults_mod.add_opinion("con-nope", "endo1", "内分泌科", "x")
    # 发起者在自己科室受邀名单内可填——但口腔科已完成意见（dent2 已投）→ 400
    with pytest.raises(consults_mod.ConsultError, match="该科室已完成意见"):
        consults_mod.add_opinion(cid, "doctor01", "口腔科", "补充病史")
    # 其它受邀科室仍可正常填写
    consults_mod.add_opinion(cid, "endo1", "内分泌科", "血糖控制后再拔牙")
    assert len(consults_mod.get(cid)["opinions"]) == 2
    # 会诊结束后 → 400
    consults_mod.close(cid, "doctor01")
    with pytest.raises(consults_mod.ConsultError):
        consults_mod.add_opinion(cid, "endo2", "内分泌科", "迟到的意见")


def test_consult_close_only_initiator_and_summary_complete(monkeypatch, tmp_path):
    """close 仅发起者；汇总 = AI 意见 + 各科医生意见并排署名；不可重复结束。"""
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    cid = consults_mod.create(initiator="doctor01", question="q", ai_analysis="AI定向意见正文",
                              target_depts=["口腔科", "内分泌科"])
    consults_mod.add_opinion(cid, "dent2", "口腔科", "口腔科意见内容")
    consults_mod.add_opinion(cid, "endo1", "内分泌科", "内分泌科意见内容")
    # 非发起者 → 403
    with pytest.raises(consults_mod.ConsultPermissionError):
        consults_mod.close(cid, "dent2")
    with pytest.raises(consults_mod.ConsultPermissionError):
        consults_mod.close(cid, "")
    # 未找到 → 404
    with pytest.raises(consults_mod.ConsultNotFoundError):
        consults_mod.close("con-nope", "doctor01")
    item = consults_mod.close(cid, "doctor01")
    assert item["status"] == "closed" and item["closed_at"]
    s = item["summary"]
    assert "AI定向意见正文" in s          # AI 意见
    assert "口腔科 · dent2" in s and "口腔科意见内容" in s   # 各科意见并排署名
    assert "内分泌科 · endo1" in s and "内分泌科意见内容" in s
    # 重复结束 → 400
    with pytest.raises(consults_mod.ConsultError):
        consults_mod.close(cid, "doctor01")


def test_consult_by_initiator_and_participated_isolation(monkeypatch, tmp_path):
    monkeypatch.setattr(consults_mod, "CONSULTS_FILE", str(tmp_path / "consults.json"))
    c1 = consults_mod.create(initiator="doctor01", question="q1", ai_analysis="a",
                             target_depts=["口腔科"])
    c2 = consults_mod.create(initiator="dent2", question="q2", ai_analysis="a",
                             target_depts=["内分泌科"])
    consults_mod.add_opinion(c2, "doctor01", "内分泌科", "我是跨科受邀")
    assert [i["id"] for i in consults_mod.by_initiator("doctor01")] == [c1]
    assert [i["id"] for i in consults_mod.by_initiator("dent2")] == [c2]
    assert [i["id"] for i in consults_mod.participated("doctor01")] == [c2]  # 参与过 c2
    assert consults_mod.participated("dent2") == []


# ==================== 路由端到端（TestClient） ====================

def test_consult_flow_end_to_end(monkeypatch, tmp_path):
    """发起 → inbox 按 dept 分发（多医生可见）→ 意见 403/400/200 → close 仅发起者
    → 汇总完整 → mine 隔离；审计 consult_dispatch 留痕。"""
    c = _isolate(monkeypatch, tmp_path)
    create_user("dent2", "Med@2026", "doctor", dept="口腔科")
    create_user("endo1", "Med@2026", "doctor", dept="内分泌科")
    create_user("endo2", "Med@2026", "doctor", dept="内分泌科")  # 同科室第二名在职医生
    create_user("doc3", "Med@2026", "doctor", dept="内科")
    doc1 = _login(c, "doctor01")   # 口腔科（发起者）
    dent2 = _login(c, "dent2")     # 口腔科
    endo1 = _login(c, "endo1")     # 内分泌科
    endo2 = _login(c, "endo2")     # 内分泌科
    doc3 = _login(c, "doc3")       # 内科（非受邀）
    _mock_team(monkeypatch, ["口腔科", "内分泌科"], reasoning="牙痛+血糖管理")

    # 发起（doctor01）
    r = c.post("/api/v1/medical/consults", headers=_h(doc1),
               json={"question": "右下后牙自发痛 3 天，既往 2 型糖尿病"})
    assert r.status_code == 200, r.text
    d = r.json()
    cid = d["id"]
    assert d["target_depts"] == ["口腔科", "内分泌科"]
    assert "【口腔科】口腔科定向意见" in d["ai_analysis"]
    assert d["team"]["reasoning"] == "牙痛+血糖管理"
    assert d["status"] == "open" and d["initiator"] == "doctor01"
    # 审计：Agent 决策点 consult_dispatch 留痕（选中科室+理由可回溯）
    ev = [e for e in get_audit_logger().entries
          if e["event_type"] == "consult"
          and e["payload"].get("action") == "consult_dispatch"
          and e["payload"].get("cid") == cid]
    assert ev and ev[-1]["payload"]["departments"] == ["口腔科", "内分泌科"]
    assert ev[-1]["payload"]["role"] == "doctor"

    # 分发按 dept：同科室两名在职医生都可达，其它科室/无单科室不可达
    for tok in (dent2, endo1, endo2):
        inbox = c.get("/api/v1/medical/consults/inbox", headers=_h(tok)).json()["items"]
        assert [i["id"] for i in inbox] == [cid]
    assert c.get("/api/v1/medical/consults/inbox", headers=_h(doc3)).json()["items"] == []

    # 意见权限矩阵（任务4 科室一票：同科室第二名医生不可再填）
    assert c.post(f"/api/v1/medical/consults/{cid}/opinion", headers=_h(doc3),
                  json={"content": "非受邀"}).status_code == 403
    assert c.post(f"/api/v1/medical/consults/{cid}/opinion", headers=_h(endo1),
                  json={"content": "血糖控制后再拔牙"}).status_code == 200
    assert c.post(f"/api/v1/medical/consults/{cid}/opinion", headers=_h(endo1),
                  json={"content": "重复一票"}).status_code == 400
    # 任务4 科室一票：同科室第二名医生（endo2）在 endo1 已投后 → 400「该科室已完成意见」
    r_dup = c.post(f"/api/v1/medical/consults/{cid}/opinion", headers=_h(endo2),
                   json={"content": "同科另一位医生不可重复投票"})
    assert r_dup.status_code == 400 and "该科室已完成意见" in r_dup.json()["detail"]
    # 发起者在自己科室受邀名单内可填（口腔科此时无人投过）
    assert c.post(f"/api/v1/medical/consults/{cid}/opinion", headers=_h(doc1),
                  json={"content": "开髓引流"}).status_code == 200

    # close 仅发起者
    assert c.post(f"/api/v1/medical/consults/{cid}/close", headers=_h(doc3)).status_code == 403
    assert c.post(f"/api/v1/medical/consults/{cid}/close", headers=_h(dent2)).status_code == 403
    r_close = c.post(f"/api/v1/medical/consults/{cid}/close", headers=_h(doc1))
    assert r_close.status_code == 200, r_close.text
    closed = r_close.json()
    assert closed["status"] == "closed" and closed["closed_at"]
    assert "开髓引流" in closed["summary"] and "doctor01" in closed["summary"]
    assert "endo1" in closed["summary"]
    assert c.post(f"/api/v1/medical/consults/{cid}/close", headers=_h(doc1)).status_code == 400
    assert c.post(f"/api/v1/medical/consults/{cid}/opinion", headers=_h(dent2),
                  json={"content": "迟到"}).status_code == 400

    # 结束后收件箱清空；mine 隔离（发起者全部+参与过的）
    assert c.get("/api/v1/medical/consults/inbox", headers=_h(endo1)).json()["items"] == []
    mine1 = c.get("/api/v1/medical/consults/mine", headers=_h(doc1)).json()
    assert [i["id"] for i in mine1["initiated"]] == [cid]
    assert [i["id"] for i in mine1["participated"]] == [cid]  # 自己也投过一票
    mine2 = c.get("/api/v1/medical/consults/mine", headers=_h(endo1)).json()
    assert mine2["initiated"] == []
    assert [i["id"] for i in mine2["participated"]] == [cid]
    mine3 = c.get("/api/v1/medical/consults/mine", headers=_h(endo2)).json()
    assert mine3["initiated"] == [] and mine3["participated"] == []  # 任务4：未投成票不进参与列表
    mine4 = c.get("/api/v1/medical/consults/mine", headers=_h(doc3)).json()
    assert mine4["initiated"] == [] and mine4["participated"] == []
    # 未找到 → 404
    assert c.post("/api/v1/medical/consults/con-nope/opinion", headers=_h(doc1),
                  json={"content": "x"}).status_code == 404
    assert c.post("/api/v1/medical/consults/con-nope/close", headers=_h(doc1)).status_code == 404


def test_consult_inbox_silent_flag_audit_matrix(monkeypatch, tmp_path):
    """T2 方案 A（读操作降噪）：/consults/inbox silent=1（前端 60s 角标轮询路径）
    跳过 list_inbox 审计写入（前后审计计数不变）；非 silent（视图内刷新路径）
    照旧写审计（业务审计保留）。两种模式数据返回完全一致。"""
    c = _isolate(monkeypatch, tmp_path)
    doc1 = _login(c, "doctor01")  # 口腔科
    _mock_team(monkeypatch, ["口腔科"])
    r = c.post("/api/v1/medical/consults", headers=_h(doc1),
               json={"question": "右下后牙自发痛 3 天，既往 2 型糖尿病"})
    assert r.status_code == 200, r.text

    def _n_inbox_audit():
        return len([e for e in get_audit_logger().entries
                    if e["event_type"] == "consult"
                    and e["payload"].get("action") == "list_inbox"])

    # 非 silent（默认，视图内刷新/收件箱 load 路径）：照旧写 list_inbox 审计
    before = _n_inbox_audit()
    r1 = c.get("/api/v1/medical/consults/inbox", headers=_h(doc1))
    assert r1.status_code == 200 and r1.json()["items"], r1.text
    assert _n_inbox_audit() == before + 1, "非 silent 必须照旧写 list_inbox 审计"

    # silent=1（角标轮询路径）：跳过审计写入（前后计数不变），数据返回与非 silent 一致
    r2 = c.get("/api/v1/medical/consults/inbox", headers=_h(doc1), params={"silent": 1})
    assert r2.status_code == 200, r2.text
    assert r2.json() == r1.json(), "silent 与非 silent 数据返回必须完全一致"
    assert _n_inbox_audit() == before + 1, "silent=1 不得新增 list_inbox 审计"


def test_consult_create_prefilter_rejects_short_or_offtopic(monkeypatch, tmp_path):
    """诊断4 发起预检：「你好」（<8 字）或纯技术问题 → 400 引导补充病例，不建单不分发。"""
    c = _isolate(monkeypatch, tmp_path)
    doc1 = _login(c, "doctor01")
    _mock_team(monkeypatch, ["口腔科"])  # 组队被 mock：若 prefilter 失效放行，会立刻建单暴露
    r1 = c.post("/api/v1/medical/consults", headers=_h(doc1),
                json={"question": "你好"})
    assert r1.status_code == 400, r1.text
    assert "请补充患者病史/症状等病例信息" in r1.json()["detail"]
    r2 = c.post("/api/v1/medical/consults", headers=_h(doc1),
                json={"question": "帮我写一个python爬虫脚本抓取网页"})
    assert r2.status_code == 400, r2.text
    assert "请补充患者病史/症状等病例信息" in r2.json()["detail"]
    # 不建单：会诊存储文件从未落盘
    assert not (tmp_path / "consults.json").exists()


def test_consult_dispatch_reaches_new_department_doctors(monkeypatch, tmp_path):
    """科室同步端到端：管理端新增「康复科」并为其建号后，组队选中该科室的会诊
    分发可达新科室医生（分发面由实时 target_depts × users.dept 决定）。"""
    c = _isolate(monkeypatch, tmp_path)
    assert dept_mod.add_department("康复科") is True  # admin 新增科室（实时字典）
    create_user("rehab1", "Med@2026", "doctor", dept="康复科")
    doc1 = _login(c, "doctor01")
    rehab1 = _login(c, "rehab1")
    _mock_team(monkeypatch, ["康复科"])
    r = c.post("/api/v1/medical/consults", headers=_h(doc1),
               json={"question": "膝关节置换术后康复评估"})
    assert r.status_code == 200, r.text
    assert r.json()["target_depts"] == ["康复科"]
    inbox = c.get("/api/v1/medical/consults/inbox", headers=_h(rehab1)).json()
    assert [i["id"] for i in inbox["items"]] == [r.json()["id"]]
    assert inbox["dept"] == "康复科"


def test_consult_routes_require_auth(monkeypatch, tmp_path):
    c = _isolate(monkeypatch, tmp_path)
    assert c.post("/api/v1/medical/consults", json={"question": "q"}).status_code in (401, 403)
    assert c.get("/api/v1/medical/consults/inbox").status_code in (401, 403)
    assert c.get("/api/v1/medical/consults/mine").status_code in (401, 403)
    assert c.post("/api/v1/medical/consults/con-x/opinion", json={"content": "x"}).status_code in (401, 403)
    assert c.post("/api/v1/medical/consults/con-x/close").status_code in (401, 403)


def test_consult_images_whitelist_cap_ten(monkeypatch, tmp_path):
    """images 走现有图片白名单逻辑。轮 A2 语义变更注明：上限 6→10 且入口改为
    「压缩替代拒绝」——原「7 张 → 422」断言按新语义更新：7 张已过图片校验
    （请求进入后续流程，此处因 question 过短被 400 拒绝），11 张仍 422。"""
    c = _isolate(monkeypatch, tmp_path)
    doc1 = _login(c, "doctor01")
    _mock_team(monkeypatch, ["口腔科"])
    r = c.post("/api/v1/medical/consults", headers=_h(doc1),
               json={"question": "q", "images": ["data:image/jpeg;base64,AAAA"] * 7})
    assert r.status_code == 400, r.text  # 图片校验已过（7 ≤ 10），卡在问题过短
    r2 = c.post("/api/v1/medical/consults", headers=_h(doc1),
                json={"question": "q", "images": ["data:image/jpeg;base64,AAAA"] * 11})
    assert r2.status_code == 422, r2.text  # 张数超上限仍 422


# ==================== T1 分科两条规则 + reason 字段（分科理由随单入库） ====================

def test_pick_departments_prompt_two_rules_and_reason_parsed(monkeypatch):
    """T1 组队 prompt 必须含两条分科规则（①必须输出分科理由·带图优先引用影像所见；
    ②无明确影像/症状指向最少召 2 科·仅明确影像急症指征允许单科）；LLM 返回的
    reason（分科理由）被解析透出（旧 mock 无 reason 属性 → 回退 reasoning，向后兼容）。"""
    seen = []

    class _Fake:
        async def ainvoke(self, messages):
            seen.append(messages[-1].content)

            class _D:
                departments = ["口腔科", "内分泌科"]
                reasoning = "牙痛+血糖管理"
                reason = "右下后牙自发痛考虑牙源性感染，血糖异常需内分泌协同评估"
            return _D()

    monkeypatch.setattr(mdt_mod, "get_structured_llm", lambda *a, **k: _Fake())
    monkeypatch.setattr(dept_mod, "list_departments", lambda: ["口腔科", "内分泌科"])
    out = asyncio.run(mdt_mod.pick_departments("右下后牙自发痛 3 天，既往 2 型糖尿病"))
    prompt = seen[0]
    # 规则一：分科理由必出（带图优先引用影像所见）
    assert "必须输出分科理由" in prompt and "影像所见" in prompt
    # 规则二：无明确指向最少 2 科（宁多勿漏），仅明确影像急症指征（靶征/游离气体）允许单科
    assert "最少召 2 科" in prompt and "宁多勿漏" in prompt
    assert "靶征" in prompt and "游离气体" in prompt
    # reason 字段解析透出
    assert out["reason"] == "右下后牙自发痛考虑牙源性感染，血糖异常需内分泌协同评估"
    assert out["reasoning"] == "牙痛+血糖管理"


def test_pick_departments_reason_falls_back_to_reasoning(monkeypatch):
    """T1 向后兼容：旧 mock 对象（仅 departments/reasoning 属性）→ reason 回退 reasoning，
    既有 pick_departments 调用方零破坏（schema 加字段不重排既有断言）。"""
    seen = []
    _patch_structured(monkeypatch, seen, ["口腔科"], reasoning="旧形态理由")
    monkeypatch.setattr(dept_mod, "list_departments", lambda: ["口腔科"])
    out = asyncio.run(mdt_mod.pick_departments("右下后牙自发痛 3 天"))
    assert out["departments"] == ["口腔科"]
    assert out["reasoning"] == "旧形态理由"
    assert out["reason"] == "旧形态理由"  # 回退 reasoning（分科理由不缺位）


def test_consult_create_stores_dept_reason(monkeypatch, tmp_path):
    """T1 reason 入库：pick 返回的 reason（分科理由）经 consults.create 随 team 存进
    会诊单（路由响应 team.reason 可见）；旧形态 team（无 reason 键）→ 存空串向后兼容。"""
    c = _isolate(monkeypatch, tmp_path)
    doc1 = _login(c, "doctor01")

    async def fake_pick(question, images=None):
        return {"departments": ["口腔科", "内分泌科"], "reasoning": "按病例需要",
                "reason": "牙源性感染主科+内分泌鉴别，宁多勿漏"}

    async def fake_briefs(question, depts, images=None):
        return {d: f"{d}定向意见" for d in depts}

    monkeypatch.setattr(mdt_mod, "pick_departments", fake_pick)
    monkeypatch.setattr(mdt_mod, "dept_briefs", fake_briefs)
    r = c.post("/api/v1/medical/consults", headers=_h(doc1),
               json={"question": "右下后牙自发痛 3 天，既往 2 型糖尿病"})
    assert r.status_code == 200, r.text
    assert r.json()["team"]["reason"] == "牙源性感染主科+内分泌鉴别，宁多勿漏"
    # 旧形态（team 无 reason 键）向后兼容：core 层存空串，不抛错
    cid = consults_mod.create(initiator="doctor01", question="q", ai_analysis="a",
                              target_depts=["口腔科"],
                              team={"departments": ["口腔科"], "reasoning": "r"})
    assert consults_mod.get(cid)["team"]["reason"] == ""


def test_consult_reason_frontend_markers_present():
    """T1 前端标记锁：「我的会诊」卡「受邀科室」旁渲染「AI 分科依据：<reason>」
    （灰色小字 .src；旧会诊单无 team.reason 字段 → v-if 不渲染，读取容错）。"""
    from tests.test_frontend_syntax import _vue_source
    html = _vue_source()
    for marker in (
        "function deptReason(",
        'v-if="deptReason(c)"',
        "AI 分科依据：{{ deptReason(c) }}",
        'data-testid="consult-dept-reason"',
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    fn = html[html.index("function deptReason("):]
    fn = fn[:fn.index("\n}", 10)]
    # 读取容错：必须走 c.team?.reason 式的安全取值（旧单无字段返回空串而非抛错）
    assert "c.team" in fn and "reason" in fn, "deptReason 必须从容错读取 team.reason"
