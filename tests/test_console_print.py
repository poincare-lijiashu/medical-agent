"""打印通道一致性回归（第 225 批）。

单据打印此前是每个页面自己 `window.open` + 拼 HTML + `document.write`：
转义函数各写一份（其中一份遗漏了 `&` 之外的顺序问题）、`<title>` 未转义、
打印版式各写一套。医院每天都要打票据/同意书，这类重复实现一旦漂移，
典型后果是“某个单据里的 `<` 把后面整段吞掉”或“打印机任务名乱码”。

本锁把打印收口成一条通道，并用静态断言防止回退：
- 只允许 `utils/print.ts` 使用 `window.open('', '_blank')` 与 `document.write`；
- `escapeHtml` 全仓库只有一份实现（`utils/html.ts`），`markdown.ts` 复用它；
- 通道必须具备 A4 `@page`、标题转义、`script` 剥离、打印后关窗；
- 业务页面不得再直接调用 `win.print()`。
"""
from __future__ import annotations

import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "frontend-console" / "src"
PRINT_UTIL = SRC / "utils" / "print.ts"
HTML_UTIL = SRC / "utils" / "html.ts"


def _sources() -> list[Path]:
    return [p for p in SRC.rglob("*") if p.suffix in {".vue", ".ts"}]


def test_only_print_util_opens_blank_window():
    offenders: list[str] = []
    for path in _sources():
        if path == PRINT_UTIL:
            continue
        text = path.read_text(encoding="utf-8")
        if re.search(r"window\.open\(\s*''", text):
            offenders.append(str(path.relative_to(REPO)).replace("\\", "/"))
    assert not offenders, f"打印窗口必须在 utils/print.ts 统一创建，以下文件自建：{offenders}"


def test_document_write_is_centralized():
    offenders: list[str] = []
    for path in _sources():
        if path == PRINT_UTIL:
            continue
        if "document.write" in path.read_text(encoding="utf-8"):
            offenders.append(str(path.relative_to(REPO)).replace("\\", "/"))
    assert not offenders, f"document.write 只允许出现在 utils/print.ts：{offenders}"


def test_escape_html_has_single_implementation():
    impls: list[str] = []
    for path in _sources():
        if re.search(r"(export\s+)?function\s+escapeHtml\s*\(", path.read_text(encoding="utf-8")):
            impls.append(str(path.relative_to(REPO)).replace("\\", "/"))
    assert impls == ["frontend-console/src/utils/html.ts"], (
        f"escapeHtml 应只有 utils/html.ts 一份实现，实际：{impls}"
    )
    markdown = (SRC / "utils" / "markdown.ts").read_text(encoding="utf-8")
    assert "from './html'" in markdown, "markdown.ts 必须复用 utils/html.ts 的 escapeHtml"


def test_print_util_has_a4_and_hardening():
    text = PRINT_UTIL.read_text(encoding="utf-8")
    assert "@page" in text, "打印通道必须声明 A4 页面（@page）"
    assert "escapeHtml(doc.title)" in text, "打印窗口标题必须转义"
    assert re.search(r"script", text, re.I), "打印正文必须剥离 script 片段"
    assert "onafterprint" in text, "打印结束后应回收窗口"


def test_views_print_through_channel():
    for name in (
        "ReceiptView.vue",
        "ConsentsView.vue",
        "PharmacyView.vue",
        "PivasView.vue",
        "InpatientView.vue",
        "NursingView.vue",
    ):
        text = (SRC / "views" / name).read_text(encoding="utf-8")
        assert "printHtml(" in text, f"{name} 应通过 printHtml 打印"
        assert not re.search(r"\.print\(\)", text), f"{name} 不应再直接调用 window.print()"
        # 第 230 批：打印成功必须留痕（失败静默，不阻断已出纸的打印）
        assert "recordPrint(" in text, f"{name} 打印后必须调用 recordPrint 留痕"


def test_print_audit_is_silent_and_centralized():
    text = (SRC / "utils" / "printAudit.ts").read_text(encoding="utf-8")
    assert "/clinical/print-events" in text and "post<" in text, "打印留痕必须走 Java /clinical/print-events"
    assert ".catch(" in text, "留痕失败必须静默，不能把已完成的打印变成报错"
    assert "toast" not in text, "打印留痕工具不得耦合 toast（失败不提示用户）"
    # 单据类型集中定义，禁止各页面各写一套大小写口径
    for doc_type in ("RECEIPT", "CONSENT", "PRESCRIPTION", "PIVAS_CARD", "INPATIENT_FINANCE", "WRISTBAND", "NURSING_SHEET"):
        assert doc_type in text, f"打印留痕缺少单据类型 {doc_type}"


