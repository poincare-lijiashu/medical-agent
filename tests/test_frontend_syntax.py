"""任务5/6 前端回归：index.html 内联脚本语法校验（node --check）+ 新增功能标记存在性。

node 不可用时跳过（仅环境兜底；本机/CI 均有 node）。
"""
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

import pytest

FRONTEND = Path(__file__).resolve().parent.parent / "frontend" / "index.html"


def _inline_scripts() -> list[str]:
    """提取 index.html 全部内联 <script> 源码（外链 src= 跳过）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    out, pos = [], 0
    while True:
        s = html.find("<script", pos)
        if s == -1:
            break
        tag_end = html.find(">", s)
        if "src=" in html[s:tag_end + 1]:  # 外链脚本不校验
            pos = html.find("</script>", tag_end) + len("</script>")
            continue
        e = html.find("</script>", tag_end)
        assert e != -1, "存在未闭合的 <script> 标签"
        out.append(html[tag_end + 1:e])
        pos = e + len("</script>")
    return out


def test_index_html_inline_script_syntax():
    """node --check 校验全部内联脚本语法（任务5/6 前端改动回归防线）。"""
    node = shutil.which("node")
    if not node:
        pytest.skip("node 不可用，跳过前端语法校验")
    scripts = _inline_scripts()
    assert scripts, "index.html 应包含内联脚本"
    for i, src in enumerate(scripts):
        fd = tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8")
        fd.write(src)
        fd.close()
        try:
            r = subprocess.run([node, "--check", fd.name], capture_output=True,
                               text=True, timeout=60)
        finally:
            Path(fd.name).unlink(missing_ok=True)
        assert r.returncode == 0, f"内联脚本 #{i} 语法错误：{r.stderr[:600]}"


def test_task5_task6_frontend_markers_present():
    """新增功能关键标记存在：应用日志卡片（任务5）/ 重提链 UI（任务6）/ esc 转义纪律。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 任务5：应用日志 tail 卡片
        "appLogView", "appLogN", "loadAppLogs", "admin/logs?lines=",
        ".map(l=>esc(l)).join('\\n')",  # 日志行逐行 esc 防注入
        # 任务6：重新提交 / 预填 / 徽标 / escalated 状态
        'data-act="qc-resubmit"', "qcResubmitOf", "resubmit_of",
        "rejected_escalated", "已升级病案科复核", "第 ${att} 次提交",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_task1234_frontend_markers_present():
    """任务1/3/4 前端关键标记存在：
    - 任务1：ICON.myconsults 图标（「undefined我的会诊」根因）+ 概览 humanizeAudit 全覆盖；
    - 任务3：空响应兜底文案 + admin 留痕模式开关卡（config-toggle）；
    - 任务4：审核中心 tab 未读徽标。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 任务1：myconsults 导航图标（缺失时 renderRail 渲染出 "undefined我的会诊"）
        "myconsults:'<svg",
        "esc(humanizeAudit(e))",  # 概览最近活动同样人话化
        # 任务3：空响应兜底 + 留痕模式开关卡
        "AI 未生成所见，请重新上传或补充描述",
        "admin/config-toggle", "flagFullBtn", "toggleFullFlag", "留痕模式（全交互留痕 · 运行时开关）",
        # 任务4：会诊 tab 未读徽标
        "updateConsultBadge", "refreshConsultBadge",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_diag_fixes_frontend_markers_present():
    """诊断1/2/3 前端关键标记存在（锁定防回退）：
    - 诊断1：imaging 视图「图片已发送」提示（区分待发送图与历史已发送图）；
    - 诊断2：admin 留痕模式开关卡显示 runtime_flags 当前值来源 + 变更 toast；
    - 诊断3：uploadKb 失败提示带 HTTP 状态码+响应原因（不再吞原因）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 诊断1：输入区「已发送」提示 + imaging 专属触发
        "sentImgHint", "showSentHint", "图片已发送，如需继续问诊请重新上传",
        # 诊断2：留痕模式开关卡状态来源（runtime_flags 值）+ 变更 toast 文案
        "状态来源：runtime_flags（qc_auto_sign_full=", "（runtime_flags 已更新，即时生效）",
        # 诊断3：uploadKb 失败统一「失败：文件名（状态码 原因）」格式（原因读响应 detail/文本）
        "'（'+r.status+' '+", "String(txt||'').trim().slice(0,80)",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


