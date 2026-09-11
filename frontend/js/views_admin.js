/* ---- review center ---- */
let reviewTab='pending', historyItems=[];
let myConfirmItems=[];  /* 任务3：doctor/pharmacist 的「我的待确认」项（my-pending-confirm 数据源） */
let rxPendingItems=[];  /* 阶段3.2：开药待审队列（GET /prescriptions/pending；问题4 收权后仅 pharmacist/admin，doctor/qc 403 不拉取） */
let rxHistoryItems=[];  /* 问题3②：pharmacist 本人审核过的处方（GET /prescriptions/reviewed-by-me），「我的历史」tab 合并渲染 */
function canSign(){return (ROLE==='qc'||ROLE==='admin')&&!CFG.full;}  /* 任务2/3：非 drug 类签发/驳回/翻案仅 qc/admin；留痕模式下全局只读档案 */
/* 本批次任务2：按 agent 分流的签字权（与后端 review_resolve 矩阵一致）——drug 类豁免
   留痕模式（高危恒在 pending），可签发/驳回（药剂科一票，不受 CFG.full 影响）；
   其它 agent 维持 qc/admin 且留痕模式只读。双控（mine）另行判定。
   问题4 收窄（语义变更）：drug 类签字权=pharmacist/admin（原含 qc——质控科只管病例，
   药物核对与处方审核权收归药剂科+管理员）。 */
function canSignItem(p){
  if(!p)return false;
  if(p.agent==='drug')return ROLE==='admin'||ROLE==='pharmacist';
  return canSign();}
function renderReviewHeader(){  /* 任务3：审核中心按角色切换文案与 tab 标签；本批问题2：pharmacist 药物待核对区移除（审核中心=开药待审+我的历史+字典管理 tab） */
  const mine=!(ROLE==='qc'||ROLE==='admin');
  const ph=ROLE==='pharmacist';
  const h=document.querySelector('#scr-review .shead h2'),p=document.querySelector('#scr-review .shead p');
  if(h)h.textContent=mine?(ph?'审核中心 · 药剂科':'审核中心 · 我的记录'):'审核中心 · 高风险双人核对';
  if(p)p.textContent=mine?(ph?'开药处方由您签发/驳回；药品字典与相互作用规则在下方标签页维护（药学专业数据药剂科维护）':'仅显示您提交的待确认项与历史记录；签发/驳回由质控员（qc）/管理员执行'):'高风险结论需第二名医师/药师签发';
  const tp=document.getElementById('tabPending'),th=document.getElementById('tabHistory');
  if(tp)tp.textContent=mine?(ph?'待处理':'我的待确认'):'待处理';  /* 问题2：pharmacist「药物待核对」→「待处理」（开药待审+我的待确认） */
  if(th)th.textContent=mine?'我的历史':'历史记录';
  const tdd=document.getElementById('tabDrugDict'),tdr=document.getElementById('tabDrugRules');  /* 问题3：药品字典/相互作用规则管理 tab 仅药剂科可见 */
  if(tdd)tdd.style.display=ph?'':'none';
  if(tdr)tdr.style.display=ph?'':'none';
  const tca=document.getElementById('tabCaseArchive');  /* 阶段4：病例库 tab 仅 qc/admin 可见 */
  if(tca)tca.style.display=mine?'none':'';
  const tc=document.getElementById('tabConsults');  /* 跨科室会诊收件箱：doctor/pharmacist 专属第三栏目 */
  if(tc)tc.style.display=mine?'':'none';
  const fi=document.getElementById('reviewFilter');  /* 档案筛选仅 qc/admin 可见 */
  if(fi)fi.style.display=mine?'none':'';}
let reviewFilterQ='';
function onReviewFilter(v){reviewFilterQ=(v||'').trim().toLowerCase();renderReview();}
function _rvMatch(p){  /* 任务3：全局档案关键字筛选（agent/问题/提交人/风险） */
  if(!reviewFilterQ)return true;
  const s=((p.agent||'')+' '+(p.question||'')+' '+(p.submitted_by||'')+' '+(p.risk_reason||'')).toLowerCase();
  return s.includes(reviewFilterQ);}
function switchReviewTab(t){reviewTab=t;
  document.getElementById('tabPending').className='btn sm '+(t==='pending'?'primary':'ghost');
  document.getElementById('tabHistory').className='btn sm '+(t==='history'?'primary':'ghost');
  const tc=document.getElementById('tabConsults');  /* 第三栏目高亮与 doctor/pharmacist 同步切换 */
  if(tc)tc.className='btn sm '+(t==='consults'?'primary':'ghost');
  const tdd=document.getElementById('tabDrugDict'),tdr=document.getElementById('tabDrugRules');  /* 问题3：字典/规则 tab 高亮同步 */
  if(tdd)tdd.className='btn sm '+(t==='drugdict'?'primary':'ghost');
  if(tdr)tdr.className='btn sm '+(t==='drugrules'?'primary':'ghost');
  const tca=document.getElementById('tabCaseArchive');  /* 阶段4：病例库 tab 高亮同步 */
  if(tca)tca.className='btn sm '+(t==='casearchive'?'primary':'ghost');
  if(t==='consults')return loadConsultInbox();  /* 会诊收件箱独立数据源（/consults/inbox），不走 renderReview */
  if(t==='drugdict')return renderDrugDictEditor(document.getElementById('reviewBody'));  /* 问题3：药品字典管理（共享编辑器渲染进审核中心） */
  if(t==='drugrules')return renderDrugRulesEditor(document.getElementById('reviewBody'));  /* 问题3：相互作用规则管理 */
  if(t==='casearchive')return loadCaseArchive();  /* 阶段4：病例库（qc/admin，独立数据源 /case-archive） */
  renderReview();}
let reviewLoadSeq=0;  /* 问题1：审核中心加载请求序号守卫——点刷新/点审核触发的并发加载只应用
  最新一次响应，过期响应在每个 await 后被丢弃（防旧响应后到覆盖新数据/中途置空 → 列表「时有时无」） */
async function loadReview(){
  const seq=++reviewLoadSeq;  /* 问题1：本批请求取号；之后每次状态写入前校验，过期即放弃 */
  const b=document.getElementById('reviewBody');renderReviewHeader();
  b.innerHTML=`<div class="card"><div class="sk" style="height:18px;width:30%;margin-bottom:14px"></div><div class="sk" style="margin-bottom:8px"></div><div class="sk"></div></div>`;
  if(!(ROLE==='qc'||ROLE==='admin'))return loadReviewMine(b,seq);  /* 任务3：doctor/pharmacist = 我的待确认+我的历史 */
  try{const r=await fetch('/api/v1/medical/review/pending',{headers:auth()});
    if(r.status===401){logout();return;} const d=await r.json();
    if(seq!==reviewLoadSeq)return;  /* 问题1：期间又触发了新加载 → 丢弃本批过期响应 */
    reviewItems=d.pending; reviewMe=d.me; reviewCount=d.pending.length; renderRail();
    document.getElementById('purgeBtn').style.display=(ROLE==='admin')?'inline-flex':'none';
    try{const h=await fetch('/api/v1/medical/review/history',{headers:auth()});
      if(seq!==reviewLoadSeq)return;
      /* 问题2 语义变更：drug 类记录**所有角色**都过滤（含 pharmacist/admin——两药快查已下线，
         遗留 drug 队列已由 scripts/purge_drug_review_items.py 清理且不再入队，界面彻底不见 drug 项）。 */
      if(h.ok)historyItems=((await h.json()).items||[]).filter(i=>i.status!=='pending'&&i.agent!=='drug');}catch(e){}
    rxPendingItems=[];  /* 问题4：qc 无开药审核权（/prescriptions/pending 403），开药待审区不渲染（不拉取不留旧值） */
    if(ROLE==='admin'){
      try{const xp=await fetch('/api/v1/medical/prescriptions/pending',{headers:auth()});  /* 问题4 收权后仅 admin 拉取开药待审区（qc 403） */
        if(seq!==reviewLoadSeq)return;
        if(xp.ok)rxPendingItems=(await xp.json()).items||[];}catch(e){/* 开药待审加载失败静默 */}}
    /* 本批问题2：审核中心数字角标随数据刷新——qc=其它助手高危项（drug 除外，问题4 收权）；
       admin=全部待核对+开药待审 */
    reviewTodoCount=(ROLE==='qc')?(reviewItems||[]).filter(i=>i.agent!=='drug').length
      :(reviewItems||[]).length+(rxPendingItems||[]).length;
    renderRail();
    const pages=Math.max(1,Math.ceil(reviewItems.length/REVIEW_PER));
    if(reviewPage>=pages)reviewPage=pages-1; if(reviewPage<0)reviewPage=0;
    switchReviewTab(reviewTab);
  }catch(e){if(seq!==reviewLoadSeq)return;b.innerHTML='<div class="card">⚠ '+esc(e.message)+'</div>';}}
/* 任务3：doctor/pharmacist 视图——我的待确认（self-confirm）+ 我的历史（不再是全员工作台） */
async function loadReviewMine(b,seq){
  try{const r=await fetch('/api/v1/medical/review/my-pending-confirm',{headers:auth()});
    if(r.status===401){logout();return;} const d=await r.json();
    if(seq!==reviewLoadSeq)return;  /* 问题1：过期响应丢弃 */
    myConfirmItems=d.items||[]; reviewMe=d.me||''; reviewCount=myConfirmItems.length; renderRail();
    document.getElementById('purgeBtn').style.display='none';
    try{const h=await fetch('/api/v1/medical/review/history',{headers:auth()});
      if(seq!==reviewLoadSeq)return;
      /* 开药审核 UX 问题1：「我的历史」= 本人参与的记录（submitted_by=me OR reviewed_by=me）——
         pharmacist 签发/驳回他人提交的记录后历史不再为空；他人记录仍不显示（隔离边界不放宽，
         服务端本就只对 admin 返回全量档案、其余角色 images 收窄）。
         问题2 语义变更：追加 drug 类过滤（所有角色一致，含 pharmacist）。 */
      if(h.ok)historyItems=(((await h.json()).items)||[]).filter(i=>(i.submitted_by===reviewMe||i.reviewed_by===reviewMe)&&i.status!=='pending'&&i.agent!=='drug');}catch(e){}
    reviewItems=[];
    /* 问题2：「药物待核对」区已移除——两药快查下线后 drug 项不再入队（遗留已由
       scripts/purge_drug_review_items.py 清理），审核中心=开药待审+我的历史
       （+药品字典/相互作用规则管理 tab，本批问题3）。 */
    if(ROLE==='pharmacist'){
      try{const xp=await fetch('/api/v1/medical/prescriptions/pending',{headers:auth()});  /* 阶段3.2：药剂科开药待审队列 */
        if(seq!==reviewLoadSeq)return;
        if(xp.ok)rxPendingItems=(await xp.json()).items||[];}catch(e){/* 开药待审加载失败静默 */}
      try{const rh=await fetch('/api/v1/medical/prescriptions/reviewed-by-me',{headers:auth()});  /* 问题3②：本人审核过的处方（「我的历史」合并渲染数据源） */
        if(seq!==reviewLoadSeq)return;
        if(rh.ok)rxHistoryItems=(await rh.json()).items||[];}catch(e){/* 处方历史加载失败静默 */}
      /* 问题2 语义变更：pharmacist 待办角标只算待审处方数（drug 待核对区已移除） */
      reviewTodoCount=(rxPendingItems||[]).length;
      renderRail();}
    else rxHistoryItems=[];  /* 非 pharmacist 无处方审核历史（doctor/其他角色清空防串号） */
    switchReviewTab(reviewTab);
    refreshConsultBadge();  /* 任务4：进入审核中心即刷新「待我意见 + 我的待确认」徽标 */
  }catch(e){if(seq!==reviewLoadSeq)return;b.innerHTML='<div class="card">⚠ '+esc(e.message)+'</div>';}}
const REVIEW_PER=12, HIST_PER=12;
let histPage=0;
function histGo(d){histPage+=d;renderReview();document.getElementById('reviewBody').scrollTop=0;}
const _tsFmt=new Intl.DateTimeFormat('zh-CN',{month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});
function fmtTs(s){if(!s)return '';const d=new Date(s);return isNaN(d)?esc(s):_tsFmt.format(d);}
/* 任务1：审核中心列表行缩略图条——imaging/case 留痕记录的影像此前在列表行不显示，
   现以缩略图条直接可见。安全纪律与 renderUser/openReviewDetail 一致：
   createElement('img') + 属性赋值 .src（不用 HTML 字符串拼接图片 src），
   data:image/ 前缀白名单过滤，点击复用 showImg 放大。 */
function reviewThumbStrip(p){
  if(!p)return null;
  const imgs=(p.images||[]).filter(u=>String(u).startsWith('data:image/'));
  if(!imgs.length)return null;  /* 无合规影像 → 不渲染（接口剥离 images 时自然无条） */
  const box=document.createElement('div');
  box.className='review-thumbs';  /* 多图 grid 布局（样式见 .review-thumbs） */
  imgs.forEach(u=>{
    const im=document.createElement('img');
    im.className='thumb';im.alt='留痕影像缩略图';im.loading='lazy';
    im.style.cursor='zoom-in';
    im.src=u;  /* 属性赋值，非 HTML 字符串拼接 */
    im.addEventListener('click',()=>showImg(u));
    box.appendChild(im);});
  return box;
}
function mountReviewThumbs(items){
  /* 任务1：renderReview() 设置 innerHTML 后调用——把行内占位容器 [data-rthumbs]
     替换为真实缩略图条 DOM（无合规影像的占位直接移除，不留空 div） */
  const b=document.getElementById('reviewBody');
  const map=new Map((items||[]).map(x=>[x.id,x]));
  b.querySelectorAll('[data-rthumbs]').forEach(ph=>{
    const strip=reviewThumbStrip(map.get(ph.getAttribute('data-rthumbs')));
    if(strip)ph.replaceWith(strip);else ph.remove();});
}
function reviewRow(p,resolved){
  const mine=p.submitted_by===reviewMe;
  /* 双控自动签发：reviewed_by 含「AI·阈值自动」或「AI·留痕模式(自动)」→ 徽标展示；
     qc/admin 且非留痕模式可翻案（转人工复核）——任务3：留痕模式下全局只读，无翻案入口 */
  const auto=resolved&&p.status==='approved'&&/AI·(阈值自动|留痕模式)/.test(String(p.reviewed_by||''));
  const statusChip=resolved?`<span class="chip ${p.status==='approved'?(auto?'mid':'ok'):'warn'}"><span class="dot"></span>${p.status==='approved'?(auto?'自动签发':'已签发'):'已驳回'}</span>`:'';
  return `<tr><td><details><summary class="qtext" style="cursor:pointer;list-style:none">${esc(p.agent)}：${esc((p.question||'').slice(0,60))} <span class="src">▾ 展开全文</span></summary>
      <div class="txt" style="margin-top:8px;white-space:pre-wrap">${esc(p.question||'')}</div>
      <div class="txt md" style="margin-top:6px">${md(p.answer||'')}</div></details>
      <div data-rthumbs="${esc(p.id)}"></div></td>
      <td class="num">${(p.confidence||0).toFixed(2)}</td><td><span class="chip warn"><span class="dot"></span>${esc(p.risk_reason||'高风险')}</span></td>
      <td class="num">${esc(p.submitted_by)}<div class="src">${fmtTs(p.ts)}</div></td>
      <td>${resolved?(statusChip+`<div class="src">${esc(p.reviewed_by||'')}${p.resolved_at?' · '+fmtTs(p.resolved_at):''} ${esc(p.review_note||'')}</div>`+
        (auto&&canSign()&&p.submitted_by!==reviewMe?`<div style="margin-top:6px"><button class="btn sm secondary" onclick="reopenReview('${p.id}')">转人工复核</button></div>`:'')):
        (mine?'<span class="src">双控：你不能核对本人提交</span>':
        (canSignItem(p)?`<div style="display:inline-flex;gap:6px;vertical-align:middle"><button class="btn sm primary" onclick="resolve('${p.id}','approved')">通过签发</button>
        <button class="btn sm secondary" onclick="resolve('${p.id}','rejected')">驳回</button></div>`
        :'<span class="src">'+(p.agent==='drug'
          /* 问题4 收权后的 drug 例外说明：药物核对权=药师/管理员（qc 不再可签发/驳回） */
          ?(ROLE==='pharmacist'?'药物核对项：药师仅可处理本人之外提交的条目（双控）':'药物核对项：签发/驳回由药师（pharmacist）/管理员执行')
          :(CFG.full?'留痕模式：AI 已自动签发（只读档案）':'签发/驳回需质控员或管理员'))+'</span>'))}
        <button class="btn sm ghost" onclick="openReviewDetail('${p.id}')">详情</button></td></tr>`;}