def test_ward_batch_print_uses_single_endpoint():
    """第 234 批：整区批量打印必须走一次批量接口，不得回退成逐床请求。"""
    text = (SRC / "views" / "NursingView.vue").read_text(encoding="utf-8")
    assert "clinicalApi.wardNursingSheets(" in text, "整区打印应调用后端批量端点"
    assert "data.sheets" in text, "整区打印应消费批量结果的 sheets"
    # 一床一页：分页样式必须存在，否则整区单据会连成一页
    assert "page-break-after" in text, "整区打印必须声明按床位分页"


def test_print_history_anomaly_panel():
    """第 237 批：留痕页必须能发现“同一单号被反复打印”，而不只是能查。"""
    view = (SRC / "views" / "PrintHistoryView.vue").read_text(encoding="utf-8")
    assert "clinicalApi.printAnomalies(" in view, "留痕页缺少补打异常查询"
    assert "loadAnomalies(" in view and "threshold" in view, "异常区必须支持阈值"
    assert "focusDoc(" in view, "异常行应能跳回该单号流水"
    # 异常区也必须有空/错/加载态，不能静默空白
    assert "没有补打异常" in view, "异常区缺少空态说明"


def test_print_history_export_csv():
    """第 236 批：导出必须按当前筛选拉全量（分页），且 CSV 要防注入、带 BOM。"""
    view = (SRC / "views" / "PrintHistoryView.vue").read_text(encoding="utf-8")
    assert "exportCsvByFilter(" in view, "留痕页缺少按筛选导出"
    assert "downloadCsv(" in view, "导出必须走统一 CSV 工具"
    assert "EXPORT_MAX" in view, "导出必须有上限，避免一次拉全表"
    assert "filterParams()" in view, "列表与导出必须共用同一套筛选条件"

    csv = (SRC / "utils" / "csv.ts").read_text(encoding="utf-8")
    assert "\\uFEFF" in csv, "CSV 必须带 UTF-8 BOM（否则 Excel 打开中文乱码）"
    assert 'replace(/"/g, \'""\')' in csv, "CSV 引号必须双写转义"
    assert "[=+\\-@" in csv, "CSV 必须防公式注入（= + - @ 开头强制文本）"


def test_print_history_page_wired():
    """第 231 批：留痕必须有人能查——页面、路由、导航、文案四处齐全。"""
    view = SRC / "views" / "PrintHistoryView.vue"
    assert view.exists(), "缺少打印留痕页面 PrintHistoryView.vue"
    text = view.read_text(encoding="utf-8")
    assert "clinicalApi.printEvents(" in text, "打印留痕页必须走 clinicalApi.printEvents"
    for component in ("PermissionDenied", "LoadError", "EmptyState", "TableSkeleton"):
        assert component in text, f"打印留痕页缺少 {component}（空/错/无权限/加载态）"
    assert "isForbidden(" in text, "403 必须与故障区分（不显示重试）"
    # 第 235 批：时间区间 + 分页（长期流水不能一次拉全表）
    assert 'type="date"' in text, "打印留痕页缺少日期区间筛选"
    assert "page.items" in text and "page.total" in text, "打印留痕页必须消费分页结果"
    assert "上一页" in text and "下一页" in text, "打印留痕页缺少分页按钮"

    router = (SRC / "router" / "index.ts").read_text(encoding="utf-8")
    assert "PrintHistoryView.vue" in router, "打印留痕页未注册路由"
    nav = (SRC / "navigation.ts").read_text(encoding="utf-8")
    assert "name: 'print-history'" in nav and "path: '/print-events'" in nav, "打印留痕未进导航"
    assert "clinical:print:read" in nav, "打印留痕导航必须用 clinical:print:read 收敛"


# ---------------------------------------------------------------- 第 238 批：补打原因

JAVA_CLINICAL = REPO / "platform" / "clinical" / "src" / "main"
JAVA_COMMON = REPO / "platform" / "common" / "src" / "main"


def _read(path: Path) -> str:
    assert path.exists(), f"缺少文件：{path}"
    return path.read_text(encoding="utf-8")