# ---------- 本批次 5 项修复的回归锁（追加，不改动既有断言） ----------

def _extract_fn(src: str, name: str) -> str:
    """从内联脚本源码截取完整 JS 函数定义（按「function name(」起点 + 列 0 收口「}」）。
    humanizeAudit 内字符串中的花括号均来自成对的 ${...} 模板插值，列 0 的「}」必为函数收口。"""
    start = src.index("function " + name + "(")
    end = src.index("\n}", start)
    return src[start:end + 2]


def test_humanize_audit_consult_events_node():
    """任务3：humanizeAudit 对 consult 各事件的输出断言（纯函数 → node 直接单测）。"""
    node = shutil.which("node")
    if not node:
        pytest.skip("node 不可用，跳过 humanizeAudit 单测")
    html = FRONTEND.read_text(encoding="utf-8")
    fn = _extract_fn(html, "humanizeAudit")
    cases = [
        # [输入事件, 期望人话化输出]
        [{"event": "consult", "action": "consult_created",
          "payload": {"cid": "c1", "depts": ["心内科", "内分泌科"], "role": "doctor"}},
         "发起了跨科室会诊（召集：心内科、内分泌科）"],
        [{"event": "consult", "action": "consult_created", "payload": {"cid": "c1"}},
         "发起了跨科室会诊"],  # payload 缺 depts → 兜底不带括号
        [{"event": "consult", "action": "consult_dispatch",
          "payload": {"cid": "c1", "departments": ["a", "b", "c"]}},
         "会诊分发给 3 个科室"],
        [{"event": "consult", "action": "consult_opinion",
          "payload": {"cid": "c1", "dept": "心内科"}},
         "提交了会诊意见（心内科）"],
        [{"event": "consult", "action": "consult_closed", "payload": {"cid": "c9"}},
         "结束了会诊 c9"],
        [{"event": "consult", "action": "list_mine",
          "payload": {"initiated": 5, "participated": 0, "role": "doctor"}},
         "查看了我的会诊（发起 5 · 参与 0）"],
        [{"event": "consult", "action": "list_inbox", "payload": {"n": 2, "dept": "心内科"}},
         "查看了会诊收件箱（2 条待意见）"],
        # 裸 payload 兜底：缺省值不产生 undefined/null 字样
        [{"event": "consult", "action": "list_mine", "payload": {}},
         "查看了我的会诊（发起 0 · 参与 0）"],
        [{"event": "consult", "action": "list_inbox", "payload": {}},
         "查看了会诊收件箱（0 条待意见）"],
    ]
    runner = (fn + "\nconst CASES=" + json.dumps(cases, ensure_ascii=False) + ";\n"
              "for(const [e,want] of CASES){const got=humanizeAudit(e);"
              "if(got!==want){console.error('FAIL action='+e.action+' got=['+got+'] want=['+want+']');"
              "process.exit(1);}}"
              "console.log('HUMANIZE_OK '+CASES.length);")
    fd = tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8")
    fd.write(runner)
    fd.close()
    try:
        r = subprocess.run([node, fd.name], capture_output=True, text=True, timeout=60)
    finally:
        Path(fd.name).unlink(missing_ok=True)
    assert r.returncode == 0, f"humanizeAudit consult 断言失败：{r.stderr[:600]}"
    assert "HUMANIZE_OK" in r.stdout