/* 问题3②：处方审核历史行（rid/状态/意见/时间）——pharmacist「我的历史」tab 合并渲染。
   处方审核不在 review 队列体系（双轨），rid/药单/意见/时间直接取自处方记录字段。 */
function rxHistRow(p){
  const ok=p.status==='approved';
  return `<tr><td><details><summary class="qtext" style="cursor:pointer;list-style:none">处方审核：${esc(p.id)}（医生 ${esc(p.doctor||'—')}）<span class="src">▾ 展开详情</span></summary>
      <div class="txt" style="margin-top:8px;white-space:pre-wrap">${esc(p.case_text||'')}</div>
      <ul class="feed" style="margin-top:6px">${(p.drugs||[]).map(d=>`<li><span class="e">${esc(d.name)}</span><span class="src">${esc(d.dose||'剂量待定')} · ${esc(d.freq||'频次待定')}</span></li>`).join('')}</ul></details></td>
      <td class="num">—</td>
      <td><span class="chip ${p.forced_high_risk?'bad':'mid'}"><span class="dot"></span>${p.forced_high_risk?'高危强制开立':'处方审核'}</span></td>
      <td class="num">${esc(p.doctor||'—')}<div class="src">${fmtTs(p.created_at)}</div></td>
      <td>${ok?'<span class="chip ok"><span class="dot"></span>已签发通过</span>':'<span class="chip warn"><span class="dot"></span>已驳回</span>'}
        <div class="src">${esc(p.pharm_reviewer||'')}${p.pharm_reviewed_at?' · '+fmtTs(p.pharm_reviewed_at):''} ${esc(p.pharm_opinion||'')}</div></td></tr>`;}
/* 任务3：doctor「我的待确认」行——AI 自动签发的高危项等待本人知情确认（仅确认，无签发权） */
function myConfirmRow(p){
  const autoSigned=p.status==='approved'&&/AI·/.test(String(p.reviewed_by||''));
  return `<tr><td><details><summary class="qtext" style="cursor:pointer;list-style:none">${esc(p.agent)}：${esc((p.question||'').slice(0,60))} <span class="src">▾ 展开全文</span></summary>
      <div class="txt" style="margin-top:8px;white-space:pre-wrap">${esc(p.question||'')}</div>
      <div class="txt md" style="margin-top:6px">${md(p.answer||'')}</div></details>
      <div data-rthumbs="${esc(p.id)}"></div></td>
      <td class="num">${(p.confidence||0).toFixed(2)}</td><td><span class="chip warn"><span class="dot"></span>${esc(p.risk_reason||'高风险')}</span></td>
      <td class="num">${esc(p.submitted_by)}<div class="src">${fmtTs(p.ts)}</div></td>
      <td>${autoSigned?'<span class="chip mid"><span class="dot"></span>AI 自动签发 · 待您知情确认</span>':''}
        <button class="btn sm primary" onclick="selfConfirm('${p.id}')">知情确认</button>
        <button class="btn sm ghost" onclick="openReviewDetail('${p.id}')">详情</button></td></tr>`;}
function renderReview(){
  const b=document.getElementById('reviewBody');
  const mine=!(ROLE==='qc'||ROLE==='admin');
  if(reviewTab==='history'){
    /* 问题3②：「我的历史」合并渲染——review 队列记录 + 本人审核的处方记录（pharmacist，
       GET /prescriptions/reviewed-by-me）。处方记录补 agent/submitted_by/risk_reason
       供 _rvMatch 筛选与行渲染分流（_rx 标记），统一分页。 */
    const rxh=(ROLE==='pharmacist'?(rxHistoryItems||[]):[])
      .map(p=>({...p,_rx:true,agent:'处方审核',submitted_by:p.doctor||'—',
        risk_reason:p.forced_high_risk?'高危强制开立':'处方审核'}))
      .filter(_rvMatch);
    const hist=rxh.concat((historyItems||[]).filter(_rvMatch));  /* 任务3：筛选后分页（问题3② 起含处方审核记录） */
    if(!hist.length){b.innerHTML='<div class="empty">'+ICON.review+`<div>${mine?'暂无您的历史记录。':'暂无已处理的历史记录。'}</div><div class="lead" style="margin:8px auto 0">${reviewFilterQ?'无匹配结果，可调整筛选词。':'签发/驳回过的项会出现在这里。'}</div></div>`;return;}
    const pages=Math.max(1,Math.ceil(hist.length/HIST_PER));
    if(histPage>=pages)histPage=pages-1; if(histPage<0)histPage=0;
    const hslice=hist.slice(histPage*HIST_PER,histPage*HIST_PER+HIST_PER);  /* 任务1：切片留存供缩略图挂载 */
    const rows=hslice.map(p=>p._rx?rxHistRow(p):reviewRow(p,true)).join('');  /* 问题3②：处方审核记录专用行渲染 */
    const hpager=`<div style="display:flex;align-items:center;gap:12px;padding:12px 16px;border-top:1px solid var(--line)">
      <button class="btn sm secondary" ${histPage===0?'disabled':''} onclick="histGo(-1)">‹ 上一页</button>
      <span class="src num">第 ${histPage+1} / ${pages} 页 · 共 ${hist.length} 条已处理</span>
      <button class="btn sm secondary" ${histPage>=pages-1?'disabled':''} onclick="histGo(1)">下一页 ›</button></div>`;
    b.innerHTML=`<div class="card" style="padding:0;overflow:hidden"><table><thead><tr><th>项目（点击展开全文）</th><th>置信度</th><th>风险</th><th>提交人</th><th>处理结果</th></tr></thead><tbody>${rows}</tbody></table>${hpager}</div>`;
    mountReviewThumbs(hslice);  /* 任务1：历史行缩略图条挂载 */
    return;}
  /* 任务3：doctor/pharmacist「我的待确认」分支（数据源 my-pending-confirm，仅本人）。
     问题2 更新注明：pharmacist「药物高危待核对」表已随两药快查下线移除——
     待处理 tab=开药待审区+我的待确认；drug 项不再入队、历史全角色过滤。 */
  if(mine){
    const ph=ROLE==='pharmacist';
    const mineItems=(myConfirmItems||[]).filter(_rvMatch);
    if(!mineItems.length&&!rxPendingItems.length){b.innerHTML=`<div class="empty">${ICON.review}<div>${ph?'暂无待办（开药待审）。':'没有您的待确认项。'}</div><div class="lead" style="margin:8px auto 0">${reviewFilterQ?'无匹配结果，可调整筛选词。':(ph?'开药工作台直送的处方会出现在这里，由您签发/驳回；药品字典与相互作用规则在上方标签页维护。':(CFG.full?'留痕模式下 AI 自动签发的高危结论会出现在这里，等待您知情确认。':'留痕模式未开启（QC_AUTO_SIGN_FULL=false），高危结论走人工双控'))}</div></div>`;return;}
    /* 阶段3.2：开药待审区（pharmacist 专属数据源；doctor 恒返回空串不渲染） */
    const rxSec=rxPendingSection();
    const pagesM=Math.max(1,Math.ceil(mineItems.length/REVIEW_PER));
    if(reviewPage>=pagesM)reviewPage=pagesM-1; if(reviewPage<0)reviewPage=0;
    const startM=reviewPage*REVIEW_PER, sliceM=mineItems.slice(startM,startM+REVIEW_PER);
    const rowsM=sliceM.map(myConfirmRow).join('');
    const pagerM=`<div style="display:flex;align-items:center;gap:12px;padding:12px 16px;border-top:1px solid var(--line)">
      <button class="btn sm secondary" ${reviewPage===0?'disabled':''} onclick="reviewGo(-1)">‹ 上一页</button>
      <span class="src num">第 ${reviewPage+1} / ${pagesM} 页 · 共 ${mineItems.length} 项</span>
      <button class="btn sm secondary" ${reviewPage>=pagesM-1?'disabled':''} onclick="reviewGo(1)">下一页 ›</button></div>`;
    /* 我的待确认为空时只渲染开药待审区（不出空表壳） */
    const mineTable=mineItems.length?`<div class="card" style="padding:0;overflow:hidden"><table><thead><tr><th>项目（点击展开全文）</th><th>置信度</th><th>风险</th><th>提交人</th><th>操作</th></tr></thead><tbody>${rowsM}</tbody></table>${pagerM}</div>`:'';
    b.innerHTML=rxSec+mineTable;
    mountReviewThumbs(sliceM);  /* 任务1：待确认行缩略图条挂载（问题2：drugItems 并入渲染已随药物待核对区移除） */
    return;}
  const pend=(reviewItems||[]).filter(_rvMatch);  /* 任务3：筛选后分页 */
  if(!pend.length){b.innerHTML=rxPendingSection()+`<div class="empty">${ICON.review}<div>没有待核对项。</div><div class="lead" style="margin:8px auto 0">${reviewFilterQ?'无匹配结果，可调整筛选词。':'当出现药物禁忌、影像高危征象或证据不足的低置信回答时，会自动进入这里，等待质控员/管理员签发。'}</div></div>`;return;}
  const pages=Math.max(1,Math.ceil(pend.length/REVIEW_PER));
  if(reviewPage>=pages)reviewPage=pages-1; if(reviewPage<0)reviewPage=0;
  const start=reviewPage*REVIEW_PER, slice=pend.slice(start,start+REVIEW_PER);
  const rows=slice.map(p=>reviewRow(p,false)).join('');
  const total=Math.ceil(pend.length/REVIEW_PER);
  const pager=`<div style="display:flex;align-items:center;gap:12px;padding:12px 16px;border-top:1px solid var(--line)">
    <button class="btn sm secondary" ${reviewPage===0?'disabled':''} onclick="reviewGo(-1)">‹ 上一页</button>
    <span class="src num">第 ${reviewPage+1} / ${total} 页 · 共 ${reviewItems.length} 项</span>
    <button class="btn sm secondary" ${reviewPage>=total-1?'disabled':''} onclick="reviewGo(1)">下一页 ›</button></div>`;
  b.innerHTML=rxPendingSection()+`<div class="card" style="padding:0;overflow:hidden"><table><thead><tr><th>项目</th><th>置信度</th><th>风险</th><th>提交人</th><th>操作</th></tr></thead><tbody>${rows}</tbody></table>${pager}</div>`;
  mountReviewThumbs(slice);  /* 任务1：待处理行缩略图条挂载 */
}
/* ---- 阶段3.2：药剂科开药审核队列（prescriptions/pending → 处方卡 → 通过/驳回） ---- */
function rxPendingSection(){  /* 开药待审区：问题4 收权后仅 pharmacist/admin 渲染（qc 无处方审核权、doctor 403，均恒返回空串） */
  if(ROLE!=='pharmacist'&&ROLE!=='admin')return '';
  const items=rxPendingItems||[];
  const head=`<div class="card" style="margin-bottom:12px;border-left:3px solid var(--accent)"><h3 style="margin:0">开药待审（处方队列）</h3>
    <div class="src" style="margin-top:4px">开药工作台直送的处方：核对药单与相互作用；驳回必须填写意见（双控：不能审核本人提交的处方）。</div></div>`;
  if(!items.length)return head+'<div class="card" style="margin-bottom:16px"><div class="src">暂无开药待审处方。</div></div>';
  return head+items.map(rxReviewCard).join('');
}
function rxReviewCard(it){  /* 处方卡：医生/科室/病例摘要截断/药单；forced_high_risk 红标 + 联用理由 */
  const forced=it.forced_high_risk?'<span class="chip bad"><span class="dot"></span>高危强制开立</span>':'';
  return `<div class="card" data-rxid="${esc(it.id)}" style="margin-bottom:12px">
    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <b class="num">${esc(it.id)}</b>${forced}
      <span class="src">医生 ${esc(it.doctor||'—')} · 科室 ${esc(it.dept||'—')} · ${fmtTs(it.created_at)}</span>
      <span style="margin-left:auto;display:inline-flex;gap:6px">
        <button class="btn sm primary" onclick="rxReview('${esc(it.id)}','approve')">通过</button>
        <button class="btn sm secondary" onclick="rxReview('${esc(it.id)}','reject')">驳回</button></span></div>
    <div class="txt" style="margin-top:6px">病例摘要：${esc((it.case_text||'').slice(0,120))}${(it.case_text||'').length>120?'…':''}</div>
    <ul class="feed" style="margin-top:4px">${(it.drugs||[]).map(d=>`<li><span class="e">${esc(d.name)}</span><span class="src">${esc(d.dose||'剂量待定')} · ${esc(d.freq||'频次待定')}${d.note?' · '+esc(d.note):''}</span></li>`).join('')}</ul>
    ${(it.contraindication_reason||it.forced_high_risk)?`<div class="src" style="margin-top:4px;border-left:3px solid var(--danger);padding-left:8px">联用理由：${esc(it.contraindication_reason||'（未填写）')}</div>`:''}
  </div>`;
}
async function rxReview(id,action){  /* 通过/驳回：POST /prescriptions/{rid}/review（驳回必填意见，服务端 422 双保险） */
  if(action==='reject'){
    document.getElementById('mTitle').textContent='驳回处方 · 填写审核意见';
    document.getElementById('mBody').innerHTML=`<textarea class="inp" id="rxRejectOp" rows="3" aria-label="驳回意见" placeholder="驳回意见（必填，将反馈给开药医生用于重写）"></textarea>
      <div style="margin-top:12px;display:flex;gap:8px;justify-content:flex-end">
        <button class="btn ghost sm" onclick="closeModal()">取消</button>
        <button class="btn danger sm" id="rxRejectGo">确认驳回</button></div>`;
    openModal();
    document.getElementById('rxRejectOp').focus();
    document.getElementById('rxRejectGo').onclick=async()=>{
      const op=document.getElementById('rxRejectOp').value.trim();
      if(!op){toast('驳回必须填写审核意见');return;}
      closeModal();
      await _rxReviewPost(id,{action:'reject',opinion:op});
    };
    return;}
  await _rxReviewPost(id,{action:'approve',opinion:''});
}
async function _rxReviewPost(id,body){
  try{const r=await fetch('/api/v1/medical/prescriptions/'+encodeURIComponent(id)+'/review',
    {method:'POST',headers:auth(),body:JSON.stringify(body)});
    if(r.status===401){logout();return;}
    if(r.ok){toast(body.action==='approve'?'处方已签发通过':'已驳回，意见将反馈给开药医生');loadReview();}
    else{const d=await r.json();toast('操作失败：'+(d.detail||r.status));}
  }catch(e){toast('操作失败：'+e.message);}
}
function reviewGo(d){reviewPage+=d;renderReview();document.getElementById('reviewBody').scrollTop=0;}
/* ---- 阶段4：病例库（qc/admin 审核中心 tab；数据源 GET /case-archive）----
   质控 approve 自动归档（后端挂点），本视图提供：统计头（N 例在库 / M 移除）+
   科室/时间筛选 + status 徽章（active 绿 / removed 灰）+ 质控结论摘要 +
   record 展开详情（<pre> esc 后入 HTML）+ 移除按钮（qc/admin，原因必填）。 */
let caseArchItems=[],caseArchStats={active:0,removed:0,total:0};
async function loadCaseArchive(){
  const b=document.getElementById('reviewBody');if(!b)return;
  b.innerHTML='<div class="card"><div class="sk" style="height:18px;width:30%;margin-bottom:14px"></div><div class="sk"></div></div>';
  try{const r=await fetch('/api/v1/medical/case-archive',{headers:auth()});
    if(r.status===401){logout();return;}
    const d=await r.json();
    caseArchItems=d.items||[];caseArchStats=d.stats||{};
    renderCaseArchive();
  }catch(e){b.innerHTML='<div class="card">⚠ '+esc(e.message)+'</div>';}}