def test_print_reason_schema_and_server_enforcement():
    """补打原因必须落库并由服务端判定，否则前端换个日期就绕过了。"""
    migration = JAVA_CLINICAL / "resources" / "db" / "migration" / "clinical" / "V61__print_reason.sql"
    sql = _read(migration)
    assert "reprint_seq" in sql and "reason" in sql, "V61 必须补次序与原因两列"

    service = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "service" / "PrintEventService.java"
    )
    assert "countByDoc(" in service, "补打判定必须基于该单号的历史留痕"
    assert "PRINT_REASON_REQUIRED" in service, "补打未登记原因必须由服务端拒绝"
    # 首次打印不得要求原因，否则会把正常开单也变成“填个理由”
    assert "prior > 0" in service, "只有第 2 次及以后才要求补打原因"

    codes = _read(JAVA_COMMON / "java" / "com" / "medassist" / "common" / "api" / "ErrorCode.java")
    assert "PRINT_REASON_REQUIRED(45440" in codes, "补打原因必须使用稳定业务码 45440"

    handler = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "web" / "GlobalExceptionHandler.java"
    )
    assert "PRINT_REASON_REQUIRED" in handler, "新业务码必须在异常映射里显式落 422"


def test_print_audit_prompts_reason_and_retries_once():
    """前端只弹一次原因并重发，绝不进入无限弹窗或把已出纸的打印变成报错。"""
    text = (SRC / "utils" / "printAudit.ts").read_text(encoding="utf-8")
    assert "45440" in text, "上报工具必须按业务码识别“补打需原因”"
    assert "askReprintReason(" in text, "补打被拒时必须弹出原因选择"
    assert "send({ ...input, reason }, false)" in text, "补打原因必须重发且只重试一次"
    assert "REASON_REUSE_MS" in text, "整区批量补打不得逐单弹窗（同类型短窗口内复用原因）"

    prompt = (SRC / "utils" / "reprintReason.ts").read_text(encoding="utf-8")
    assert "REPRINT_REASONS" in prompt, "原因必须给预设选项（护士站没人手打整句）"
    assert "未说明" in prompt, "必须保留“未说明”出口，否则会逼出假原因"
    assert "queue" in prompt, "批量打印会并发触发，原因弹窗必须排队而不是叠层"

    dialog = SRC / "components" / "ui" / "ReprintReasonDialog.vue"
    text_dialog = _read(dialog)
    assert ":dismissible=\"false\"" in text_dialog, "原因弹窗不可关闭（关闭等于丢弃这次留痕）"
    assert "answerReprintReason(" in text_dialog, "弹窗确认必须回交原因并继续下一条"

    app = (SRC / "App.vue").read_text(encoding="utf-8")
    assert "ReprintReasonDialog" in app, "全局原因弹窗必须挂载一次，而不是每个打印页各挂一份"


def test_print_history_shows_reason_and_unexplained():
    """质控要能区分“合理补打”和“没说明的补打”，否则补打原因白记。"""
    view = (SRC / "views" / "PrintHistoryView.vue").read_text(encoding="utf-8")
    assert "reasonText(" in view, "留痕列表必须展示补打原因"
    assert "未说明" in view, "无原因的补打必须显式写“未说明”，不能留白"
    assert "次序" in view, "留痕列表必须展示第几次打印"
    assert "unexplained" in view, "异常区必须单列“未说明”次数"
    assert "补打原因" in view, "CSV 导出必须包含补打原因列"

    types = (SRC / "api" / "types.ts").read_text(encoding="utf-8")
    assert "reprintSeq: number" in types and "reason: string | null" in types, "留痕类型缺少次序/原因"
    assert "unexplained: number" in types, "异常类型缺少未说明次数"


# ---------------------------------------------------------------- 第 239 批：核查处置闭环


def test_anomaly_review_schema_and_rules():
    """异常必须能签字，且“需整改”必须写清楚改什么。"""
    sql = _read(
        JAVA_CLINICAL / "resources" / "db" / "migration" / "clinical" / "V62__print_anomaly_review.sql"
    )
    assert "print_anomaly_review" in sql, "V62 必须建核查处置表"
    assert "verdict" in sql and "reviewed_at" in sql, "处置表必须记录结论与时间"

    service = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "service" / "PrintEventService.java"
    )
    assert "PRINT_ANOMALY_NOTE_REQUIRED" in service, "判定需整改时说明必填"
    assert "countByDoc(type, no) == 0" in service, "没有打印记录的单号不允许凭空核查"
    assert "insertReview(" in service, "处置必须追加落库（不覆盖历史结论）"

    codes = _read(JAVA_COMMON / "java" / "com" / "medassist" / "common" / "api" / "ErrorCode.java")
    assert "PRINT_ANOMALY_NOTE_REQUIRED(45441" in codes, "处置说明必填需要稳定业务码 45441"

    handler = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "web" / "GlobalExceptionHandler.java"
    )
    assert "PRINT_ANOMALY_NOTE_REQUIRED" in handler, "新业务码必须显式落 422"


