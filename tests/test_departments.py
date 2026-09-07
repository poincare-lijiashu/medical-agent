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
    assert dept_mod.remove_department("内科") is True
    assert dept_mod.remove_department("内科") is False  # 已不存在
    assert "内科" not in dept_mod.list_departments()


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
    # 中文名 URL 编码删除（前端 encodeURIComponent 场景）
    r = c.delete("/api/v1/medical/admin/departments/" + quote("口腔科"), headers=H)
    assert r.status_code == 200, r.text
    assert "口腔科" not in c.get("/api/v1/medical/admin/departments", headers=H).json()["list"]
    # 删除不存在 → 404
    assert c.delete("/api/v1/medical/admin/departments/" + quote("口腔科"),
                    headers=H).status_code == 404


def test_department_routes_require_admin(monkeypatch, tmp_path):
    c = _isolate(monkeypatch, tmp_path)
    doc = _login(c, "doctor01", "Med@2026")
    H = _h(doc)
    assert c.get("/api/v1/medical/admin/departments", headers=H).status_code == 403
    assert c.post("/api/v1/medical/admin/departments", headers=H,
                  json={"name": "x"}).status_code == 403
    assert c.delete("/api/v1/medical/admin/departments/内科", headers=H).status_code == 403