def test_front_5fixes_markers_present():
    """本批次 5 项修复的前端标记锁（追加）：
    - 任务1：发起会诊确认成功回调里自动关闭 modal；
    - 任务2：会诊详情 emoji → ICON 表新增 doc/analysis/staff 三枚 SVG；
    - 任务3：humanizeAudit consult_* 事件人话化映射；
    - 任务4：日志健康卡（路径/大小/总行数元数据 + ERROR/WARNING 行高亮）；
    - 任务5：KB 上传成功（含轮询 done）后刷新列表与向量总数。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 任务1：POST /consults 200 回调里关闭确认弹窗（复用共享 closeModal）
        "closeModal();  /* 任务1：发起成功自动关闭确认弹窗",
        # 任务2：ICON 表新增三枚图标 + consultDetailBlocks 三段式标题全部换用
        "doc:'<svg", "analysis:'<svg", "staff:'<svg",
        "ICON.doc+' 摘要", "ICON.doc+' AI 会诊小结", "ICON.doc+' 会诊汇总",
        "ICON.analysis+' 各专科 AI 意见", "ICON.staff+' 各科室医生意见",
        # 任务3：consult 事件人话化文案
        "发起了跨科室会诊（召集：", "会诊分发给 ", "提交了会诊意见（",
        "结束了会诊 ", "查看了我的会诊（发起 ", "查看了会诊收件箱（",
        # 任务4：日志健康卡（元数据行 + 大小人话化 + 等级高亮 + CSS）
        "appLogMeta", "fmtLogSize", "hlLogLevels", "总行数：", "log-err", "log-warn",
        ".log-err{", ".log-warn{",
        # 任务5：上传成功（任一）→ 重新拉取列表/总数
        "if(okAny)loadKb()",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


_EMOJI_RE = re.compile(
    "[\U0001F000-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF"
    "\u2B00-\u2BFF\uFE0F\u200D\u2049\u203C\u2122]")
_EMOJI_ALLOW = {"⚠", "✓", "✗"}  # 文本符号（错误提示/连通性文字），非图标不在清理范围


def test_no_emoji_icons_remain():
    """任务2 回归锁：前端不得残留 emoji 图标（如 📌/🔬/👨‍⚕️，含 ZWJ/FE0F 组合件）；
    ⚠/✓/✗ 为文本提示符号（错误文案），显式豁免。"""
    html = FRONTEND.read_text(encoding="utf-8")
    bad = sorted({c for c in _EMOJI_RE.findall(html) if c not in _EMOJI_ALLOW})
    assert not bad, f"残留 emoji 图标：{bad}"


# ---------- 任务：审核中心列表行缩略图条（追加标记锁，不改动既有断言） ----------

def test_review_thumbs_frontend_markers_present():
    """审核中心列表行缩略图条标记锁（防回退）：
    - reviewThumbStrip 缩略图渲染函数 + mountReviewThumbs 占位挂载（列表行 innerHTML 后挂 DOM）；
    - data:image/ 前缀白名单过滤必须存在于缩略图函数体内；
    - 安全纪律：createElement('img') + 属性赋值 .src，禁止 innerHTML 拼图片 src；
    - grid 多图布局样式（.review-thumbs，每图 max-height 120px）+ 点击复用 showImg 放大。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        "function reviewThumbStrip(", "function mountReviewThumbs(",
        "data-rthumbs",            # 行内占位容器（reviewRow / myConfirmRow 各一处 + 挂载查询）
        ".review-thumbs{",          # grid 多图布局
        ".review-thumbs img{", "max-height:120px",  # 每图最高 120px
        "mountReviewThumbs(hslice)",
            # 本批次任务2 注记：pharmacist 视图新增 drug 待核对表，缩略图挂载合并传参
            # （drugItems.concat(sliceM)）——原字面量 mountReviewThumbs(sliceM) 相应更新
            "mountReviewThumbs(drugItems.concat(sliceM))", "mountReviewThumbs(slice)",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    fn = _extract_fn(html, "reviewThumbStrip")
    # 白名单过滤 + createElement 属性赋值必须存在于缩略图函数体内（而非仅其它函数）
    assert "startsWith('data:image/')" in fn, "缩略图渲染缺少 data:image/ 前缀白名单过滤"
    assert "createElement('img')" in fn, "缩略图必须 createElement('img') 创建"
    assert ".src=u" in fn, "图片 src 必须属性赋值"
    assert "innerHTML" not in fn, "缩略图渲染禁止 innerHTML 拼图片 src"
    assert "showImg" in fn, "缩略图点击应复用 showImg 放大"