function caseArchFiltered(){  /* 客户端二次筛选（dept/status/日期区间），数据源已按角色限权 */
  const g=id=>{const el=document.getElementById(id);return el?el.value.trim():'';};
  const dep=g('caDept'),st=g('caStatus'),from=g('caFrom'),to=g('caTo');
  return (caseArchItems||[]).filter(i=>(!dep||(i.dept||'')===dep)&&(!st||(i.status||'')===st)
    &&(!from||String(i.archived_at||'').slice(0,10)>=from)
    &&(!to||String(i.archived_at||'').slice(0,10)<=to));}
function renderCaseArchive(){
  const b=document.getElementById('reviewBody');if(!b)return;
  /* 重渲染保留当前筛选值（innerHTML 重建会重置表单控件） */
  const prev={};['caDept','caStatus','caFrom','caTo'].forEach(id=>{const el=document.getElementById(id);if(el)prev[id]=el.value;});
  const list=caseArchFiltered();
  const depts=[...new Set((caseArchItems||[]).map(i=>i.dept||'').filter(Boolean))];
  const rows=list.map(caseArchRow).join('');
  b.innerHTML=`<div class="card" style="border-left:3px solid var(--accent)">
    <h3 style="margin:0">病例库（质控通过自动归档）</h3>
    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:8px">
      <span class="chip ok"><span class="dot"></span>${esc(String(caseArchStats.active??0))} 例在库</span>
      <span class="chip"><span class="dot"></span>${esc(String(caseArchStats.removed??0))} 已移除</span>
      <span class="src num">共 ${esc(String(caseArchStats.total??0))} 条 · 移除为软删除留痕（可审计）</span>
    </div></div>
  <div class="card">
    <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
      <select class="inp" id="caDept" style="max-width:150px" aria-label="科室筛选" onchange="renderCaseArchive()">
        <option value="">全部科室</option>${depts.map(d=>`<option value="${esc(d)}"${prev.caDept===d?' selected':''}>${esc(d)}</option>`).join('')}</select>
      <select class="inp" id="caStatus" style="max-width:130px" aria-label="状态筛选" onchange="renderCaseArchive()">
        <option value="">全部状态</option>
        <option value="active"${prev.caStatus==='active'?' selected':''}>在库</option>
        <option value="removed"${prev.caStatus==='removed'?' selected':''}>已移除</option></select>
      <input class="inp num" id="caFrom" type="date" style="max-width:160px" aria-label="归档起始日期" value="${esc(prev.caFrom||'')}" onchange="renderCaseArchive()">
      <input class="inp num" id="caTo" type="date" style="max-width:160px" aria-label="归档截止日期" value="${esc(prev.caTo||'')}" onchange="renderCaseArchive()">
      <span class="src num">${esc(String(list.length))} 条</span>
    </div>
    <ul class="feed" style="margin-top:8px">${rows||'<li><span class="a">暂无归档病例（质控 approve 后自动入库）。</span></li>'}</ul></div>`;}
function caseArchRow(i){  /* 单条归档行：status 徽章 + 脱敏患者标识 + 结论摘要 + record 展开详情 */
  const active=i.status==='active';
  const badge=active?'<span class="chip ok"><span class="dot"></span>在库</span>'
    :'<span class="chip"><span class="dot"></span>已移除</span>';
  return `<li style="border-top:1px solid var(--line);padding:10px 0">
    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <b class="num">${esc(i.id||'')}</b>${badge}
      <span class="chip mid"><span class="dot"></span>${esc(i.dept||'科室—')}</span>
      <span class="src">患者标识：${esc(i.patient_ref||'—')}</span>
      <span class="t num">${fmtTs(i.archived_at)}</span>
      <span class="src">质控签字：${esc(i.reviewed_by||'—')}</span>
      ${active?`<button class="btn sm danger" style="margin-left:auto" data-act="ca-remove" data-aid="${esc(i.id||'')}">移除</button>`:''}
    </div>
    <details style="margin-top:6px"><summary class="src" style="cursor:pointer">质控结论摘要与病历详情 ▾</summary>
      <div class="txt" style="white-space:pre-wrap;margin-top:4px">${esc(i.qc_conclusion||'（无结论摘要）')}</div>
      <div class="src num" style="margin-top:4px">质控置信：${esc(String(i.qc_confidence??'—'))}</div>
      <pre style="background:var(--rail);border:1px solid var(--line);border-radius:6px;padding:10px;font-size:11.5px;margin:6px 0 0;overflow-x:auto;white-space:pre-wrap">${esc(JSON.stringify(i.record||{},null,2))}</pre>
      ${(i.labs&&Object.keys(i.labs).length)?`<div class="src" style="margin-top:4px">检验值：${esc(Object.entries(i.labs).map(([k,v])=>k+'='+v).join('；'))}</div>`:''}
    </details>
    ${active?'':`<div class="src" style="margin-top:4px">移除人：${esc(i.removed_by||'—')} · 移除原因：${esc(i.removed_reason||'—')}</div>`}
  </li>`;}
/* 移除按钮事件委托（与 KB/科室同纪律，禁内联 onclick 拼接 id） */
if(!window.__caseArchDelegate){window.__caseArchDelegate=true;document.addEventListener('click',e=>{
  const b=e.target.closest('button[data-act="ca-remove"]');if(!b)return;
  caseArchiveRemove(b.dataset.aid);});}
function caseArchiveRemove(aid){  /* 移出病例库：原因必填（空值 toast 阻断 + 服务端 422 双保险） */
  document.getElementById('mTitle').textContent='移出病例库 · '+aid;
  document.getElementById('mBody').innerHTML=`<div class="src">移除为软删除（status=removed 留痕，可审计不可静默抹除），移除原因必填并记入审计。</div>
    <textarea class="inp" id="caRemoveReason" rows="3" aria-label="移除原因（必填）" placeholder="移除原因（必填，如：患者身份信息录入有误需重新归档）" style="margin-top:8px"></textarea>
    <div style="margin-top:12px;display:flex;gap:8px;justify-content:flex-end">
      <button class="btn ghost sm" onclick="closeModal()">取消</button>
      <button class="btn danger sm" id="caRemoveGo">确认移除</button></div>`;
  openModal();
  document.getElementById('caRemoveGo').onclick=async()=>{
    const reason=document.getElementById('caRemoveReason').value.trim();
    if(!reason){toast('移除原因必填');return;}
    try{const r=await fetch('/api/v1/medical/case-archive/'+encodeURIComponent(aid)+'/remove',
      {method:'POST',headers:auth(),body:JSON.stringify({reason})});
      if(r.status===401){logout();return;}
      if(r.ok){toast('已移出病例库');closeModal();loadCaseArchive();}
      else{const d=await r.json();toast('失败：'+(d.detail||r.status));}
    }catch(e){toast('失败：'+e.message);}
  };
}
let rejectTarget=null, signTarget=null; /* signTarget：待确认的签发操作 {id,dec,note}（F4） */
/* F4：轻量签发确认（复用 #modal 容器，不要求输入确认词）。确认后才执行 doResolve；
   Escape / 取消 / 关闭 / 点击遮罩均只 closeModal，不产生任何 API 调用。 */
function askSignConfirm(id,dec,note){
  signTarget={id,dec,note:note||''};
  const label=dec==='approved'?'通过签发':'驳回';
  document.getElementById('mTitle').textContent='签发确认';
  document.getElementById('mBody').innerHTML=`<div class="txt">确认【${label}】该核对项？此操作记入审计留痕。</div>
    <div style="margin-top:12px;display:flex;gap:8px;justify-content:flex-end">
      <button class="btn secondary sm" id="scCancel">取消</button>
      <button class="btn ${dec==='approved'?'primary':'danger'} sm" id="scGo">确认</button></div>`;
  openModal();
  const c=document.getElementById('scCancel');c.focus();
  c.onclick=()=>{signTarget=null;closeModal();};
  document.getElementById('scGo').onclick=()=>{const t=signTarget;signTarget=null;closeModal();doResolve(t.id,t.dec,t.note);};
}
function resolve(id,dec){
  if(dec==='rejected'){ /* 模态框填写驳回原因，替代原生 prompt()；签发确认框在备注之后（F4） */
    rejectTarget=id;
    document.getElementById('mTitle').textContent='驳回 · 填写原因';
    document.getElementById('mBody').innerHTML=`<textarea class="inp" id="rejectNote" rows="3" aria-label="驳回原因" placeholder="驳回原因（可选，将记入审计留痕）"></textarea>
      <div style="margin-top:12px;display:flex;gap:8px;justify-content:flex-end">
        <button class="btn ghost sm" onclick="closeModal()">取消</button>
        <button class="btn danger sm" onclick="confirmReject()">确认驳回</button></div>`;
    openModal();
    document.getElementById('rejectNote').focus();
    return;}
  askSignConfirm(id,dec,'');
}
async function doResolve(id,dec,note){
  const r=await fetch('/api/v1/medical/review/'+id+'/resolve',{method:'POST',headers:auth(),body:JSON.stringify({decision:dec,note})});
  if(r.status===401){logout();return;} if(r.ok){toast(dec==='approved'?'已签发通过':'已驳回');closeModal();loadReview();}
  else{const d=await r.json();toast('操作失败：'+(d.detail||r.status));}}
function confirmReject(){const n=document.getElementById('rejectNote');askSignConfirm(rejectTarget,'rejected',n?n.value.trim():'');} /* F4：备注之后先确认再执行 */
/* 双控自动签发翻案：POST /review/{id}/reopen，回到待核对由另一名人员人工复核（双控对称：提交人不可翻案） */
async function reopenReview(id){
  try{const r=await fetch('/api/v1/medical/review/'+id+'/reopen',{method:'POST',headers:auth()});
    if(r.status===401){logout();return;}
    if(r.ok){toast('已转人工复核，请在「待处理」中核对');loadReview();}
    else{const d=await r.json();toast('操作失败：'+(d.detail||r.status));}
  }catch(e){toast('操作失败：'+e.message);}}
/* 任务3：医生对 AI 留痕自动签发项知情确认（仅本人；后端校验+审计 self_confirmed） */
async function selfConfirm(id){
  try{const r=await fetch('/api/v1/medical/review/'+id+'/self-confirm',{method:'POST',headers:auth()});
    if(r.status===401){logout();return;}
    if(r.ok){toast('已确认知悉，感谢配合');loadReview();}
    else{const d=await r.json();toast('操作失败：'+(d.detail||r.status));}
  }catch(e){toast('操作失败：'+e.message);}}
function openReviewDetail(id){const p=(reviewItems||[]).concat(historyItems||[],myConfirmItems||[]).find(x=>x.id===id);if(!p){toast('未找到');return;}
  const mine=p.submitted_by===(document.getElementById('who').textContent);
  const auto=p.status==='approved'&&/AI·(阈值自动|留痕模式)/.test(String(p.reviewed_by||''));  /* 双控自动签发 */
  /* F3 核对可见图片：仅渲染 data:image/ 前缀的影像（防属性逃逸再叠加 esc），点击放大复用模态 */
  const imgs=(p.images||[]).filter(u=>String(u).startsWith('data:image/'));
  const imgsHtml=imgs.length?`<h4 style="margin-top:14px">影像（点击放大）</h4><div style="margin-top:6px;display:flex;flex-wrap:wrap;gap:6px">${imgs.map(u=>`<img class="thumb" style="width:38px;height:38px;object-fit:cover;border-radius:6px;cursor:zoom-in" src="${esc(u)}" alt="待核对影像" onclick="showImg(this.src)">`).join('')}</div>`:'';
  /* 任务3：操作区按角色/模式渲染——本批次任务2 改按 canSignItem（agent 分流）：
     qc/admin/pharmacist 对 drug 项可签发/驳回（含留痕模式）；其它 agent 维持 qc/admin
     且非留痕模式。医生本人且待确认项 → 知情确认；翻案仍按 canSign（qc/admin）。其余只读 */
  const actions=`<div style="margin-top:16px;display:flex;gap:8px;flex-wrap:wrap">${
    (mine&&p.self_confirm_required&&!p.confirmed_by_self)?`<button class="btn sm primary" onclick="selfConfirm('${p.id}')">知情确认</button>`:''}${(canSignItem(p)&&!mine)?`<button class="btn sm primary" onclick="resolve('${p.id}','approved')">通过签发</button><button class="btn sm secondary" onclick="resolve('${p.id}','rejected')">驳回</button>`:''}
      ${(canSign()&&!mine&&auto)?`<button class="btn sm ghost" onclick="reopenReview('${p.id}')">转人工复核</button>`:''}
      ${(!canSignItem(p)&&!mine)?'<span class="src">'+(p.agent==='drug'
        /* 问题4 收权后的 drug 例外说明：药物核对权=药师/管理员 */
        ?'签发/驳回由药师（pharmacist）/管理员执行':'签发/驳回由质控员（qc）/管理员执行')+'</span>':''}</div>`;
  document.getElementById('mTitle').textContent='核对详情 · '+p.agent;
  document.getElementById('mBody').innerHTML=`<div class="foot" style="margin-bottom:12px">${confChip(p.confidence||0,true)}<span class="chip warn"><span class="dot"></span>${esc(p.risk_reason||'高风险')}</span>${auto?'<span class="chip mid"><span class="dot"></span>自动签发</span>':''}${p.confirmed_by_self?'<span class="chip ok"><span class="dot"></span>本人已确认</span>':''}</div>
    <h4>问题</h4><div class="txt md">${md(p.question||'')}</div>
    <h4 style="margin-top:14px">AI 回答</h4><div class="txt md">${md(p.answer||'')}</div>
    ${imgsHtml}
    <h4 style="margin-top:14px">提交人</h4><div>${esc(p.submitted_by||'')}</div>
    ${(p.reviewed_by&&p.status!=='pending')?`<h4 style="margin-top:14px">审核</h4><div>${esc(p.reviewed_by)}${p.resolved_at?' · '+fmtTs(p.resolved_at):''}${p.review_note?' · '+esc(p.review_note):''}</div>`:''}
    ${actions}`;
  openModal();document.getElementById('mBody').querySelector('input,button,textarea,[tabindex]')?.focus();}
/* F3：影像放大查看（复用 #modal）。data URL 前缀校验：非 data:image/ 一律拒绝渲染 */
function showImg(src){if(!src||!src.startsWith('data:image/'))return;
  document.getElementById('mTitle').textContent='影像查看';
  document.getElementById('mBody').innerHTML=`<img style="max-width:100%" src="${esc(src)}" alt="待核对影像">`;
  openModal();}
let _modalReturn=null;
function openModal(){_modalReturn=document.activeElement;document.getElementById('modal').classList.add('on');}
function closeModal(){document.getElementById('modal').classList.remove('on');if(_modalReturn&&_modalReturn.focus){_modalReturn.focus();}_modalReturn=null;}
document.addEventListener('keydown',e=>{
  const m=document.getElementById('modal');
  if(e.key==='Escape'&&m.classList.contains('on')){closeModal();return;}
  if(e.key==='Tab'&&m.classList.contains('on')){  /* 焦点圈禁：Tab 循环限制在模态内可聚焦元素 */
    const f=[...m.querySelectorAll('button,input,textarea,select,summary,a[href],[contenteditable=true],[tabindex]:not([tabindex="-1"])')]
      .filter(el=>!el.disabled&&el.offsetParent!==null);
    if(!f.length){  /* FIND-10：模态内无可聚焦元素（如纯图片查看）→ 圈禁在卡片容器，防焦点逃逸到背景 */
      e.preventDefault();
      const card=document.querySelector('#modal .mcard');
      card?.setAttribute('tabindex','-1');card?.focus();
      return;
    }
    if(!m.contains(document.activeElement)){e.preventDefault();f[0].focus();return;}
    const first=f[0],last=f[f.length-1];
    if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}
    else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}
  }
});

