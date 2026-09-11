/* ---- auth ---- */
function showLogin(on){document.getElementById('login').classList.toggle('on',on);}
function setWho(u,r){document.getElementById('who').textContent=u||'';document.getElementById('role').textContent=r||'';
  document.getElementById('who').style.display=u?'':'none';document.getElementById('role').style.display=u?'':'none';document.getElementById('lo').style.display=u?'':'none';
  document.getElementById('cpBtn').style.display=u?'':'none';}
async function doLogin(e){e.preventDefault();const err=document.getElementById('lerr');err.textContent='';
  const u=document.getElementById('lu').value.trim(),p=document.getElementById('lp').value;
  try{const r=await fetch('/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:u,password:p})});
    const d=await r.json(); if(!r.ok){err.textContent=d.detail||'登录失败';return false;}
    sessionStorage.setItem('med_token',d.access_token);await loadConfig();
    let dept='';try{const mr=await fetch('/api/v1/auth/me',{headers:{'Authorization':'Bearer '+token()}});if(mr.ok)dept=(await mr.json()).dept||'';}catch(e){}
    boot(d.username,d.role,dept);return false;
  }catch(ex){err.textContent='网络错误：'+ex.message;return false;}}
function logout(){window.removeEventListener('beforeunload',beforeUnloadGuard);window.onbeforeunload=null;  /* FIND-09：主动登出不受 beforeunload 拦截 */
  sessionStorage.removeItem('med_token');localStorage.removeItem('med_token');
  history.replaceState(null,'',location.pathname+location.search);  /* 退出清视图深链：下一个登录者回默认概览 */
  location.reload();} /* 双清：兼容旧版 localStorage 登录态 */

/* ---- nav ---- */
let reviewCount=0, reviewItems=[], reviewPage=0, reviewMe='';
let reviewTodoCount=0;  /* 本批问题2：审核中心数字角标（导航待办数）——问题2 语义变更：pharmacist=待审处方数（药物待核对区已移除）；admin=全部待核对+待审处方；qc=其它助手高危项（问题4 收权后 qc 不审 drug/开药）。登录 refreshReviewBadge / 进审核中心 loadReview 按服务端真值刷新 */
/* 导航项单一权威源（hash 深链白名单的键集由此派生） */
const NAV_ITEMS=[
  {g:'总览'},{k:'overview',t:'概览'},
  {g:'临床助手'},{k:'literature',t:'医学文献'},{k:'rx',t:'开药工作台'},{k:'imaging',t:'影像辅助'},{k:'case',t:'病例总结'},
  {g:'协作'},{k:'mdt',t:'多智能体会诊'},{k:'myconsults',t:'我的会诊'},
  {g:'质控'},{k:'qc',t:'病历质控（病案科）'},{k:'review',t:'审核中心'},
  {g:'系统'},{k:'kb',t:'知识库管理'},{k:'data',t:'数据面板'},{k:'arch',t:'架构 · 合规'},
];
const NAV_KEYS=NAV_ITEMS.filter(i=>i.k).map(i=>i.k);
function renderRail(){
  const allow=ROLE_NAV[ROLE]||ROLE_NAV.doctor;
  const items=NAV_ITEMS;
  let html='',pendingGroup=null;
  for(const it of items){
    if(it.g){pendingGroup=it.g;continue;}
    if(!allow.includes(it.k))continue;
    if(pendingGroup!==null){html+=`<div class="grp">${pendingGroup}</div>`;pendingGroup=null;}
    const act=(cur===it.k)?'active':'';
    let badge='';
    if(it.k==='rx'){  /* 开药审核 UX 问题2：rx 导航红点角标=本人被驳回（rejected）处方数；
        点进「我的处方」清零（本地状态），进入 rx 视图/提交处方后 refreshRxBadge 按服务端真值刷新 */
      if(rxRejectedCount)badge='<span class="tag red num" title="被驳回的处方">'+rxRejectedCount+'</span>';}
    else if(it.k==='review'){
      /* 任务6：侧边栏审核中心彩色数字徽标——黄=我的待确认数（doctor/pharmacist；
         qc/admin=待核对数），红=待我意见的会诊数。进入审核中心/提交意见/知情确认后
         由 renderRail 刷新（updateConsultBadge 亦触发）。
         本批问题2+4：qc/admin/pharmacist 均改用/增用 reviewTodoCount 待办角标——
         qc 只算其它助手高危项（drug/处方审核权已收归药剂科+管理员）；
         问题2 语义变更：pharmacist=待审处方数（药物待核对区已移除）；admin=全部待核对+待审处方。 */
      if(ROLE==='qc'){if(reviewTodoCount)badge='<span class="tag num" title="待核对（其它助手高危项）">'+reviewTodoCount+'</span>';}
      else if(ROLE==='admin'){if(reviewTodoCount)badge='<span class="tag num" title="待核对">'+reviewTodoCount+'</span>';}
      else{const cf=(myConfirmItems||[]).length;
        if(ROLE==='pharmacist'&&reviewTodoCount)badge+='<span class="tag red num" title="待办：待审处方">'+reviewTodoCount+'</span>';
        if(cf)badge+='<span class="tag num" title="我的待确认">'+cf+'</span>';
        if(consultPendingOps)badge+='<span class="tag red num" title="待我意见的会诊">'+consultPendingOps+'</span>';}}
    html+=`<button class="nav ${act}" data-k="${it.k}" onclick="go('${it.k}')">${ICON[it.k]}<span>${it.t}</span>${badge}</button>`;
  }
  document.getElementById('rail').innerHTML=html;
}
function hashView(){  /* 深链恢复：hash 仅在 NAV 键集且当前角色白名单内才生效 */
  let k='';try{k=decodeURIComponent((location.hash||'').replace(/^#/,'')).trim();}catch(e){return null;}
  const allow=ROLE_NAV[ROLE]||ROLE_NAV.doctor;
  return (k&&NAV_KEYS.includes(k)&&allow.includes(k))?k:null;
}
function go(k){
  /* 任务2：离开聊天视图前把该视图草稿存入 chatInput（切到任何视图都先保存，不丢） */
  if(cur&&AGENTS[cur])chatInput[cur]=document.getElementById('input').value;
  cur=k;
  history.replaceState(null,'','#'+k);  /* 深链：视图切换同步地址栏（review 内 tab/分页不写入） */
  document.querySelectorAll('.screen').forEach(s=>s.classList.remove('on'));
  if(k==='overview'){document.getElementById('scr-home').classList.add('on');loadOverview();}
  else if(k==='kb'){document.getElementById('scr-kb').classList.add('on');loadKb();}
  else if(k==='mdt')document.getElementById('scr-mdt').classList.add('on');
  else if(k==='myconsults'){document.getElementById('scr-myconsults').classList.add('on');loadMyConsults();}
  else if(k==='qc'){document.getElementById('scr-qc').classList.add('on');loadQcRejections();loadQcArch();}  /* 任务2：医生视图「我的质控驳回」卡片；阶段4：doctor「我的归档」小节 */
  else if(k==='rx'){document.getElementById('scr-rx').classList.add('on');loadRx();}  /* 阶段3：开药工作台（取代原 drug 视图） */
  else if(k==='data'){document.getElementById('scr-data').classList.add('on');loadData();}
  else if(k==='arch')document.getElementById('scr-arch').classList.add('on');
  else if(k==='review'){document.getElementById('scr-review').classList.add('on');loadReview();}
  else{document.getElementById('scr-chat').classList.add('on');setupChat(k);}
  renderRail();
}
/* ---- 概览 ---- */
function fmtUp(s){ if(s<60)return s+'s'; const m=Math.floor(s/60); if(m<60)return m+'m'; return Math.floor(m/60)+'h'+(m%60)+'m'; }
/* doc_count sentinel：后端查询失败返回 -1（区分「真 0」），显示 … 表示未知并提示可刷新重试 */
function fmtCnt(v){ return v<0 ? '<span title="知识库查询失败/未知，可点击刷新重试">…</span>' : esc(String(v)); }
async function loadOverview(){
  const b=document.getElementById('homeBody');
  b.innerHTML='<div class="stats">'+'<div class="stat"><span class="k">&nbsp;</span><span class="v"><span class="sk" style="display:inline-block;width:44px;height:20px"></span></span></div>'.repeat(5)+'</div>';
  let d; try{ const r=await fetch('/api/v1/medical/overview',{headers:auth()}); if(r.status===401){logout();return;} d=await r.json(); }
  catch(e){ b.innerHTML='<div class="panel" style="padding:16px">⚠ 概览加载失败</div>'; return; }
  const stats=[['待双人核对',d.pending,'项'],['已处理核对',d.resolved,'项'],['知识库文档',fmtCnt(d.kb_docs),'条'],['近期事件',d.events_total,'条'],['系统版本',d.version,'']].map(([k,v,u])=>`<div class="stat"><span class="k">${k}</span><span class="v num">${v}${u?` <small>${u}</small>`:''}</span></div>`).join('');
  /* 任务1：人话化全覆盖——概览「最近活动」同样走 humanizeAudit（后端 recent 已带 payload），esc 后入 HTML */
  const acts=(d.recent&&d.recent.length)? d.recent.map(e=>`<li><span class="t num">${esc(e.ts)}</span><span class="e">${esc(humanizeAudit(e))}</span><span class="a">${esc(e.actor)}</span></li>`).join('') : '<li><span class="a" style="margin:0">暂无活动记录</span></li>';
  const allow=ROLE_NAV[ROLE]||[];
  const quick=[['literature','医学文献','答案附文献出处'],['rx','开药工作台','AI 推荐+药剂科审核'],['imaging','影像辅助','AI 初步所见'],['case','病例总结','摘要与鉴别清单'],['mdt','多智能体会诊','三专科意见与分歧'],['qc','病历质控','病案科工作台']].filter(([k])=>allow.includes(k)).map(([k,n,dd])=>`<li><button onclick="go('${k}')">${ICON[k]}<span><span class="qn">${n}</span><br><span class="qd">${dd}</span></span><span class="ar">›</span></button></li>`).join('');
  const who=document.getElementById('who').textContent||'医师';
  b.innerHTML=`<div class="hero"><h2>欢迎回来，${esc(who)}</h2><p>答案均附出处、高风险结论双人核对；本工具不构成诊断、不开处方。</p></div>
    <div class="stats">${stats}</div>
    <div class="dash">
      <div class="panel"><h3>最近活动</h3><ul class="feed">${acts}</ul></div>
      <div class="panel"><h3>快速进入</h3><ul class="qlist">${quick}</ul></div>
    </div>`;
}

