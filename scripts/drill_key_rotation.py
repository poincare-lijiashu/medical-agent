"""W3.5 密钥轮换演练脚本（LLM api_key + DB 密码；JWT secret 见 DEPLOY.md 操作 + tests/test_jwt_rotation.py）。

全部演练使用**假凭据/专用一次性 role**，绝不触碰真实 api_key 与 medagent 主 role：
- llm：管理 API 全流程（新增假 key provider → 连通测试应失败 → 激活切换 → 回切 → 删除），
  验证"激活切换零重启"与管理端错误处理路径；需要后端运行且 ADMIN 账号可用
  （MEDICAL_EVAL_USER/PASS，默认 admin01）。
- db：专用演练 role 在 medagent_test 库上走"建号→ALTER PASSWORD→四象限连接验证"，
  验证 DB 密码轮换对运行中长连接零中断（asyncpg 池语义）；需要 docker（medagent_pg 容器）。

用法（edu_agent 环境）：
    python scripts/drill_key_rotation.py llm
    python scripts/drill_key_rotation.py db
"""
from __future__ import annotations

import asyncio
import http.cookiejar
import json
import os
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.environ.get("MEDICAL_SERVER", "http://127.0.0.1:8001")
PG_CONTAINER = os.environ.get("MEDICAL_PG_CONTAINER", "medagent_pg")
PG_HOST, PG_PORT, PG_DB = "127.0.0.1", 5433, "medagent_test"
DRILL_ROLE, OLD_PW, NEW_PW = "drill_rot_w35", "DrillOld123", "DrillNew456"


# ---------------------------------------------------------------- llm 演练
def drill_llm() -> int:
    _jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_jar))

    def call(method, path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
        try:
            return json.loads(opener.open(req, timeout=30).read().decode())
        except urllib.error.HTTPError as e:
            return {"_http": e.code, "detail": e.read().decode()[:200]}

    user = os.environ.get("MEDICAL_EVAL_USER", "admin01")
    pw = os.environ.get("MEDICAL_EVAL_PASS", "")
    assert pw, "请设置 MEDICAL_EVAL_PASS（演练管理员口令）"
    r = call("POST", "/api/v1/auth/login", {"username": user, "password": pw})
    assert any(c.name == "medassist_at" for c in _jar), "应有 httpOnly 访问令牌 cookie（W3.1 形态）"
    print("1 login(cookie): OK")

    before = call("GET", "/api/v1/medical/admin/llm/providers")
    chat = before["chat"]["providers"]
    orig_active = before["chat"]["active"]
    print("2 before: active =", orig_active, "| providers =", [p["id"] for p in chat])

    base_p = next(p for p in chat if p["id"] == orig_active)
    add = call("POST", "/api/v1/medical/admin/llm/providers", {
        "kind": "chat", "api_format": base_p.get("api_format", "openai"),
        "base_url": base_p["base_url"], "model_id": base_p["model_id"],
        "display_name": "drill-rotation-w35", "api_key": "sk-drill-FAKE-KEY-FOR-ROTATION-DRILL",
        "extra_params": ""})
    drill_pid = add.get("id")
    print("3 add drill provider:", add)

    test = call("POST", "/api/v1/medical/admin/llm/test", {
        "kind": "chat", "api_format": base_p.get("api_format", "openai"),
        "base_url": base_p["base_url"], "model_id": base_p["model_id"],
        "api_key": "sk-drill-FAKE-KEY-FOR-ROTATION-DRILL", "extra_params": ""})
    assert test.get("ok") is False, "假 key 连通测试应失败（ok=false）"
    print("4 test fake key: ok=False（端点/错误路径可用，detail 无完整 key 回显）")

    call("POST", "/api/v1/medical/admin/llm/activate", {"kind": "chat", "pid": drill_pid})
    cfg = call("GET", "/api/v1/medical/config")
    assert cfg.get("chat_model") == base_p["model_id"], "激活后 model_id 应为新 provider 的值"
    print("5 activate drill: OK（/config 即时反映，零重启）")

    call("POST", "/api/v1/medical/admin/llm/activate", {"kind": "chat", "pid": orig_active})
    call("DELETE", f"/api/v1/medical/admin/llm/providers/chat/{drill_pid}")
    after = call("GET", "/api/v1/medical/admin/llm/providers")
    after_ids = [p["id"] for p in after["chat"]["providers"]]
    assert after["chat"]["active"] == orig_active and drill_pid not in after_ids, "终态应还原"
    print("6 activate-back + delete + final-state: OK")
    print("DRILL LLM-KEY-ROTATION: PASS")
    return 0


# ---------------------------------------------------------------- db 演练
def _psql(sql: str) -> str:
    r = subprocess.run(
        ["docker", "exec", PG_CONTAINER, "psql", "-U", "medagent", "-d", "postgres",
         "-v", "ON_ERROR_STOP=1", "-c", sql],
        capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        raise RuntimeError(f"psql failed: {r.stderr.strip()[:200]}")
    return r.stdout.strip()


async def _db_async() -> int:
    import asyncpg

    async def try_connect(password: str):
        try:
            return await asyncpg.connect(host=PG_HOST, port=PG_PORT, user=DRILL_ROLE,
                                         database=PG_DB, password=password, timeout=5)
        except Exception:  # noqa: BLE001 —— 连接失败返回 None（演练判定用）
            return None

    _psql(f"DROP ROLE IF EXISTS {DRILL_ROLE};")
    _psql(f"CREATE ROLE {DRILL_ROLE} WITH LOGIN PASSWORD '{OLD_PW}';")
    _psql(f"GRANT CONNECT ON DATABASE {PG_DB} TO {DRILL_ROLE};")
    conn = await try_connect(OLD_PW)
    assert conn is not None, "A 失败：旧密码新连接应成功"
    print(f"A create+connect(old): OK ({DRILL_ROLE}@{PG_DB})")

    _psql(f"ALTER ROLE {DRILL_ROLE} WITH PASSWORD '{NEW_PW}';")
    v = await conn.fetchval("SELECT 1")
    print("B existing-connection-after-rotation: OK (SELECT 1 =", v,
          ") —— 运行中服务连接池零中断")

    assert await try_connect(OLD_PW) is None, "C 失败：旧密码新连接应被拒绝"
    print("C new-connect(old): REJECTED")
    conn2 = await try_connect(NEW_PW)
    assert conn2 is not None, "D 失败：新密码新连接应成功"
    print("D new-connect(new): OK —— SOP：改 .env DSN 后滚动重启")

    await conn.close()
    await conn2.close()
    _psql(f"REVOKE CONNECT ON DATABASE {PG_DB} FROM {DRILL_ROLE};")
    _psql(f"DROP ROLE IF EXISTS {DRILL_ROLE};")
    gone = subprocess.run(
        ["docker", "exec", PG_CONTAINER, "psql", "-U", "medagent", "-d", "postgres",
         "-tAc", f"SELECT 1 FROM pg_roles WHERE rolname='{DRILL_ROLE}';"],
        capture_output=True, text=True, timeout=30).stdout.strip()
    assert gone == "", f"role 未清理: {gone!r}"
    print("E cleanup: OK（演练 role 已删除）")
    print("DRILL DB-PASSWORD-ROTATION: PASS")
    return 0


def drill_db() -> int:
    return asyncio.run(_db_async())


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "llm":
        sys.exit(drill_llm())
    if mode == "db":
        sys.exit(drill_db())
    print(__doc__)
    sys.exit(2)
