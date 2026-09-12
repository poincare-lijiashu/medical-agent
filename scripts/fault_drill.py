"""故障注入演练脚本（对抗轮 A2）：真实破坏 → 探活 → 恢复 → 复验，全程自动回原状。

用法：
    python scripts/fault_drill.py --check                 # 只验证当前健康基线（零副作用）
    python scripts/fault_drill.py --scenario pg --yes     # 停 PG → 探活(JSON 兜底) → 恢复 → 数据完整性
    python scripts/fault_drill.py --scenario milvus --yes # 停 Milvus → 检索降级语义 → 恢复 → 检索复通
    python scripts/fault_drill.py --scenario llm --yes    # admin API 置无效 LLM key → 降级语义 → 恢复

共同探活面（每场景停机窗口内全部执行）：
    /healthz 必 200；/drug/dict 或 /literature/ask 降级语义不 500；/prescriptions/mine 可用。
破坏性操作（docker stop / 激活无效 key）必须 --scenario 显式指定 + --yes 确认；
任何步骤失败仍走 finally 恢复，恢复后复验探针通过才算场景 PASS。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time

import httpx

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

BASE_DEFAULT = "http://127.0.0.1:8001"
PG_CONTAINER = "edu_agent_postgres"
MILVUS_CONTAINER = "edu_agent_milvus"  # docker ps 实测确认（2026-09-12）
LIT_QUESTION = "高血压患者长期用药有哪些注意事项？"
_rx_rows: list[dict] = []  # 场景行收集器（打印 + 退出码判定）


def row(name: str, expect: str, actual: str, ok: bool) -> None:
    """收集并即时打印一行探活/操作结果（演练记录直接取自本输出）。"""
    mark = "PASS" if ok else "FAIL"
    _rx_rows.append((name, expect, actual, ok))
    print(f"  [{mark}] {name:<38} 期望:{expect:<28} 实际:{actual}")


def docker(*args: str) -> tuple[int, str]:
    """执行 docker CLI，返回 (rc, 合并输出)。"""
    r = subprocess.run(["docker", *args], capture_output=True, text=True,
                       timeout=180, encoding="utf-8", errors="replace")
    return r.returncode, ((r.stdout or "") + (r.stderr or "")).strip()


def wait_container_healthy(name: str, timeout_s: int = 150) -> tuple[bool, str]:
    """轮询容器 Health.Status 直至 healthy（PG/Milvus 均带 HEALTHCHECK）。"""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        rc, out = docker("inspect", "-f", "{{.State.Health.Status}}", name)
        if rc == 0 and out.strip() == "healthy":
            return True, "healthy"
        time.sleep(3)
    return False, f"timeout({timeout_s}s)"


def login(client: httpx.Client, username: str, password: str) -> str:
    r = client.post("/api/v1/auth/login",
                    json={"username": username, "password": password}, timeout=30)
    if r.status_code != 200:
        raise RuntimeError(f"登录失败 {username}: {r.status_code} {r.text[:120]}")
    return r.json()["access_token"]


def tok(t: str) -> dict:
    return {"Authorization": "Bearer " + t}


def probe_lit_ask(client: httpx.Client, t_doc: str, timeout: int = 600) -> dict:
    """literature ask 探针：返回 {status, degraded, detail}。
    降级判定：fallback:/degraded: 前缀、空来源、或 conf<0.6（正常答=有出处+高置信+不需复核）。"""
    try:
        # 长超时：停机窗口降级链路慢（见 DR 发现：stale gRPC 通道首个 RPC 挂起数分钟）
        r = client.post("/api/v1/medical/literature/ask", headers=tok(t_doc),
                        json={"question": LIT_QUESTION}, timeout=timeout)
    except Exception as e:  # noqa: BLE001 —— 网络/超时本身即故障证据
        return {"status": 0, "degraded": True, "detail": f"请求异常:{type(e).__name__}"}
    if r.status_code != 200:
        return {"status": r.status_code, "degraded": True,
                "detail": f"HTTP{r.status_code}(裸错误)"}
    try:
        d = r.json()
    except Exception:  # noqa: BLE001
        return {"status": 200, "degraded": True, "detail": "非 JSON 裸文本"}
    srcs = [str(s) for s in (d.get("sources") or [])]
    fb = any(s.startswith(("fallback:", "degraded:")) for s in srcs)
    degraded = fb or (not srcs) or float(d.get("confidence") or 0) < 0.6
    return {"status": 200, "degraded": degraded,
            "detail": f"src={srcs[:1]} conf={d.get('confidence')} "
                      f"review={d.get('needs_human_review')}"}


def milvus_search_ready(timeout_s: int = 60) -> bool:
    """Milvus 真检索探针（直连）：embedding anns_field 搜索成功即检索面恢复。"""
    try:
        from pymilvus import MilvusClient
        c = MilvusClient(uri="http://127.0.0.1:19531", token="root:Milvus",
                         timeout=max(15, timeout_s // 3))
        c.search(collection_name="medical_kb", data=[[0.0] * 1024],
                 anns_field="embedding", limit=1, output_fields=["source_name"])
        return True
    except Exception:  # noqa: BLE001
        return False


def milvus_full_stack_restart() -> None:
    """DR 处置（Milvus v2.4.0 standalone 实测缺陷）：graceful stop→start 后 load 任务
    卡死 0%（检索报 503 channel not subscribed，反复重启单容器无效）。处置：
    全栈干净重启（etcd/minio 稳定后再拉 milvus）清除卡死任务 → 重新触发 collection
    load（服务端补齐，客户端超时可能早于完成）→ 直连 search 探针就绪。"""
    docker("stop", "edu_agent_milvus", "edu_agent_etcd", "edu_agent_minio")
    time.sleep(15)
    docker("start", "edu_agent_etcd", "edu_agent_minio")
    time.sleep(45)
    docker("start", "edu_agent_milvus")
    time.sleep(120)
    healthy, _hs = wait_container_healthy("edu_agent_milvus", timeout_s=120)
    if not healthy:
        return
    try:  # 重新触发加载（服务器端补齐；客户端超时不代表失败，以 search 探针为准）
        from pymilvus import MilvusClient
        c = MilvusClient(uri="http://127.0.0.1:19531", token="root:Milvus", timeout=30)
        try:
            c.load_collection("medical_kb", timeout=120)
        except Exception:  # noqa: BLE001 —— 加载可能仍在服务端进行
            pass
    except Exception:  # noqa: BLE001
        pass
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline and not milvus_search_ready():
        time.sleep(15)

# ---------------- 场景①：PG 停机（JSON 兜底读写 + 数据完整性） ----------------

def scenario_pg(client: httpx.Client, t_doc: str) -> None:
    pg = PG_CONTAINER
    rid = None
    dict_r = client.get("/api/v1/medical/drug/dict", headers=tok(t_doc), timeout=30)
    drug_name = (dict_r.json().get("drugs") or [{}])[0].get("name", "华法林")
    mine0 = client.get("/api/v1/medical/prescriptions/mine",
                       headers=tok(t_doc), timeout=30).json()["items"]
    base_ids = [i["id"] for i in mine0]
    n0 = len(base_ids)
    # PG 全表基线（全医生总数，与个人 mine 计数口径不同）：验证探针写不进 PG 真源
    _rc, _out = docker("exec", pg, "psql", "-U", "medagent", "-d", "medagent",
                       "-tAc", "SELECT count(*) FROM prescriptions")
    try:
        pg_count0 = int((_out or "").strip().splitlines()[-1])
    except Exception:  # noqa: BLE001
        pg_count0 = -1
    print(f"  基线：/prescriptions/mine（doctor01）共 {n0} 条；PG 全表 {pg_count0} 条；"
          f"探针药名={drug_name}")

    try:
        rc, out = docker("stop", pg)
        row("docker stop postgres", "rc=0", f"rc={rc} {out[-60:]}", rc == 0)

        r = client.get("/healthz", timeout=30)
        row("停机·healthz", "200 ok", f"{r.status_code} {r.text[:40]}", r.status_code == 200)
        r = client.get("/api/v1/medical/drug/dict", headers=tok(t_doc), timeout=60)
        ok = r.status_code == 200 and "disclaimer" in r.json()
        row("停机·drug/dict(JSON 兜底)", "200 非空字典", f"{r.status_code} "
            f"drugs={len(r.json().get('drugs', [])) if r.status_code == 200 else 'N/A'}", ok)
        r = client.get("/api/v1/medical/prescriptions/mine", headers=tok(t_doc), timeout=60)
        row("停机·prescriptions/mine", "200", str(r.status_code), r.status_code == 200)
        r = client.post("/api/v1/medical/prescriptions", headers=tok(t_doc), timeout=120,
                        json={"case_text": "故障演练探针：PG 停机窗口内 JSON 兜底写验证用例。",
                              "drugs": [{"name": drug_name, "dose": "1mg", "freq": "qd"}]})
        rid = r.json().get("id") if r.status_code == 200 else None
        row("停机·prescriptions 提交(JSON 兜底写)", "200 不 500",
            f"{r.status_code} id={rid or r.text[:60]}", r.status_code == 200)
        r = client.get("/api/v1/medical/prescriptions/mine", headers=tok(t_doc), timeout=60)
        n_out = len(r.json()["items"]) if r.status_code == 200 else -1
        row("停机·写后计数", f"{n0 + 1}", str(n_out), n_out == n0 + 1)

        rc, out = docker("start", pg)
        row("docker start postgres", "rc=0", f"rc={rc} {out[-60:]}", rc == 0)
        healthy, hs = wait_container_healthy(pg)
        row("PG 容器恢复 healthy", "healthy", hs, healthy)
        time.sleep(2)  # 连接池自愈缓冲

        r = client.get("/healthz", timeout=30)
        row("恢复·healthz", "200", str(r.status_code), r.status_code == 200)
        r = client.get("/api/v1/medical/drug/dict", headers=tok(t_doc), timeout=60)
        row("恢复·drug/dict", "200", str(r.status_code), r.status_code == 200)
        mine2 = client.get("/api/v1/medical/prescriptions/mine",
                           headers=tok(t_doc), timeout=60).json()["items"]
        ids2 = {i["id"] for i in mine2}
        lost = [i for i in base_ids if i not in ids2]
        row("恢复·停机前数据无丢失", "0 条丢失", f"{len(lost)} 条丢失", not lost)
        row("恢复·停机期写入无丢失(探针在列)", f"{rid} 在列",
            "在列" if rid in ids2 else "探针丢失", rid in ids2)
        # PG 真源完好证据：停机期 JSON 兜底写未污染 PG（探针只落 JSON），存量完整
        rc, out = docker("exec", pg, "psql", "-U", "medagent", "-d", "medagent",
                         "-tAc", "SELECT count(*) FROM prescriptions")
        try:
            pg_count = int((out or "").strip().splitlines()[-1])
        except Exception:  # noqa: BLE001
            pg_count = -1
        row("恢复·PG 真源行数完好(探针未进PG)", str(pg_count0), str(pg_count),
            pg_count == pg_count0)
    finally:
        # 清理探针：从 JSON 兜底文件摘除演练探针（PG 内本就无此行），恢复原状
        if rid:
            _remove_rx_probe(rid)
            mine3 = client.get("/api/v1/medical/prescriptions/mine",
                               headers=tok(t_doc), timeout=60).json()["items"]
            ids3 = {i["id"] for i in mine3}
            row("清理·探针摘除后计数复原", str(n0), f"{len(ids3)} 探针残留={rid in ids3}",
                len(ids3) == n0 and rid not in ids3)


def _remove_rx_probe(rid: str) -> None:
    """从 data/prescriptions.json 原子摘除探针处方（与域层同原子写纪律）。
    恢复后该域读回落 JSON（stale 位由停机期写失败置位）→ 摘除即全真源生效。"""
    path = os.path.join(ROOT, "data", "prescriptions.json")
    if not os.path.isfile(path):
        return
    with open(path, "r", encoding="utf-8") as f:
        items = json.load(f)
    kept = [i for i in items if i.get("id") != rid]
    tmp = path + ".drill.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(kept, f, ensure_ascii=False)
    os.replace(tmp, path)


# ---------------- 场景②：Milvus 停机（检索降级语义） ----------------

def scenario_milvus(client: httpx.Client, t_doc: str) -> None:
    mv = MILVUS_CONTAINER
    base = probe_lit_ask(client, t_doc)
    row("基线·literature/ask", "200 非降级(KB 来源)",
        f"{base['status']} degraded={base['degraded']} {base['detail'][:60]}",
        base["status"] == 200 and not base["degraded"])
    try:
        rc, out = docker("stop", mv)
        row("docker stop milvus", "rc=0", f"rc={rc} {out[-60:]}", rc == 0)

        r = client.get("/healthz", timeout=30)
        row("停机·healthz", "200", str(r.status_code), r.status_code == 200)
        # 排空首请求并计时（DR 实测：加固前 stale gRPC 通道首个 RPC 挂起 ~7.5min；
        # 看门狗加固后：20s 超时 + 重建客户端失败即降级 → 停机响应必须 <30s）
        t0 = time.monotonic()
        drain = probe_lit_ask(client, t_doc, timeout=60)
        drain_s = time.monotonic() - t0
        print(f"  [排空] 首个停机 ask 返回：{drain['status']} degraded={drain['degraded']} "
              f"{drain['detail'][:60]} 耗时 {drain_s:.1f}s")
        row("停机·首个 ask 响应时间(看门狗加固)", "<30s（不再 7.5min 挂起）",
            f"{drain_s:.1f}s", drain_s < 30)
        d = probe_lit_ask(client, t_doc)
        row("停机·literature/ask 降级语义", "200 + 降级(fallback/空来源) 不 500",
            f"{d['status']} degraded={d['degraded']} {d['detail'][:60]}",
            d["status"] == 200 and d["degraded"])

        rc, out = docker("start", mv)
        row("docker start milvus", "rc=0", f"rc={rc} {out[-60:]}", rc == 0)
        healthy, hs = wait_container_healthy(mv)
        row("Milvus 容器恢复 healthy", "healthy", hs, healthy)

        back = {"status": 0, "degraded": True, "detail": "未探测"}
        for _ in range(2):  # 常规窗口：轮询直至检索来源复通
            back = probe_lit_ask(client, t_doc)
            if not back["degraded"]:
                break
            time.sleep(10)
        if back["degraded"]:
            # DR 处置（v2.4.0 standalone 实测：重启后 load 卡死 0%，检索 503
            # channel not subscribed）——全栈干净重启 + 重新加载 + search 就绪探针
            print("  [DR] 检索未复通（疑似 load 卡死），执行全栈干净重启处置…")
            milvus_full_stack_restart()
            for _ in range(3):
                back = probe_lit_ask(client, t_doc)
                if not back["degraded"]:
                    break
                time.sleep(10)
        row("恢复·检索复通(有出处高置信)", "200 非降级",
            f"{back['status']} degraded={back['degraded']} {back['detail'][:60]}",
            back["status"] == 200 and not back["degraded"])
        ok = milvus_search_ready()
        row("恢复·Milvus 直连 search 探针", "可搜索", "OK" if ok else "FAIL", ok)
    finally:
        rc, out = docker("start", mv)  # 兜底恢复（幂等：已启动则报错但不影响状态）
        print(f"  [finally] 兜底 docker start {mv}: rc={rc} {out[-40:]}")


# ---------------- 场景③：LLM key 失效（admin API 置换） ----------------

def scenario_llm(client: httpx.Client, t_doc: str, t_admin: str) -> None:
    pr = client.get("/api/v1/medical/admin/llm/providers",
                    headers=tok(t_admin), timeout=30).json()
    active_pid = (pr.get("chat") or {}).get("active")
    cur = next((p for p in (pr.get("chat") or {}).get("providers", [])
                if p.get("id") == active_pid), None)
    if not cur:
        row("定位激活 chat provider", "存在", f"active={active_pid}", False)
        return
    print(f"  当前激活：{cur['id']} {cur['base_url']} {cur['model_id']}")

    def _activate(pid: str) -> bool:
        r = client.post("/api/v1/medical/admin/llm/activate", headers=tok(t_admin),
                        json={"kind": "chat", "pid": pid}, timeout=30)
        return r.status_code == 200

    pid_bad = None
    try:
        base = probe_lit_ask(client, t_doc)
        row("基线·literature/ask", "200 非降级", f"degraded={base['degraded']} "
            f"{base['detail'][:60]}", base["status"] == 200 and not base["degraded"])

        r = client.post("/api/v1/medical/admin/llm/providers", headers=tok(t_admin),
                        timeout=30, json={"kind": "chat", "api_format": cur["api_format"],
                                          "base_url": cur["base_url"],
                                          "model_id": cur["model_id"],
                                          "display_name": "fault-drill-invalid",
                                          "api_key": "sk-invalid-fault-drill-000"})
        pid_bad = r.json().get("id") if r.status_code == 200 else None
        row("admin API 添加无效 key provider", "200", f"{r.status_code} id={pid_bad}",
            r.status_code == 200)
        ok = _activate(pid_bad)
        row("admin API 激活无效 key", "200", str(ok), ok)

        d = probe_lit_ask(client, t_doc)
        row("停机·literature/ask 降级语义", "200 + 降级(degraded:llm) 不 500 不裸文本",
            f"{d['status']} degraded={d['degraded']} {d['detail'][:60]}",
            d["status"] == 200 and d["degraded"])
    finally:
        ok = _activate(active_pid)  # 无条件恢复：激活回原 provider
        row("恢复·激活回原 provider", "200", str(ok), ok)
        if pid_bad:
            r = client.delete(f"/api/v1/medical/admin/llm/providers/chat/{pid_bad}",
                              headers=tok(t_admin), timeout=30)
            row("恢复·删除演练 provider", "200", str(r.status_code), r.status_code == 200)
        pr2 = client.get("/api/v1/medical/admin/llm/providers",
                         headers=tok(t_admin), timeout=30).json()
        row("恢复·active 复原", active_pid, str((pr2.get("chat") or {}).get("active")),
            (pr2.get("chat") or {}).get("active") == active_pid)
        back = {"status": 0, "degraded": True, "detail": "未探测"}
        for _ in range(3):
            back = probe_lit_ask(client, t_doc)
            if not back["degraded"]:
                break
            time.sleep(5)
        row("恢复·literature/ask 复通", "200 非降级",
            f"degraded={back['degraded']} {back['detail'][:60]}",
            back["status"] == 200 and not back["degraded"])


# ---------------- 入口 ----------------

def main() -> int:
    ap = argparse.ArgumentParser(description="故障注入演练（对抗轮 A2）")
    ap.add_argument("--base", default=BASE_DEFAULT)
    ap.add_argument("--password", default="Med@2026", help="种子账号统一口令")
    ap.add_argument("--check", action="store_true", help="只验证当前健康基线")
    ap.add_argument("--scenario", choices=["pg", "milvus", "llm"])
    ap.add_argument("--yes", action="store_true", help="确认执行破坏性演练")
    args = ap.parse_args()

    print(f"== 故障演练 == base={args.base}")
    with httpx.Client(base_url=args.base) as client:
        t_doc = login(client, "doctor01", args.password)
        t_admin = login(client, "admin01", args.password)
        r = client.get("/healthz", timeout=15)
        row("基线·healthz", "200", str(r.status_code), r.status_code == 200)
        r = client.get("/api/v1/medical/drug/dict", headers=tok(t_doc), timeout=60)
        row("基线·drug/dict", "200", str(r.status_code), r.status_code == 200)
        r = client.get("/api/v1/medical/prescriptions/mine", headers=tok(t_doc), timeout=60)
        row("基线·prescriptions/mine", "200", str(r.status_code), r.status_code == 200)
        if args.check:
            return 0 if all(x[3] for x in _rx_rows) else 1
        if not args.yes:
            print("拒绝：破坏性场景需 --scenario <name> --yes 显式确认")
            return 2
        if not all(x[3] for x in _rx_rows):
            print("拒绝：健康基线未通过，不做故障注入")
            return 2
        _rx_rows.clear()
        runner = {"pg": lambda: scenario_pg(client, t_doc),
                  "milvus": lambda: scenario_milvus(client, t_doc),
                  "llm": lambda: scenario_llm(client, t_doc, t_admin)}[args.scenario]
        print(f"== 场景 {args.scenario} ==")
        runner()
    bad = [x for x in _rx_rows if not x[3]]
    print(f"== 场景 {args.scenario} 结果：{'PASS' if not bad else 'FAIL(%d)' % len(bad)} ==")
    return 0 if not bad else 1


if __name__ == "__main__":
    raise SystemExit(main())
