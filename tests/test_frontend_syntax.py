"""轮4 Vue3 切换后前端标记锁（docs/archive/plans/vue3_migration.md 迁移轮收尾）。

断言源：frontend-vue/src/** 拼接全文（Vue SFC / JS / CSS，按路径排序保证确定性）——
legacy frontend/index.html + frontend/js/*.js 已随轮4 删除（git tag v-legacy-frontend
永久保存，回退见手册）。迁移口径：
- legacy 特有实现标记（如 rxPick/renderRxSuggest 函数名）→ 对应 Vue 组件内同语义实现
  的存在断言（函数名迁移时保留原命名，Vue 模板标记改 data-testid/文案逐字）；
- node --check 逐文件语法校验 → 语义由 vite build 接管（构建期编译校验，部署步骤
  手动执行 npm ci && npm run build）；本文件只断言 dist 构建产物与 src 同步存在
  （dist 随轮入库策略：改前端必须重新构建提交 dist）；
- 已无对应物的断言（legacy DOM 挂载/事件委托/masonry 排版等 vanilla 特有实现）
  删除，并在对应 docstring 注明「语义由 Playwright 行为测试接管」或由 Vue 框架
  机制（模板插值自动转义 / @click 声明式绑定）接管。
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VUE_ROOT = ROOT / "frontend-vue"
VUE_SRC = VUE_ROOT / "src"
VUE_DIST = VUE_ROOT / "dist"


def _vue_source() -> str:
    """拼接 frontend-vue/src 全部源文件（Vue SFC/JS/CSS，轮4 起前端标记锁的权威全文）。"""
    files = sorted(p for p in VUE_SRC.rglob("*") if p.suffix in (".vue", ".js", ".css", ".html"))
    assert files, "frontend-vue/src 应存在源文件"
    return "\n".join(p.read_text(encoding="utf-8") for p in files)


# 兼容别名：test_audit_humanize_coverage / test_drug_dict_admin 等经此取源。
# （轮4 前 _frontend_source 指 legacy index.html+js 拼接，轮4 起统一切到 Vue 源。）
def _frontend_source() -> str:
    return _vue_source()


def _all_frontend_js() -> str:
    """拼接 src 下全部 .js 文件（humanizeAudit 等纯 JS 工具的提取源；旧调用方兼容）。"""
    files = sorted(p for p in VUE_SRC.rglob("*.js"))
    assert files, "frontend-vue/src 应存在 JS 文件"
    return "\n".join(p.read_text(encoding="utf-8") for p in files)


def _extract_fn(src: str, name: str) -> str:
    """截取「function name(」起点到列 0 收口「}」的完整函数体。

    Vue <script setup> 顶层函数与 utils/*.js 模块级函数均为列 0 收口（函数体内
    字符串模板花括号均成对且缩进，列 0 的「}」必为函数收口）。"""
    start = src.index("function " + name + "(")
    end = src.index("\n}", start)
    return src[start:end + 2]


def _extract_export_fn(src: str, name: str) -> str:
    """截取 export function（utils/*.js 模块级导出函数）到下一个模块级导出前的完整函数体。"""
    start = src.index("export function " + name + "(")
    nxt = src.find("\nexport ", start + 1)
    return src[start:nxt] if nxt >= 0 else src[start:]


def _extract_badge_action(name: str) -> str:
    """从 stores/badges.js 截取 store action 方法体（pinia actions 内 4 空格缩进的
    async name() { ... } 方法，到下一个同缩进 async 方法前）。"""
    src = (VUE_SRC / "stores" / "badges.js").read_text(encoding="utf-8")
    start = src.index(f"async {name}(")
    nxt = src.find("\n    async ", start + 1)
    return src[start:nxt] if nxt >= 0 else src[start:]


# ---------- 构建产物与源同步（vite build 接管 node --check 语义后的回归锁） ----------

def test_vue_dist_build_output_in_sync():
    """dist 构建产物存在且与 src 同步（「dist 随轮入库」策略的回归锁）：
    - dist/index.html 存在（回退免 node 策略：checkout 即可用，无需现场构建）；
    - dist/assets 至少 1 个 JS 构建产物；
    - src 源文件数 > 0（防止 dist 存在但源被误删的伪同步）。
    构建由部署步骤执行（npm ci && npm run build，见 docs/DEPLOY.md 前端构建与部署）；
    语法校验由 vite build 编译期接管（替代 legacy 时期的 node --check 逐文件校验）。"""
    idx = VUE_DIST / "index.html"
    assert idx.is_file(), "frontend-vue/dist/index.html 应入库（dist 随轮提交策略）"
    assets = VUE_DIST / "assets"
    assert assets.is_dir(), "dist/assets 应存在"
    built_js = list(assets.glob("*.js"))
    assert built_js, "dist/assets 应至少存在 1 个 JS 构建产物"
    src_files = [p for p in VUE_SRC.rglob("*") if p.suffix in (".vue", ".js", ".css")]
    assert src_files, "frontend-vue/src 源文件不应为空（dist 与 src 同步性）"
    html = idx.read_text(encoding="utf-8")
    assert "/assets/" in html and 'id="app"' in html, \
        "dist/index.html 应引用 /assets/* 产物且含 Vue 挂载点 #app（vite base=/）"


# ---------- 任务5/6 前端标记锁（应用日志卡 / 质控重提链） ----------

def test_task5_task6_frontend_markers_present():
    """任务5/6 关键标记锁（Vue 语义版）：
    - 任务5：应用日志健康卡（DataView log-panel）——loadAppLogs 拉取 /medical/admin/logs
      （lines 参数）、行级 ERROR/WARNING 高亮 class；日志行经 Vue 模板插值渲染
      （自动转义，接管 legacy 逐行 esc() 防注入语义）；
    - 任务6：质控重提链（QcView）——qcResubmit 预填（resubmit_of 随运行质控提交）、
      rejected_escalated 三档徽标、已升级病案科复核徽标、第 N 次提交徽标。"""
    html = _vue_source()
    for marker in (
        # 任务5：应用日志 tail 卡片（DataView.vue）
        "function loadAppLogs()", "'/medical/admin/logs'", "params: { lines: n }",
        "'log-err': l.indexOf('ERROR') >= 0", "'log-warn': l.indexOf('WARNING') >= 0",
        "logMeta",  # 文件/大小/总行数元数据行
        # 任务6：重新提交 / 预填 / 徽标 / escalated 状态（QcView.vue）
        "function qcResubmit(", "resubmit_of", "rejected_escalated",
        "已升级病案科复核", "次提交",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_task1234_frontend_markers_present():
    """任务1/3/4 前端关键标记锁（Vue 语义版）：
    - 任务1：myconsults 导航项存在（接管 legacy「myconsults SVG 图标」标记——Vue 布局壳
      导航为 RouterLink 文本项，图标语义由 Playwright 行为测试接管）+ 审计流人话化
      （humanizeAudit 于 DataView 审计流/搜索 haystack 中使用，Vue 模板插值自动转义）；
    - 任务3：影像空响应兜底文案 + admin 留痕模式开关卡（runtime 开关 + 确认弹条）；
    - 任务4：审核中心 tab 未读徽标（badges store + AppShell 角标渲染）。
    legacy updateConsultBadge/refreshConsultBadge（会诊 tab 未读徽标）在 Vue 重写中
    未迁移（轮3 角标语义仅 review/rx 两类），对应断言删除——会诊视图行为语义由
    Playwright 行为测试接管（tests/e2e/test_vue_assist.py 打开「我的会诊」断言）。"""
    html = _vue_source()
    for marker in (
        # 任务1：myconsults 导航 + 审计人话化
        "myconsults: { t: '我的会诊' }", "humanizeAudit(e)",
        # 任务3：空响应兜底 + 留痕模式开关卡（文案逐字）
        "AI 未生成所见，请重新上传或补充描述",
        "留痕模式（全交互留痕 · 运行时开关）", "function toggleFullFlag()",
        "'/medical/admin/config-toggle'", "（runtime_flags 已更新，即时生效）",
        "状态来源：runtime_flags（qc_auto_sign_full=",
        # 任务4：审核中心角标（store 动作 + AppShell 渲染）
        "async refreshReviewBadge(", "reviewTodoCount",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # 留痕模式卡仅渲染一处（防重复卡片；计数只针对模板 h3 文本，不含注释引用）
    assert html.count(">留痕模式（全交互留痕 · 运行时开关）<") == 1, "留痕模式卡必须仅渲染一处"


def test_diag_fixes_frontend_markers_present():
    """诊断1/2/3 前端标记锁（Vue 语义版）：
    - 诊断1：imaging 视图「图片已发送」提示（区分待发送图与历史已发送图）——Vue 影像视图
      重写为表单式（已选 N 张计数 + 缩略预览+单张删除），无 chat 式「已发送」概念，
      对应断言删除，语义由 Playwright 行为测试接管（上传→预览→提交→结构化所见渲染）；
    - 诊断2：留痕模式开关卡显示 runtime_flags 当前值来源 + 变更 toast（文案逐字）；
    - 诊断3：KB 上传失败统一「失败：文件名（状态码 原因）」格式（先取响应文本/状态码，
      不再吞原因；终评 F2 XSS 收口：拼接经 esc() 转义后进 v-html）。"""
    html = _vue_source()
    for marker in (
        # 诊断2：留痕模式开关卡状态来源（runtime_flags 值）+ 变更 toast 文案
        "状态来源：runtime_flags（qc_auto_sign_full=", "（runtime_flags 已更新，即时生效）",
        # 诊断3：上传失败统一「失败：文件名（状态码 原因）」（KbView uploadKb；F2 起 esc 包裹）
        "kbMsg.value = '失败：' + esc(f.name) + '（' + (st || '网络异常')",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


# ---------- humanizeAudit 纯函数 node 直跑单测（consult 事件族） ----------

def test_humanize_audit_consult_events_node():
    """任务3：humanizeAudit 对 consult 各事件的输出断言（纯函数 → node 直接单测）。
    函数源提取自 frontend-vue/src/utils/audit.js（去掉 export 前缀后即可独立运行）。"""
    import json
    import shutil
    import subprocess
    import tempfile

    node = shutil.which("node")
    if not node:
        import pytest
        pytest.skip("node 不可用，跳过 humanizeAudit 单测")
    fn = _extract_export_fn(_all_frontend_js(), "humanizeAudit").replace(
        "export function humanizeAudit(", "function humanizeAudit(", 1)
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
    r = subprocess.run([node, fd.name], capture_output=True, text=True, timeout=60)
    Path(fd.name).unlink(missing_ok=True)
    assert r.returncode == 0, f"humanizeAudit consult 断言失败：{r.stderr[:600]}"
    assert "HUMANIZE_OK" in r.stdout


def test_front_5fixes_markers_present():
    """本批次 5 项修复的前端标记锁（Vue 语义版）：
    - 任务1：发起真实会诊成功后自动清空病例与底稿（接管 legacy「closeModal 自动关弹窗」
      标记——Vue 用 ElMessageBox 承载确认，成功回调语义=清空待附影像+底稿）；
    - 任务2：我的会诊三段式详情标题（摘要/AI 会诊小结/会诊汇总/各专科 AI 意见/各科室医生意见，
      接管 legacy ICON 表 SVG 标记——Vue 用 el-collapse 标题文本承载同语义）；
    - 任务3：humanizeAudit consult_* 事件人话化文案（utils/audit.js 逐字迁移）；
    - 任务4：日志健康卡元数据行 + 大小人话化 + 等级高亮（CSS 类）；
    - 任务5：KB 上传成功（含轮询 done）后刷新列表与向量总数。"""
    html = _vue_source()
    for marker in (
        # 任务1：发起成功回调清空底稿与待附影像（ConsultView startRealConsult）
        "lastMdtReport = '' // 底稿已随单发出", "consultImages.value = [] // 图已随单发出，清空待附影像",
        # 任务2：三段式详情标题（legacy ICON.doc/analysis/staff 的 Vue 文本承载）
        "摘要（病例/会诊问题）", "AI 会诊小结（共识 · 分歧 · 建议）", "会诊汇总",
        "各专科 AI 意见（", "各科室医生意见（并排署名 · 仅建议权）",
        # 任务3：consult 事件人话化文案
        "发起了跨科室会诊（召集：", "会诊分发给 ", "提交了会诊意见（",
        "结束了会诊 ", "查看了我的会诊（发起 ", "查看了会诊收件箱（",
        # 任务4：日志健康卡（元数据行 + 大小人话化 + 等级高亮 + CSS）
        "logMeta", "fmtLogSize", "总行数：", "log-err", "log-warn",
        ".log-err {", ".log-warn {",
        # 任务5：上传成功（任一）→ 重新拉取列表/总数
        "if (okAny) loadKb()",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


_EMOJI_RE = re.compile(
    "[\U0001F000-\U0001FAFF\U00002600-\U000027BF\U0001F1E6-\U0001F1FF"
    "\u2B00-\u2BFF\uFE0F\u200D\u2049\u203C\u2122]")
_EMOJI_ALLOW = {"⚠", "✓", "✗"}  # 文本符号（错误提示/连通性文字），非图标不在清理范围


def test_no_emoji_icons_remain():
    """任务2 回归锁：前端源不得残留 emoji 图标（如 📌/🔬/👨‍⚕️，含 ZWJ/FE0F 组合件）；
    ⚠/✓/✗ 为文本提示符号（警示条/连通性文案），显式豁免。"""
    html = _vue_source()
    bad = sorted({c for c in _EMOJI_RE.findall(html) if c not in _EMOJI_ALLOW})
    assert not bad, f"残留 emoji 图标：{bad}"


# ---------- 审核中心列表行缩略图条（data:image/ 白名单消费） ----------

def test_review_thumbs_frontend_markers_present():
    """审核中心列表行缩略图条标记锁（Vue 语义版）：
    - imgOf() data:image/ 前缀白名单过滤（_review_image_view 按角色收窄后的前端消费）；
    - 缩略图经 Vue 模板 <img :src> 绑定渲染（接管 legacy createElement('img') 属性赋值
      纪律——模板插值无字符串拼接 src，innerHTML 注入面不存在）；点击 showImg 放大；
    - .review-thumbs grid 布局样式。
    问题2 语义变更注明：pharmacist「药物待核对」表已随两药快查下线移除；legacy 每图
    max-height:120px 样式在 Vue 布局（grid + aspect-ratio 等比缩略）下无对应物，
    相应样式断言删除（视觉语义由 Playwright 行为测试接管）。"""
    html = _vue_source()
    for marker in (
        "function imgOf(p)", ".review-thumbs {", "review-thumbs",
        "function showImg(src)", "@click=\"showImg(u)\"",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    fn = _extract_fn(html, "imgOf")
    # 白名单过滤必须存在于缩略图过滤函数体内（而非仅其它函数）
    assert "startsWith('data:image/')" in fn, "缩略图渲染缺少 data:image/ 前缀白名单过滤"
    assert "innerHTML" not in fn, "缩略图渲染禁止 innerHTML 拼图片 src"


# ---------- 阶段0/阶段3：质控重提预填 + 开药工作台全链路标记锁 ----------

def test_humanize_full_map_markers_present():
    """任务3（本批次）标记锁：humanizeAudit 显式映射表（MAP）+ 覆盖回归锁配套注释
    （Vue 版：映射表逐字迁移于 frontend-vue/src/utils/audit.js）。"""
    html = _vue_source()
    for marker in (
        "const MAP = {",
        "tests/test_audit_humanize_coverage.py",  # 映射表头注释指向覆盖回归锁
        "药物问答（置信 ${cf}",                    # drug.answered / drug.answer 人话
        "查看了我的质控驳回（${p.n} 条）",
        "提交了质控结论（置信 ${cf}",              # qc.answer 点分镜像
        "留痕模式自动签发（${rid}）",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_pharm_sign_markers_present():
    """任务2（历史批次）标记锁（Vue 语义版）：药剂科一票——按 agent 分流的签字权
    （canSignItem）。问题2 语义变更注明：pharmacist「药物待核对」表与相关文案已随
    两药快查下线移除（审核中心=开药待审+我的历史+药品字典/规则管理 tab）；
    canSignItem 的 drug 签字权矩阵保留（后端权限矩阵未变，作防御性兜底）。
    legacy data-act 按钮分流标记由 Vue 模板 v-if/v-else-if 声明式渲染接管。"""
    html = _vue_source()
    for marker in (
        "function canSignItem(p)",                     # 签字权按 agent 分流
        # 问题4 收窄后的 drug 签字权矩阵（原 qc 已移除）
        "if (p.agent === 'drug') return role.value === 'admin' || role.value === 'pharmacist'",
        'v-else-if="canSignItem(p)"',                  # 列表行按条目出签发/驳回按钮
        "canSignItem(detail) && !detailMine",          # 详情模态操作区同矩阵
        # 问题2 更新：pharmacist 审核中心标题与待处理空态文案（药物待核对区已移除）
        "审核中心 · 药剂科",
        "暂无待办（开药待审）",
        "开药工作台直送的处方会出现在这里，由您签发/驳回",
        # 问题4：qc 看 drug 项的只读说明文案（drug 例外：药师/管理员，不再是质控员）
        "药物核对项：签发/驳回由药师（pharmacist）/管理员执行",
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_phase0_frontend_markers_present():
    """阶段0 前端标记锁（Vue 语义版）：
    - 0.1：qcResubmit 从 meta.record 逐栏直填并统计 filled；仅确有预填内容才声明
      「已预填」，旧版无留档条目如实提示手动填写（不再谎报）；重提上下文以横幅
      （qc-resubmit-banner）呈现于表单上方——legacy 的 scrollIntoView 跨视图滚动
      定位与 qc-flash 高亮在 Vue 单页内联横幅下无对应物，相应断言删除（视觉语义由
      Playwright 行为测试接管）；drug 类历史驳回并入审核中心历史（阶段3 语义）。"""
    html = _vue_source()
    for marker in (
        # 0.1：结构化逐栏直填 + 预填内容计数（签名栏 i=6 锁定当前账号不回填）
        "let filled = 0", "if (i === 6) return", "if (v.trim()) filled++",
        "filled > 0",  # 有值才声明已预填（横幅诚实化）
        "该记录未留存结构化原病历（旧版条目），请手动填写病历内容",
        "qcLabs.value",  # 回填检验值（meta.labs → qcLabs）
    ):
        assert marker in html, f"缺少前端标记：{marker}"


def test_phase3_rx_frontend_markers_present():
    """阶段3 前端标记锁（Vue 语义版，docs/archive/plans/pharmacy_roadmap.md 阶段3）：
    - 3.1 开药工作台（RxView）：suggest → 勾选（默认全选且渲染时同步进药单，rxPick
      语义）→ 字典搜索加药 → 手动加药（字典外确认）→ 药单增删/剂量频次内联编辑 →
      高危阻断 + 联用理由（填了才允许提交）→ 提交/改写重提；免责声明条 DRUG_DISCLAIMER；
    - 药物助手独立入口已删（反向锁）；
    - 3.2 药剂科开药审核队列（ReviewCenterView）：开药待审区 + 通过/驳回（驳回必填意见）
      + 高危强制开立徽标；
    - 3.3 我的处方：状态徽章（pending_pharm 黄/approved 绿/rejected 红/draft 灰）+
      驳回意见 + 重写回填（提交走 rewrite 自动重提）。"""
    html = _vue_source()
    for marker in (
        # 3.1 开药工作台：视图骨架与双 tab
        "开药工作台", "data-testid=\"rx-tab-compose\"", "data-testid=\"rx-tab-mine\"",
        "function switchTab(",
        # 3.1 suggest → 推荐卡（勾选）+ 中危警示
        "function rxSuggest(", "生成开药建议", "'/medical/prescriptions/suggest'",
        "function rxPick(", "⚠ 中危联用警示",
        # 3.1 自定义加药：字典搜索（前端过滤）+ 字典外确认弹条
        "'/medical/drug/dict'", "function rxAddDict(", "function rxAddManual(",
        "该药不在字典，药剂科将重点审核",
        # 3.1 已选药单（可删/可编辑）
        "function rxEditDrug(", "function rxDelDrug(", "function inDict(",
        # 3.1 高危联用阻断条 + 联用理由（填了才允许提交）+ 提交
        "高危联用已阻断", "submitDisabled", "contraindication_reason",
        "function rxSubmit(", "提交开药", "'/medical/prescriptions'",
        "'/medical/prescriptions/' + encodeURIComponent(rxEditId.value) + '/rewrite'",  # 改写重提
        # 免责声明条（DRUG_DISCLAIMER 常量逐字同源）
        "data-testid=\"rx-disclaimer\"", "DRUG_DISCLAIMER",
        # 3.2 药剂科开药审核队列（ReviewCenterView）
        "'/medical/prescriptions/pending'", "开药待审（处方队列）",
        "高危强制开立", "function rxReview(", "rxReviewPost(",
        "'/medical/prescriptions/' + encodeURIComponent(id) + '/review'",
        # 3.3 我的处方：状态徽章 + 驳回意见 + 重写回填
        "'/medical/prescriptions/mine'", "function loadRxMine(",
        "pending_pharm: ['待药剂科审核', 'warning']", "rejected: ['已驳回', 'danger']",
        "draft: ['草稿', 'info']", "function rxStartRewrite(", "药剂科意见：",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # 药物助手独立入口已删（反向锁：残留即回退）
    for gone in (
        "药物信息助手",            # AGENTS.drug 独立入口
        "loadDrugRejections",      # drug 视图驳回提示函数
    ):
        assert gone not in html, f"药物助手独立入口应已删除，仍残留：{gone}"


# ---------- 开药审核 UX / 轮2 四项修复标记锁 ----------

def test_rx_review_ux_fixes_frontend_markers_present():
    """开药审核 UX 三修复标记锁（Vue 语义版）：
    - 问题1：pharmacist「我的历史」= 本人参与的记录（submitted_by=me OR reviewed_by=me
      前端过滤；他人记录仍不显示，数据隔离边界不放宽）；
    - 问题2：rx 导航红点角标 = 本人被驳回处方数（refreshRxBadge 从 /prescriptions/mine
      统计，仅 doctor；进 rx 视图清零、离开按服务端恢复）；
    - 问题3：处方卡与历史行显示审核人 + 操作时间（pharm_reviewer/pharm_reviewed_at、
      resolved_at）。"""
    html = _vue_source()
    for marker in (
        # 问题1：「我的历史」过滤含本人作为审核人的记录
        "(i.submitted_by === reviewMe.value || i.reviewed_by === reviewMe.value)",
        # 问题2：rx 被驳回处方红点角标 + 刷新/清零时机（badges store + AppShell watch）
        "async refreshRxBadge(", "rxRejectedCount",
        "title: '被驳回的处方'",  # 红点角标语义
        "if (nv === 'rx' && ov !== 'rx') badges.rxRejectedCount = 0",
        "if (ov === 'rx' && nv !== 'rx') badges.refreshRxBadge()",
        # 问题3：审核人 + 操作时间留痕显示（处方卡 / history 行）
        "pharm_reviewed_at", "审核人 {{ it.pharm_reviewer }}", "fmtTs(p.resolved_at)",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    badge = _extract_badge_action("refreshRxBadge")
    assert "/medical/prescriptions/mine" in badge and "rejected" in badge, \
        "角标数据源必须为 /prescriptions/mine 的 rejected 统计（无需新端点）"
    assert "auth.role !== 'doctor'" in badge, "角标仅 doctor 刷新（mine 为 doctor 专属，避免 403 噪音）"
    # 刷新时机：AppShell 登录首刷 + 离开 rx 恢复 + 60s 定时轮询（≥3 处调用）
    shell = (VUE_SRC / "layouts" / "AppShell.vue").read_text(encoding="utf-8")
    assert shell.count("badges.refreshRxBadge()") >= 2, "登录首刷与离开 rx 恢复均须刷新角标"
    assert "badges.poll()" in shell, "60s 定时轮询应挂载于布局壳"


def test_rx_round2_suggest_sync_markers_present():
    """问题1 标记锁：推荐建议渲染时对 checked=true 的建议项同步调用加入药单函数
    （rxPick 语义，syncPickedToSelected 承接 legacy renderRxSuggest 首段）——勾选框
    默认选中必须程序化触发加入逻辑（去重：药名+剂量相同不重复加），已选药单不再为空
    （用户取消勾选再勾选才加入的回退被锁定）。"""
    html = _vue_source()
    fn = _extract_fn(html, "syncPickedToSelected")
    assert "rxPick(i, true)" in fn, "syncPickedToSelected 必须对勾选项同步调用加入药单函数 rxPick"
    assert "if (!s.pick) return" in fn, "仅同步 checked=true 的建议项"
    # 去重：药名+剂量都相同不重复加（重复渲染幂等）
    assert "d.name === s.name && (d.dose || '') === (s.dose || '')" in fn, \
        "同步加入必须按药名+剂量去重（防重复渲染重复加）"
    # rxSuggest 成功回调必须触发同步（默认全选 → 药单立即非空）
    suggest = _extract_fn(html, "rxSuggest")
    assert "syncPickedToSelected()" in suggest, "建议加载成功后必须同步进药单"


def test_rx_round2_review_badge_markers_present():
    """问题2 标记锁：审核中心数字角标（导航入口）——refreshReviewBadge 复用
    refreshRxBadge 模式（登录/切视图/60s 轮询刷新）。
    pharmacist 角标只算待审处方数（「药物待核对」区随两药快查下线移除，不再拉取
    /review/pending）；qc=其它助手高危项（问题4 收权后不审 drug/开药），
    admin=全部待核对+待审处方。"""
    html = _vue_source()
    for marker in (
        "async refreshReviewBadge(",          # 角标数据刷新动作
        "reviewTodoCount",                    # 角标数状态
        "title: '待办：待审处方'",              # pharmacist 待办角标语义
        "title: '待核对（其它助手高危项）'",      # qc 角标语义（问题4 收权）
        "badges.refreshReviewBadge()",        # 登录首刷（AppShell onMounted）
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    fn = _extract_badge_action("refreshReviewBadge")
    assert "/medical/review/pending" in fn, "角标数据源必须含 /review/pending（qc/admin 待核对）"
    assert "/medical/prescriptions/pending" in fn, "角标数据源必须含 /prescriptions/pending（待审处方数）"
    assert "role !== 'pharmacist'" in fn, "pharmacist 不再拉取 /review/pending（跳过 drug 待核对）"
    assert "i.agent !== 'drug'" in fn, "qc 只算其它助手高危项（问题4 分流）"
    # 问题2：pharmacist 角标=待审处方数（进入审核中心 load() 即刷新）
    assert "badges.reviewTodoCount = (rxPendingItems.value || []).length" in html, \
        "pharmacist 角标=待审处方数（drug 待核对区已移除）"


def test_rx_round2_reviewed_history_markers_present():
    """问题3 标记锁：①「我的历史」前端过滤含本人作为审核人的记录（既有锁保持）；
    ②pharmacist 拉取 GET /prescriptions/reviewed-by-me 并在「我的历史」tab 合并渲染
    处方审核记录（_rx 分流行：rid/状态/意见/时间）。"""
    html = _vue_source()
    for marker in (
        "'/medical/prescriptions/reviewed-by-me'",   # 问题3② 新端点调用
        "rxHistoryItems",                            # 处方审核历史缓存
        "处方审核：{{ p.id }}",                        # 行首标识（rid 可见）
        "histSlice.filter((x) => x._rx)",            # 「我的历史」合并渲染分流（_rx 专用行）
        "(i.submitted_by === reviewMe.value || i.reviewed_by === reviewMe.value)",  # 问题3① 前端过滤
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # 处方审核历史行必须展示 状态/意见/审核时间（_rx 行渲染段）
    rxrow = html[html.index("histSlice.filter((x) => x._rx)"):]
    rxrow = rxrow[:rxrow.index("</tr>")]
    for field in ("p.pharm_opinion", "p.pharm_reviewed_at", "p.status"):
        assert field in rxrow, f"处方审核历史行必须展示 {field}（rid/状态/意见/时间）"


def test_rx_round2_qc_scope_markers_present():
    """问题4 标记锁：qc 不审核药剂——①开药待审区仅 pharmacist/admin 渲染
    （qc/doctor 登录不渲染开药待审区）；②qc 路径不拉取开药待审队列（403 不请求，
    仅 admin 拉取）；③历史过滤 drug 旧遗留记录（所有角色统一 i.agent!=='drug'）。"""
    html = _vue_source()
    # ①开药待审区仅 pharmacist/admin 渲染
    assert "tab === 'pending' && (role === 'pharmacist' || role === 'admin')" in html, \
        "开药待审区必须仅 pharmacist/admin 渲染（qc/doctor 不渲染）"
    # ②qc/admin 路径仅 admin 拉取 /prescriptions/pending（qc 403 不请求）
    assert "if (role.value === 'admin') {" in html, \
        "qc/admin 路径仅 admin 拉取 /prescriptions/pending（qc 403 不请求）"
    # ③两处历史装载（qc/admin 与 mine）均过滤 drug 旧遗留记录
    assert html.count("i.agent !== 'drug'") >= 2, \
        "历史列表须所有角色过滤 drug 旧遗留记录（两处装载点）"


def test_review_race_guard_frontend_markers_present():
    """问题1 标记锁（Vue 语义版）：审核中心加载请求序号守卫（防轮询/连点并发 race
    导致列表时有时无）：loadSeq 模块级序号；load() 入口 const seq=++loadSeq 取号；
    每个 await 后 if(seq!==loadSeq)return 丢弃过期响应（含 catch 分支）。
    legacy 的 qc/admin 与 mine 双入口函数（loadReview/loadReviewMine(b,seq)）在 Vue 中
    合并为单一 load() 内双分支（同守卫），对应函数名断言删除。"""
    html = _vue_source()
    for marker in (
        "let loadSeq = 0",
        "const seq = ++loadSeq",
        "if (seq !== loadSeq) return",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    assert html.count("if (seq !== loadSeq) return") >= 7, \
        "过期响应守卫必须覆盖两分支全部 await 之后的状态写入点（含 catch）"


def test_drug_purge_frontend_markers_present():
    """问题2 标记锁：遗留 drug 审核记录全清（前端侧）——
    ①反向锁：「药物待核对」UI 字面量不得残留（药物待核对区已随两药快查下线移除）；
    ②pharmacist 不再从 /review/pending 拉取 drug 项（load 双分支无该请求）；
    ③后端 drug 入队分支注入「不再入队」注记注释（行为不变）；
    ④清理脚本就位（scripts/purge_drug_review_items.py）；
    ⑤pg_store 新增 purge_drug_reviews（PG 镜像清理配套）。"""
    html = _vue_source()
    # 反向锁针对 UI 字面量（注释中的「语义变更注明」不计）：drug 待核对表头/tab 已移除
    assert "ph?'药物待核对'" not in html, "pharmacist 待处理 tab 标签「药物待核对」应已移除"
    assert "药物高危待核对（需药剂科复核）" not in html, "drug 待核对表头应已移除"
    assert "badges.reviewTodoCount = (rxPendingItems.value || []).length" in html, \
        "pharmacist 角标只算待审处方数"
    router = (ROOT / "backend" / "api" / "v1" / "medical" / "medical_router.py").read_text(encoding="utf-8")
    assert "两药快查已下线，drug 项不再入队（开药走 prescriptions）" in router, \
        "_enqueue_if_risk 的 drug 分支应注入下线注记（仅注释不改行为）"
    assert (ROOT / "scripts" / "purge_drug_review_items.py").is_file(), "清理脚本应就位"
    pg = (ROOT / "backend" / "core" / "pg_store.py").read_text(encoding="utf-8")
    assert "async def purge_drug_reviews()" in pg, "pg_store 应提供 purge_drug_reviews 镜像清理"


def test_drug_dict_manage_frontend_markers_present():
    """问题3 标记锁：药品字典/相互作用规则管理 UI（pharmacist 审核中心 tab，Vue 版）：
    - 双 tab（review-tab-drugdict / review-tab-drugrules，v-if=isPh 仅药剂科可见）；
    - DrugDictEditor：五字段表单（name/category/level/aliases/brand_names）+ 搜索/
      分类过滤 + 编辑（编辑态锁定规范名）/删除；写端点 POST /admin/drug/dict；
    - DrugRulesEditor：规则表单（drug_a/drug_b/severity 高危|中危/mechanism/management）
      + 编辑/删除；写端点 POST /admin/drug/rules；
    - 安全纪律：Vue 模板插值自动转义 + @click 声明式绑定接管 legacy esc()+事件委托
      （data-act/__drugDictDelegate 为 vanilla DOM 特有实现，无对应物，断言删除）；
    - 数据源 GET /drug/dict + 免责声明。"""
    html = _vue_source()
    for marker in (
        # 双 tab（pharmacist 可见性由 v-if=isPh 控制）
        "data-testid=\"review-tab-drugdict\"", "data-testid=\"review-tab-drugrules\"",
        "switchTab('drugdict')", "switchTab('drugrules')",
        # 字典编辑器（DrugDictEditor.vue）
        "'/medical/drug/dict'", "'/medical/admin/drug/dict'",
        "brand_names: csv(form.brand)", "aliases: csv(form.alias)",
        "level: String(form.level || '').trim() || '处方药'",
        "规范名（唯一键，编辑态锁定）", "新增药品",
        # 规则编辑器（DrugRulesEditor.vue）：严重度下拉两档（高危/中危）
        "'/medical/admin/drug/rules'", '<option value="高危">高危</option>',
        '<option value="中危">中危</option>',
        "drug_a: a, drug_b: b", "新增规则",
        # 数据源 + 免责声明
        "http.get('/medical/drug/dict')", "DRUG_DISCLAIMER",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # 编辑/删除/重置函数成对存在（两编辑器）
    for fn_name in ("startEdit", "removeItem", "reset"):  # DrugDictEditor
        assert re.search(rf"function {fn_name}\(", html), f"字典编辑器缺少函数：{fn_name}"
    for fn_name in ("startEdit", "removeRule", "reset", "markReviewed"):  # DrugRulesEditor
        assert re.search(rf"function {fn_name}\(", html), f"规则编辑器缺少函数：{fn_name}"
    # 分类过滤下拉（字典 tab）
    assert 'placeholder="全部分类"' in html, "字典 tab 应有分类过滤下拉"


def test_admin_dict_card_removed_frontend_markers():
    """阶段4 任务1 反向锁（防回退）：admin 数据面板药品字典卡不得存在——药品字典管理
    UI 只在药剂科审核中心 tab 显示（轮3 Vue 实现即此形态：DataView 无字典卡/容器/
    挂载函数；legacy 时期的删除注记为 legacy 注释，Vue 实现天然不含）。"""
    html = _vue_source()
    for gone in (
        "drugDictPanel",            # admin 卡容器
        "药品字典（可编辑",           # admin 卡标题
        "loadDrugDictPanel",        # admin 卡挂载函数
    ):
        assert gone not in html, f"admin 字典卡应已移除，仍残留：{gone}"


# ---------- 阶段4：病例库前端标记锁 ----------

def test_phase4_case_archive_frontend_markers_present():
    """阶段4 前端标记锁（Vue 语义版）：
    - qc/admin 审核中心「病例库」tab（review-tab-casearchive + switchTab('casearchive')
      分发，v-if=!isMine 仅 qc/admin 可见）；
    - 病例库视图：统计头（N 例在库 / M 已移除）+ 科室/状态/日期筛选 + status 徽章
      （在库 success / 已移除 info）+ 质控结论摘要 + record 展开详情（Vue 模板插值
      自动转义接管 legacy esc(JSON...) 纪律）+ 移除（原因必填，软删除留痕）；
    - doctor 病历质控视图「我的归档」小节（loadQcArch + /case-archive/mine，只读）；
    - humanizeAudit 补 case_archive.* 映射（覆盖率锁配套）。"""
    html = _vue_source()
    for marker in (
        # 病例库 tab（qc/admin）
        "data-testid=\"review-tab-casearchive\"", "switchTab('casearchive')",
        "if (t === 'casearchive') loadCaseArchive()",
        # 视图数据源与渲染
        "function loadCaseArchive()", "function caRemove(",
        "'/medical/case-archive'",
        # 统计头（N 例在库 / M 已移除）
        "例在库", "已移除",
        # 科室/状态筛选
        "placeholder=\"全部科室\"", 'label="在库" value="active"', 'label="已移除" value="removed"',
        # 质控结论摘要 + record 展开详情
        "质控结论摘要与病历详情", "qc_conclusion", "JSON.stringify(i.record || {}, null, 2)",
        # 移除（必填原因）
        "移除原因必填", "'/medical/case-archive/' + encodeURIComponent(caRemoveId.value) + '/remove'",
        # doctor 我的归档（QcView）
        "function loadQcArch(", "'/medical/case-archive/mine'", "我的归档", "暂无在库归档病历",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # humanizeAudit 补 case_archive.* 映射（覆盖率锁配套）
    hum = _extract_export_fn(_all_frontend_js(), "humanizeAudit")
    assert "case_archive" in hum, "humanizeAudit 必须包含 case_archive.* 事件映射"


# ---------- 阶段6：商用化去 demo 前端标记锁 ----------

def test_phase6_dedemo_frontend_markers_removed():
    """阶段6（去 demo 商用化）前端标记锁（防回退）：
    - 质控工作台「填入示例」按钮与 qcSample() 假病历一键填充保持移除
      （商用环境不得存在一键注入虚构病历的入口，防误用与数据混淆；
      病历录入走「粘贴整段病历 → qcParsePaste 拆分」或手动填写）；
    - 登录页无「演示账号」用户可见字样（账号由管理员统一开通；
      演示账号的前提条件与说明仅存在于 README / docs/DEPLOY.md）。"""
    html = _vue_source()
    assert "qcSample" not in html, "qcSample() 假病历一键填充必须保持移除（商用不得一键注入虚构病历）"
    assert "填入示例" not in html, "「填入示例」按钮必须保持移除（阶段6 去demo）"
    assert "演示账号" not in html, "用户可见界面不应出现「演示账号」字样（改中性文案，演示账号说明放文档）"


# ---------- 整改轮 B：任务1 驳回闭环刷新 / 任务2 字典外方案A / 任务3 科室-角色联动 ----------

def test_round_b_frontend_markers_present():
    """整改轮 B 三项任务前端标记锁（Vue 语义版）：
    - 任务1（驳回无反应修复）：开药驳回提交失败分支强制 load() 刷新列表（防僵尸按钮），
      错误提示照常透出；复核队列签发/驳回/翻案/知情确认同样失败即刷新；
    - 任务2（字典外药方案 A）：RxView 药单行红标「字典外」+ 行内理由输入（必填 ≥5 字，
      提交前端预检 toast 阻断），理由随 note 上送；ReviewCenter 开药待审卡「含字典外
      药品·重点审核」红徽 + 外典药红字；audit.js submitted 人话带外典药名单；
    - 任务3（科室-角色归属）：DataView 建号表单角色→科室下拉联动过滤（deptOptions，
      pharmacist 只见药剂科 / qc 只见质控科+医务处 / doctor 隐藏职能部门）+ 切角色清脏值。"""
    html = _vue_source()
    for marker in (
        # 任务2：RxView 方案A（红标 + 理由输入 + 前端预检 + 理由随 note 上送）
        "data-testid=\"rx-ood-reason\"",
        "字典外使用理由（必填 ≥5 字，说明何药/为何用，将交药剂科重点审核）",
        "需填写使用理由后才能提交（将交药剂科重点审核）",
        "的使用理由需 ≥5 字（说明何药/为何用）",
        "(!inDict(d.name) && (d.ood || '').trim()) ? d.ood.trim() : (d.note || '')",
        # 任务2：ReviewCenter 开药待审卡红徽 + 外典药红字/徽标
        "含字典外药品·重点审核",
        "d.out_of_dict ? 'color: var(--danger, #c0392b)' : ''",
        # 任务2：审计人话带外典药名单
        "含字典外药 ' + p.out_of_dict_drugs.join('、')",
        # 任务3：DataView 科室下拉联动过滤
        "const FUNC_DEPTS = ['药剂科', '医务处', '质控科', '病案室']",
        "function onRoleChange()",
        "if (nu.role === 'pharmacist') return all.filter((n) => n === '药剂科')",
        "if (nu.role === 'qc') return all.filter((n) => n === '质控科' || n === '医务处')",
        "if (nu.role === 'doctor') return all.filter((n) => !FUNC_DEPTS.includes(n))",
        "@change=\"onRoleChange\"",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    # 任务1：四个列表操作提交函数的失败分支必须刷新列表（防僵尸按钮）
    for fn_name in ("rxReviewPost", "doResolve", "reopen", "selfConfirm"):
        fn = _extract_fn(html, fn_name)
        assert "load()" in fn, f"{fn_name} 失败分支必须强制刷新列表（任务1 防僵尸按钮）"
    # 任务2 反向锁：外典药直接阻断提交的旧文案必须移除（方案A 语义变更：允许提交+强制理由）
    assert "服务端硬校验将拒绝提交" not in html, "方案A 后外典药不再阻断提交（旧文案残留即回退）"


# ---------- 医学蓝统一（品牌色对齐）+ 会诊角标实时化（方案 A）标记锁 ----------

def test_medical_blue_theme_markers_present():
    """医学蓝统一标记锁：styles.css :root 覆盖 Element Plus 主色为品牌蓝 #2563c9——
    Element Plus dist/index.css 只输出 light-3/5/7/8/9 + dark-2 五个梯度变量
    （button/link/radio/checkbox/pagination/dialog 等主色消费均走这组），
    值按混色规则固化：light-N = #2563c9 混 N% 白，dark-2 = 混 20% 黑。"""
    css = (VUE_SRC / "styles.css").read_text(encoding="utf-8")
    for var, val in (
        ("--el-color-primary:", "#2563c9"),
        ("--el-color-primary-light-3:", "#6692d9"),  # 混 30% 白
        ("--el-color-primary-light-5:", "#92b1e4"),  # 混 50% 白
        ("--el-color-primary-light-7:", "#bed0ef"),  # 混 70% 白
        ("--el-color-primary-light-8:", "#d3e0f4"),  # 混 80% 白
        ("--el-color-primary-light-9:", "#e9effa"),  # 混 90% 白
        ("--el-color-primary-dark-2:", "#1e4fa1"),   # 混 20% 黑
    ):
        assert f"{var} {val}" in css, f"Element Plus 主色覆盖缺失或值不对：{var} {val}"
    # 概览 hero：描述文字品牌蓝 --accent（浅蓝底 --accent-soft 保证可读性）
    ov = (VUE_SRC / "views" / "OverviewView.vue").read_text(encoding="utf-8")
    hero_p = ov[ov.index(".hero p {"):]
    hero_p = hero_p[:hero_p.index("}")]
    assert "color: var(--accent" in hero_p, "hero 描述文字应使用品牌蓝 --accent"


def test_consult_badge_poll_silent_markers_present():
    """会诊角标实时化（方案 A）标记锁：
    - poll() 并入 refreshConsultBadge(true)（仅 doctor/pharmacist 生效，qc/admin 内部跳过）；
    - 轮询路径请求带 params { silent: 1 }（后端跳过 list_inbox 审计，读操作降噪）；
    - 非 silent 分支（params: {}）保留——进审核中心/收件箱视图的刷新点业务审计不丢。"""
    poll = _extract_badge_action("poll")
    assert "refreshConsultBadge(true)" in poll, "poll() 必须并入 refreshConsultBadge（silent 轮询路径）"
    consult = _extract_badge_action("refreshConsultBadge")
    assert "params: silent ? { silent: 1 } : {}" in consult, \
        "refreshConsultBadge 轮询路径必须带 silent=1 参数（非 silent 刷新点保持业务审计）"
    assert "'/medical/consults/inbox'" in consult, "角标数据源必须为 /consults/inbox"


# ---------- T2 修改密码入口 / T3 beforeunload 防误关（矩阵 A-4/A-7 补齐标记锁） ----------

def test_change_password_frontend_markers_present():
    """T2 标记锁（矩阵 A-4 补齐）：AppShell 顶栏用户名旁「改密」入口 → 对话框
    （旧密码/新密码/确认新密码）→ 前端一致性校验（非空/两次一致/≥8 位）→
    POST /auth/change-password（old_password/new_password，以后端 ChangePwdReq 为准）→
    成功 toast + 强制重新登录（auth.logout 清 token + 跳 /login，后端已 revoke 旧令牌）。"""
    html = _vue_source()
    for marker in (
        # 顶栏入口（用户名旁）
        'data-testid="topbar-changepwd"', '@click="openChangePwd"', ">改密<",
        # 对话框三字段（旧密码/新密码/确认新密码）
        "修改本人密码", 'data-testid="pwd-old"', 'data-testid="pwd-new1"', 'data-testid="pwd-new2"',
        # 一致性校验 + 提交
        "两次输入的新密码不一致", "新密码至少 8 位", "function submitChangePwd(",
        # 端点字段与后端 ChangePwdReq 对齐
        "old_password: pwdForm.old, new_password: pwdForm.new1",
        # 成功 toast + 强制重新登录（清 token）
        "密码已修改，请使用新密码重新登录",
        "auth.logout() // 改密后旧 token 已被服务端撤销，强制重新登录并清本地凭证",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    shell = (VUE_SRC / "layouts" / "AppShell.vue").read_text(encoding="utf-8")
    submit = shell[shell.index("async function submitChangePwd("):]
    submit = submit[:submit.index("\n}", 10)]
    # 校验顺序锁：一致性/长度校验必须先于请求发出
    assert submit.index("两次输入的新密码不一致") < submit.index("/auth/change-password"), \
        "改密前端一致性校验必须先于 POST 请求"
    assert "router.push('/login')" in submit, "改密成功后必须跳转登录页强制重新登录"


def test_beforeunload_guard_frontend_markers_present():
    """T3 标记锁（矩阵 A-7 补齐，legacy app.js beforeUnloadGuard 语义迁移）：
    - AppShell 挂 window beforeunload 监听（onMounted 注册/onUnmounted 注销成对）；
    - 登录态 + 存在进行中操作（dirty.hasUnsaved）→ preventDefault + returnValue 弹原生确认；
    - dirty store（stores/dirty.js）为脏标记状态源；RxView watch 同步 rx 工作台
      （case_text 非空或已选药单非空 → dirty.rx）。"""
    html = _vue_source()
    for marker in (
        # AppShell：beforeunload 守卫（登录态 + 脏标记 → preventDefault + returnValue）
        "window.addEventListener('beforeunload', onBeforeUnload)",
        "window.removeEventListener('beforeunload', onBeforeUnload)",
        "function onBeforeUnload(e)",
        "e.preventDefault()",
        "e.returnValue = ''",
        "auth.isLoggedIn && dirty.hasUnsaved",
        # dirty store：脏标记状态源
        "useDirtyStore",
    ):
        assert marker in html, f"缺少前端标记：{marker}"
    dirty_src = (VUE_SRC / "stores" / "dirty.js").read_text(encoding="utf-8")
    assert "rx: false" in dirty_src, "dirty store 应持有 rx 工作台脏标记"
    assert "hasUnsaved" in dirty_src, "dirty store 应暴露 hasUnsaved 聚合判定"
    # RxView：watch 同步（病例文本非空或已选药单非空 → dirty.rx）
    rx = (VUE_SRC / "views" / "RxView.vue").read_text(encoding="utf-8")
    assert "useDirtyStore" in rx and "dirty.rx =" in rx, "RxView 应同步 rx 工作台脏标记"
    assert "String(txt || '').trim() || n > 0" in rx, \
        "脏判定口径必须为「case_text 非空或已选药单非空」"


def test_kbview_xss_esc_markers_present():
    """终评 F2 XSS 收口标记锁（KbView.vue v-html 消费 kbMsg）：所有动态拼接
    （文件名 label/f.name、后端错误 st.error/det、异常 e.message）必须经 esc() 转义；
    固定标签 <b>/<span class="num"> 保留（纯静态骨架不受影响）。"""
    src = (VUE_SRC / "views" / "KbView.vue").read_text(encoding="utf-8")
    # esc 从 utils/assist 导入（共享实现，与 legacy esc 同净化策略）
    assert "import { esc, fmtTs } from '../utils/assist'" in src, "KbView 应导入共享 esc()"
    for marker in (
        "esc(label) + ' 状态查询失败，重试中…'",
        "esc(label) + ' 任务不存在'",
        "esc(label) + ' → 入库 <b class=\"num\">'",
        "esc(label) + ' 失败：' + esc(st.error || '未知原因')",
        "esc(label) + ' 向量化中 <span class=\"num\">'",
        "esc(label) + ' 超时（20 分钟未完成）",
        "'处理中：' + esc(f.name) + ' …'",
        "esc(f.name) + ' 不支持（仅 .md/.txt/.pdf）'",
        "'失败：' + esc(f.name) + '（' + (st || '网络异常') + ' ' + esc(det || (e && e.message) || '') + '）'",
        "${esc(f.name)} → 入库 ${d.inserted} 片",
        "'失败：' + esc(f.name) + '（网络异常：' + esc((e && e.message) || '') + '）'",
    ):
        assert marker in src, f"KbView kbMsg 拼接缺少 esc() 包裹：{marker}"
    # 反向锁：kbMsg 拼接处不得再出现未转义的 f.name/label/拼接（esc 漏包即回退）。
    # 固定标签 <b class="num">/<span class="num"> 为静态骨架，允许保留。
    body = src[src.index("async function pollKbTask"):]
    for raw in ("+ label +", "' + f.name + '", "`${f.name}", "+ f.name +"):
        assert raw not in body, f"KbView kbMsg 出现未转义拼接：{raw}"