# ---------- 任务3 admin 面板排版（追加标记锁，不改动既有断言） ----------

def test_admin_panel_layout_markers_present():
    """任务3 排版标记锁：admin 数据面板 .dash 改 masonry 式（CSS columns:2 自动均衡），
    留痕模式卡紧邻审计流（DOM 顺序即列内顺序）；卡片 break-inside:avoid 不跨列截断；
    高度动态变化（审计流展开/用户表增删）时浏览器自动重排，任何卡片下方不留大块空白。"""
    html = FRONTEND.read_text(encoding="utf-8")
    assert ".dash{columns:2;column-gap:16px}" in html, "缺少 masonry 双列样式"
    assert ".dash>.panel{break-inside:avoid;margin:0 0 16px}" in html, "缺少卡片不跨列截断样式"
    assert "@media(max-width:1024px){.dash{columns:1}}" in html, "缺少窄屏单列回落"
    assert "任务3 排版：masonry 式" in html, "缺少 masonry 重排注释标记"
    assert "留痕模式卡紧邻审计流" in html, "留痕模式卡必须紧邻审计流"
    # 留痕模式卡必须仅渲染一处，且旧的整宽 margin-top 版本已移除
    assert html.count("留痕模式（全交互留痕 · 运行时开关）") == 1, "留痕模式卡必须仅渲染一处"
    assert '<div class="panel" style="margin-top:16px"><h3>留痕模式' not in html, \
        "旧的整宽留痕模式卡（margin-top 版）必须已移除"


# ---------- 本批次任务1/2 前端标记锁（追加，不改动既有断言） ----------

def test_resubmit_jump_markers_present():
    """任务1（本批次）标记锁：我的质控驳回「重新提交」→ 切质控工作台视图（go('qc')）
    + 滚动定位到病历表单（scrollIntoView）+ 表单短暂高亮（.qc-flash CSS 边框闪烁，2s 移除）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        'id="qcFormCard"',                       # 病历表单卡定位锚点
        "@keyframes qcFlash", ".qc-flash{",      # 边框闪烁动画样式
        "if(cur!=='qc')go('qc');",               # 复用 go 切质控视图（已在 qc 不重复切换）
        "formCard.scrollIntoView",               # 滚动到病历表单
        "formCard.classList.add('qc-flash')",    # 加高亮 class
        "formCard.classList.remove('qc-flash'),2000",  # 2s 后移除
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_pharm_sign_markers_present():
    """任务2（本批次）标记锁：药剂科一票——按 agent 分流的签字权（canSignItem）、
    pharmacist drug 待核对表与「药物高危结论需药剂科复核」文案。
    问题4 语义变更注明：drug 类签字权由 qc/admin/pharmacist 收窄为 pharmacist/admin
    （质控科只管病例，药物核对权=药剂科+管理员），原 canSignItem 断言随之更新。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        "function canSignItem(",                       # 签字权按 agent 分流
        # 问题4 收窄后的 drug 签字权矩阵（原 ROLE==='qc'||ROLE==='admin'||ROLE==='pharmacist' 已移除 qc）
        "if(p.agent==='drug')return ROLE==='admin'||ROLE==='pharmacist';",
        "canSignItem(p)?",                             # 列表行按条目出签发/驳回按钮
        "canSignItem(p)&&!mine",                       # 详情模态操作区同矩阵
        "药物高危结论需药剂科复核",                       # 核心文案（头部说明 + 空态提示）
        "药物高危待核对（需药剂科复核）",                  # pharmacist drug 待核对表头
        "pharmacist（药剂科一票）额外拉取待核对清单",       # 药剂科 pending 拉取标记
        "审核中心 · 药剂科药物核对",                       # pharmacist 审核中心标题
        # 问题4：qc 看 drug 项的只读说明文案（drug 例外：药师/管理员，不再是质控员）
        "药物核对项：签发/驳回由药师（pharmacist）/管理员执行",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_humanize_full_map_markers_present():
    """任务3（本批次）标记锁：humanizeAudit 显式映射表（MAP）+ 覆盖回归锁配套注释。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        "const MAP={",
        "tests/test_audit_humanize_coverage.py",  # 映射表头注释指向覆盖回归锁
        "药物问答（置信 ${cf}",                    # drug.answered / drug.answer 人话
        "查看了我的质控驳回（${p.n} 条）",
        "提交了质控结论（置信 ${cf}",              # qc.answer 点分镜像
        "留痕模式自动签发（${rid}）",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


# ---------- 阶段0.1/0.2：重提预填真实修复 + drug 驳回分流（追加标记锁） ----------

def test_phase0_frontend_markers_present():
    """阶段0 前端标记锁（防回退）：
    - 0.1：qcResubmit 从 meta.record 逐栏直填并统计 filled；仅确有预填内容才声明
      「已预填」，旧版无留档条目如实提示手动填写（不再谎报）；
    - 0.2：药物助手视图置顶「曾被药剂科驳回」提示——【阶段3 更新注明】开药工作台
      （rx 视图）取代 drug 视图，药物助手独立入口整体删除（AGENTS.drug/导航/loadDrugRejections/
      chat 免责条），0.2 的 drug 视图标记随新结构移除；drug 类历史驳回仍可在
      审核中心历史与审计流中回溯，相关断言并入 test_phase3_rx_frontend_markers_present
      的「独立入口已删」反向锁。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 0.1：结构化逐栏直填 + 预填内容计数（签名栏 i=6 锁定当前账号不回填）
        "let filled=0;",
        "QC_LABELS.forEach((l,i)=>{if(i===6)return;const v=String(rec[l]||'');"
        "document.getElementById('qcf_'+i).value=v;if(v.trim())filled++;});",
        "${filled?'原病历内容已预填'",                 # 横幅诚实化：有值才声明已预填
        "该记录未留存结构化原病历（旧版条目），请手动填写病历内容",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    fn = _extract_fn(html, "qcResubmit")
    assert "filled++" in fn, "qcResubmit 必须统计非空预填栏数（横幅诚实化依据）"
    assert "qcLabs" in fn, "qcResubmit 必须回填检验值（meta.labs → qcLabs）"