/* ---- 病历质控 ---- */
async function runQc(){const rec={};QC_LABELS.forEach((l,i)=>{const v=document.getElementById('qcf_'+i).value.trim();if(v)rec[l]=v;});
  if(!Object.keys(rec).length){toast('请至少填写病历内容');return;}
  const btn=document.getElementById('qcRun');btn.disabled=true;
  const labs={};document.getElementById('qcLabs').value.split(/\r?\n/).forEach(ln=>{const m=ln.split('=');if(m.length===2&&m[0].trim())labs[m[0].trim()]=m[1].trim();});
  const out=document.getElementById('qcOut'); out.innerHTML='<div class="card"><div class="sk" style="height:16px;width:40%"></div></div>';
  try{ const d=await apiPost('/qc/record',{record:rec,labs:labs,...(qcResubmitOf?{resubmit_of:qcResubmitOf}:{})});
    if(d.detail&&!d.answer){out.innerHTML='<div class="card">⚠ '+esc(d.detail)+'</div>';return;}  /* 如：账号未设置科室 */
    /* QC 三档分流徽标：按后端实际字段渲染（status='auto_rejected'/'rejected_escalated' / needs_human_review+review_id）
       任务6：rejected_escalated=重提自动升级病案科复核 */
    const triage=d.status==='auto_rejected'?'<span class="chip bad"><span class="dot"></span>AI自动驳回</span>'
      :d.status==='rejected_escalated'?'<span class="chip bad"><span class="dot"></span>已升级病案科复核</span>'
      :(!d.needs_human_review&&!d.review_id)?'<span class="chip ok"><span class="dot"></span>AI预审通过</span>'
      :'<span class="chip mid"><span class="dot"></span>人工复核</span>';
    /* 任务1：按维度分组渲染（QC_CATEGORIES 六枚举，与后端一致）+ 快速过滤 chip；
       无结构化 qc_defects（异常/降级）时回落到原文渲染，保证任何情况都有结果可见。 */
    qcLastDefects=(d.qc_defects||[]).filter(x=>x&&x.category);qcCatFilter='全部';
    const grouped=qcLastDefects.length?`<div id="qcChips" style="display:flex;gap:6px;flex-wrap:wrap;margin:10px 0 2px">${qcChipRow()}</div>
      <div id="qcGroups"></div>
      <details style="margin-top:10px"><summary class="src" style="cursor:pointer">AI 建议原文 ▾</summary>
        <div class="txt md" style="margin-top:6px">${md(d.answer)}</div></details>`
      :`<div class="txt md">${md(d.answer)}</div>`;
    out.innerHTML=`<div class="card"><div class="foot" style="margin:0 0 8px">${confChip(d.confidence,d.needs_human_review)}${triage}</div>${grouped}</div>`;
    if(qcLastDefects.length)renderQcGroups();
    if(qcResubmitOf){qcCancelResubmit();loadQcRejections();}  /* 任务6：重提成功→清上下文并刷新驳回卡片（新结果置顶+第 N 次提交徽标） */
  }catch(e){out.innerHTML='<div class="card">⚠ 质控请求失败</div>';}
  finally{btn.disabled=false;}}
/* ---- 任务1：质控结果按维度分组 + 快速过滤 ---- */
const QC_CATEGORIES=['完整性','一致性','诊断依据','鉴别诊断','书写规范','其他'];
let qcLastDefects=[],qcCatFilter='全部';
function qcChipRow(){const counts={};qcLastDefects.forEach(d=>{counts[d.category]=(counts[d.category]||0)+1;});
  const cats=['全部'].concat(QC_CATEGORIES.filter(c=>counts[c]));
  return cats.map(c=>{const n=c==='全部'?qcLastDefects.length:(counts[c]||0);
    return `<button class="btn sm ${qcCatFilter===c?'primary':'ghost'}" onclick="qcFilter('${c}')">${esc(c)} <span class="num">${n}</span></button>`;}).join('');}
function qcFilter(c){qcCatFilter=c;const chips=document.getElementById('qcChips');if(chips)chips.innerHTML=qcChipRow();renderQcGroups();}
function renderQcGroups(){const el=document.getElementById('qcGroups');if(!el)return;
  const list=qcLastDefects.filter(d=>qcCatFilter==='全部'||d.category===qcCatFilter);
  const by={};list.forEach(d=>{(by[d.category]=by[d.category]||[]).push(d);});
  const html=QC_CATEGORIES.filter(c=>by[c]).map(c=>`<div style="margin-top:10px"><b>${esc(c)}</b>
      <ul class="rules" style="margin-top:4px">${by[c].map(d=>`<li>[${esc(d.level||'中')}] ${esc(d.issue||'')}${(d.field&&d.field!=='内涵质量')?`（${esc(d.field)}）`:''}</li>`).join('')}</ul></div>`).join('');
  el.innerHTML=html||'<div class="src" style="margin-top:8px">当前维度暂无缺陷条目。</div>';}
/* ---- 任务2：医生视图「我的质控驳回」卡片（只读；数据隔离由后端 /qc/my-rejections 服务端过滤保证）
   任务6：重提链可视化——重提后新条目置顶（后端把 resubmit_of 非空条目一并返回）；
   每条带「重新提交」按钮（预填原病历到质控表单并附 resubmit_of）；
   attempt>1 显示「第 N 次提交」徽标；attempt>=2 且被驳回显示「已升级病案科复核」徽标。 ---- */
let qcRejItems=[];
async function loadQcRejections(){
  const card=document.getElementById('qcRejCard'),el=document.getElementById('qcRej');
  if(!card||!el)return;
  if(ROLE==='qc'||ROLE==='admin'){card.style.display='none';return;}  /* qc/admin 走审核中心全局档案 */
  card.style.display='';
  el.innerHTML='<div class="src">加载中…</div>';
  try{const r=await fetch('/api/v1/medical/qc/my-rejections',{headers:auth()});
    if(r.status===401){logout();return;}
    const d=await r.json();const items=d.items||[];qcRejItems=items;
    el.innerHTML=items.length?items.map(i=>{
      const att=Number(i.attempt||1);
      const st={pending:'<span class="chip"><span class="dot"></span>待复核</span>',
        approved:'<span class="chip ok"><span class="dot"></span>已通过</span>',
        rejected:'<span class="chip warn"><span class="dot"></span>已驳回</span>'}[i.status]
        ||`<span class="chip">${esc(String(i.status||'—'))}</span>`;
      const badge=(att>1?`<span class="chip mid"><span class="dot"></span>第 ${att} 次提交</span>`:'')
        +((att>=2&&i.status==='rejected')?'<span class="chip bad"><span class="dot"></span>已升级病案科复核</span>':'');
      return `<div style="padding:8px 0;border-top:1px solid var(--line)">
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap"><span class="chip mid"><span class="dot"></span>${esc(i.agent||'qc')}</span>
      ${st}<b class="num">${fmtTs(i.resolved_at||i.ts)}</b><span class="src">审核人：${esc(i.reviewed_by||'—')}</span>${badge}
      <button class="btn sm secondary" style="margin-left:auto" data-act="qc-resubmit" data-rid="${esc(i.id)}">重新提交</button></div>
      <div class="txt" style="margin-top:4px;white-space:pre-wrap">${esc(String(i.question||'').slice(0,120))}</div>
      <div class="txt" style="margin-top:2px">驳回原因：${esc(i.review_note||'未填写')}</div>
    </div>`;}).join(''):'<div class="src">暂无驳回记录。</div>';
  }catch(e){el.innerHTML='<div class="src">⚠ 加载失败</div>';}}
/* ---- 阶段4：doctor「我的归档」小节（数据源 /case-archive/mine，仅本人 status=active，只读）----
   qc/admin 走审核中心「病例库」tab（全量+移除），pharmacist 无质控提交（端点 403 → 隐藏）。 */
let qcArchItems=[];
async function loadQcArch(){
  const card=document.getElementById('qcArchCard'),el=document.getElementById('qcArch');
  if(!card||!el)return;
  if(ROLE!=='doctor'){card.style.display='none';return;}
  card.style.display='';
  el.innerHTML='<div class="src">加载中…</div>';
  try{const r=await fetch('/api/v1/medical/case-archive/mine',{headers:auth()});
    if(r.status===401){logout();return;}
    if(r.status===403){card.style.display='none';return;}  /* 非 doctor 角色（如 pharmacist）端点 403 → 隐藏 */
    const d=await r.json();qcArchItems=d.items||[];
    el.innerHTML=qcArchItems.length?qcArchItems.map(i=>`<div style="padding:8px 0;border-top:1px solid var(--line)">
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap">
        <span class="chip ok"><span class="dot"></span>在库</span>
        <span class="src">患者标识：${esc(i.patient_ref||'—')}</span>
        <span class="src">${esc(i.dept||'')}</span>
        <b class="num">${fmtTs(i.archived_at)}</b>
        <span class="src">质控签字：${esc(i.reviewed_by||'—')}</span></div>
      <details style="margin-top:4px"><summary class="src" style="cursor:pointer">质控结论摘要 ▾</summary>
        <div class="txt" style="white-space:pre-wrap;margin-top:4px">${esc(i.qc_conclusion||'')}</div></details>
    </div>`).join(''):'<div class="src">暂无在库归档病历。</div>';
  }catch(e){el.innerHTML='<div class="src">⚠ 加载失败</div>';}}
/* 任务6：重新提交按钮事件委托（与 KB/科室同纪律，禁内联 onclick 拼接） */
if(!window.__qcRejDelegate){window.__qcRejDelegate=true;document.addEventListener('click',e=>{
  const b=e.target.closest('button[data-act="qc-resubmit"]');if(!b)return;
  qcResubmit(b.dataset.rid);});}
/* 任务6：预填原病历到质控表单并标记重提上下文（resubmit_of 随下次运行质控一并提交）
   阶段0.1 重提预填真实修复：meta.record 是后端 /qc/record 留档的结构化原病历（中文
   字段键与 QC_LABELS 一致）、meta.labs 是检验值键值对——逐栏 .value 直填（签名栏
   i=6 锁定当前账号不回填）；仅当确有非空预填内容才显示「已预填」横幅，旧版条目未
   留档（meta 为空）时如实提示手动填写，不再谎报预填导致"横幅已预填但各栏没值"。 */
let qcResubmitOf=null;
function qcResubmit(rid){
  const it=(qcRejItems||[]).find(x=>x.id===rid);
  if(!it){toast('未找到原记录，请刷新后重试');return;}
  qcResubmitOf=rid;
  const meta=it.meta||{},rec=meta.record||{};
  let filled=0;
  QC_LABELS.forEach((l,i)=>{if(i===6)return;const v=String(rec[l]||'');document.getElementById('qcf_'+i).value=v;if(v.trim())filled++;});
  const labs=meta.labs||{};
  document.getElementById('qcLabs').value=Object.entries(labs).map(([k,v])=>k+'='+v).join('\n');
  const bn=document.getElementById('qcResubmitBanner');
  if(bn){bn.style.display='';
    bn.innerHTML=`<b>重新提交（第 ${Number(it.attempt||1)+1} 次）</b>
      <div class="src" style="margin-top:4px;line-height:1.7">${filled?'原病历内容已预填':'该记录未留存结构化原病历（旧版条目），请手动填写病历内容'}（原记录 <b class="num">${esc(rid)}</b>），可修改后运行质控；医师签名绑定当前账号。第 2 次起仍不合格将自动升级病案科复核。</div>
      <div style="margin-top:8px"><button class="btn sm ghost" onclick="qcCancelResubmit()">取消重提</button></div>`;}
  /* 任务1：驳回重提跳转——确保处于质控工作台视图（复用 go('qc')；已在 qc 视图则不重复
     切换/重拉数据），滚动定位到病历表单并短暂高亮（CSS 边框闪烁 2s 后移除 class），
     引导医生视线落到预填内容上。 */
  if(cur!=='qc')go('qc');
  const formCard=document.getElementById('qcFormCard');
  if(formCard){formCard.scrollIntoView({behavior:'smooth',block:'start'});
    formCard.classList.add('qc-flash');
    setTimeout(()=>formCard.classList.remove('qc-flash'),2000);}
  /* 阶段0.1：toast 同步横幅语义——仅确有预填内容才提示"已预填" */
  toast(filled?'已按原记录预填病历内容，请核对后运行质控':'原记录未留档病历内容，请手动填写后运行质控');
}
function qcCancelResubmit(){qcResubmitOf=null;const bn=document.getElementById('qcResubmitBanner');
  if(bn){bn.style.display='none';bn.innerHTML='';}}
const QC_LABELS=['主诉','现病史','既往史','体格检查','辅助检查','初步诊断','医师签名'];
/* F2：粘贴整段病历 → /qc/parse LLM 拆分 → 填充 qcf_0-6（仅覆盖拆分出的非空字段，保留已填内容） */
async function qcParsePaste(){
  const t=document.getElementById('qcPaste').value.trim();
  if(!t){toast('请先粘贴病历文本');return;}
  const btn=document.getElementById('qcParse');btn.disabled=true;
  try{const r=await fetch('/api/v1/medical/qc/parse',{method:'POST',headers:auth(),body:JSON.stringify({text:t.slice(0,20000)})});
    if(r.status===401){logout();return;}
    const d=await r.json();
    if(!r.ok){toast('拆分失败：'+(d.detail||r.status));return;}
    const f=d.fields||{};let n=0;
    QC_LABELS.forEach((l,i)=>{if(i===6)return;const v=String(f[l]||'').trim();if(v){document.getElementById('qcf_'+i).value=v;n++;}});  /* i=6 签名锁定为当前账号，不被拆分覆盖 */
    document.getElementById('qcPaste').value='';
    toast(n?`已拆分填充 ${n} 个字段，请核对后运行质控`:'未拆分出内容，请手动填写');
  }catch(e){toast('拆分失败：'+e.message);}
  finally{btn.disabled=false;}
}

/* ---- 知识库管理（admin）---- */
function readB64(f){return new Promise(res=>{const rd=new FileReader();rd.onload=()=>res(String(rd.result).split(',')[1]);rd.readAsDataURL(f);});}
/* KB 行按钮事件委托：文档名可含引号，禁用内联 onclick 字符串拼接（防存储型 XSS） */
if(!window.__kbDelegate){window.__kbDelegate=true;document.addEventListener('click',e=>{
  const b=e.target.closest('button[data-act^="kb-"]');if(!b)return;
  if(b.dataset.act==='kb-preview')previewKb(b.dataset.tag);
  else if(b.dataset.act==='kb-delete')deleteKb(b.dataset.tag,b.dataset.name,b.dataset.kind);});}
let kbPage=0,kbData=null;const KB_PER=50;  /* KB 客户端分页：数据一次拉取，仅 tbody 分页渲染 */
function kbGo(d){kbPage+=d;renderKbList();const el=document.getElementById('kbList');if(el)el.scrollTop=0;}
function renderKbList(){
  const el=document.getElementById('kbList');if(!el||!kbData)return;const d=kbData;
  const pages=Math.max(1,Math.ceil(d.docs.length/KB_PER));
  if(kbPage>=pages)kbPage=pages-1; if(kbPage<0)kbPage=0;
  const slice=d.docs.slice(kbPage*KB_PER,kbPage*KB_PER+KB_PER);
  el.innerHTML=`<div class="lead" style="margin-bottom:10px">向量总数 <b class="num">${d.total}</b> · 在线上传 <b class="num">${d.uploaded}</b> 篇 · 内置库 <b class="num">${d.docs.length-d.uploaded}</b> 个<br>
      <span class="src">增=上传；查=预览切片；删=下方删除；改=同名文件重新上传即覆盖更新（内置库更新请重跑种子脚本）。</span></div>`+
    (d.docs.length?`<div class="card" style="padding:0;overflow:hidden"><table><thead><tr><th>名称</th><th>类型</th><th>切片</th><th>时间</th><th></th></tr></thead><tbody>`+slice.map(x=>{
        const badge=x.kind==='builtin'?'<span class="chip mid"><span class="dot"></span>内置</span>':'<span class="chip"><span class="dot"></span>上传</span>';
        return `<tr><td>${esc(x.name)}</td><td>${badge}</td><td class="num">${x.chunks}</td><td class="num">${fmtTs(x.ts)}</td>
          <td style="white-space:nowrap"><button class="btn sm secondary" data-act="kb-preview" data-tag="${esc(x.doc_tag)}">预览</button>
          <button class="btn sm secondary" data-act="kb-delete" data-tag="${esc(x.doc_tag)}" data-name="${esc(x.name)}" data-kind="${esc(x.kind)}">删除</button></td></tr>`;}).join('')+`</tbody></table>
      <div style="display:flex;align-items:center;gap:12px;padding:12px 16px;border-top:1px solid var(--line)">
      <button class="btn sm secondary" ${kbPage===0?'disabled':''} onclick="kbGo(-1)">‹ 上一页</button>
      <span class="src num">第 ${kbPage+1} / ${pages} 页 · 共 ${d.docs.length} 篇</span>
      <button class="btn sm secondary" ${kbPage>=pages-1?'disabled':''} onclick="kbGo(1)">下一页 ›</button></div></div>`
      :'<div class="src">暂无文档。</div>');
}
async function loadKb(){const el=document.getElementById('kbList');if(!el)return;el.innerHTML='<div class="sk" style="height:16px"></div>';
  try{kbData=await apiGet('/admin/kb');renderKbList();}
  catch(e){el.innerHTML='<div class="src">⚠ 加载失败（需管理员权限）</div>';}}
