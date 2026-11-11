"""跨域审计页可读化与按对象反查（第 242 批）。

背景：统一审计页此前把 `action` 原样打在表格里（`PRINT_ANOMALY_REVIEW`、
`PACS_READING_CLAIM`），质控看不懂动作，只能靠猜；排障时也不能按单据号反查
“谁改过这条结论”。

锁死：
1. 动作/对象必须有中文口径，且未识别时**回退原文**（不猜、不吞）；
2. 界面同时保留原始 action/targetType（title 与详情），译文错了也能对照；
3. 后端支持按 targetId 精确过滤，且空条件不写进 SQL（避免索引失效）。
"""
from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
AUTH = REPO / "platform" / "auth" / "src" / "main" / "java" / "com" / "medassist" / "auth"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_audit_labels_cover_print_flow_and_fall_back():
    labels = _read(SRC / "utils" / "auditLabels.ts")
    assert "PRINT_ANOMALY_REVIEW" in labels, "打印异常核查必须有专门中文说法"
    assert "核查补打异常" in labels
    # 词表拼装：新增普通动作无需改代码
    assert "const VERBS" in labels and "const NOUNS" in labels
    assert "DISABLE: '停用'" in labels, "动词表缺少停用"
    # 未识别必须回退原文
    assert "return key" in labels, "未识别的动作/对象必须回退原文，不能猜"
    assert "export function actionLabel" in labels
    assert "export function targetTypeLabel" in labels
    assert "export function auditSourceLabel" in labels


def test_system_view_keeps_raw_action_beside_translation():
    view = _read(SRC / "views" / "SystemView.vue")
    assert "actionLabel(" in view and "targetTypeLabel(" in view, "审计页必须显示中文口径"
    assert "auditSourceLabel(" in view, "来源域也要中文化"
    assert ':title="e.action"' in view, "列表必须保留原始动作（悬停可见）"
    assert "detail-raw" in view, "详情里必须同时给出原始 action/targetType"

    api = _read(SRC / "api" / "index.ts")
    assert "targetId?: string" in api, "审计接口必须支持按对象 ID 反查"
    assert "auditForm.targetId" in view, "筛选区必须有对象 ID 输入"
    assert "auditForm.targetId = ''" in view, "清空条件必须一并清掉对象 ID"


def test_audit_backend_supports_target_id_filter():
    controller = _read(AUTH / "web" / "AuditController.java")
    assert "@RequestParam(required = false) String targetId" in controller, "控制器必须接收 targetId"

    service = _read(AUTH / "service" / "AuditService.java")
    assert "targetId" in service, "服务层必须透传 targetId"

    repo = _read(AUTH / "repo" / "AuditRepository.java")
    assert "AND target_id = :targetId" in repo, "SQL 必须按 target_id 过滤"
    assert "targetId.isEmpty() ? 0 : 8" in repo, "缓存键必须区分 targetId 有无（16 种组合）"


def test_audit_action_catalog_feeds_the_filter_but_is_optional():
    """动作下拉来自真实出现的动作；它挂了也不能挡住手输。"""
    controller = _read(AUTH / "web" / "AuditController.java")
    assert '"/actions"' in controller, "缺少动作字典端点"
    assert "actionCatalog(" in controller

    service = _read(AUTH / "service" / "AuditService.java")
    assert "MAX_ACTIONS" in service and "Math.min(Math.max(limit, 1), MAX_ACTIONS)" in service, (
        "动作字典必须收敛上限，避免动作爆炸拉满页面"
    )

    repo = _read(AUTH / "repo" / "AuditRepository.java")
    assert "GROUP BY e.action" in repo, "字典必须按实际出现的动作聚合（静态清单会过期）"
    assert "ORDER BY n DESC" in repo, "高频动作应排在前面"

    view = _read(SRC / "views" / "SystemView.vue")
    assert "audit-action-options" in view and "datalist" in view, "筛选应为可搜索下拉"
    assert "auditActionOptions" in view and "loadAuditActions(" in view
    assert ":label=" in view, "下拉项应带中文名与次数说明"
    # 失败静默：字典是“少打字”，不能变成红色错误
    assert "catch {" in view and "auditActionOptions.value = []" in view, "字典加载失败必须静默清空"

    api = _read(SRC / "api" / "index.ts")
    assert "auditActions:" in api and "'/audit/events/actions'" in api
    types = _read(SRC / "api" / "types.ts")
    assert "interface AuditActionOption" in types, "缺少动作字典类型"