def test_anomaly_list_and_count_share_one_aggregate():
    """待办计数与列表必须同口径，否则会出现“待办 3 条、点进去 2 条”。"""
    repo = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "repo" / "PrintEventRepository.java"
    )
    assert "AGGREGATE_CTE" in repo, "异常聚合必须抽成共用 CTE"
    assert repo.count("AGGREGATE_CTE") >= 3, "列表与计数都必须复用同一个 CTE 常量"
    assert "count(*) FILTER" in repo and "l.reviewed_at < a.last_at" in repo, (
        "处置后又被补打必须重新计入待核查"
    )

    controller = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "web" / "PrintEventController.java"
    )
    assert '"/anomalies/summary"' in controller, "缺少待办计数端点"
    assert '"/anomalies/review"' in controller, "缺少核查处置端点"
    # 能看异常的人就是负责核查的人，不额外引入权限点
    assert controller.count("Permissions.require(permissions, READ)") >= 3, "核查端必须复用 print:read"


def test_print_history_review_ui_closed_loop():
    """发现 → 待办提醒 → 处置 → 不再打扰，四段都要在界面上有落点。"""
    view = _read(SRC / "views" / "PrintHistoryView.vue")
    assert "pendingCount" in view and "待核查" in view, "异常区必须显示待核查条数"
    assert "openReview(" in view and "submitReview(" in view, "异常行必须能发起核查"
    assert "clinicalApi.printAnomalyReview(" in view, "处置必须落 Java 核查端点"
    assert "verdictLabel(" in view, "结论必须中文化展示"
    assert "重新核查" in view, "已处置行应允许复查（追加新结论）"
    assert "scrollIntoView" in view and "anomaly" in view, "待办跳转必须定位到异常区"

    types = _read(SRC / "api" / "types.ts")
    assert "pending: boolean" in types and "review: PrintAnomalyReview | null" in types, (
        "异常类型必须带处置状态"
    )
    assert "PrintAnomalySummary" in types, "缺少待办计数类型"


# ---------------------------------------------------------------- 第 240 批：整区分批保护


def test_ward_batch_is_bounded_and_paged_by_patient():
    """整区打印必须有上限，且按患者分页（按医嘱行切会把一张单截成两半）。"""
    service = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "service" / "NursingSheetService.java"
    )
    assert "BATCH_MAX" in service, "整区批量必须有显式上限"
    assert "Math.min(limit, BATCH_MAX)" in service, "上限必须在服务端夹取（前端可传任意 limit）"
    assert "hasMore" in service, "必须告诉前端还有没有下一批"

    repo = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "repo" / "NursingSheetRepository.java"
    )
    assert "countSheetPatients(" in repo, "分页总数必须与明细同口径（有长嘱的患者）"
    # 患者分页：先取本批患者（子查询 + LIMIT），再 JOIN 医嘱
    assert "LIMIT :limit OFFSET :offset" in repo
    assert ") p\n" in repo or "FROM (\n" in repo, "必须先分页取患者，再取这些患者的医嘱"
    assert "EXISTS (" in repo, "子查询必须只取当日有有效长嘱的患者"

    controller = _read(
        JAVA_CLINICAL / "java" / "com" / "medassist" / "clinical" / "web" / "NursingSheetController.java"
    )
    assert "Integer offset" in controller and "Integer limit" in controller, "批量端点必须支持分批"


def test_ward_batch_ui_continues_instead_of_pretending_done():
    """超上限时要给“继续打印下一批”，不能静默截断。"""
    view = _read(SRC / "views" / "NursingView.vue")
    assert "batchOffset" in view and "batchRemain" in view, "整区打印必须记录批次进度"
    assert "继续打印下一批" in view, "还有未打印患者时必须引导继续"
    assert "data.hasMore" in view, "必须消费服务端 hasMore 而不是自己猜"
    assert "data.sheetPatients" in view, "进度对照的是需出单人数，不是本页条数"
    assert "batchKey" in view, "切病区/日期必须回到第一批"

    api = _read(SRC / "api" / "index.ts")
    assert "offset?: number" in api and "limit?: number" in api, "批量接口必须暴露分批参数"
    types = _read(SRC / "api" / "types.ts")
    assert "sheetPatients: number" in types and "hasMore: boolean" in types, "整区类型缺少分批字段"


def test_reprint_reason_is_shared_within_one_batch():
    """整区补打会并发触发几十次 422：只能弹一次原因，不能排队几十次。"""
    text = _read(SRC / "utils" / "printAudit.ts")
    assert "pendingAsk" in text, "并发补打必须共享同一次原因询问"
    assert "reasonFor(" in text, "原因获取必须收口到一处，便于去重"
    assert "recentReason === " not in text  # 防回退成对象字面量比较