/* 任务2：大文件后台上传任务轮询（2s 间隔）：progress 条/完成/失败原因。
   返回 true=成功（继续后续文件/刷新列表）。状态查询偶发失败不终止轮询（下轮重试）。 */
async function pollKbTask(taskId,label,msg){
  const bar=p=>`<div style="margin:4px 0 2px;height:6px;border-radius:4px;background:var(--rail);overflow:hidden;max-width:320px"><div style="height:100%;width:${p}%;background:var(--accent);transition:width .4s"></div></div>`;
  for(let i=0;i<600;i++){  // 上限 20 分钟：真实兜底，正常由 done/error 出循环
    await new Promise(r=>setTimeout(r,2000));
    let st;try{st=await apiGet('/admin/kb/upload/status/'+encodeURIComponent(taskId));}
    catch(e){msg.textContent=label+' 状态查询失败，重试中…';continue;}
    if(!st){msg.textContent=label+' 任务不存在';return false;}
    if(st.status==='done'){msg.innerHTML=esc(label)+' → 入库 <b class="num">'+((st.result&&st.result.inserted)||st.total||0)+'</b> 片';return true;}
    if(st.status==='error'){msg.textContent=label+' 失败：'+(st.error||'未知原因');return false;}
    const total=st.total||0,prog=st.progress||0;
    const p=total?Math.min(100,Math.round(prog/total*100)):0;
    msg.innerHTML=esc(label)+' 向量化中 <span class="num">'+prog+' / '+(total||'…')+'</span> 片'+(total?bar(p):' …');
  }
  msg.textContent=label+' 超时（20 分钟未完成），请稍后在文档列表确认';return false;}
async function uploadKb(){const fs=document.getElementById('kbFile').files;if(!fs.length){toast('请选择文件');return;}const msg=document.getElementById('kbMsg');const btn=document.getElementById('kbUp');btn.disabled=true;
  let okAny=false;
  for(const f of fs){const label=f.name;msg.textContent='处理中：'+f.name+' …';
    if(!/\.(md|txt|pdf)$/i.test(f.name)){msg.textContent=f.name+' 不支持（仅 .md/.txt/.pdf）';continue;}
    let r=null,d=null,txt='';
    try{const b=await readB64(f);r=await fetch('/api/v1/medical/admin/kb/upload',{method:'POST',headers:auth(),body:JSON.stringify({name:f.name,data_b64:b})});
      /* 诊断3：先取响应文本再尝试解析 JSON——413 等非 JSON 体也能带上状态码+原因，
         失败提示统一为「失败：文件名（状态码 原因）」，不再吞原因 */
      try{txt=await r.text();d=JSON.parse(txt);}catch(_){/* 非 JSON 响应：保留 txt 供提示 */}
      if(!r.ok){msg.textContent='失败：'+f.name+'（'+r.status+' '+((d&&d.detail)||String(txt||'').trim().slice(0,80))+'）';continue;}
      /* 任务2：大文件返回 task_id → 轮询状态直至完成；小文件同步返回结果（现状不变） */
      if(d.mode==='async'&&d.task_id){if(await pollKbTask(d.task_id,f.name+'（大文件，后台向量化）',msg))okAny=true;}
      else{msg.textContent=`${f.name} → 入库 ${d.inserted} 片`;okAny=true;}}
    catch(e){msg.textContent='失败：'+f.name+(r?('（'+r.status+'）'):'（网络异常：'+e.message+'）');}}
  document.getElementById('kbFile').value='';btn.disabled=false;if(okAny)loadKb();}
async function previewKb(tag){
  document.getElementById('mTitle').textContent='切片预览 · '+tag;
  document.getElementById('mBody').innerHTML='<div class="sk" style="height:16px"></div>';
  openModal();document.getElementById('mBody').querySelector('input,button,textarea,[tabindex]')?.focus();
  try{const d=await apiGet('/admin/kb/'+encodeURIComponent(tag)+'/preview');
    document.getElementById('mBody').innerHTML=(d.chunks||[]).length
      ? d.chunks.map((c,i)=>`<h4 style="margin-top:${i?12:0}px">切片 ${i+1} · ${esc(c.source)}</h4><div class="txt" style="white-space:pre-wrap">${esc(c.content)}…</div>`).join('')
      : '<div class="src">该文档暂无可预览的切片（或已被删除）。</div>';
  }catch(e){document.getElementById('mBody').innerHTML='<div class="src">⚠ 预览失败</div>';}}
async function deleteKb(tag,name,kind){
  /* 轮 A1：内置库可删除（后端已放开），确认文案区分——误删可重跑种子脚本恢复 */
  const isBuiltin=kind==='builtin';
  confirmDanger('删除知识库文档',
    isBuiltin?`将删除内置库「${esc(name||tag)}」的全部向量；内置库可删除，重跑种子脚本可恢复。此操作记入审计。`
             :`将删除「${esc(name||tag)}」的全部向量，检索中不再出现；此操作记入审计。`,'确认删除',async()=>{
    try{const r=await fetch('/api/v1/medical/admin/kb/'+encodeURIComponent(tag),{method:'DELETE',headers:auth()});
      if(r.status===401){logout();return;}
      if(!r.ok){const d=await r.json();toast('失败：'+(d.detail||r.status));return;}
      toast('已删除');closeModal();loadKb();
    }catch(e){toast('删除失败：'+e.message);}});
}

/* ---- admin：队列清理 / 数据面板 ---- */
function openChangePwd(){
  document.getElementById('mTitle').textContent='修改密码';
  document.getElementById('mBody').innerHTML=`
    <label class="src" for="cpOld">原密码</label><input class="inp" id="cpOld" type="password" style="margin:4px 0 10px">
    <label class="src" for="cpNew">新密码（≥8 位）</label><input class="inp" id="cpNew" type="password" autocomplete="new-password" style="margin:4px 0 10px">
    <label class="src" for="cpNew2">确认新密码</label><input class="inp" id="cpNew2" type="password" autocomplete="new-password" style="margin:4px 0 10px">
    <div style="display:flex;gap:8px;justify-content:flex-end"><button class="btn ghost sm" onclick="closeModal()">取消</button>
    <button class="btn primary sm" id="cpGo">确认修改</button></div>`;
  openModal();document.getElementById('mBody').querySelector('input,button,textarea,[tabindex]')?.focus();
  document.getElementById('cpGo').onclick=async()=>{
    const o=document.getElementById('cpOld').value,n=document.getElementById('cpNew').value,n2=document.getElementById('cpNew2').value;
    if(n!==n2){toast('两次输入的新密码不一致');return;}
    try{const r=await fetch('/api/v1/auth/change-password',{method:'POST',headers:auth(),body:JSON.stringify({old_password:o,new_password:n})});
      if(r.status===401){logout();return;}
      if(!r.ok){const d=await r.json();toast('失败：'+(d.detail||r.status));return;}
      toast('密码已修改，请重新登录');closeModal();setTimeout(logout,600);
    }catch(e){toast('操作失败：'+e.message);}};
}
let resetTarget=null;
function openResetPwd(name){
  resetTarget=name;
  document.getElementById('mTitle').textContent='重置密码 · '+name;
  document.getElementById('mBody').innerHTML=`
    <label class="src" for="rpNew">新密码（≥8 位）</label><input class="inp" id="rpNew" type="password" autocomplete="new-password" style="margin:4px 0 10px">
    <div style="display:flex;gap:8px;justify-content:flex-end"><button class="btn ghost sm" onclick="closeModal()">取消</button>
    <button class="btn primary sm" id="rpGo">确认重置</button></div>`;
  openModal();document.getElementById('mBody').querySelector('input,button,textarea,[tabindex]')?.focus();
  document.getElementById('rpGo').onclick=async()=>{
    const n=document.getElementById('rpNew').value;
    try{const r=await fetch('/api/v1/auth/reset-password',{method:'POST',headers:auth(),body:JSON.stringify({username:resetTarget,new_password:n})});
      if(r.status===401){logout();return;}
      if(!r.ok){const d=await r.json();toast('失败：'+(d.detail||r.status));return;}
      toast('已重置 '+resetTarget+' 的密码');closeModal();
    }catch(e){toast('操作失败：'+e.message);}};
}
async function purgePending(){
  let n=0;try{const r=await fetch('/api/v1/medical/review/pending',{headers:auth()});
    if(r.status===401){logout();return;} n=((await r.json()).pending||[]).length;}catch(e){toast('加载失败：'+e.message);return;}
  document.getElementById('mTitle').textContent='清空待核对 · 高危操作';
  document.getElementById('mBody').innerHTML=`
    <div class="txt">将删除全部 <b class="num">${n}</b> 条「待核对」项；已签发/驳回的历史保留。<br>
      <span class="src">此操作记入审计。为防误触，请输入「清空」二字后确认。</span></div>
    <input class="inp" id="purgeConfirm" aria-label="输入清空确认" spellcheck="false" style="margin-top:10px" placeholder="输入：清空" autocomplete="off">
    <div style="margin-top:12px;display:flex;gap:8px;justify-content:flex-end">
      <button class="btn secondary sm" id="purgeCancel">取消</button>
      <button class="btn danger sm" id="purgeGo" disabled>确认清空</button></div>`;
  openModal();
  const inp=document.getElementById('purgeConfirm'),go=document.getElementById('purgeGo'),cancel=document.getElementById('purgeCancel');
  cancel.onclick=()=>closeModal();  // 修复：此前取消按钮未绑事件（死按钮）
  cancel.focus();  // 默认焦点在取消，回车不会执行
  inp.addEventListener('input',()=>{go.disabled=inp.value.trim()!=='清空';});
  inp.addEventListener('keydown',e=>{if(e.key==='Enter')e.preventDefault();});  // 回车不触发确认
  go.onclick=async()=>{try{
      const r=await fetch('/api/v1/medical/admin/review/purge-pending',{method:'POST',headers:auth(),body:JSON.stringify({confirm_text:'清空'})});
      if(r.status===401){logout();return;}
      if(!r.ok){const d=await r.json();toast('失败：'+(d.detail||r.status));return;}
      const d=await r.json();toast(`已清空 ${d.purged} 条待核对`);closeModal();loadReview();
    }catch(e){toast('操作失败：'+e.message);}};
}
/* ---- 任务A：审计流人话化 / 翻页 / CSV 导出 ----
   auditOffset：当前页偏移（每页 50，数据源 /admin/data?audit_offset=，负数后端按 0 处理）；
   auditItemsCache：最近一次加载的本页条目（含原始 event/action/actor/payload），供 CSV 导出与翻过头回退。 */
const AUDIT_PAGE_SIZE = 50;
let auditOffset = 0;
let auditItemsCache = [];
/* 任务4：审计流区块默认收起（标题「审计流（N 条）▸」点击展开）——右侧「新增用户」等
   操作卡片无需滚动即可见；展开后列表限高滚动（max-height+overflow）。
   auditPanelOpen 为模块级状态：翻页/搜索触发 loadData 重渲染后保持已展开状态不回缩。 */
let auditPanelOpen = false;
function toggleAuditPanel(){
  auditPanelOpen=!auditPanelOpen;
  const b=document.getElementById('auditPanelBody');if(b)b.style.display=auditPanelOpen?'':'none';
  const a=document.getElementById('auditArrow');if(a)a.textContent=auditPanelOpen?'▾':'▸';
  const h=document.getElementById('auditHead');if(h)h.setAttribute('aria-expanded',auditPanelOpen?'true':'false');
}
/* 审计流翻页：上一页/下一页（每页 50）。翻过头（新事件插入导致空页）自动回退上一页。 */
async function auditPage(delta){
  const prev=auditOffset;
  auditOffset=Math.max(0,auditOffset+delta*AUDIT_PAGE_SIZE);
  await loadData();
  if(delta>0&&!auditItemsCache.length){auditOffset=prev;await loadData();}
}
/* 导出审计 CSV：前端生成（导出本页、应用搜索框过滤后）条目，后端不新增导出端点。
   BOM 头保证 Excel 中文不乱码；CSV 引号转义防逗号/换行注入。 */
