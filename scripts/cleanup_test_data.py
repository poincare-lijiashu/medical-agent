# -*- coding: utf-8 -*-
"""F2：e2e 测试残留处方清理脚本（幂等；审计 jsonl 不动）。

背景：e2e（test_vue_parity 驳回闭环/字典外方案A 等）经 API/UI 创建的真实处方留在了
存储层——doctor01 的 rx 角标（被驳回处方数）恒为 3（rx-0812af6a/62416c2c/2e59f904，
均为阿司匹林单药被 pharm01 驳回），属于测试数据污染运行库。

清理条件（命中任一即删，逐条打印；删除经域层 delete_prescriptions：JSON 兜底真源
过滤重写 + PG 全量同步，两侧真源一致）：
  A. 明确 id 清单（本次诊断实锤的三条 e2e 残留）；
  B. doctor == "loadtest"（压测脚本产物）；
  C. case_text 含 "[e2e]"（防再犯标记：e2e 新建处方统一携带）；
  D. 任一药品 note 含 "[e2e]"（同上，随药单条目标记）；
  E. 任一药品 name == "测试"（e2e 字典外手动药特征名——真实业务不会开药名「测试」）。

用法：
    python scripts/cleanup_test_data.py            # 执行清理
    python scripts/cleanup_test_data.py --dry-run  # 只列出命中项，不删除

幂等：重复执行第二次删 0 条。防再犯：e2e 测试创建处方统一带 [e2e] 标记
（case_text/drugs note），teardown 自清理；本脚本按标记兜底清（批跑中断残留）。
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from backend.core import pg_store                      # noqa: E402
from backend.core import prescriptions as rx_mod       # noqa: E402
from backend.core.prescriptions import (               # noqa: E402
    PRESCRIPTIONS_FILE, _load_json, delete_prescriptions)

# 条件 A：明确 id 清单（本轮诊断实锤的 e2e 残留三条，均为 doctor01 阿司匹林被驳回）
E2E_RX_IDS = ["rx-0812af6a", "rx-62416c2c", "rx-2e59f904"]
E2E_MARKER = "[e2e]"        # 条件 C/D：防再犯标记
E2E_OOD_DRUG = "测试"        # 条件 E：e2e 字典外手动药特征名
E2E_DOCTOR = "loadtest"     # 条件 B：压测产物


def match_reason(rx: dict) -> str | None:
    """命中返回原因描述，未命中返回 None（条件逐条判，首个命中即返回）。"""
    if rx.get("id") in E2E_RX_IDS:
        return "命中明确 id 清单（诊断实锤 e2e 残留）"
    if str(rx.get("doctor") or "") == E2E_DOCTOR:
        return f"医生={E2E_DOCTOR}（压测产物）"
    if E2E_MARKER in str(rx.get("case_text") or ""):
        return f"病例文本含 {E2E_MARKER} 标记"
    for d in rx.get("drugs") or []:
        if E2E_MARKER in str(d.get("note") or ""):
            return f"药品理由含 {E2E_MARKER} 标记（药名：{d.get('name')}）"
        if str(d.get("name") or "").strip() == E2E_OOD_DRUG:
            return f"含字典外特征药「{E2E_OOD_DRUG}」（e2e 方案A 手动药）"
    return None


def main() -> int:
    ap = argparse.ArgumentParser(description="e2e 测试残留处方清理（幂等）")
    ap.add_argument("--dry-run", action="store_true", help="只列出命中项，不删除")
    args = ap.parse_args()

    # 统计基于 _load()（PG 池可用 → PG 真源；不可用 → JSON 兜底）——与删除路径同源，
    # 避免打印口径与实际删除口径不一致；JSON 兜底文件计数单独参考输出。
    pg_ready = False
    try:
        pg_ready = asyncio.run(pg_store.init())
    except Exception as exc:  # noqa: BLE001 —— PG 不可用降级 JSON-only（不阻塞清理）
        print(f"[cleanup] PG 初始化失败（{str(exc)[:80]}），仅清理 JSON 兜底真源")
    items = rx_mod._load()
    before = len(items)
    print(f"[cleanup] 处方总数（{'PG 真源' if pg_ready else 'JSON 兜底'}）：{before}；"
          f"JSON 文件条数：{len(_load_json())}（{PRESCRIPTIONS_FILE}）")
    hits = []
    for rx in items:
        reason = match_reason(rx)
        if reason:
            hits.append((rx, reason))
            print(f"  [hit] {rx.get('id')} | doctor={rx.get('doctor')} | "
                  f"status={rx.get('status')} | created={rx.get('created_at')}")
            print(f"         case={str(rx.get('case_text') or '')[:60]}…")
            print(f"         原因：{reason}")
    print(f"[cleanup] 命中 {len(hits)} 条 / 共 {before} 条")
    if not hits:
        print("[cleanup] 无 e2e 残留，无需清理。")
        if pg_ready:
            asyncio.run(pg_store.close())
        return 0
    if args.dry_run:
        print("[cleanup] --dry-run：未删除。")
        if pg_ready:
            asyncio.run(pg_store.close())
        return 0

    removed = delete_prescriptions([rx.get("id") for rx, _ in hits])
    after = len(rx_mod._load())  # 池关闭前取数（口径与删除同源）
    if pg_ready:
        asyncio.run(pg_store.close())
    print(f"[cleanup] 已删除 {len(removed)} 条：{removed}")
    print(f"[cleanup] 计数：{before} → {after}（删除路径同源真源）；JSON 文件条数：{len(_load_json())}")
    print("[cleanup] 审计 jsonl 未触碰（留痕完整）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
