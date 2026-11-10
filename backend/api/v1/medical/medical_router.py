"""MedAssist 医疗 API 聚合路由（W4.3 router 拆分）。

本文件只做三件事：
1. 聚合：include 各业务域子路由（rate_limit 由各子路由自带，行为与拆分前一致）；
2. /health 服务健康探针；
3. 兼容再导出：tests/scripts 此前经 medical_router 模块引用的类型/常量/纯函数
   （AskReq/AskResp/QcReq/_has_risk/RISK_WORDS/_load_optional_table/_get_mdt_graph/
   router 等）保持原导入路径可用。

⚠️ 运行时可替换符号（monkeypatch 目标）不再经本模块转发——patch 必须落在符号的
属主模块，否则是无效的空操作：
- get_audit_logger()        → backend.api.v1.medical.shared
- get_llm                   → backend.api.v1.medical.prescriptions（suggest 端点）
- _lit_graph / _get_lit_graph 写全局 → backend.api.v1.medical.literature
- _mdt_graph / _get_mdt_graph 写全局 → backend.api.v1.medical.consults

各域端点一览（原 medical_router.py 2,275 行按域拆分，单文件 <600 行）：
- admin.py        /config /admin/config-toggle /overview /admin/users|departments|data|logs|llm/*
- literature.py   /literature/ask /literature/stream
- drug.py         /drug/dict /drug/ask /drug/my-rejections /admin/drug/*
- prescriptions.py /prescriptions/suggest /prescriptions（提交/我的/待审/审核/改写）
- imaging.py      /imaging/ask /case/ask
- consults.py     /mdt/consult /consults（发起/收件箱/我的/意见/结束）
- review.py       /review/pending|history|status|my-pending-confirm|self-confirm|resolve|reopen
- qc.py           /qc/record /qc/my-rejections /qc/parse /case-archive*
- kb.py           /kb/search /admin/kb*
- integration.py  /integration/status|patient/{id}|orders/{id}（HIS 深度集成诊断/读端点）
"""
from __future__ import annotations

import os  # noqa: F401 —— 兼容：tests/test_security 经 mr.os.path 打桩 isfile（_load_optional_table）

from fastapi import APIRouter, Depends

from backend.api.deps import rate_limit
from backend.api.v1.medical import (admin, consults, drug, imaging, inline, integration,
                                    kb, literature, prescriptions, qc, review)
from backend.api.v1.medical.literature import _get_lit_graph  # noqa: F401 —— 兼容再导出（函数读属主模块全局）
from backend.api.v1.medical.consults import _get_mdt_graph    # noqa: F401 —— 兼容再导出
from backend.api.v1.medical.qc import (QC_FIELDS, QcParseReq, QcReq,        # noqa: F401
                                       _load_optional_table)                # noqa: F401
from backend.api.v1.medical.schemas import (AUDIT_Q_SNIPPET, MAX_IMAGES,    # noqa: F401
                                            AskReq, AskResp)                # noqa: F401
from backend.api.v1.medical.shared import (RISK_WORDS, _has_risk,           # noqa: F401
                                           _is_fallback_answer)             # noqa: F401

router = APIRouter()

# include 顺序保持与拆分前端点注册序一致（防同前缀路径解析顺序变化）；
# rate_limit 在各域子路由的 APIRouter(dependencies=[...]) 上声明，逐路由生效一次。
router.include_router(admin.router)
router.include_router(literature.router)
router.include_router(drug.router)
router.include_router(prescriptions.router)
router.include_router(imaging.router)
router.include_router(consults.router)
router.include_router(review.router)
router.include_router(qc.router)
router.include_router(kb.router)
# 第 132 批：业务流程内嵌（开医嘱内联审方 / 写病历内联质控）
router.include_router(inline.router)
# 评测「HIS 深度集成」：患者摘要 / 医嘱读取（HIS_ADAPTER=none 时 501）
router.include_router(integration.router)


@router.get("/health", dependencies=[Depends(rate_limit)])  # 拆分前 /health 在带 rate_limit 的路由器上，显式声明保持等价
async def health():
    return {"ok": True, "service": "MedAssist"}
