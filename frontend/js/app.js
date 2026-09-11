/* ---- boot ---- */
let ME_DEPT='';  /* 当前账号科室（/auth/me 下发；质控页只读展示，无则显示 —） */
function boot(u,r,dept){ROLE=r||'doctor';ME_DEPT=dept||'';setWho(u,r);showLogin(false);
  /* 医师签名绑定当前账号：默认填入并锁定（后端仍权威覆盖，前端只读防误改） */
  const sig=document.getElementById('qcf_6');
  if(sig){sig.value=u||'';sig.readOnly=true;sig.title='医师签名绑定当前登录账号，不可修改（后端强制覆盖）';}
  const dp=document.getElementById('qcDept');
  if(dp)dp.textContent='当前科室：'+(ME_DEPT||'—')+'（服务端绑定账号科室'+(ME_DEPT?'':'，请联系管理员设置')+'）';
  const hk=hashView();go(hk||'overview');
  if(hk!=='review')loadReview();  /* hash 恢复 review 时 go('review') 已加载，避免双请求 */
  refreshRxBadge();  /* 开药审核 UX 问题2：登录即刷新被驳回处方角标（无需先进 rx 视图） */
  refreshReviewBadge();}  /* 本批问题2：登录即刷新审核中心待办角标（pharmacist/qc/admin，无需先进审核中心） */
/* 本批问题2：审核中心数字角标数据刷新（复用 refreshRxBadge 模式：登录/切视图按服务端真值刷新）。
   问题2 语义变更：pharmacist 角标只算待审处方数（「药物待核对」区已随两药快查下线移除，
   不再拉取 /review/pending）；admin=全部待核对+待审处方；qc=其它助手高危项
   （问题4 收权后 qc 不审 drug/开药，角标不计这两类）。进审核中心时由 loadReview/loadReviewMine
   按已拉取数据更新并 renderRail，本函数只负责登录后的首刷（当前不在审核中心才重绘导航）。 */
async function refreshReviewBadge(){
  if(ROLE!=='pharmacist'&&ROLE!=='admin'&&ROLE!=='qc')return;
  let n=0;
  if(ROLE!=='pharmacist'){  /* 问题2：pharmacist 不计 drug 待核对（区已移除），跳过 /review/pending */
    try{const d=await apiGet('/review/pending');
      const pend=d.pending||[];
      n+=(ROLE==='qc')?pend.filter(i=>i.agent!=='drug').length
        :pend.length;
    }catch(e){/* 角标刷新失败静默（不干扰登录主流程） */}}
  if(ROLE==='pharmacist'||ROLE==='admin'){  /* 开药待审处方数（qc 无处方审核权不拉取，避免 403 噪音） */
    try{const r=await apiGet('/prescriptions/pending');n+=(r.items||[]).length;}catch(e){/* 静默 */}}
  reviewTodoCount=n;
  if(cur!=='review')renderRail();}
function beforeUnloadGuard(e){  /* 防误关丢失未发送内容：聊天输入或质控字段任一有内容时拦截；发送/拆分清空后自然放行（具名函数供 logout 移除，FIND-09） */
  const qcDirty=[...document.querySelectorAll('[id^="qcf_"]')].some(el=>el.value.trim());
  if(document.getElementById('input').value.trim()||qcDirty){e.preventDefault();e.returnValue='';}
}
window.addEventListener('beforeunload',beforeUnloadGuard);
document.getElementById('send').onclick=send;
document.getElementById('input').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();const btn=document.getElementById('send');if(btn.disabled)return;send();}});
document.getElementById('input').addEventListener('input',e=>{e.target.style.height='auto';e.target.style.height=Math.min(e.target.scrollHeight,160)+'px';});
(async function init(){
  if(!token()){showLogin(true);return;}
  try{const r=await fetch('/api/v1/auth/me',{headers:{'Authorization':'Bearer '+token()}});
    if(r.ok){const m=await r.json();await loadConfig();boot(m.username,m.role,m.dept);}else{showLogin(true);}}
  catch(e){showLogin(true);}
})();
