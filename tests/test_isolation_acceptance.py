"""ADR-001 §283 隔离验收契约锁。

锁的不是"有没有写脚本"，而是**验收口径本身不会被悄悄放宽**：

1. 故障注入脚本只能碰一次性 Cell，且必须自带护栏（否则一次误操作就打掉演示院）；
2. 越权套件必须双向验证（A→B 与 B→A），并区分 403/404/401 的语义；
3. 发布台账必须有 Cell 维度，回滚必须按 Cell 匹配（否则会"回滚到别家"）；
4. 租户页的红黄标记来自最近一次已发布列车，不做版本号字符串比较；
5. 平台运营需要只读发布可见性，但不能获得部署权。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

CHAOS = REPO / "scripts/chaos_cell.ps1"
ISOLATION = REPO / "scripts/e2e/cell_isolation_suite.py"
DRILL = REPO / "scripts/release/cell_rollout_drill.ps1"

RELEASE_MAIN = REPO / "platform/release/src/main"
RELEASE_V3 = RELEASE_MAIN / "resources/db/migration/release/V3__cell_deployment.sql"
RELEASE_REPO = RELEASE_MAIN / "java/com/medassist/release/repo/ReleaseRepository.java"
DEPLOY_SERVICE = RELEASE_MAIN / "java/com/medassist/release/service/DeploymentService.java"
CELL_STATUS = RELEASE_MAIN / "java/com/medassist/release/service/CellReleaseStatusService.java"
RELEASE_CTRL = RELEASE_MAIN / "java/com/medassist/release/web/ReleaseController.java"

AUTH_MAIN = REPO / "platform/auth/src/main"
V97 = AUTH_MAIN / "resources/db/migration/auth/V97__platform_operator_release_read.sql"

CONSOLE = REPO / "frontend-console/src"
TENANT_VIEW = CONSOLE / "views/TenantView.vue"
RELEASE_API = CONSOLE / "api/release.ts"


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _bom(path: Path) -> bool:
    return path.read_bytes().startswith(b"\xef\xbb\xbf")


# ---------------------------------------------------------------- 故障注入


def test_chaos_script_has_guardrails():
    assert _bom(CHAOS), "chaos_cell.ps1 必须以 UTF-8 BOM 保存（PS 5.1 中文兼容）"
    text = _read(CHAOS)
    # 护栏：只能打一次性 Cell，不能打主演示库与主演示栈端口
    assert "medagent_pg_tenant" in text
    assert "禁止对主演示库 medagent_pg" in text
    assert "8080" in text and "8095" in text
    assert "platform-auth" in text  # 只 kill 校验过命令行的 auth 进程
    # 三类故障 + 恢复
    assert "docker exec -d" in text and "dd if=/dev/zero" in text
    assert "Stop-Process -Id" in text
    assert "docker stop" in text and "docker start" in text
    assert "pg_isready" in text
    # 证据：P99 波动与判定
    assert "P99" in text and "DeltaP99" in text
    assert "artifacts/chaos" in text
    assert "processIsolation" in text and "dbIsolation" in text


def test_chaos_script_documents_local_cpu_limitation():
    text = _read(CHAOS)
    # 本地单机共享内核：CPU 阶段只能作为参考，不能伪装成"已证明隔离"
    assert "LOCAL_REFERENCE" in text
    assert "ResourceQuota" in text
    assert "EnforceCpu" in text


# ---------------------------------------------------------------- 越权套件


def test_isolation_suite_covers_both_directions_and_semantics():
    text = _read(ISOLATION)
    # 标准库：验收脚本不依赖项目 Python 环境
    assert "import requests" not in text
    assert "urllib.request" in text
    # A→B
    assert "/auth/tenants/" in text and "404" in text
    assert "40321" in text
    assert "HTTP 401" in text  # A 院独有账号在 B 院库里不存在
    assert "a-only-username" in text
    # B→A（反向）
    assert "cell-b-username" in text
    assert "B 院令牌打 A 院入口" in text
    # 数据面：账号列表按租户过滤
    assert "tenantCode" in text and "/auth/users" in text
    # 证据落盘
    assert "artifacts" in text and "verdict" in text


# ---------------------------------------------------------------- 发布台账


def test_release_ledger_has_cell_dimension():
    sql = _read(RELEASE_V3)
    assert "ADD COLUMN IF NOT EXISTS tenant_code" in sql
    assert "batch_label" in sql
    assert "ix_rel_deploy_tenant" in sql
    # 历史记录不臆造归属
    assert "空表示平台级" in sql or "平台级" in sql


def test_rollback_is_scoped_to_same_cell():
    repo = _read(RELEASE_REPO)
    # 空=空也要成立：否则历史记录会命中某家医院
    assert "tenant_code IS NOT DISTINCT FROM :tenant" in repo
    service = _read(DEPLOY_SERVICE)
    assert "current.tenantCode()" in service
    # 回滚记录继承原 Cell
    assert "current.tenantCode()," in service
    assert "previousSuccessfulDeployment(current.componentCode(),\n                        current.environment(), current.tenantCode(), current.id())" in service


def test_deployment_list_can_be_filtered_by_cell():
    repo = _read(RELEASE_REPO)
    assert "tenant_code = :tenant" in repo
    ctrl = _read(RELEASE_CTRL)
    assert '@RequestParam(required = false) String tenantCode' in ctrl
    assert 'GetMapping("/cells/{tenantCode}/status")' in ctrl


def test_cell_status_uses_released_train_not_version_strings():
    service = _read(CELL_STATUS)
    assert "latestReleasedTrain" in service
    assert "UP_TO_DATE" in service and "NEVER_DEPLOYED" in service and "BEHIND" in service
    assert "FAILED" in service and "UNKNOWN" in service
    # 用版本 ID 相等判定，避免 "1.10.0 < 1.9.0" 这类字符串比较事故
    assert "fact.versionId().equals(item.versionId())" in service
    assert "compareTo" not in service


def test_status_endpoint_is_read_only_and_permission_gated():
    ctrl = _read(RELEASE_CTRL)
    marker = 'GetMapping("/cells/{tenantCode}/status")'
    tail = ctrl.split(marker, 1)[1][:600]
    assert "Permissions.require(permissions, READ)" in tail
    assert "PostMapping" not in tail.split("ApiResponse.ok", 1)[0]


# ---------------------------------------------------------------- 控制台


def test_console_shows_release_alignment_badge():
    api = _read(RELEASE_API)
    assert "cellStatus:" in api
    assert "CellReleaseStatus" in api
    assert "tenantCode" in api and "batchLabel" in api
    view = _read(TENANT_VIEW)
    assert "发布列车对齐" in view
    assert "cellStatuses" in view
    assert "releaseBadge" in view
    # 只有平台运营才请求发布台账（租户管理员没有 release:read）
    assert "if (platformLevel.value)" in view


def test_platform_operator_gets_read_only_release_visibility():
    sql = _read(V97)
    assert "'PLATFORM_OPERATOR', 'release:read'" in sql
    for forbidden in ("release:deploy", "release:approve", "release:write"):
        assert forbidden not in sql, f"平台运营不应获得 {forbidden}"


# ---------------------------------------------------------------- 发布演练


def test_rollout_drill_is_batched_and_repeatable():
    assert _bom(DRILL), "cell_rollout_drill.ps1 必须以 UTF-8 BOM 保存"
    text = _read(DRILL)
    for batch in ("BASELINE", "PILOT", "CANARY_10", "ALL", "ROLLBACK"):
        assert batch in text, f"缺少批次 {batch}"
    # 台账必须带 Cell 与批次
    assert "tenantCode = $tenant" in text and "batchLabel = $batchLabel" in text
    # 回滚不得影响其他院
    assert "回滚不影响其他院台账" in text
    assert "artifacts/release" in text


# ---------------------------------------------------------------- 开通与报告


def test_new_cell_registers_tenant_inside_the_cell_database():
    text = _read(REPO / "scripts/new_cell.ps1")
    # auth_svc.app_user 有 tenant_code 外键：Cell 库必须自带租户行，控制面台账不能替代
    assert "在 Cell 内登记本租户" in text
    assert text.count("INSERT INTO auth_svc.tenant") >= 2
    assert "fk_app_user_tenant" in text


def test_acceptance_report_states_contract_and_honest_gaps():
    doc = _read(REPO / "docs/ISOLATION-ACCEPTANCE-20261112.md")
    for dimension in ("数据", "身份", "计算", "流量", "发布", "备份", "观测"):
        assert dimension in doc, f"验收报告缺少维度：{dimension}"
    # 不允许把未覆盖项写成通过
    assert "未演练" in doc and "未压测" in doc and "参考" in doc
    # 证据快照随仓库提交，原始采样目录不入库
    assert "docs/evidence/20261112-cell-acceptance" in doc
    evidence = REPO / "docs/evidence/20261112-cell-acceptance"
    assert len(list(evidence.glob("*.json"))) >= 3