# ---------- 阶段3：开药前端（rx 视图取代 drug 视图 + 药剂科队列 + 我的处方）标记锁 ----------

def test_phase3_rx_frontend_markers_present():
    """阶段3 前端标记锁（防回退，docs/plans/pharmacy_roadmap.md 阶段3）：
    - 3.1 开药工作台：rx 视图/导航位存在，suggest→勾选→自定义加药（字典外确认）→
      药单→高危阻断+联用理由→提交全链路标记；免责声明条复用 DRUG_DISCLAIMER；
    - 药物助手独立入口已删（AGENTS.drug / drug 导航项 / loadDrugRejections / chat 免责条）；
    - 3.2 药剂科开药审核队列：开药待审区 + 通过/驳回（驳回必填意见）+ forced 红标；
    - 3.3 我的处方：状态徽章（pending_pharm 黄/approved 绿/rejected 红/draft 灰）+
      驳回意见 + 重写回填（提交走 rewrite 自动重提）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 3.1 开药工作台：导航与视图骨架
        "{k:'rx',t:'开药工作台'}", 'id="scr-rx"', "function loadRx(",
        "function switchRxTab(", 'id="rxTabCompose"', 'id="rxTabMine"',
        # 3.1 suggest → 推荐卡片（勾选）+ 中危警示
        "function rxSuggest(", "生成开药建议", "prescriptions/suggest",
        "function rxPick(", "function renderRxSuggest(", "⚠ 中危联用警示",
        # 3.1 自定义加药：字典搜索 + 字典外确认弹条
        "function rxDictFilter(", "function rxAddDict(", "function rxAddManual(",
        "/drug/dict", "该药不在字典，药剂科将重点审核",
        # 3.1 已选药单（可删/可编辑）
        "function renderRxSelected(", "function rxEditDrug(", "function rxDelDrug(",
        # 3.1 高危联用阻断条 + 联用理由（填了才允许提交）+ 提交
        'id="rxBlockPanel"', "⚠ 高危联用已阻断", 'id="rxReason"',
        "contraindication_reason", "function rxSubmit(", "提交开药",
        "apiPost('/prescriptions',",
        "prescriptions/'+encodeURIComponent(rxEditId)+'/rewrite",  # 改写重提
        # 免责声明条（复用 DRUG_DISCLAIMER 常量）
        'id="rxDisclaimer"', "DRUG_DISCLAIMER",
        # 3.2 药剂科开药审核队列
        "prescriptions/pending", "rxPendingItems", "function rxPendingSection(",
        "开药待审（处方队列）", "function rxReviewCard(", "高危强制开立</span>",
        "function rxReview(", "_rxReviewPost", "/review'",
        # 3.3 我的处方：状态徽章 + 驳回意见 + 重写回填
        "prescriptions/mine", "function loadRxMine(", "function rxMineCard(",
        "pending_pharm:['待药剂科审核','warn']", "rejected:['已驳回','bad']",
        "draft:['草稿','']", "function rxStartRewrite(", "药剂科意见：",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # 药物助手独立入口已删（反向锁：残留即回退）
    for gone in (
        "drug:{name:'药物信息助手'",        # AGENTS.drug 独立入口
        "{k:'drug',t:'药物信息'}",          # drug 导航项
        "async function loadDrugRejections(",  # drug 视图驳回提示函数
        'id="drugDisclaimer"',              # chat 屏 drug 免责条（移至 rx 视图）
        "if(k==='drug')loadDrugRejections();",
    ):
        assert gone not in html, f"药物助手独立入口应已删除，仍残留：{gone}"


# ---------- 开药审核 UX 三修复标记锁（追加，不改动既有断言） ----------

def test_rx_review_ux_fixes_frontend_markers_present():
    """开药审核 UX 三修复标记锁（防回退）：
    - 问题1：pharmacist「我的历史」= 本人参与的记录（submitted_by=me OR reviewed_by=me
      前端过滤；他人记录仍不显示，数据隔离边界不放宽）；
    - 问题2：rx 导航红点角标=本人被驳回处方数（refreshRxBadge 从 /prescriptions/mine 统计；
      切换 rx 视图/提交处方/登录后刷新；点进「我的处方」本地清零）；
    - 问题3：处方卡与核对详情显示审核人+操作时间（pharm_reviewed_at / resolved_at），
      审核 history 行同理带操作时间。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        # 问题1：「我的历史」过滤含本人作为审核人的记录
        "i.submitted_by===reviewMe||i.reviewed_by===reviewMe",
        # 问题2：rx 被驳回处方红点角标 + 刷新/清零时机
        "function refreshRxBadge(", "rxRejectedCount",
        'title="被驳回的处方"',                       # 红点角标语义（tag red num）
        "rxRejectedCount=0;renderRail();loadRxMine();",  # 点进「我的处方」清零
        # 问题3：审核人 + 操作时间留痕显示（处方卡 / 核对详情 / history 行）
        "pharm_reviewed_at", "审核人 ",
        "' · '+fmtTs(p.resolved_at)",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    fn = _extract_fn(html, "switchRxTab")
    assert "rxRejectedCount=0" in fn, "switchRxTab(mine) 必须本地清零被驳回角标"
    badge = _extract_fn(html, "refreshRxBadge")
    assert "prescriptions/mine" in badge and "rejected" in badge, \
        "角标数据源必须为 /prescriptions/mine 的 rejected 统计（无需新端点）"
    assert "ROLE!=='doctor'" in badge, "角标仅 doctor 刷新（mine 为 doctor 专属，避免 403 噪音）"
    assert html.count("refreshRxBadge()") >= 3, "切换 rx 视图/提交处方/登录后均须刷新角标"


