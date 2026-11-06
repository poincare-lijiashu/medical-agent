"""共享 fixtures：每个测试前后清理进程级鉴权状态（令牌撤销表/限流窗口）。"""
import os

# 必须在任何 backend import 之前：锁定 seed 演示口令，保证既有测试硬编码 Med@2026 不因 A5 随机化而破坏
os.environ.setdefault("AUTH_DEMO_PASSWORD", "Med@2026")
# W3.1：测试消费方依赖响应体回带令牌（40+ 处既有断言），此处显式开启；
# 生产环境不设置该变量 → body 恒空，令牌只经 httpOnly cookie 下发
os.environ.setdefault("AUTH_TOKEN_IN_BODY", "true")
# 测试进程强制内存后端：本机 .env.local 可能配置 REDIS_URL（二级形态实测时加入），
# 且 redis 库已安装会让测试进程真的连上共享 Redis——chat_memory/撤销等测试要求
# 进程级隔离语义。环境变量优先级高于 .env.local，置空即锁回 MemoryStore。
os.environ["REDIS_URL"] = ""
# 评审 M-28：快照调度器默认在启动时补当天快照；测试进程关闭，避免
# TestClient 上下文把"今天已有快照"带进用例（调度逻辑由专门单测覆盖）。
os.environ.setdefault("AGENT_SNAPSHOT_HOUR", "0")
# W4.1 事故隔离（2026-10-30）：pytest 集成测试进程连的是真 PG（.env.local 的
# medagent 库），全量同步 save_* 会用测试数据覆盖生产表（实测 users 17 行被清成
# 1 行 → 全站 401）。独立测试库 medagent_test（与服务库物理隔离，表结构由
# pg_store.init() 的 CREATE TABLE IF NOT EXISTS 自动建齐）+ CI 同步建库步骤。
if not os.environ.get("PG_OFFLINE"):
    os.environ["PG_DATABASE"] = "medagent_test"

import pytest

from backend.api import deps
from backend.core import pg_store as _pg_store
from backend.core import security_rate, stores

# 评审 M-30：在 autouse fixture 替换之前捕获 Agent 域 PG 读写原始实现，
# 供 tests/test_agent_pg_real.py 做真库断言。
_PG_ORIGINALS = {
    "load_skills": _pg_store.load_skills,
    "save_skills": _pg_store.save_skills,
    "load_json_list": _pg_store.load_json_list,
    "save_json_list": _pg_store.save_json_list,
}


@pytest.fixture(autouse=True)
def _clean_auth_state():
    deps._revoked_before.clear()
    security_rate.reset()
    stores.reset_store()  # 轮 B2：重建共享 store 单例（撤销/会话键不跨测试残留）
    # 评审 M-45：/metrics 有 5s 进程内缓存，测试间必须清空，否则读到上一用例的 exposition
    from backend import main as _main
    _main._metrics_cache.update(at=0.0, text="")
    yield
    deps._revoked_before.clear()


@pytest.fixture(autouse=True)
def _isolate_pg_runtime():
    """评测修复（2026-10-30）：重置 pg_store 进程级运行时状态，杜绝跨测试顺序污染。

    背景：全量跑时 test_pg_repo 的 16 项假池写入断言漂移，而单文件/组合跑全绿——
    根因是专用 DB 循环（_db_loop）与 stale 集合被前序测试（init/close 流程）留在
    不一致状态，后续假池协程提交后静默超时（3s 桥接上限）而走 JSON 分支。
    teardown 统一复位（在 monkeypatch 还原之后执行，不会与各测试自己的 fixture 冲突）。"""
    yield
    from backend.core import pg_store
    pg_store._db_loop = None
    pg_store._pool = None
    pg_store._repo_pg_stale.clear()
    pg_store._stale_warned.clear()


@pytest.fixture(autouse=True)
def _isolate_runtime_flags(tmp_path, monkeypatch):
    """任务3：每个测试独立的 runtime_flags 文件——测试进程绝不读写真实
    data/runtime_flags.json（服务端 admin 运行时切换值不泄漏进测试，
    测试中的 set_flag 也不污染真实运行配置）。文件默认不存在 → 全部回落 settings。"""
    from backend.core import runtime_flags
    monkeypatch.setattr(runtime_flags, "FLAGS_FILE", str(tmp_path / "runtime_flags.json"))
    yield


@pytest.fixture()
def pg_originals() -> dict:
    """Agent 域 PG 读写函数的**原始实现**（在被 `_isolate_agent_stores` 替换之前捕获）。

    仅供需要真库断言的测试还原使用；普通测试不要用，否则会读写共享测试库。
    """
    return dict(_PG_ORIGINALS)