function exportAuditCsv(){
  const q=(document.getElementById('auditSearch')?.value||'').trim().toLowerCase();
  const rows=auditItemsCache.filter(e=>{
    if(!q)return true;
    const hay=((e.event||'')+' '+(e.action||'')+' '+(e.actor||'')+' '+humanizeAudit(e)).toLowerCase();
    return hay.includes(q);});
  if(!rows.length){toast('当前无可导出的审计条目');return;}
  const cell=v=>'"'+String(v??'').replace(/"/g,'""')+'"';
  const head=['时间','事件','动作','描述','操作者','payload'];
  const lines=[head.map(cell).join(',')].concat(rows.map(e=>[
    e.ts||'',e.event||'',e.action||'',humanizeAudit(e),e.actor||'',JSON.stringify(e.payload||{})]
    .map(cell).join(',')));
  const blob=new Blob(['\ufeff'+lines.join('\r\n')],{type:'text/csv;charset=utf-8'});
  const a=document.createElement('a');a.href=URL.createObjectURL(blob);
  a.download='audit_'+new Date().toISOString().slice(0,10)+'.csv';
  document.body.appendChild(a);a.click();a.remove();
  setTimeout(()=>URL.revokeObjectURL(a.href),1000);
  toast('已导出 '+rows.length+' 条审计');
}
/* ---- 任务5 + 本批次：admin 应用日志「健康卡」（GET /admin/logs，只读）----
   日志行可能含用户输入（问题文本/用户名等），逐行 esc() 后再入 <pre> 防注入；
   等宽字体 + 自动滚底（最新在底部）；行数输入 1~500 服务端亦有上限。
   本批次：元数据行（路径/大小/总行数）+ ERROR/WARNING 行高亮（红/黄）。 */
async function loadAppLogs(){
  const el=document.getElementById('appLogView');if(!el)return;
  const inp=document.getElementById('appLogN');
  let n=parseInt((inp&&inp.value)||'200',10);if(!Number.isFinite(n))n=200;n=Math.max(1,Math.min(n,500));
  if(inp)inp.value=String(n);
  el.textContent='加载中…';
  const meta=document.getElementById('appLogMeta');if(meta)meta.textContent='加载中…';
  try{const r=await fetch('/api/v1/medical/admin/logs?lines='+n,{headers:auth()});
    if(r.status===401){logout();return;}
    const d=await r.json();
    if(!r.ok){el.textContent='⚠ 加载失败：'+(d.detail||r.status);if(meta)meta.textContent='';return;}
    el.innerHTML=hlLogLevels((d.lines||[]).map(l=>esc(l)).join('\n')||'（日志为空）');  /* 任务4：等级行高亮 */
    el.scrollTop=el.scrollHeight;  /* tail 视图自动滚底 */
    const note=document.getElementById('appLogNote');if(note)note.textContent=d.note||'';
    /* 任务4：健康卡元数据行（后端 {path,size_bytes,exists,total_lines}） */
    if(meta){
      if(d.exists===false)meta.textContent='日志文件不存在：'+(d.path||'（未知路径）')+'（LOG_TO_FILE 未开启或尚未产生写入）';
      else meta.textContent='文件：'+(d.path||'—')+' · 大小：'+fmtLogSize(d.size_bytes)
        +' · 总行数：'+(d.total_lines??'—')+' · 本次采样：'+((d.lines||[]).length)+' 行（ERROR 红 / WARNING 黄 高亮）';
    }
  }catch(e){el.textContent='⚠ 加载失败：'+e.message;if(meta)meta.textContent='';}
}
/* 任务4：字节数 → KB/MB 人话（<1KB 显示 B） */
function fmtLogSize(b){b=Number(b)||0;
  if(b<1024)return b+' B';
  if(b<1024*1024)return (b/1024).toFixed(1)+' KB';
  return (b/1024/1024).toFixed(2)+' MB';}
/* 任务4：日志等级行高亮（入参已逐行 esc 转义，此处仅按行包 span，不引入未转义内容） */
function hlLogLevels(text){return String(text).split('\n').map(l=>{
  if(l.indexOf('ERROR')>=0)return '<span class="log-err">'+l+'</span>';
  if(l.indexOf('WARNING')>=0)return '<span class="log-warn">'+l+'</span>';
  return l;}).join('\n');}
/* ---- 任务3：admin「留痕模式」运行时开关卡（POST /admin/config-toggle 写 runtime_flags.json，即时生效）---- */
let _flagState=null;  /* 最近一次 admin_data 下发的运行时开关状态 {qc_auto_sign_full: bool} */
async function toggleFullFlag(){
  const cur=!!(_flagState&&_flagState.qc_auto_sign_full);
  confirmDanger(cur?'关闭留痕模式':'开启留痕模式',
    cur?'关闭后仅高风险结论进入双人核对队列，等待质控员/管理员人工签发（旧语义）。确认关闭？'
       :'开启后全部 AI 问答/阅片/会诊响应无条件入审核队列并自动签发留痕（AI·留痕模式(自动)）；低置信/高危项由提交医生知情确认。确认开启？',
    '确认切换',async()=>{
    try{const d=await apiPost('/admin/config-toggle',{key:'qc_auto_sign_full',value:!cur});
      if(d&&d.ok){toast('留痕模式已'+(d.value?'开启':'关闭')+'（runtime_flags 已更新，即时生效）');loadData();loadConfig();}
      else toast('操作失败：'+(d.detail||'未知错误'));
    }catch(e){toast('操作失败：'+e.message);}});
}
async function loadData(){
  const b=document.getElementById('dataBody');
  b.innerHTML='<div class="card"><div class="sk" style="height:18px;width:30%;margin-bottom:14px"></div><div class="sk"></div></div>';
  let d;try{const r=await fetch('/api/v1/medical/admin/data?audit_offset='+auditOffset,{headers:auth()});
    if(r.status===401){logout();return;} if(!r.ok){b.innerHTML='<div class="card">⚠ 加载失败</div>';return;} d=await r.json();}
  catch(e){b.innerHTML='<div class="card">⚠ '+e.message+'</div>';return;}
  const stats=[['待核对',d.review.pending,'项'],['已签发',d.review.approved,'项'],['已驳回',d.review.rejected,'项'],
      ['知识库向量',fmtCnt(d.kb_docs),'条'],['审计事件',d.events_total,'条'],['运行',fmtUp(d.uptime_s||0),(d.device==='cuda'?'GPU':'CPU')]]
    .map(([k,v,u])=>`<div class="stat"><span class="k">${k}</span><span class="v num">${v}${u?` <small>${u}</small>`:''}</span></div>`).join('');
  /* 任务3：留痕模式开关卡状态（runtime_flags 运行时值） */
  _flagState=d.flags||{};
  const fullOn=!!_flagState.qc_auto_sign_full;
  const users=d.users.map(u=>{
      const del=(u.username===document.getElementById('who').textContent)?'':
        `<button class="btn sm danger" onclick="deleteUser('${esc(u.username)}')">删除</button>`;
      const rp=`<button class="btn sm secondary" onclick="openResetPwd('${esc(u.username)}')">重置密码</button>`;
      return `<tr><td>${esc(u.username)}</td><td><span class="role">${esc(u.role)}</span></td><td>${esc(u.dept||'—')}</td><td style="white-space:nowrap">${rp}${del}</td></tr>`;}).join('');
  const auditItems=d.audit_recent||[];
  auditItemsCache=auditItems;  // 任务A：缓存本页条目供 CSV 导出与翻页回退判定
  const auditList=auditItems.map((e,i)=>{
    const cat=(e.event||'').split('.')[0]||'';
    const cls={auth:'chip ok',admin:'chip mid',review:'chip warn',kb:'chip',pharm:'chip mid',qc:'chip',lit:'chip',drug:'chip',case:'chip',imaging:'chip',mdt:'chip',precheck:'chip'}[cat]||'chip';
    const payloadJson=esc(JSON.stringify(e.payload||{},null,2));
    const desc=humanizeAudit(e);  // 任务A：人话化描述（纯文本，esc 后入 HTML）
    return `<li class="audit-row" data-cat="${esc(cat)}" data-q="${esc((e.event||'')+' '+(e.action||'')+' '+(e.actor||'')+' '+desc)}">
      <details><summary>
        <span class="${cls}">${esc(e.event||'')}</span>
        <span class="e">${esc(desc)}</span>
        <span class="t">${fmtTs(e.ts)}</span>
        <span class="a">${esc(e.actor||'')}</span>
        <span class="src">▾ 点开查看原始 JSON</span>
      </summary>
      <pre style="background:var(--rail);border:1px solid var(--line);border-radius:6px;padding:10px;font-size:11.5px;margin:6px 0 0;overflow-x:auto;white-space:pre-wrap">${payloadJson}</pre>
    </details></li>`;}).join('');
  const audit=auditList||'<li><span class="a">暂无记录</span></li>';
  /* 基础设施运维卡片（数据源 /admin/data 的 infra 只读状态）：milvus/pg 状态 chip +
     对应重启命令文本 +「复制命令」按钮。安全红线：只展示/复制，系统绝不代为执行。 */
  const infra=d.infra||{};
  const mv=infra.milvus||{}, pg=infra.pg||{};
  const infraRows=[
    {name:'Milvus 知识库', ok:mv.connected===true, detail:mv.uri||'', cmd:'docker compose restart milvus'},
    {name:'PostgreSQL 镜像', ok:pg.ok===true, detail:'审计/用户/复核队列双写镜像', cmd:'docker compose restart postgres'},
  ].map(x=>`<div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:4px 0">
      <span class="chip ${x.ok?'ok':'bad'}"><span class="dot"></span>${x.ok?'正常':'不可达'}</span>
      <b style="font-size:13px">${esc(x.name)}</b>
      <span class="src">${esc(x.detail)}</span>
      <span class="src num" style="margin-left:auto">${esc(x.cmd)}</span>
      <button class="btn sm secondary" data-act="infra-copy" data-cmd="${esc(x.cmd)}">复制命令</button>
    </div>`).join('');
  b.innerHTML=`<div class="stats">${stats}</div>
    <div class="dash">
      <!-- 任务3 排版：masonry 式（.dash columns:2 自动均衡）——审计流展开后与留痕模式卡
           紧邻（DOM 顺序即列内顺序），两列高度由浏览器均衡，不再出现被另一列撑出的
           大片空白；窄屏回落单列。 -->
      <div class="panel"><h3 id="auditHead" role="button" tabindex="0" aria-expanded="${auditPanelOpen?'true':'false'}" style="cursor:pointer;user-select:none" onclick="toggleAuditPanel()" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();toggleAuditPanel();}" title="点击展开/收起审计流">审计流（共 ${esc(String(d.events_total??auditItems.length))} 条）<span id="auditArrow">${auditPanelOpen?'▾':'▸'}</span> <span style="font-weight:400">第 ${Math.floor(auditOffset/AUDIT_PAGE_SIZE)+1} 页 · 每页 ${AUDIT_PAGE_SIZE} 条 · 点开看原始 JSON</span></h3>
        <div id="auditPanelBody" style="display:${auditPanelOpen?'':'none'}">
        <div style="padding:8px 16px 4px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
          <input class="inp" id="auditSearch" aria-label="搜索审计事件" placeholder="搜索 事件/操作/用户（如 drug / purge_pending / doctor01，本页内过滤）" style="flex:1;min-width:180px" oninput="filterAudit(this.value)">
          <button class="btn sm secondary" ${auditOffset>0?'':'disabled'} onclick="auditPage(-1)">上一页</button>
          <button class="btn sm secondary" ${auditItems.length>=AUDIT_PAGE_SIZE?'':'disabled'} onclick="auditPage(1)">下一页</button>
          <button class="btn sm secondary" onclick="exportAuditCsv()">导出审计 CSV</button>
        </div>
        <!-- 任务4：展开后限高滚动（max-height+overflow），不再把整页撑长 -->
        <ul class="feed" id="auditList" style="max-height:420px;overflow:auto">${audit}</ul>
        </div></div>
      <!-- 任务3：留痕模式卡紧邻审计流（masonry 列内顺序 = DOM 顺序） -->
      <div class="panel"><h3>留痕模式（全交互留痕 · 运行时开关）</h3>
      <div style="display:flex;gap:10px;align-items:center;padding:8px 16px 14px;flex-wrap:wrap">
        <span class="chip ${fullOn?'ok':'mid'}"><span class="dot"></span>${fullOn?'已开启（全部响应自动留痕签发）':'已关闭（仅高危入队人工双控）'}</span>
        <span class="src num" style="white-space:nowrap">状态来源：runtime_flags（qc_auto_sign_full=${fullOn}）</span>
        <span class="src" style="flex:1;min-width:260px">开启后全部 AI 问答/阅片/会诊响应无条件写入审核队列并自动签发（AI·留痕模式(自动)），低置信/高危项由提交医生知情确认；关闭恢复旧语义。写入 data/runtime_flags.json，切换即时生效、无需重启。</span>
        <button class="btn sm ${fullOn?'danger':'primary'}" id="flagFullBtn">${fullOn?'关闭留痕模式':'开启留痕模式'}</button>
      </div></div>
      <div class="panel"><h3>新增用户</h3>
          <div class="qgrid" style="padding:12px 16px">
            <label>用户名（3-32 位字母/数字/_/-）<input class="inp" id="nuName" autocomplete="off" spellcheck="false"></label>
            <label>角色<select class="inp" id="nuRole"><option value="doctor">doctor</option><option value="pharmacist">pharmacist</option><option value="admin">admin</option><option value="qc">qc（质控员）</option></select></label>
            <label style="grid-column:1/-1">科室（必选，质控签名/科室绑定）<select class="inp" id="nuDept"><option value="">加载中…</option></select></label>
            <label style="grid-column:1/-1">密码（≥8 位）<input class="inp" id="nuPass" type="password" autocomplete="new-password"></label>
          </div>
          <div style="padding:0 16px 12px"><button class="btn primary sm" onclick="createUser()">创建用户</button></div>
        </div>
        <div class="panel"><h3>用户（JSON 权威源，PG 双写镜像）</h3><table><thead><tr><th>用户名</th><th>角色</th><th>科室</th><th></th></tr></thead><tbody>${users}</tbody></table>
          <div class="src" style="padding:10px 16px">PG 镜像行数：audit=${esc(String((d.pg_counts||{}).audit_log??'—'))} · users=${esc(String((d.pg_counts||{}).users??'—'))} · review=${esc(String((d.pg_counts||{}).review_queue??'—'))}</div></div>
        <div class="panel"><h3>科室管理（质控科室字典 · 在用科室不可删除）</h3>
          <ul class="feed" id="deptList"><li><span class="a">加载中…</span></li></ul>
          <div style="display:flex;gap:8px;padding:10px 16px 14px">
            <input class="inp" id="deptNew" placeholder="新科室名称（如：康复科）" aria-label="新科室名称" style="flex:1" autocomplete="off"
              onkeydown="if(event.key==='Enter'){event.preventDefault();addDept();}">
            <button class="btn primary sm" id="deptAddBtn">新增科室</button>
          </div>
        </div>
        <!-- 阶段4 任务1 语义变更（注明）：admin 数据面板「药品字典」卡已删除（用户拍板：
             药品字典管理 UI 只在药剂科审核中心 tab 显示，管理面板不再重复承载药学数据编辑）；
             renderDrugDictEditor 渲染函数保留（pharmacist tab 专用），POST /admin/drug/dict
             后端 pharmacist+admin 权限不变（admin 兜底）。原 <div class="panel"> 药品字典卡
             与 id=drugDictPanel 容器一并移除。 -->
    </div>
    <div class="panel" style="margin-top:16px"><h3>基础设施（只读状态 · 重启命令仅供复制，系统不代执行）</h3><div id="infraRows" style="padding:8px 16px 12px">${infraRows}</div></div>
    <!-- 任务5 + 本批次：应用日志「健康卡」（GET /admin/logs 只读；行可能含用户输入，逐行 esc 防注入） -->
    <div class="panel" style="margin-top:16px"><h3>应用日志健康卡（logs/app.log · 只读 · 尾部最多 500 行）</h3>
      <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:8px 16px 4px">
        <label class="src" for="appLogN">尾部行数</label>
        <input class="inp num" id="appLogN" type="number" min="1" max="500" value="200" style="max-width:110px" aria-label="应用日志行数"
          onkeydown="if(event.key==='Enter'){event.preventDefault();loadAppLogs();}">
        <button class="btn sm secondary" onclick="loadAppLogs()">刷新</button>
        <span class="src" id="appLogNote"></span>
      </div>
      <!-- 任务4：健康卡元数据行（文件绝对路径 / 大小人话化 / 总行数 / 采样行数） -->
      <div class="src num" id="appLogMeta" style="padding:0 16px 6px">加载中…</div>
      <pre id="appLogView" style="margin:8px 16px 14px;padding:10px;background:var(--rail);border:1px solid var(--line);border-radius:6px;font-size:12px;line-height:1.55;max-height:340px;overflow:auto;white-space:pre-wrap;word-break:break-all;font-family:ui-monospace,SFMono-Regular,Consolas,'Courier New',monospace">加载中…</pre></div>
    <div class="panel" style="margin-top:16px"><h3>模型配置（对话 / 视觉 · 双格式）</h3><div id="llmPanel"><div class="src" style="padding:10px 16px">加载中…</div></div></div>`;
  /* 基础设施卡片「复制命令」绑定：仅复制命令文本（copyText），绝不提供/触发任何执行 */
  document.querySelectorAll('#infraRows [data-act="infra-copy"]').forEach(btn=>{
    btn.onclick=()=>copyText(btn.dataset.cmd);});
  const ffBtn=document.getElementById('flagFullBtn');  /* 任务3：留痕模式开关绑定 */
  if(ffBtn)ffBtn.onclick=toggleFullFlag;
  loadAppLogs();  /* 任务5：应用日志 tail 卡片随面板加载 */
  /* 科室管理卡片 + 新增用户科室下拉（数据源 GET /admin/departments） */
  document.getElementById('deptAddBtn').onclick=addDept;
  loadDeptPanel();
  /* 阶段4 任务1：admin 药品字典卡渲染调用已移除（loadDrugDictPanel 随卡片删除，
     字典管理 UI 只在药剂科审核中心 tab；数据面板少一次 /drug/dict 请求）。 */
  apiGet('/admin/llm/providers').then(renderLlm).catch(()=>{const el=document.getElementById('llmPanel');
    if(el)el.innerHTML='<div class="src" style="padding:10px 16px">⚠ 模型配置加载失败</div>';});
}

/* ---- admin 科室管理：列表 + 新增 + 删除（字典为账号 dept/质控科室权威取值；在用科室后端拒绝删除→400）---- */
async function loadDeptPanel(){
  const el=document.getElementById('deptList');if(!el)return;
  try{const d=await apiGet('/admin/departments');
    el.innerHTML=(d.list||[]).length
      ?(d.list||[]).map(n=>`<li><span class="e">${esc(n)}</span>
          <span class="a"><button class="btn sm secondary" data-act="dept-del" data-name="${esc(n)}">删除</button></span></li>`).join('')
      :'<li><span class="a">暂无科室</span></li>';
    refreshDeptOptions(d.list||[]);
  }catch(e){el.innerHTML='<li><span class="a">⚠ 科室加载失败（需管理员权限）</span></li>';}
}
async function refreshDeptOptions(list){
  const sel=document.getElementById('nuDept');if(!sel)return;
  const prev=sel.value;  // 重渲染后尽量保留已选科室
  try{list=list||((await apiGet('/admin/departments')).list||[]);
    sel.innerHTML='<option value="">请选择科室</option>'+list.map(n=>`<option value="${esc(n)}">${esc(n)}</option>`).join('');
    if(prev&&list.includes(prev))sel.value=prev;
  }catch(e){sel.innerHTML='<option value="">科室加载失败</option>';}
}
async function addDept(){
  const inp=document.getElementById('deptNew');const name=(inp.value||'').trim();
  if(!name){toast('请输入科室名称');return;}
  const btn=document.getElementById('deptAddBtn');btn.disabled=true;
  try{const d=await apiPost('/admin/departments',{name});
    if(d&&d.ok){toast('已新增科室 '+name);inp.value='';loadDeptPanel();}
    else toast('失败：'+(d.detail||'未知错误'));  /* 400=科室已存在 */
  }catch(e){toast('新增失败：'+e.message);}
  finally{btn.disabled=false;}
}
async function deleteDept(name){
  confirmDanger('删除科室 · '+name,
    `将删除科室「${esc(name)}」；仍有账号绑定该科室时后端会拒绝（400）。此操作记入审计。`,'确认删除',async()=>{
    try{const r=await fetch('/api/v1/medical/admin/departments/'+encodeURIComponent(name),{method:'DELETE',headers:auth()});
      if(r.status===401){logout();return;}
      const d=await r.json();
      if(!r.ok){toast('失败：'+(d.detail||r.status));return;}  /* 在用保护：后端 400 提示先调整账号 */
      toast('已删除科室 '+name);closeModal();loadDeptPanel();
    }catch(e){toast('删除失败：'+e.message);}});
}
/* 科室删除事件委托：科室名来自字典/用户输入（可含引号等字符），禁用内联 onclick 拼接（与 KB 同纪律） */
if(!window.__deptDelegate){window.__deptDelegate=true;document.addEventListener('click',e=>{
  const b=e.target.closest('button[data-act="dept-del"]');if(!b)return;
  deleteDept(b.dataset.name);});}

/* ---- 问题3：药品字典/相互作用规则编辑器（共享渲染函数）----
   阶段4 任务1 语义变更：admin 数据面板字典卡已移除，renderDrugDictEditor/
   renderDrugRulesEditor 仅由 pharmacist 审核中心 tab 调用（原「admin 数据面板字典卡
   复用同一渲染函数」的挂载点 loadDrugDictPanel 已删除）；权限后端同源：
   POST /admin/drug/dict|rules 均为 pharmacist+admin（admin 兜底不变）。
   数据源 GET /drug/dict（全量字典+规则+stats+免责声明）。
   安全纪律：全部用户输入 esc() 转义；编辑/删除按 data 属性 + 事件委托定位
   （禁内联 onclick 拼接药名，与科室删除同纪律）；编辑态锁定定位键（规范名/药对）——
   重命名/调药对不支持（需删除后新增），规避后端按 name/规范药对定位的 update 语义歧义。 */
let drugDictCache={drugs:[],rules:[],stats:null};
let drugDictEditName=null;   /* 药品编辑中：null=新增模式；否则被编辑条目规范名（定位键锁定） */
let drugRuleEditPair=null;   /* 规则编辑中：null=新增模式；否则 '药a\u0001药b' 规范药对键 */
let drugDictEditorRoot=null; /* 当前编辑器挂载容器（pharmacist=reviewBody），保存/删除后原地重渲染 */

function _csv(v){return String(v||'').split(/[,，、]/).map(s=>s.trim()).filter(Boolean);}

async function renderDrugDictEditor(el){
  if(!el)return;
  drugDictEditorRoot=el;
  el.innerHTML='<div class="src" style="padding:10px 16px">加载中…</div>';
  let d;try{d=await apiGet('/drug/dict');}catch(e){el.innerHTML='<div class="src" style="padding:10px 16px">⚠ 药品字典加载失败：'+esc(e.message||'')+'</div>';return;}
  drugDictCache={drugs:d.drugs||[],rules:d.rules||[],stats:d.stats||{}};
  drugDictEditName=null;
  const st=d.stats||{};
  const cats=Object.entries(st.categories||{}).sort((a,b)=>b[1]-a[1]);
  el.innerHTML=`<div class="src" style="padding:8px 16px 0">⚠ ${esc(d.disclaimer||DRUG_DISCLAIMER)}</div>
    <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;padding:8px 16px 4px">
      <span class="chip ok"><span class="dot"></span>药品 ${esc(String(st.drugs??'—'))} 种</span>
      <span class="chip warn"><span class="dot"></span>规则 ${esc(String(st.rules??'—'))} 条（高危 ${esc(String(st.high??'—'))} / 中危 ${esc(String(st.mid??'—'))}）</span>
      ${cats.length?'<span class="src">分类分布：</span>'+cats.map(([k,v])=>`<span class="chip mid">${esc(k)} ${esc(String(v))}</span>`).join(' '):''}
    </div>
    <div class="qgrid" style="padding:4px 16px" id="ddForm">
      <label>规范名（唯一键，编辑态锁定）<input class="inp" id="ddF_name" autocomplete="off" spellcheck="false" placeholder="如：华法林"></label>
      <label>药理类别<input class="inp" id="ddF_cat" autocomplete="off" spellcheck="false" placeholder="如：抗凝抗栓"></label>
      <label>处方等级<input class="inp" id="ddF_level" autocomplete="off" spellcheck="false" placeholder="处方药 / OTC"></label>
      <label>别名（逗号分隔）<input class="inp" id="ddF_alias" autocomplete="off" spellcheck="false" placeholder="法华林, warfarin"></label>
      <label style="grid-column:1/-1">商品名（逗号分隔）<input class="inp" id="ddF_brand" autocomplete="off" spellcheck="false" placeholder="可密达"></label>
    </div>
    <div style="display:flex;gap:8px;align-items:center;padding:0 16px 8px;flex-wrap:wrap">
      <button class="btn primary sm" id="ddSaveBtn" onclick="saveDrugDictItem()">新增药品</button>
      <button class="btn ghost sm" id="ddResetBtn" onclick="resetDrugDictForm()">重置</button>
      <span class="src" id="ddFormHint"></span>
    </div>
    <div style="display:flex;gap:8px;padding:4px 16px 10px;align-items:center;flex-wrap:wrap">
      <input class="inp" id="ddSearch" placeholder="搜索药品名/别名/商品名" aria-label="搜索药品字典" style="flex:1;min-width:160px" autocomplete="off" oninput="renderDrugDictList()">
      <select class="inp" id="ddCat" aria-label="分类过滤" style="max-width:160px" onchange="renderDrugDictList()">
        <option value="">全部分类</option>${cats.map(([k])=>`<option value="${esc(k)}">${esc(k)}</option>`).join('')}</select>
    </div>
    <ul class="feed" id="ddList" style="max-height:320px;overflow:auto"></ul>`;
  renderDrugDictList();
}
function renderDrugDictList(){  /* 问题3：药品列表（搜索+分类过滤，前端过滤不出网） */
  const ul=document.getElementById('ddList');if(!ul)return;
  const s=String((document.getElementById('ddSearch')||{}).value||'').trim().toLowerCase();
  const cat=String((document.getElementById('ddCat')||{}).value||'');
  const hits=(drugDictCache.drugs||[]).filter(d=>{
    if(cat&&(d.category||'其他')!==cat)return false;
    if(!s)return true;
    const hay=[d.name,d.category,...(d.aliases||[]),...(d.brand_names||[])].join(' ').toLowerCase();
    return hay.includes(s);});
  ul.innerHTML=hits.length?hits.map(d=>`<li><span class="e">${esc(d.name)}</span>
      <span class="src">${esc(d.category||'其他')}${d.level?' · '+esc(d.level):''}${(d.aliases||[]).length?' · 别名：'+esc((d.aliases||[]).join('、')):''}${(d.brand_names||[]).length?' · 商品名：'+esc((d.brand_names||[]).join('、')):''}</span>
      <span class="a" style="display:inline-flex;gap:6px">
        <button class="btn sm secondary" data-act="dd-edit" data-name="${esc(d.name)}">编辑</button>
        <button class="btn sm danger" data-act="dd-del" data-name="${esc(d.name)}">删除</button></span></li>`).join('')
    :'<li><span class="a">无匹配药品</span></li>';
}
function resetDrugDictForm(){
  drugDictEditName=null;
  ['ddF_name','ddF_cat','ddF_level','ddF_alias','ddF_brand'].forEach(id=>{
    const el=document.getElementById(id);if(el){el.value='';el.readOnly=false;}});
  const b=document.getElementById('ddSaveBtn');if(b)b.textContent='新增药品';
  const h=document.getElementById('ddFormHint');if(h)h.textContent='';}
function startDrugDictEdit(name){  /* 问题3：编辑=回填表单并锁定规范名（定位键） */
  const d=(drugDictCache.drugs||[]).find(x=>x.name===name);if(!d)return;
  drugDictEditName=name;
  document.getElementById('ddF_name').value=d.name;document.getElementById('ddF_name').readOnly=true;
  document.getElementById('ddF_cat').value=d.category||'';
  document.getElementById('ddF_level').value=d.level||'';
  document.getElementById('ddF_alias').value=(d.aliases||[]).join(', ');
  document.getElementById('ddF_brand').value=(d.brand_names||[]).join(', ');
  const b=document.getElementById('ddSaveBtn');if(b)b.textContent='保存修改';
  const h=document.getElementById('ddFormHint');if(h)h.textContent='编辑中：'+esc(name)+'（规范名锁定；重命名请删除后新增）';}
async function saveDrugDictItem(){  /* 问题3：新增/更新 → POST /admin/drug/dict（pharmacist+admin） */
  const g=id=>document.getElementById(id)||{value:''};
  const name=String(g('ddF_name').value||'').trim();
  if(!name){toast('药品规范名必填');return;}
  const item={name,category:String(g('ddF_cat').value||'').trim()||'其他',
    level:String(g('ddF_level').value||'').trim()||'处方药',
    aliases:_csv(g('ddF_alias').value),brand_names:_csv(g('ddF_brand').value)};
  const action=drugDictEditName?'update':'add';
  const btn=document.getElementById('ddSaveBtn');if(btn)btn.disabled=true;
  try{const r=await apiPost('/admin/drug/dict',{action,item});
    if(r&&r.ok){toast((action==='update'?'已更新药品 ':'已新增药品 ')+name);resetDrugDictForm();
      renderDrugDictEditor(drugDictEditorRoot);}
    else toast('失败：'+((r&&r.detail)||'未知错误'));  /* 422=重名/不存在等业务校验 */
  }catch(e){toast('保存失败：'+e.message);}
  finally{if(btn)btn.disabled=false;}}
async function deleteDrugDictItem(name){  /* 问题3：删除（确认弹条；记入审计） */
  confirmDanger('删除药品 · '+name,
    `将把「${esc(name)}」从药品字典移除：开药建议/服务端硬校验随即不再识别该药（已开处方不受影响）。此操作记入审计。`,
    '确认删除',async()=>{
      try{const r=await apiPost('/admin/drug/dict',{action:'delete',item:{name}});
        if(r&&r.ok){toast('已删除药品 '+name);renderDrugDictEditor(drugDictEditorRoot);}
        else toast('失败：'+((r&&r.detail)||'未知错误'));
      }catch(e){toast('删除失败：'+e.message);}});}

async function renderDrugRulesEditor(el){  /* 问题3：相互作用规则管理（共享编辑器； pharmacist tab 与未来复用同源） */
  if(!el)return;
  drugDictEditorRoot=el;
  el.innerHTML='<div class="src" style="padding:10px 16px">加载中…</div>';
  let d;try{d=await apiGet('/drug/dict');}catch(e){el.innerHTML='<div class="src" style="padding:10px 16px">⚠ 规则加载失败：'+esc(e.message||'')+'</div>';return;}
  drugDictCache={drugs:d.drugs||[],rules:d.rules||[],stats:d.stats||{}};
  drugRuleEditPair=null;
  const st=d.stats||{},rules=d.rules||[];
  el.innerHTML=`<div class="src" style="padding:8px 16px 0">⚠ ${esc(d.disclaimer||DRUG_DISCLAIMER)}</div>
    <div style="display:flex;gap:8px;flex-wrap:wrap;align-items:center;padding:8px 16px 4px">
      <span class="chip warn"><span class="dot"></span>规则 ${esc(String(st.rules??rules.length))} 条（高危 ${esc(String(st.high??'—'))} / 中危 ${esc(String(st.mid??'—'))}）</span>
      <span class="chip ${(st.total>0&&st.reviewed===st.total)?'ok':'mid'}"><span class="dot"></span>审校 ${esc(String(st.reviewed??0))}/${esc(String(st.total??rules.length))} · 高危 ${esc(String(st.high_risk_reviewed??0))}/${esc(String(st.high_risk_total??st.high??'—'))}</span>
      <span class="src">高危=开药组合强制阻断（须填联用理由方可开立）；中危=开药时提示警示。</span>
    </div>
    <div class="qgrid" style="padding:4px 16px" id="drForm">
      <label>药品 A（编辑态锁定）<input class="inp" id="drF_a" autocomplete="off" spellcheck="false" placeholder="如：华法林"></label>
      <label>药品 B（编辑态锁定）<input class="inp" id="drF_b" autocomplete="off" spellcheck="false" placeholder="如：布洛芬"></label>
      <label>严重度<select class="inp" id="drF_sev"><option value="高危">高危</option><option value="中危">中危</option></select></label>
      <label>机制（可空）<input class="inp" id="drF_mech" autocomplete="off" spellcheck="false" placeholder="如：NSAID 抑制血小板并置换蛋白结合"></label>
      <label style="grid-column:1/-1">处置建议（可空）<input class="inp" id="drF_mgmt" autocomplete="off" spellcheck="false" placeholder="如：避免联用并监测 INR"></label>
    </div>
    <div style="display:flex;gap:8px;align-items:center;padding:0 16px 8px;flex-wrap:wrap">
      <button class="btn primary sm" id="drSaveBtn" onclick="saveDrugRule()">新增规则</button>
      <button class="btn ghost sm" onclick="resetDrugRuleForm()">重置</button>
      <span class="src" id="drFormHint"></span>
    </div>
    <div style="display:flex;gap:8px;padding:4px 16px 10px;align-items:center;flex-wrap:wrap">
      <select class="inp" id="drReview" aria-label="审校筛选" style="max-width:150px" onchange="renderDrugRulesList()">
        <option value="">全部</option><option value="unreviewed">未审校</option><option value="approved">已审校</option><option value="unreviewed_high">未审校高危</option></select>
      <button class="btn secondary sm" id="drReviewBtn" onclick="markRulesReviewed()">标记已审校</button>
      <span class="src">勾选规则后批量标记已审校（审校人以当前登录药师名落痕）</span>
    </div>
    <ul class="feed" id="drList" style="max-height:320px;overflow:auto"></ul>`;
  renderDrugRulesList();
}
function renderDrugRulesList(){  /* 评测行动项4：规则列表（审校徽章/筛选/高危默认排前；前端过滤不出网） */
  const ul=document.getElementById('drList');if(!ul)return;
  const revOf=r=>r.review_status==='approved';  /* 缺省视为未审校（读取时 computed） */
  const f=String((document.getElementById('drReview')||{}).value||'');
  const rules=drugDictCache.rules||[];
  let view=rules.map((r,i)=>({r,i}));  /* 携带原数组下标：编辑/删除 data-idx 仍定位原始条目 */
  view.sort((x,y)=>(y.r.severity==='高危')-(x.r.severity==='高危'));  /* 高危默认排前（稳定排序，同档保持原序） */
  if(f==='approved')view=view.filter(v=>revOf(v.r));
  else if(f==='unreviewed')view=view.filter(v=>!revOf(v.r));
  else if(f==='unreviewed_high')view=view.filter(v=>!revOf(v.r)&&v.r.severity==='高危');
  ul.innerHTML=view.length?view.map(({r,i})=>{
    const rev=revOf(r);
    return `<li><input type="checkbox" class="dr-chk" data-key="${esc(r.drug_a+'||'+r.drug_b)}" aria-label="选择规则 ${esc(r.drug_a)} × ${esc(r.drug_b)}">
      <span class="e">${esc(r.drug_a)} × ${esc(r.drug_b)}</span>
      <span class="chip ${r.severity==='高危'?'bad':'mid'}"><span class="dot"></span>${esc(r.severity||'中危')}</span>
      <span class="chip ${rev?'ok':''}"><span class="dot"></span>${rev?'已审校':'未审校'}</span>
      <span class="src">${esc(r.mechanism||'')}${r.management?' · 处置：'+esc(r.management):''}</span>
      ${rev?`<span class="src">审校人：${esc(r.reviewed_by||'—')}${r.reviewed_at?' · '+esc(fmtTs(r.reviewed_at)):''}</span>`:''}
      <span class="a" style="display:inline-flex;gap:6px">
        <button class="btn sm secondary" data-act="dr-edit" data-idx="${i}">编辑</button>
        <button class="btn sm danger" data-act="dr-del" data-idx="${i}">删除</button></span></li>`;}).join('')
    :'<li><span class="a">无匹配规则</span></li>';
}
async function markRulesReviewed(){  /* 评测行动项4：批量标记已审校（reviewed_by 由后端取当前登录名，不传名防冒充） */
  const keys=[...document.querySelectorAll('#drList .dr-chk:checked')].map(b=>b.dataset.key);
  if(!keys.length){toast('请先勾选要标记的规则');return;}
  const btn=document.getElementById('drReviewBtn');if(btn)btn.disabled=true;
  try{const r=await apiPost('/admin/drug/rules/review',{keys,decision:'approved'});
    if(r&&r.ok){toast('已标记 '+keys.length+' 条为已审校');renderDrugRulesEditor(drugDictEditorRoot);}
    else toast('失败：'+((r&&r.detail)||'未知错误'));
  }catch(e){toast('标记失败：'+e.message);}
  finally{if(btn)btn.disabled=false;}
}
function resetDrugRuleForm(){
  drugRuleEditPair=null;
  ['drF_a','drF_b','drF_mech','drF_mgmt'].forEach(id=>{
    const el=document.getElementById(id);if(el){el.value='';el.readOnly=false;}});
  const s=document.getElementById('drF_sev');if(s)s.value='高危';
  const b=document.getElementById('drSaveBtn');if(b)b.textContent='新增规则';
  const h=document.getElementById('drFormHint');if(h)h.textContent='';}
function startDrugRuleEdit(idx){  /* 问题3：编辑=回填表单并锁定药对（定位键） */
  const r=(drugDictCache.rules||[])[idx];if(!r)return;
  drugRuleEditPair=r.drug_a+'\u0001'+r.drug_b;
  document.getElementById('drF_a').value=r.drug_a;document.getElementById('drF_a').readOnly=true;
  document.getElementById('drF_b').value=r.drug_b;document.getElementById('drF_b').readOnly=true;
  document.getElementById('drF_sev').value=(r.severity==='中危')?'中危':'高危';
  document.getElementById('drF_mech').value=r.mechanism||'';
  document.getElementById('drF_mgmt').value=r.management||'';
  const b=document.getElementById('drSaveBtn');if(b)b.textContent='保存修改';
  const h=document.getElementById('drFormHint');if(h)h.textContent='编辑中：'+esc(r.drug_a+' × '+r.drug_b)+'（药对锁定；调整药对请删除后新增）';}
async function saveDrugRule(){  /* 问题3：新增/更新 → POST /admin/drug/rules（severity 限 高危|中危，后端 422 双保险） */
  const g=id=>document.getElementById(id)||{value:''};
  const a=String(g('drF_a').value||'').trim(),b=String(g('drF_b').value||'').trim();
  if(!a||!b){toast('药对两药必填');return;}
  if(a===b){toast('药对两药不能为同一药品');return;}
  const item={drug_a:a,drug_b:b,severity:String(g('drF_sev').value||'中危'),
    mechanism:String(g('drF_mech').value||'').trim(),management:String(g('drF_mgmt').value||'').trim()};
  const action=drugRuleEditPair?'update':'add';
  const btn=document.getElementById('drSaveBtn');if(btn)btn.disabled=true;
  try{const r=await apiPost('/admin/drug/rules',{action,item});
    if(r&&r.ok){toast((action==='update'?'已更新规则 ':'已新增规则 ')+a+' × '+b);resetDrugRuleForm();
      renderDrugRulesEditor(drugDictEditorRoot);}
    else toast('失败：'+((r&&r.detail)||'未知错误'));
  }catch(e){toast('保存失败：'+e.message);}
  finally{if(btn)btn.disabled=false;}}
async function deleteDrugRule(idx){  /* 问题3：删除规则（确认弹条；记入审计） */
  const r=(drugDictCache.rules||[])[idx];if(!r)return;
  const pair=r.drug_a+' × '+r.drug_b;
  confirmDanger('删除规则 · '+pair,
    `将删除相互作用规则「${esc(pair)}」，开药硬校验/警示随即不再命中该药对。此操作记入审计。`,
    '确认删除',async()=>{
      try{const resp=await apiPost('/admin/drug/rules',{action:'delete',item:{drug_a:r.drug_a,drug_b:r.drug_b}});
        if(resp&&resp.ok){toast('已删除规则 '+pair);renderDrugRulesEditor(drugDictEditorRoot);}
        else toast('失败：'+((resp&&resp.detail)||'未知错误'));
      }catch(e){toast('删除失败：'+e.message);}});}
/* 字典/规则编辑器事件委托（一次性挂载）：药名/药对来自字典数据（可含引号等字符），
   禁用内联 onclick 拼接（与科室删除同纪律） */
if(!window.__drugDictDelegate){window.__drugDictDelegate=true;document.addEventListener('click',e=>{
  const eb=e.target.closest('button[data-act="dd-edit"]');if(eb){startDrugDictEdit(eb.dataset.name);return;}
  const db=e.target.closest('button[data-act="dd-del"]');if(db){deleteDrugDictItem(db.dataset.name);return;}
  const er=e.target.closest('button[data-act="dr-edit"]');if(er){startDrugRuleEdit(parseInt(er.dataset.idx,10));return;}
  const dr=e.target.closest('button[data-act="dr-del"]');if(dr){deleteDrugRule(parseInt(dr.dataset.idx,10));return;}});}

/* ---- 阶段4 任务1 语义变更（注明）：admin 数据面板「药品字典」卡的挂载函数
   loadDrugDictPanel（renderDrugDictEditor(document.getElementById('drugDictPanel'))）
   已随卡片整体删除——字典管理 UI 只在药剂科审核中心 tab（switchReviewTab('drugdict')）；
   renderDrugDictEditor 函数本身保留供其调用。 ---- */

/* ---- F6 模型配置管理（admin 数据面板）---- */
let _llmData=null;
function renderLlm(d){
  const el=document.getElementById('llmPanel');if(!el)return;
  _llmData=d||{};
  const trunc=s=>{s=String(s||'');return s.length>42?s.slice(0,42)+'…':s;};
  let html='';
  for(const [kind,label] of [['chat','对话模型'],['vision','视觉模型']]){
    const g=_llmData[kind]||{active:null,providers:[]};
    const rows=(g.providers||[]).map(p=>{
      const act=g.active===p.id;
      return `<tr><td>${esc(p.display_name||p.model_id)}${act?' <span class="chip ok"><span class="dot"></span>激活中</span>':''}</td>
        <td><span class="src">${esc(p.api_format)}</span></td>
        <td class="num">${esc(p.model_id)}<div class="src">${esc(trunc(p.base_url))}</div>${p.extra_params?`<div class="src">extra: ${esc(trunc(p.extra_params))}</div>`:''}</td>
        <td style="white-space:nowrap">${act?'':`<button class="btn sm secondary" onclick="llmActivate('${kind}','${esc(p.id)}')">激活</button>`}<button class="btn sm danger" onclick="llmDel('${kind}','${esc(p.id)}')">删除</button></td></tr>`;}).join('');
    html+=`<h4 style="margin:12px 16px 0;display:flex;align-items:center;gap:8px;flex-wrap:wrap">${label}
      <span class="src">${g.active?'':'（当前使用内置配置 Qwen）'}</span>
      ${g.active?`<button class="btn ghost sm" onclick="llmDeactivate('${kind}')">使用内置配置</button>`:''}</h4>`+
      ((g.providers||[]).length?`<table><thead><tr><th>名称</th><th>格式</th><th>模型 / URL</th><th></th></tr></thead><tbody>${rows}</tbody></table>`
        :'<div class="src" style="padding:6px 16px">未配置第三方模型，回落 .env 内置配置。</div>');
  }
  html+=`<h4 style="margin:16px 16px 0">新增模型</h4>
    <div class="qgrid" style="padding:10px 16px">
      <label>类别<select class="inp" id="llmKind"><option value="chat">对话模型</option><option value="vision">视觉模型</option></select></label>
      <label>API 格式<select class="inp" id="llmFormat"><option value="openai">openai</option><option value="anthropic">anthropic</option></select></label>
      <label style="grid-column:1/-1">完整 URL（openai 例：https://api.openai.com/v1；anthropic 例：https://api.anthropic.com/v1）<input class="inp" id="llmUrl" spellcheck="false" placeholder="https://…"></label>
      <label>模型 ID<input class="inp" id="llmModel" spellcheck="false"></label>
      <label>展示名称<input class="inp" id="llmName"></label>
      <label style="grid-column:1/-1">API 密钥<input class="inp" id="llmKey" type="password" autocomplete="new-password"></label>
      <label style="grid-column:1/-1">额外参数（JSON，可选）<input class="inp" id="llmExtra" spellcheck="false" placeholder='按厂商自动预填（如 GLM：{"thinking":{"type":"enabled"},"reasoning_effort":"high"}），可修改；无特殊参数留空'></label>
    </div>
    <div style="padding:0 16px 12px;display:flex;gap:8px;align-items:center;flex-wrap:wrap">
      <button class="btn secondary sm" onclick="llmTest()">连通性测试</button>
      <button class="btn primary sm" onclick="llmSave()">保存</button>
      <span class="src" id="llmTestOut"></span></div>`;
  el.innerHTML=html;
  /* 任务2 厂商自动预设：URL/模型 ID 变化时按后端 suggest 预填「额外参数」（仅建议可改；
     用户已填时不覆盖）。300ms 防抖避免逐键请求。 */
  const urlInp=document.getElementById('llmUrl'),modelInp=document.getElementById('llmModel'),extraInp=document.getElementById('llmExtra');
  let _sgTimer=null;
  const suggestPrefill=()=>{
    clearTimeout(_sgTimer);
    _sgTimer=setTimeout(async()=>{
      const u=urlInp.value.trim(),m=modelInp.value.trim();
      if(!u||!m)return;
      try{const d=await apiGet('/admin/llm/suggest-extra?base_url='+encodeURIComponent(u)+'&model_id='+encodeURIComponent(m));
        if(d&&d.suggested&&!extraInp.value.trim()){
          extraInp.value=d.suggested;
          extraInp.placeholder='已按厂商预设填充（可修改）';}
      }catch(_){/* 预设失败静默：不阻塞表单 */}} ,300);};
  urlInp.addEventListener('input',suggestPrefill);
  modelInp.addEventListener('input',suggestPrefill);
}
async function llmRefresh(){try{renderLlm(await apiGet('/admin/llm/providers'));}
  catch(e){const el=document.getElementById('llmPanel');if(el)el.innerHTML='<div class="src" style="padding:10px 16px">⚠ 模型配置加载失败</div>';}}
function llmForm(){
  const f={kind:document.getElementById('llmKind').value,api_format:document.getElementById('llmFormat').value,
    base_url:document.getElementById('llmUrl').value.trim(),model_id:document.getElementById('llmModel').value.trim(),
    display_name:document.getElementById('llmName').value.trim(),api_key:document.getElementById('llmKey').value,
    extra_params:document.getElementById('llmExtra').value.trim()};  /* 任务2：额外参数 JSON（可选，逃生舱） */
  if(!f.base_url||!f.model_id){toast('请填写完整 URL 与模型 ID');return null;}
  return f;}
/* 任务2：pydantic 422 校验错误（detail 为数组）→ 取首条 msg 人性化展示 */
function _llmErrText(d){const det=d&&d.detail;
  if(Array.isArray(det))return det.map(x=>x.msg||x).join('；');
  return (det||'失败')+'';
}
async function llmTest(){
  const f=llmForm();if(!f)return;
  const out=document.getElementById('llmTestOut');out.textContent='测试中…';
  try{const d=await apiPost('/admin/llm/test',f);
    if(!d||d.detail===undefined||d.ok===undefined){out.textContent='✗ '+_llmErrText(d);return;}  /* 422/异常响应 */
    out.textContent=d.ok?`✓ 连通正常 · ${d.latency_ms} ms`:'✗ '+(d.detail||'失败');
  }catch(e){out.textContent='✗ '+e.message;}}
async function llmSave(){
  const f=llmForm();if(!f)return;
  try{const d=await apiPost('/admin/llm/providers',f);
    if(d&&d.id){toast('模型配置已保存');llmRefresh();}
    else toast('保存失败：'+_llmErrText(d));
  }catch(e){toast('保存失败：'+e.message);}}
async function llmActivate(kind,pid){
  try{const d=await apiPost('/admin/llm/activate',{kind,pid});
    if(d&&d.ok){toast('已激活：问答/视觉将改用该模型');llmRefresh();}
    else toast('激活失败：'+(d.detail||'未知错误'));
  }catch(e){toast('激活失败：'+e.message);}}
/* 回退内置配置：清空激活（active 置空，用户配置保留可切回），实跑回落 .env 内置 Qwen */
async function llmDeactivate(kind){
  try{const d=await apiPost('/admin/llm/deactivate',{kind});
    if(d&&d.ok){toast('已使用内置配置（Qwen），原配置保留可随时切回');llmRefresh();}
    else toast('操作失败：'+(d.detail||'未知错误'));
  }catch(e){toast('操作失败：'+e.message);}}
function llmDel(kind,pid){
  const g=(_llmData||{})[kind]||{providers:[]};
  const p=(g.providers||[]).find(x=>x.id===pid)||{};
  confirmDanger('删除模型配置',
    `将删除「${esc(p.display_name||p.model_id||pid)}」（${esc(kind)}）；若为当前激活项，将回落 .env 内置配置。`,'确认删除',async()=>{
    try{const r=await fetch('/api/v1/medical/admin/llm/providers/'+kind+'/'+encodeURIComponent(pid),{method:'DELETE',headers:auth()});
      if(r.status===401){logout();return;}
      const d=await r.json();
      if(!r.ok){toast('失败：'+(d.detail||r.status));return;}
      toast('已删除');closeModal();llmRefresh();
    }catch(e){toast('删除失败：'+e.message);}});}
async function createUser(){
  const name=document.getElementById('nuName').value.trim(),pass=document.getElementById('nuPass').value,role=document.getElementById('nuRole').value;
  const dept=document.getElementById('nuDept').value;  /* 科室必选：质控签名/科室服务端绑定 */
  if(!name||!pass){toast('请填写用户名与密码');return;}
  if(!dept){toast('请选择科室（创建账号必选）');return;}
  try{const r=await fetch('/api/v1/medical/admin/users',{method:'POST',headers:auth(),body:JSON.stringify({username:name,password:pass,role,dept})});
    if(r.status===401){logout();return;}
    if(!r.ok){const d=await r.json();toast('失败：'+(d.detail||r.status));return;}
    toast('用户 '+name+' 已创建');loadData();
  }catch(e){toast('创建失败：'+e.message);}}
function filterAudit(q){
  q=(q||'').trim().toLowerCase();
  const rows=document.querySelectorAll('#auditList .audit-row');
  rows.forEach(r=>{
    const data=(r.dataset.q||'').toLowerCase();
    r.style.display=!q||data.includes(q)?'':'none';
  });
}
async function deleteUser(name){
  confirmDanger('删除用户 · '+name,
    `将删除用户 <b class="num">${esc(name)}</b>；其登录凭证立即失效，审计记录保留。<br><span class="src">为防误删，请输入该用户名以确认。</span>
     <input class="inp" id="dgInput" aria-label="输入确认词" spellcheck="false" style="margin-top:8px" placeholder="输入：${esc(name)}" autocomplete="off">`,'确认删除',async()=>{
    try{const r=await fetch('/api/v1/medical/admin/users/'+encodeURIComponent(name),{method:'DELETE',headers:auth()});
      if(r.status===401){logout();return;}
      if(!r.ok){const d=await r.json();toast('失败：'+(d.detail||r.status));return;}
      toast('已删除 '+name);closeModal();loadData();
    }catch(e){toast('删除失败：'+e.message);}},name);
}

