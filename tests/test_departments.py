"""科室字典与 admin 路由：默认文件首次自动创建、重名 400、删除 404、中文 URL 编码往返。

departments.json 与 users.json 均隔离到 tmp_path，不污染真实数据。
"""
from urllib.parse import quote

from fastapi.testclient import TestClient

from backend.core import auth as auth_mod
from backend.core import departments as dept_mod
from backend.core.auth import seed_default_users
from backend.main import app


def _isolate(monkeypatch, tmp_path):
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(tmp_path / "departments.json"))
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    return TestClient(app)


def _login(c, u, p):
    r = c.post("/api/v1/auth/login", json={"username": u, "password": p})
    assert r.status_code == 200, r.text
    return r.json()["access_token"]


def _h(t):
    return {"Authorization": "Bearer " + t}


# ---- core：默认创建 / add 重名 / remove 存在校验 ----

def test_first_list_creates_default_file(monkeypatch, tmp_path):
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(tmp_path / "departments.json"))
    assert not (tmp_path / "departments.json").exists()
    lst = dept_mod.list_departments()
    for name in ("内科", "外科", "口腔科", "妇产科", "儿科", "急诊科"):
        assert name in lst
    assert len(lst) == 6
    on_disk = (tmp_path / "departments.json").read_text(encoding="utf-8")
    assert on_disk  # 首次访问自动创建默认文件
    import json
    assert json.loads(on_disk)["list"] == lst


def test_add_department_and_duplicate(monkeypatch, tmp_path):
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(tmp_path / "departments.json"))
    assert dept_mod.add_department("康复科") is True
    assert dept_mod.add_department("康复科") is False  # 重名
    assert "康复科" in dept_mod.list_departments()
    assert len(dept_mod.list_departments()) == 7


def test_add_department_empty_name_raises(monkeypatch, tmp_path):
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(tmp_path / "departments.json"))
    import pytest
    with pytest.raises(ValueError):
        dept_mod.add_department("   ")


def test_remove_department_requires_existing(monkeypatch, tmp_path):
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(tmp_path / "departments.json"))
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))  # 隔离在用校验依赖的用户表
    seed_default_users()
    assert dept_mod.remove_department("内科") is True
    assert dept_mod.remove_department("内科") is False  # 已不存在
    assert "内科" not in dept_mod.list_departments()


def test_remove_department_in_use_by_account(monkeypatch, tmp_path):
    """有账号绑定的科室删除被拒（ValueError→路由400）；账号删除后可删除。"""
    import pytest
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(tmp_path / "departments.json"))
    monkeypatch.setattr(auth_mod, "USERS_FILE", str(tmp_path / "users.json"))
    seed_default_users()
    with pytest.raises(ValueError):
        dept_mod.remove_department("口腔科")  # doctor01 使用中
    assert "口腔科" in dept_mod.list_departments()
    assert auth_mod.delete_user("doctor01") is True  # 使用方消失后即可删除
    assert dept_mod.remove_department("口腔科") is True


# ---- admin 路由：门禁 + 重名 400 + 中文 URL 编码 ----

def test_admin_departments_routes_roundtrip(monkeypatch, tmp_path):
    c = _isolate(monkeypatch, tmp_path)
    adm = _login(c, "admin01", "Med@2026")
    H = _h(adm)
    r = c.get("/api/v1/medical/admin/departments", headers=H)
    assert r.status_code == 200, r.text
    assert "口腔科" in r.json()["list"]
    # 新增
    assert c.post("/api/v1/medical/admin/departments", headers=H,
                  json={"name": "康复科"}).status_code == 200
    # 重名 400
    assert c.post("/api/v1/medical/admin/departments", headers=H,
                  json={"name": "康复科"}).status_code == 400
    # 删除被账号使用的科室 → 400（在用保护：doctor01 的 dept=口腔科）
    r = c.delete("/api/v1/medical/admin/departments/" + quote("口腔科"), headers=H)
    assert r.status_code == 400, r.text
    assert "仍有账号使用" in r.json()["detail"]
    # 中文名 URL 编码删除未被使用的科室（前端 encodeURIComponent 场景）
    r = c.delete("/api/v1/medical/admin/departments/" + quote("康复科"), headers=H)
    assert r.status_code == 200, r.text
    assert "康复科" not in c.get("/api/v1/medical/admin/departments", headers=H).json()["list"]
    # 删除不存在 → 404
    assert c.delete("/api/v1/medical/admin/departments/" + quote("康复科"),
                    headers=H).status_code == 404


def test_department_routes_require_admin(monkeypatch, tmp_path):
    c = _isolate(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")
    H = _h(doc)
    assert c.get("/api/v1/medical/admin/departments", headers=H).status_code == 403
    assert c.post("/api/v1/medical/admin/departments", headers=H,
                  json={"name": "x"}).status_code == 403
    assert c.delete("/api/v1/medical/admin/departments/内科", headers=H).status_code == 403


# ---- 整改轮 B 任务3：职能部门 + 科室-角色归属校验 ----

def test_functional_departments_ensure_idempotent(monkeypatch, tmp_path):
    """职能部门兜底入库（服务启动时调用）：首次全量补齐、再次调用幂等（不重复新增）。"""
    monkeypatch.setattr(dept_mod, "DEPARTMENTS_FILE", str(tmp_path / "departments.json"))
    added = dept_mod.ensure_functional_departments()
    assert set(added) == set(dept_mod.FUNCTIONAL_DEPARTMENTS), "缺哪个补哪个（首次全量）"
    lst = dept_mod.list_departments()
    assert set(dept_mod.FUNCTIONAL_DEPARTMENTS) <= set(lst)
    assert dept_mod.ensure_functional_departments() == [], "幂等：已存在不再新增"
    assert lst.count("药剂科") == 1 and lst.count("病案室") == 1, "无重复条目"


def test_validate_role_dept_matrix():
    """科室-角色归属校验纯函数矩阵（整改轮 B 任务3）：合规放行 / 违规 ValueError 中文文案。"""
    import pytest
    # pharmacist → 必须药剂科
    dept_mod.validate_role_dept("pharmacist", "药剂科")
    with pytest.raises(ValueError, match="药剂科"):
        dept_mod.validate_role_dept("pharmacist", "内科")
    with pytest.raises(ValueError, match="药剂科"):
        dept_mod.validate_role_dept("pharmacist", "")
    # qc → ∈ {质控科, 医务处}
    dept_mod.validate_role_dept("qc", "质控科")
    dept_mod.validate_role_dept("qc", "医务处")
    with pytest.raises(ValueError, match="质控科"):
        dept_mod.validate_role_dept("qc", "药剂科")
    # doctor → 禁职能部门（临床科室）
    dept_mod.validate_role_dept("doctor", "内科")
    dept_mod.validate_role_dept("doctor", "")
    for f in dept_mod.FUNCTIONAL_DEPARTMENTS:
        with pytest.raises(ValueError, match="临床科室"):
            dept_mod.validate_role_dept("doctor", f)
    # admin → 必须医务处（F4 收口：管理岗归属医务处）
    dept_mod.validate_role_dept("admin", "医务处")
    with pytest.raises(ValueError, match="admin 账号应归属医务处"):
        dept_mod.validate_role_dept("admin", "病案室")
    with pytest.raises(ValueError, match="admin 账号应归属医务处"):
        dept_mod.validate_role_dept("admin", "")