# ---------- 本批 4 项修复标记锁（追加，不改动既有断言；语义变更另行注明） ----------

def test_rx_round2_suggest_sync_markers_present():
    """问题1 标记锁：renderRxSuggest 渲染时对 checked=true 的建议项同步调用加入药单
    函数（rxPick）——勾选框默认选中必须程序化触发加入逻辑（去重：药名+剂量相同不重复加），
    已选药单不再为空（用户取消勾选再勾选才加入的回退被锁定）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    fn = _extract_fn(html, "renderRxSuggest")
    assert "rxPick(i,true)" in fn, "renderRxSuggest 必须对勾选项同步调用加入药单函数 rxPick"
    assert "!s.pick" in fn, "仅同步 checked=true 的建议项"
    # 去重：药名+剂量都相同不重复加（重复渲染幂等）
    assert "d.name===s.name&&(d.dose||'')===(s.dose||'')" in fn, \
        "同步加入必须按药名+剂量去重（防重复渲染重复加）"


def test_rx_round2_review_badge_markers_present():
    """问题2 标记锁：审核中心数字角标（导航入口）——refreshReviewBadge 复用
    refreshRxBadge 模式（登录/切视图刷新）；pharmacist=drug 待核对+待审处方，
    qc=其它助手高危项（问题4 收权后不审 drug/开药），admin=全部待核对+待审处方。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        "function refreshReviewBadge(",                    # 角标数据刷新函数
        "reviewTodoCount",                                 # 角标数状态
        'title="待办：药物核对/待审处方"',                     # pharmacist 待办角标语义
        'title="待核对（其它助手高危项）"',                    # qc 角标语义（问题4 收权）
        "refreshReviewBadge();",                           # 登录即刷新（boot）
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    fn = _extract_fn(html, "refreshReviewBadge")
    assert "review/pending" in fn, "角标数据源必须含 /review/pending（drug 待核对/其它高危项）"
    assert "prescriptions/pending" in fn, "角标数据源必须含 /prescriptions/pending（待审处方数）"
    assert "i.agent==='drug'" in fn and "i.agent!=='drug'" in fn, \
        "pharmacist 只算 drug 项、qc 只算其它助手高危项（问题4 分流）"
    # 刷新时机：登录 boot + 进入审核中心（loadReview/loadReviewMine 更新角标并重绘导航）
    assert html.count("refreshReviewBadge()") >= 2, "登录与进入审核中心均须刷新角标"
    assert "reviewTodoCount=(reviewItems||[]).length+(rxPendingItems||[]).length" in html, \
        "pharmacist 角标=drug 待核对数+待审处方数（进入审核中心即刷新）"