@pytest.fixture(autouse=True)
def _isolate_agent_stores(tmp_path, monkeypatch):
    """G86–G89：Agent 域（技能卡/Bot/团队/记忆）在测试中强制 JSON 存储。

    背景：这些域是 PG 真源 + JSON 兜底；若前序测试初始化过 PG 池，后续测试会读到
    ``medagent_test`` 里残留的行，导致"单跑通过、全量跑冲突"。这里把域存储锁定到
    tmp_path 的 JSON 文件，杜绝跨测试污染。
    """
    from backend.agents import (bot_memory, bot_teams, bots, skill_advice, skill_library,
                                skill_trends, skills, sleep_memory)
    from backend.core import pg_store

    monkeypatch.setattr(skills, "SKILLS_FILE", str(tmp_path / "agent_skills.json"))
    monkeypatch.setattr(bots, "BOTS_FILE", str(tmp_path / "agent_bots.json"))
    monkeypatch.setattr(bot_teams, "TEAMS_FILE", str(tmp_path / "bot_teams.json"))
    monkeypatch.setattr(bot_memory, "MEMORY_FILE", str(tmp_path / "bot_memories.json"))
    monkeypatch.setattr(sleep_memory, "REPORT_FILE", str(tmp_path / "sleep_reports.json"))
    monkeypatch.setattr(skill_library, "EXPERIENCES_FILE",
                        str(tmp_path / "skill_experiences.json"))
    monkeypatch.setattr(skill_library, "PROPOSALS_FILE",
                        str(tmp_path / "skill_proposals.json"))
    monkeypatch.setattr(skill_trends, "SNAPSHOT_FILE",
                        str(tmp_path / "skill_metrics_snapshots.json"))
    monkeypatch.setattr(skill_advice, "ADVICE_FILE", str(tmp_path / "skill_advices.json"))

    def _json_only(json_loader, default):
        return json_loader() if json_loader else default

    monkeypatch.setattr(pg_store, "load_skills",
                        lambda path, json_loader=None: _json_only(json_loader, []))
    monkeypatch.setattr(pg_store, "save_skills",
                        lambda items, path: pg_store._write_json_atomic(path, items))
    monkeypatch.setattr(pg_store, "load_json_list",
                        lambda table, path, json_loader=None: _json_only(json_loader, []))
    monkeypatch.setattr(pg_store, "save_json_list",
                        lambda table, path, items: pg_store._write_json_atomic(path, items))
    yield


@pytest.fixture(autouse=True)
def _isolate_case_archive(tmp_path, monkeypatch):
    """阶段4：每个测试独立的病例归档文件——qc approve 挂点在既有路由测试中触发归档时，
    绝不读写真实 data/case_archive.json（防测试数据污染真源）。文件默认不存在 → 空库。"""
    from backend.core import case_archive
    monkeypatch.setattr(case_archive, "CASE_ARCHIVE_FILE",
                        str(tmp_path / "case_archive.json"))
    yield


@pytest.fixture(autouse=True)
def _isolate_users_file(tmp_path, monkeypatch):
    """用户表隔离（2026-11-06 评审 M-29 整改）：此前 conftest 未覆盖 `auth.USERS_FILE`，
    凡调用 `seed_default_users()` / `create_user()` / `delete_user()` 而未自行 monkeypatch
    的测试都会读写真实 data/auth/users.json。

    实测事故：`tests/test_row_level_users.py` 单跑一次即把生产用户表清成 `{}`，
    使 4 个演示账号全部失效——与 2026-10-30「users 表被清成 1 行 → 全站 401」同型复发。
    本 fixture 与 runtime_flags / case_archive 同模式：默认指向 tmp_path 的空表，
    需要登录的测试须在自身 fixture 里显式 `seed_default_users()`（与其余用例同一纪律）。
    """
    from backend.core import auth
    monkeypatch.setattr(auth, "USERS_FILE", str(tmp_path / "users.json"))
    yield


@pytest.fixture(autouse=True)
def _isolate_review_queue_and_audit(tmp_path, monkeypatch):
    """隔离残余缺口根治（2026-10-30 收尾）：审核队列与审计文件此前无全局隔离——
    未显式 monkeypatch 的测试会写真实 data/review/queue.json 与 data/audit/audit.jsonl。

    实测症状：两个 pytest 进程并行时，Windows 对同一 queue.json 的 os.replace
    报 PermissionError（WinError 32），表现为"单跑通过、并发偶发失败"。
    本 fixture 与 runtime_flags / case_archive 同模式：默认指向 tmp_path，
    测试自身 monkeypatch 会再覆盖（顺序在 autouse 之后，语义不变）。
    """
    from backend.core import medical_review
    monkeypatch.setattr(medical_review, "QUEUE_FILE", str(tmp_path / "review_queue.json"))
    # 审计：全局单例的落盘路径改到 tmp（entries 内存缓冲不变，路径仅决定新增写入位置）
    from backend.core import medical_audit
    monkeypatch.setattr(medical_audit._log, "path", str(tmp_path / "audit.jsonl"))
    yield