def test_rx_round2_reviewed_history_markers_present():
    """问题3 标记锁：①「我的历史」前端过滤含本人作为审核人的记录（既有锁保持）；
    ②pharmacist 拉取 GET /prescriptions/reviewed-by-me 并在「我的历史」tab 合并渲染
    处方审核记录（rxHistRow 专用行：rid/状态/意见/时间）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    for marker in (
        "prescriptions/reviewed-by-me",                    # 问题3② 新端点调用
        "rxHistoryItems",                                  # 处方审核历史缓存
        "function rxHistRow(",                             # 处方审核历史行渲染
        "处方审核：",                                        # 行首标识（rid 可见）
        "p._rx?rxHistRow(p):reviewRow(p,true)",            # 「我的历史」合并渲染分流
        "i.submitted_by===reviewMe||i.reviewed_by===reviewMe",  # 问题3① 前端过滤（保持）
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    fn = _extract_fn(html, "rxHistRow")
    for field in ("p.id", "p.status", "p.pharm_opinion", "p.pharm_reviewed_at"):
        assert field in fn, f"处方审核历史行必须展示 {field}（rid/状态/意见/时间）"


def test_rx_round2_qc_scope_markers_present():
    """问题4 标记锁：qc 不审核药剂——①开药待审区仅 pharmacist/admin 渲染
    （qc 登录不渲染开药待审区）；②qc/admin 路径不拉取开药待审队列（403 不留旧值）。"""
    html = FRONTEND.read_text(encoding="utf-8")
    fn = _extract_fn(html, "rxPendingSection")
    assert "ROLE!=='pharmacist'&&ROLE!=='admin'" in fn, \
        "开药待审区必须仅 pharmacist/admin 渲染（qc/doctor 恒空串）"
    assert "if(ROLE==='admin'){" in html, \
        "qc/admin 路径仅 admin 拉取 /prescriptions/pending（qc 403 不请求）"
