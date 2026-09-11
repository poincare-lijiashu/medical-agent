function setupChat(k){const a=AGENTS[k];document.getElementById('cTitle').textContent=a.name;
  document.getElementById('cDesc').textContent=a.desc;document.getElementById('input').placeholder=a.hint;
  document.getElementById('attach').style.display=a.imageOk?'inline-flex':'none';clearImg();
  const inp=document.getElementById('input');  /* 任务2：恢复本视图独立草稿（切视图不串字） */
  inp.value=chatInput[k]||'';inp.style.height='auto';
  const m=document.getElementById('msgs');m.innerHTML='';
  const h=chatHist[k]||[];
  if(h.length){h.forEach(e=>e.role==='me'?renderUser(e.text,e.images||(e.image?[e.image]:[])):renderBot(e.text,e.meta));}
  else renderBot('你好，我是'+a.name+'。'+a.desc+'。', null, true);
  scroll();}
function newChat(){chatHist[cur]=[];chatInput[cur]='';if(cur==='literature'){litSessionId=genSid();}setupChat(cur);}
/* 任务3 会话记忆：每个 literature 会话固定一个 session_id（「新建对话」时更换），
   后端据此注入最近 N 轮问答（仅理解指代，不影响独立检索）。crypto.randomUUID 不可用
   （旧浏览器/file://）时退化为时间戳+随机串。 */
function genSid(){try{return crypto.randomUUID();}catch(_){return 'lit-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,10);}}
let litSessionId=genSid();

/* ---- chat ---- */
function renderUser(t,images){const r=document.createElement('div');r.className='row me';
  r.innerHTML=`<div class="bubble txt"></div>`;r.querySelector('.bubble').textContent=t;
  (images||[]).forEach(s=>{const im=document.createElement('img');im.className='thumb';im.alt='用户上传影像';im.width=96;im.height=96;im.src=s;r.appendChild(im);});
  document.getElementById('msgs').appendChild(r);scroll();}
function renderBot(t,meta,intro){const m=document.getElementById('msgs');
  const r=document.createElement('div');r.className='row';
  r.innerHTML=`<div class="avatar">${ICON[cur]}</div><div class="bubble"><div class="txt md"></div>
    <div class="foot" data-foot></div></div>`;
  /* 任务3 空响应兜底：后端未返回文本（如 VL 空内容漏网）→ 前端明确提示，不渲染空白 */
  const safeText=(t&&String(t).trim())?t:'AI 未生成所见，请重新上传或补充描述。';
  r.querySelector('.txt').innerHTML=md(safeText);
  const foot=r.querySelector('[data-foot]');
  if(meta){foot.innerHTML=confChip(meta.confidence||0,!!meta.needs_human_review)+srcLine(meta.sources)+traceLine(meta.refine_trace);}
  else foot.remove();
  m.appendChild(r);scroll();return r;}
function scroll(){const m=document.getElementById('msgs');m.scrollTop=m.scrollHeight;}
function skel(){const r=document.createElement('div');r.className='row';
  r.innerHTML=`<div class="avatar">${ICON[cur]}</div><div class="bubble" style="width:60%"><div class="sk" style="margin-bottom:8px"></div><div class="sk" style="width:85%;margin-bottom:8px"></div><div class="sk" style="width:55%"></div></div>`;
  document.getElementById('msgs').appendChild(r);scroll();return r;}
function thinkBubble(){const r=document.createElement('div');r.className='row';
  r.innerHTML=`<div class="avatar">${ICON[cur]}</div><div class="bubble"><div class="txt">思考中…</div></div>`;
  document.getElementById('msgs').appendChild(r);scroll();return r;}
function setThink(r,t){const el=r.querySelector('.txt');if(el)el.textContent=t;}
async function streamLit(q,think,agent){
  const res=await fetch('/api/v1/medical/literature/stream',{method:'POST',headers:auth(),body:JSON.stringify({question:q,session_id:litSessionId})});
  if(res.status===401){think.remove();logout();return true;}
  if(!res.ok||!res.body) return false;
  const rd=res.body.getReader();const dec=new TextDecoder();let buf='',ev=null;
  const label={retrieve:'正在检索并精排证据…',grade:'评估证据充分性…',generate:'生成循证回答…',fallback:'检索未命中，生成常识参考…'};
  while(true){const{value,done}=await rd.read();if(done)break;buf+=dec.decode(value,{stream:true});
    const parts=buf.split('\n');buf=parts.pop();
    for(const ln of parts){const s=ln.trim();
      if(s.startsWith('event:'))ev=s.slice(6).trim();
      else if(s.startsWith('data:')){let d;try{d=JSON.parse(s.slice(5).trim());}catch(e){continue;}
        if(ev==='progress')setThink(think,label[d.step]||'处理中…');
        else if(ev==='refine')setThink(think,refineThink(d));  /* agentic 改写决策：实时更新思考气泡（textContent 渲染天然免注入） */
        else if(ev==='result'){think.remove();const a=agent||cur;pushHist(a,{role:'bot',text:d.answer,meta:d});renderBot(d.answer,d);return true;}
        else if(ev==='error')return false;}}}
  return false;
}
async function send(){const inp=document.getElementById('input');const q=inp.value.trim();if(!q)return;
  const btn=document.getElementById('send');if(btn.disabled)return;  /* 任务2：先查在途再动输入框（原顺序在途点击会吞掉用户输入） */
  btn.disabled=true;
  const agent=cur;  // 锁定当前助手，避免请求途中切换 cur 致答案写到错误会话
  const curImagesHad=curImages.length>0;  /* 诊断1：发送前快照是否有待发送图（imaging 提示用） */
  /* 任务2：me 记录直接归属发起助手 agent——原 addUser 先写 cur、成功后再搬移，中途切视图历史错位 */
  pushHist(agent,{role:'me',text:q,images:curImages.slice()});
  if(agent===cur)renderUser(q,curImages);
  inp.value='';inp.style.height='auto';
  const think=thinkBubble();
  try{if(agent==='literature'){ let streamed=false; try{ streamed=await streamLit(q,think,agent);}catch(_){streamed=false;} if(streamed){btn.disabled=false;inp.focus();return;} }
    try{const r=await fetch('/api/v1/medical'+AGENTS[agent].endpoint,{method:'POST',headers:auth(),
        body:JSON.stringify({question:q,images:curImages.length?curImages:undefined,
          session_id:agent==='literature'?litSessionId:undefined})});  /* 任务3：literature 携带会话 id（SSE 失败回落的非流式路径同会话） */
      const d=await r.json();think.remove();
      if(r.status===401){logout();return;}
      /* 任务2：失败分支必须渲染（原只写历史不渲染 → 界面"无反应"，用户误判未发出而反复重发） */
      if(!r.ok){const msg='⚠ 服务异常：'+(d.detail||r.status);
        pushHist(agent,{role:'bot',text:msg});if(agent===cur)renderBot(msg);return;}
      // 若中途切换助手，答案仍归属发起时的会话，但 UI 提示
      if(agent!==cur){const b=document.createElement('div');b.className='src';b.style.padding='8px 12px';
        b.textContent=`（提示：请求发出后切换到了「${AGENTS[cur].name||cur}」，以下答案归属「${AGENTS[agent].name||agent}」）`;document.getElementById('msgs').appendChild(b);}
      // bot 记录按发起时的 agent 写入历史；渲染到当前 msgs（含提示条）
      pushHist(agent,{role:'bot',text:d.answer,meta:d});
      renderBot(d.answer,d);
      curImages=[];renderImgbar();  // 成功后清空已选图片，避免下次静默重发（失败保留，便于原样重试）
      /* 诊断1：imaging 视图待发送图清空 → 输入区提示「已发送，可重新上传」（历史图留在消息流里） */
      if(agent==='imaging'&&curImagesHad)showSentHint(true);
    }catch(e){think.remove();const msg='⚠ 请求失败：'+e.message;
      pushHist(agent,{role:'bot',text:msg});if(agent===cur)renderBot(msg);}
  }finally{btn.disabled=false;inp.focus();}}
function _commitUserToAgent(agent,q){}

/* ---- image ---- */
const MAX_IMG=10;  /* 轮 A2：上限 6→10；上传前前端压缩（compressImage） */
/* 诊断1：「图片已发送」提示开关（imaging 视图待发送图清空后显示，重新选图/切视图即隐藏） */
function showSentHint(on){const h=document.getElementById('sentImgHint');if(h)h.style.display=on?'block':'none';}
function clearImg(){curImages=[];const i=document.getElementById('img');i.value='';document.getElementById('imgbar').style.display='none';showSentHint(false);}
function renderImgbar(){
  const el=document.getElementById('imgthumbs');
  el.innerHTML=curImages.map(s=>`<img class="thumb" style="width:38px;height:38px;object-fit:cover;border-radius:6px;border:1px solid var(--line)" src="${s}" alt="已选影像">`).join('');
  document.getElementById('imgname').textContent=curImages.length>1?`已选 ${curImages.length} 张（可分多次添加，最多 ${MAX_IMG} 张）`:`已选 1 张（可继续添加，最多 ${MAX_IMG} 张）`;
  document.getElementById('imgbar').style.display=curImages.length?'flex':'none';}
document.getElementById('img').addEventListener('change',async e=>{const fs=[...e.target.files];if(!fs.length)return;
  // 累加模式：分次选择追加到已选图片（上限 10 张），先压缩再入列（长边>2000 缩放/JPEG 递减至 ≤6MB）
  let add=fs.slice(0,MAX_IMG-curImages.length);
  if(add.length<fs.length)toast(`最多 ${MAX_IMG} 张，已忽略超出部分`);
  if(!add.length){e.target.value='';return;}
  const imgs=(await Promise.all(add.map(f=>compressImage(f)))).filter(Boolean);
  curImages=curImages.concat(imgs).slice(0,MAX_IMG);
  e.target.value='';  // 允许下次选择同一文件
  renderImgbar();showSentHint(false);  /* 诊断1：重新选图即视为新的待发送图，隐藏「已发送」提示 */});

/* ---- MDT ---- */
let lastMdtReport='';  /* 最近一次 AI 多智能体会诊小结（发起真实跨科室会诊时作底稿附在问题后） */
/* 轮 B1：会诊发起框待附影像（独立于聊天输入区 curImages，互不干扰）；
   复用 compressImage（与 imaging 同压缩策略）+ MAX_IMG 上限；成功发起后清空 */
let consultImages=[];
async function addConsultImgs(fs){
  const arr=[...fs];if(!arr.length)return;
  const add=arr.slice(0,MAX_IMG-consultImages.length);
  if(add.length<arr.length)toast(`最多 ${MAX_IMG} 张，已忽略超出部分`);
  if(!add.length)return;
  const imgs=(await Promise.all(add.map(f=>compressImage(f)))).filter(Boolean);
  consultImages=consultImages.concat(imgs).slice(0,MAX_IMG);
  document.getElementById('consultImg').value='';  // 允许下次选择同一文件
  renderConsultImgs();}
function clearConsultImgs(){consultImages=[];const i=document.getElementById('consultImg');if(i)i.value='';renderConsultImgs();}
function renderConsultImgs(){
  const bar=document.getElementById('consultImgThumbs');
  bar.innerHTML=consultImages.map(s=>`<img class="thumb" style="width:38px;height:38px;object-fit:cover;border-radius:6px;border:1px solid var(--line);cursor:zoom-in" src="${esc(s)}" alt="会诊影像" onclick="showImg(this.src)">`).join('');
  bar.style.display=consultImages.length?'flex':'none';
  const clr=document.getElementById('consultImgClear');if(clr)clr.style.display=consultImages.length?'':'none';
  document.getElementById('consultImgName').textContent=consultImages.length
    ?`已附 ${consultImages.length} 张（最多 ${MAX_IMG} 张，AI 会结合影像给出专科意见）`:'';}
async function runMdt(){const c=document.getElementById('mdtCase').value.trim();if(!c){toast('请先填写病例');return;}
  const btn=document.getElementById('mdtRun');btn.disabled=true;const out=document.getElementById('mdtOut');
  out.innerHTML=`<div class="card"><div class="sk" style="width:40%;height:16px;margin-bottom:14px"></div><div class="sk" style="margin-bottom:8px"></div><div class="sk" style="width:90%"></div></div>`;
  try{const r=await fetch('/api/v1/medical/mdt/consult',{method:'POST',headers:auth(),body:JSON.stringify({case:c})});
    if(r.status===401){logout();return;} const d=await r.json();
    if(!r.ok){out.innerHTML='<div class="card">⚠ '+esc(String(d.detail||r.status))+'</div>';return;}
    lastMdtReport=String(d.report||'');  /* 复用 AI 分析做真实会诊底稿 */
    out.innerHTML=renderMdt(d);
  }catch(e){out.innerHTML='<div class="card">⚠ '+esc(e.message)+'</div>';}
  finally{btn.disabled=false;}}
/* 发起真实跨科室会诊：Agent 决策点（LLM 读病例+实时科室清单选专科）→ 定向初步意见
   分发给目标科室全部在职医生；问题 = 病例原文（+ AI 底稿，若有） */
async function startRealConsult(){
  const c=document.getElementById('mdtCase').value.trim();
  if(!c){toast('请先填写病例/会诊问题');return;}
  const q=lastMdtReport?(c+'\n\n【AI 多智能体会诊底稿】\n'+lastMdtReport):c;
  confirmDanger('发起真实跨科室会诊',
    'AI 将阅读病例与<b>实时科室清单</b>自主选择召集专科，并把按科室的定向初步意见分发给目标科室的全部在职医生；各科医生在审核中心「其它科室会诊协助」填写意见（仅建议权，每人一票），由您手动结束会诊并汇总。确认发起？','确认发起',async()=>{
    const btn=document.getElementById('mdtReal');btn.disabled=true;
    try{const r=await fetch('/api/v1/medical/consults',{method:'POST',headers:auth(),
      body:JSON.stringify({question:q,images:consultImages.length?consultImages:undefined})});  /* 轮 B1：随单附影像 */
      if(r.status===401){logout();return;}
      const d=await r.json();
      if(!r.ok){toast('发起失败：'+(d.detail||r.status));return;}
      closeModal();  /* 任务1：发起成功自动关闭确认弹窗（复用共享 closeModal，不再手点取消/关闭） */
      toast('会诊已发起并分发：'+((d.target_depts||[]).join('、')||'（无受邀科室）'));
      lastMdtReport='';  // 底稿已随单发出，避免下次误带旧稿
      consultImages=[];renderConsultImgs();  /* 轮 B1：图已随单发出，清空待附影像 */
      refreshConsultBadge();  /* 任务4：新会诊发起后刷新徽标（发起者可能也在受邀科室） */
    }catch(e){toast('发起失败：'+e.message);}
    finally{btn.disabled=false;}});}
function urgencyPill(u){
  const map={'高':['danger','紧急·高危排查'],'中':['warn','注意·限期评估'],'低':['ok','常规']};
  const [cls,lab]=map[u]||map['中'];
  return `<span class="chip ${cls}"><span class="dot"></span>${lab}</span>`;
}
function renderMdt(d){
  const ops=(d.opinions||[]).map(o=>`<details class="card" style="padding:0">
      <summary style="display:flex;align-items:center;gap:10px;padding:12px 16px;cursor:pointer;list-style:none">
        <b>${esc(o.specialty)}</b>${confChip(o.confidence||0,false)}
        <span class="ar" style="margin-left:auto;color:var(--ink-3)">▾</span></summary>
      <div style="padding:0 16px 14px"><div class="txt md">${md(o.opinion||'')}</div>${srcLine(o.sources)}</div>
    </details>`).join('');
  const acts=(d.key_actions||[]).length
    ? `<div class="card"><h3>建议先做</h3><ol class="rules" style="margin-top:6px">${d.key_actions.map(a=>`<li>${esc(a)}</li>`).join('')}</ol></div>`
    : '';
  return `<div class="card" style="border-left:3px solid ${d.urgency==='高'?'var(--danger)':(d.urgency==='低'?'var(--ok)':'var(--warn)')}">
      <div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
        <h3 style="margin:0">会诊结论</h3>${urgencyPill(d.urgency)}${confChip(d.confidence||0,!!d.needs_human_review)}</div>
      <div style="font-size:16px;font-weight:600;margin-top:8px">${esc(d.headline||'')}</div>
      <div class="src" style="margin-top:6px">详细小结与共识/分歧见下方；非真人会诊，结论须医师复核。</div>
    </div>
    ${acts}
    <details class="card" style="padding:0"><summary style="padding:12px 16px;cursor:pointer;font-weight:600">会诊小结 · 共识与分歧 ▾</summary>
      <div class="txt md" style="padding:0 16px 14px">${md(d.report||'')}</div></details>
    <div class="grid2" style="margin-top:14px">${ops}</div>`;
}

/* ---- 跨科室会诊：收件箱（审核中心第三栏目）+ 我的会诊（发起者视角） ----
   全部动态内容（含各科医生意见文本）一律 esc() 转义后入 HTML。 */
let consultInbox=[], consultMe='', consultPendingOps=0;
/* 任务6 徽标口径修正：「其它科室会诊协助」tab 徽标 = 待我（本科室）意见的 consult 数——
   不再叠加「我的待确认」数（此前两者相加被误读为全部会诊数；待确认数归侧边栏黄色徽标）。
   科室一票口径：本科室任一医生已提交意见即不计入（add_opinion 会以「该科室已完成意见」拒绝）。
   进入审核中心（loadReviewMine）/ 收件箱刷新（loadConsultInbox）/ 新会诊发起与提交意见后刷新，
   并同步刷新侧边栏彩色徽标（renderRail）。 */
function updateConsultBadge(){
  const tc=document.getElementById('tabConsults');if(!tc)return;
  consultPendingOps=(consultInbox||[]).filter(c=>!(c.opinions||[]).some(o=>o.dept===ME_DEPT)).length;
  tc.innerHTML='其它科室会诊协助'+(consultPendingOps?' <span class="tag num">'+consultPendingOps+'</span>':'');
  renderRail();
}
async function refreshConsultBadge(){
  if(ROLE==='qc'||ROLE==='admin')return;  /* qc/admin 无该 tab（全局档案视图） */
  try{const d=await apiGet('/consults/inbox');consultInbox=d.items||[];consultMe=d.me||'';}catch(e){}
  updateConsultBadge();
}
async function loadConsultInbox(){
  const b=document.getElementById('reviewBody');
  b.innerHTML='<div class="card"><div class="sk" style="height:16px;width:30%"></div></div>';
  try{const r=await fetch('/api/v1/medical/consults/inbox',{headers:auth()});
    if(r.status===401){logout();return;}
    const d=await r.json();consultInbox=d.items||[];consultMe=d.me||'';
    renderConsultInbox();updateConsultBadge();  /* 任务4：收件箱刷新后同步 tab 徽标 */
  }catch(e){b.innerHTML='<div class="card">⚠ '+esc(e.message)+'</div>';}}
/* 轮 B1：会诊单缩略图条（收件箱列表项与「我的会诊」详情共用）——
   data:image/ 前缀白名单过滤 + esc() 转义 src，点击复用 showImg 大图查看 */
function consultThumbs(c){
  const imgs=(c.images||[]).filter(u=>String(u).startsWith('data:image/'));
  return imgs.length?'<div style="margin-top:6px;display:flex;flex-wrap:wrap;gap:6px">'
    +imgs.map(u=>'<img class="thumb" style="width:38px;height:38px;cursor:zoom-in" src="'+esc(u)+'" alt="会诊影像" onclick="showImg(this.src)">').join('')+'</div>':'';}
function renderConsultInbox(){
  const b=document.getElementById('reviewBody');
  const items=consultInbox||[];
  if(!items.length){b.innerHTML='<div class="empty">'+ICON.review+'<div>暂无其它科室的会诊协助请求。</div>'
    +'<div class="lead" style="margin:8px auto 0">当其它科室发起跨科室会诊并邀请您所在科室（'+esc(ME_DEPT||'未设置')+'）时，会出现在这里；意见仅有建议权，每人一票。</div></div>';return;}
  b.innerHTML=items.map(c=>{
    const mine=(c.opinions||[]).some(o=>o.doctor===consultMe);  /* 已给意见标记 */
    const depts=(c.target_depts||[]).map(x=>'<span class="chip mid"><span class="dot"></span>'+esc(x)+'</span>').join(' ');
    const opForm=mine?'<div style="margin-top:8px"><span class="chip ok"><span class="dot"></span>已提交本科室意见（每人一票）</span></div>'
      :'<div style="margin-top:8px"><textarea class="inp" id="cop_'+esc(c.id)+'" rows="2" placeholder="填写您所在科室的会诊意见（仅建议权，无驳回权）"></textarea>'
      +'<div style="margin-top:6px"><button class="btn sm primary" onclick="submitOpinion(\''+esc(c.id)+'\')">提交科室意见</button></div></div>';
    return '<div class="card" style="border-left:3px solid var(--accent)">'
      +'<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap"><h3 style="margin:0">跨科室会诊协助</h3>'
      +'<span class="chip warn"><span class="dot"></span>进行中</span>'
      +'<span class="src num" style="margin-left:auto">发起人 '+esc(c.initiator||'—')+'（'+esc(c.initiator_dept||'—')+'） · '+fmtTs(c.created_at)+'</span></div>'
      +'<div style="margin-top:6px;display:flex;gap:6px;flex-wrap:wrap;align-items:center"><span class="src">受邀科室：</span>'+depts+'</div>'
      /* 任务5：三段式详情复用（不出医生意见段——意见由下方表单呈现；question 内嵌的 AI 底稿不再重复展示）；
         轮 B1：缩略图条复用 consultThumbs（与「我的会诊」详情同源渲染） */
      +consultDetailBlocks(c,{ops:false})
      +consultThumbs(c)
      +opForm+'</div>';}).join('');}
async function submitOpinion(id){
  const el=document.getElementById('cop_'+id);const content=el?el.value.trim():'';
  if(!content){toast('请先填写意见内容');return;}
  const btn=el.parentElement.querySelector('button');if(btn)btn.disabled=true;
  try{const r=await fetch('/api/v1/medical/consults/'+encodeURIComponent(id)+'/opinion',
      {method:'POST',headers:auth(),body:JSON.stringify({content})});
    if(r.status===401){logout();return;}
    if(r.ok){toast('已提交科室意见');loadConsultInbox();}
    else{const d=await r.json();toast('提交失败：'+(d.detail||r.status));if(btn)btn.disabled=false;}
  }catch(e){toast('提交失败：'+e.message);if(btn)btn.disabled=false;}}
/* 发起者「我的会诊」页：单详情（AI 意见/医生意见并排署名）+ 结束会诊按钮 */
let myConsultData=null;
async function loadMyConsults(){
  const b=document.getElementById('myConsultsBody');
  b.innerHTML='<div class="card"><div class="sk" style="height:16px;width:30%"></div></div>';
  try{const r=await fetch('/api/v1/medical/consults/mine',{headers:auth()});
    if(r.status===401){logout();return;}
    myConsultData=await r.json();renderMyConsults();
  }catch(e){b.innerHTML='<div class="card">⚠ '+esc(e.message)+'</div>';}}
/* 任务5：会诊详情信息架构重组（渐进折叠，同一内容只展示一遍）——
   此前「我的会诊」卡片同一 AI 内容出现 3 遍（question 内嵌的【AI 多智能体会诊底稿】/
   AI 初步意见/结束后的会诊汇总），现重组为三段：
   [doc] 摘要（病例原文 + AI 会诊小结/共识/分歧/建议，默认展开）→
   [analysis] 各专科 AI 意见（ai_analysis 按【科室】拆分，整段默认折叠、单科独立展开）→
   [staff] 各科室医生意见（并排署名）。会诊汇总与其组成内容重复，常规路径不再重复渲染。
   任务2：三段标题原 emoji 图钉/显微镜/医生符号统一替换为 ICON 表 SVG（doc/analysis/staff）。 */
function splitConsultQuestion(q){  /* 拆出病例原文与 AI 底稿（startRealConsult 的拼接格式） */
  const s=String(q||'');const mk='【AI 多智能体会诊底稿】';const i=s.indexOf(mk);
  return i>=0?{case:s.slice(0,i).trim(),draft:s.slice(i+mk.length).trim()}:{case:s.trim(),draft:''};}
function splitAiByDept(s){  /* ai_analysis（「【科室】意见」以空行连接）按科室拆分；无【科室】前缀 → 单块兜底 */
  const t=String(s||'');const out=[];const re=/【([^】]{1,30})】/g;let m,dept=null,li=0;
  while((m=re.exec(t))){if(dept!==null)out.push({dept,text:t.slice(li,m.index).trim()});dept=m[1];li=m.index+m[0].length;}
  if(dept!==null)out.push({dept,text:t.slice(li).trim()});
  return out.length?out:(t.trim()?[{dept:'',text:t.trim()}]:[]);}
/* opts.ops=false 时不出「医生意见」段（收件箱视图：意见以表单呈现，避免与表单语义混淆） */
function consultDetailBlocks(c,opts){
  const withOps=!(opts&&opts.ops===false);
  const sq=splitConsultQuestion(c.question);
  /* [doc] 摘要（默认展开）：病例原文 + AI 底稿单遍展示；
     兜底：无底稿、已结束且无 ai_analysis 时展示会诊汇总（防信息丢失） */
  let sumHtml='';
  if(sq.draft)sumHtml='<h4 style="margin:10px 0 4px">'+ICON.doc+' AI 会诊小结（共识 · 分歧 · 建议）</h4>'
    +'<div class="txt md">'+md(sq.draft)+'</div>';
  else if(c.status==='closed'&&!String(c.ai_analysis||'').trim()&&c.summary)
    sumHtml='<h4 style="margin:10px 0 4px">'+ICON.doc+' 会诊汇总</h4><div class="txt md">'+md(c.summary)+'</div>';
  const abstract='<h4 style="margin:0 0 4px">'+ICON.doc+' 摘要（病例/会诊问题）</h4>'
    +'<div class="txt" style="white-space:pre-wrap">'+esc(sq.case||'（无）')+'</div>'+sumHtml;
  /* [analysis] 各专科 AI 意见：整段默认折叠，单科独立展开（summary 含科室名便于扫读） */
  const ais=splitAiByDept(c.ai_analysis);
  const aiSec=ais.length?'<details style="margin-top:10px"><summary style="cursor:pointer;font-weight:600">'+ICON.analysis+' 各专科 AI 意见（'+ais.length+' 科）<span class="src"> 默认折叠 · 点击展开</span></summary>'
    +'<div style="margin-top:8px">'+ais.map(a=>'<details class="card" style="padding:0;margin-bottom:8px"><summary style="display:flex;align-items:center;gap:10px;padding:10px 14px;cursor:pointer;list-style:none"><b>'+esc(a.dept||'AI 意见')+'</b><span class="ar" style="margin-left:auto;color:var(--ink-3)">▾</span></summary><div class="txt md" style="padding:0 14px 12px">'+md(a.text)+'</div></details>').join('')+'</div></details>':'';
  /* [staff] 各科室医生意见：并排署名（grid2 卡片：科室 chip + 医生 + 时间 + 内容） */
  const ops=(c.opinions||[]);
  const opsSec=withOps?'<h4 style="margin:12px 0 6px">'+ICON.staff+' 各科室医生意见（并排署名 · 仅建议权）</h4>'
    +(ops.length?'<div class="grid2">'+ops.map(o=>'<div class="card" style="box-shadow:none">'
      +'<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap"><span class="chip mid"><span class="dot"></span>'+esc(o.dept||'—')+'</span><b>'+esc(o.doctor||'')+'</b><span class="src num" style="margin-left:auto">'+fmtTs(o.ts)+'</span></div>'
      +'<div class="txt" style="margin-top:6px;white-space:pre-wrap">'+esc(o.content||'')+'</div></div>').join('')+'</div>'
      :'<div class="src">尚未收到科室医生意见。</div>'):'';
  return '<div style="margin-top:8px">'+abstract+'</div>'+aiSec+opsSec;}
function renderMyConsults(){
  const b=document.getElementById('myConsultsBody');
  const ini=(myConsultData&&myConsultData.initiated)||[];
  const part=(myConsultData&&myConsultData.participated)||[];
  if(!ini.length&&!part.length){b.innerHTML='<div class="empty">'+ICON.mdt+'<div>暂无会诊记录。</div>'
    +'<div class="lead" style="margin:8px auto 0">在「多智能体会诊」页填写病例后，可一键「发起真实跨科室会诊」分发给目标科室医生。</div></div>';return;}
  const cards=ini.map(c=>{
    const open=c.status==='open';
    const closeBtn=open?'<div style="margin-top:10px"><button class="btn sm primary" onclick="closeConsult(\''+esc(c.id)+'\')">结束会诊并汇总</button></div>':'';
    /* 任务5：三段式详情（[doc]摘要默认展开 → [analysis]各专科 AI 意见默认折叠 → [staff]医生意见并排署名）；
       question 内嵌 AI 底稿/AI 初步意见/会诊汇总不再重复展示（会诊汇总内容已由三段覆盖）；
       轮 B1：详情渲染随单影像缩略图（consultThumbs，点击 showImg 放大） */
    return '<div class="card">'
      +'<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap"><h3 style="margin:0">会诊 '+esc(c.id)+'</h3>'
      +(open?'<span class="chip warn"><span class="dot"></span>进行中</span>':'<span class="chip ok"><span class="dot"></span>已结束</span>')
      +'<span class="src num" style="margin-left:auto">发起于 '+fmtTs(c.created_at)+'</span></div>'
      +'<div style="margin-top:6px;display:flex;gap:6px;flex-wrap:wrap;align-items:center"><span class="src">受邀科室：</span>'
      +(c.target_depts||[]).map(d=>'<span class="chip mid"><span class="dot"></span>'+esc(d)+'</span>').join('')+'</div>'
      +consultDetailBlocks(c)+consultThumbs(c)+closeBtn
      +'</div>';}).join('');
  const partHtml=part.length?'<div class="panel" style="margin-top:4px"><h3>我参与过的会诊（作为受邀科室填写过意见）</h3><ul class="feed">'
    +part.map(c=>'<li><span class="t num">'+fmtTs(c.created_at)+'</span><span class="e">'+esc(String(c.question||'').slice(0,60))+'</span>'
      +'<span class="a">发起人 '+esc(c.initiator||'—')+'</span></li>').join('')+'</ul></div>':'';
  b.innerHTML='<h3 style="margin:0">我发起的（'+ini.length+'）</h3>'+cards+partHtml;}
async function closeConsult(id){
  confirmDanger('结束会诊并汇总',
    '将结束该会诊（各科医生不再能填意见），并把 AI 意见与各科医生意见汇总（并排署名）同步到会诊记录。确认结束？','确认结束',async()=>{
    try{const r=await fetch('/api/v1/medical/consults/'+encodeURIComponent(id)+'/close',{method:'POST',headers:auth()});
      if(r.status===401){logout();return;}
      if(r.ok){toast('会诊已结束并汇总');loadMyConsults();}
      else{const d=await r.json();toast('操作失败：'+(d.detail||r.status));}
    }catch(e){toast('操作失败：'+e.message);}});}

/* ---- 阶段3：开药工作台（rx 视图，取代原 drug 药物助手导航位） ----
   流程：病例粘贴 → POST /prescriptions/suggest（字典约束推荐 + 相互作用硬校验）→
   勾选/自定义加药（字典外需确认）→ 已选药单（可删/可编辑剂量频次）→
   提交 POST /prescriptions（高危组合必须附联用理由 contraindication_reason）。
   被驳回处方走「重写」：回填表单后 POST /prescriptions/{rid}/rewrite 自动重提
   （rejected→draft→pending_pharm 一次完成，不产生重复处方）。免责声明复用 DRUG_DISCLAIMER。 */
let rxDictCache={drugs:[]};   /* 药品字典缓存（GET /drug/dict；搜索下拉与字典外判定用） */
let rxSuggests=[], rxBlocked=false, rxConflicts=[], rxWarnings=[];  /* suggest 结果与校验状态 */
let rxSelected=[];            /* 已选药单 [{name,dose,freq,note}] */
let rxEditId='', rxEditForced=false, rxEditReason='';  /* 改写重提模式：目标处方 id / 原高危标记 / 原联用理由 */
let rxManualConfirm='';       /* 字典外手动加药：二次确认记忆（同药名再次点击才加入） */
let rxTabCur='compose';       /* 工作台内 tab：compose=开药 / mine=我的处方 */
let rxMineCache=[];           /* 我的处方缓存（GET /prescriptions/mine） */
let rxRejectedCount=0;        /* 开药审核 UX 问题2：rx 导航红点角标=本人被驳回处方数（本地清零态，refreshRxBadge 按服务端刷新） */
const RX_STATUS={pending_pharm:['待药剂科审核','warn'],approved:['已通过','ok'],
  rejected:['已驳回','bad'],draft:['草稿','']};  /* 状态徽章：黄/绿/红/灰 */
async function loadRx(){
  /* 进入开药工作台：免责声明填充 + 字典缓存懒加载（失败不阻塞表单，提交时服务端仍硬校验） */
  const dd=document.getElementById('rxDisclaimer');if(dd)dd.textContent='⚠ '+DRUG_DISCLAIMER;
  if(!rxDictCache.drugs.length){
    try{const d=await apiGet('/drug/dict');rxDictCache={drugs:d.drugs||[]};}catch(e){/* 静默：不阻塞开药主流程 */}}
  rxDictFilter(document.getElementById('rxDictSearch')?document.getElementById('rxDictSearch').value:'');
  renderRxBlock();renderRxSelected();
  refreshRxBadge();  /* 开药审核 UX 问题2：切换到 rx 视图即刷新被驳回角标 */
}
async function refreshRxBadge(){  /* 开药审核 UX 问题2：rx 导航红点角标数据刷新——/prescriptions/mine 统计本人 rejected 处方数（仅 doctor：mine 为 doctor 专属端点，其他角色静默跳过） */
  if(ROLE!=='doctor')return;
  try{const d=await apiGet('/prescriptions/mine');
    rxRejectedCount=(d.items||[]).filter(i=>i.status==='rejected').length;
    if(!(cur==='rx'&&rxTabCur==='mine'))renderRail();  /* 正看着「我的处方」列表时保持清零态（点进即视为已读），离开后下次 renderRail 显示真值 */
  }catch(e){/* 静默：角标刷新失败不干扰开药主流程 */}
}
function switchRxTab(t){  /* 工作台内 tab 切换（我的处方在进入时拉取最新） */
  rxTabCur=t;
  document.getElementById('rxComposePane').style.display=(t==='compose')?'':'none';
  document.getElementById('rxMinePane').style.display=(t==='mine')?'':'none';
  document.getElementById('rxTabCompose').className='btn sm '+(t==='compose'?'primary':'ghost');
  document.getElementById('rxTabMine').className='btn sm '+(t==='mine'?'primary':'ghost');
  if(t==='mine'){rxRejectedCount=0;renderRail();loadRxMine();}  /* 开药审核 UX 问题2：点进「我的处方」清零被驳回角标（本地状态） */
  else renderRail();
}
async function rxSuggest(){  /* ① 生成开药建议：POST /prescriptions/suggest */
  const caseText=document.getElementById('rxCase').value.trim();
  if(caseText.length<20||caseText.length>5000){toast('病例摘要需 20-5000 字');return;}
  const btn=document.getElementById('rxSuggestBtn');btn.disabled=true;
  document.getElementById('rxSuggestOut').innerHTML='<div class="card"><div class="sk" style="height:16px;width:40%;margin-bottom:10px"></div><div class="sk"></div></div>';
  try{
    const d=await apiPost('/prescriptions/suggest',{case_text:caseText});
    if(d&&d.detail){const det=d.detail;throw new Error(Array.isArray(det)?det.map(x=>x.msg||x).join('；'):String(det));}
    rxSuggests=(d.suggestions||[]).map(s=>({...s,pick:true}));  /* 推荐默认全选（勾选框可取消） */
    rxBlocked=!!d.blocked;rxConflicts=d.conflicts||[];rxWarnings=d.warnings||[];
    renderRxSuggest();renderRxBlock();
  }catch(e){document.getElementById('rxSuggestOut').innerHTML='<div class="card">⚠ 建议生成失败：'+esc(e.message)+'</div>';}
  finally{btn.disabled=false;rxSyncSubmit();}
}
function renderRxSuggest(){  /* ② 推荐结果卡片（勾选加入药单）+ 中危组合警示条（黄色） */
  const el=document.getElementById('rxSuggestOut');let html='';
  /* 本批问题1修复：渲染时对 checked=true 的建议项同步调用加入药单函数（rxPick）——
     勾选框默认选中此前只是 UI 状态、不程序化触发加入逻辑，已选药单为空（用户取消
     勾选再勾选才加入）。去重：药名+剂量都相同的项不重复加（重复渲染幂等）。 */
  rxSuggests.forEach((s,i)=>{
    if(!s.pick)return;
    if(rxSelected.some(d=>d.name===s.name&&(d.dose||'')===(s.dose||'')))return;
    rxPick(i,true);});
  if(rxWarnings.length){
    html+=`<div class="card" style="border-left:3px solid var(--warn)"><b>⚠ 中危联用警示</b>
      <ul class="feed" style="margin-top:6px">${rxWarnings.map(w=>`<li><span class="e">${esc((w.pair||[]).join(' × '))}</span><span class="src">${esc(w.note||'')}</span></li>`).join('')}</ul></div>`;}
  if(rxSuggests.length){
    html+=`<div class="card"><h3>AI 开药建议（勾选加入药单）</h3>`+
      rxSuggests.map((s,i)=>`<label style="display:flex;gap:8px;align-items:flex-start;margin:8px 0;cursor:pointer">
        <input type="checkbox" ${s.pick?'checked':''} onchange="rxPick(${i},this.checked)">
        <span><b>${esc(s.name)}</b> <span class="src">${esc(s.dose||'剂量待定')} · ${esc(s.freq||'频次待定')}</span>
        <div class="src">${esc(s.reason||'')}</div></span></label>`).join('')+`</div>`;
  }else if(!rxBlocked&&!rxWarnings.length){
    html=`<div class="card"><div class="src">暂无推荐结果：可直接在下方自定义加药。</div></div>`;}
  el.innerHTML=html;
}
function rxPick(i,checked){  /* 勾选/取消推荐药 → 同步已选药单 */
  const s=rxSuggests[i];if(!s)return;
  s.pick=checked;
  rxSelected=rxSelected.filter(d=>d.name!==s.name);
  if(checked)rxSelected.push({name:s.name,dose:s.dose||'',freq:s.freq||'',note:s.reason||'AI 推荐'});
  renderRxSelected();
}
function renderRxBlock(){  /* 高危联用阻断条（红色）：suggest blocked 或改写原处方为强制开立 */
  const el=document.getElementById('rxBlockPanel');if(!el)return;
  if(rxBlocked){
    el.style.display='';
    el.innerHTML=`<div style="border-left:3px solid var(--danger);padding-left:10px">
      <b style="color:var(--danger)">⚠ 高危联用已阻断</b>
      <div class="src" style="margin-top:4px">推荐组合存在高危相互作用；若最终药单仍含以下组合，必须填写联用理由才能提交（服务端硬校验）：</div>
      <ul class="feed" style="margin-top:6px">${rxConflicts.map(c=>`<li><span class="e">${esc((c.pair||[]).join(' × '))}（${esc(c.severity||'高危')}）</span><span class="src">${esc(c.note||'')}</span></li>`).join('')}</ul>
      <input class="inp" id="rxReason" style="margin-top:8px" placeholder="联用理由（填了才允许提交，如：危及生命的感染且无替代方案）" oninput="rxSyncSubmit()">
    </div>`;
  }else if(rxEditForced){  /* 改写被驳回的高危强制开立处方：保留联用理由输入（回填原理由） */
    el.style.display='';
    el.innerHTML=`<div style="border-left:3px solid var(--danger);padding-left:10px">
      <b style="color:var(--danger)">⚠ 原处方为高危强制开立</b>
      <div class="src" style="margin-top:4px">若改写后药单仍含高危联用组合，提交时必须填写联用理由（服务端硬校验）：</div>
      <input class="inp" id="rxReason" style="margin-top:8px" placeholder="联用理由（填了才允许提交）" oninput="rxSyncSubmit()">
    </div>`;
    const r=document.getElementById('rxReason');if(r)r.value=rxEditReason||'';
  }else{el.style.display='none';el.innerHTML='';}
  rxSyncSubmit();
}
function rxInDict(name){  /* 字典内判定（规范名/别名/商品名，与后端别名图同源数据） */
  return (rxDictCache.drugs||[]).some(d=>d.name===name||(d.aliases||[]).includes(name)||(d.brand_names||[]).includes(name));}
function rxDictFilter(q){  /* ③ 字典搜索下拉（前端过滤，最多 12 条防长列表） */
  const ul=document.getElementById('rxDictList');if(!ul)return;
  const s=String(q||'').trim().toLowerCase();
  if(!s){ul.innerHTML='<li><span class="a">输入关键词搜索药品字典（结果点「加入药单」）</span></li>';return;}
  const hits=(rxDictCache.drugs||[]).filter(d=>{
    const hay=[d.name,d.category,...(d.aliases||[]),...(d.brand_names||[])].join(' ').toLowerCase();
    return hay.includes(s);}).slice(0,12);
  ul.innerHTML=hits.length?hits.map(d=>`<li style="display:flex;gap:8px;align-items:center">
      <span class="e">${esc(d.name)}</span><span class="src">${esc(d.category||'其他')}</span>
      <button class="btn sm secondary" style="margin-left:auto" data-name="${esc(d.name)}" onclick="rxAddDict(this.dataset.name)">加入药单</button></li>`).join('')
    :'<li><span class="a">无匹配药品（可在下方手动输入，字典外需确认）</span></li>';
}
function rxAddDict(name){  /* 字典药加入药单（默认空剂量频次，药单内可编辑） */
  if(rxSelected.some(d=>d.name===name)){toast(name+' 已在药单');return;}
  const src=(rxDictCache.drugs||[]).find(d=>d.name===name);
  rxSelected.push({name,dose:'',freq:'',note:'字典添加'+(src&&src.category?(' · '+src.category):'')});
  renderRxSelected();toast('已加入药单：'+name);
}
function rxAddManual(){  /* 手动加药：字典外药名首次点击弹确认条，同药名再次点击才加入 */
  const name=document.getElementById('rxAddName').value.trim();
  const dose=document.getElementById('rxAddDose').value.trim();
  const freq=document.getElementById('rxAddFreq').value.trim();
  const tip=document.getElementById('rxOutOfDictTip');
  if(!name){toast('请输入药名');return;}
  if(rxSelected.some(d=>d.name===name)){toast(name+' 已在药单');return;}
  if(!rxInDict(name)&&rxManualConfirm!==name){
    tip.style.display='';tip.textContent='该药不在字典，药剂科将重点审核；确认仍要加入请再次点击「添加」';
    rxManualConfirm=name;return;}
  rxManualConfirm='';if(tip)tip.style.display='none';
  rxSelected.push({name,dose,freq,note:'手动输入'+(rxInDict(name)?'':' · 字典外')});
  ['rxAddName','rxAddDose','rxAddFreq'].forEach(id=>{document.getElementById(id).value='';});
  renderRxSelected();
}
function renderRxSelected(){  /* ④ 已选药单（可删 / 可编辑剂量频次；字典外药名黄色标记） */
  const el=document.getElementById('rxSelected');if(!el)return;
  el.innerHTML=rxSelected.length?rxSelected.map((d,i)=>`<div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:6px 0">
      <b style="min-width:110px">${esc(d.name)}</b>
      <span class="chip ${rxInDict(d.name)?'mid':'warn'}"><span class="dot"></span>${rxInDict(d.name)?'字典内':'字典外'}</span>
      <input class="inp" style="max-width:130px" placeholder="剂量（如 0.5g）" value="${esc(d.dose)}" oninput="rxEditDrug(${i},'dose',this.value)">
      <input class="inp" style="max-width:130px" placeholder="频次（如 qd）" value="${esc(d.freq)}" oninput="rxEditDrug(${i},'freq',this.value)">
      <button class="btn sm ghost" onclick="rxDelDrug(${i})">移除</button>
      ${d.note?`<span class="src">${esc(d.note)}</span>`:''}</div>`).join('')
    :'<div class="src">药单为空：勾选上方 AI 建议，或通过字典搜索/手动输入添加药品。</div>';
  rxSyncSubmit();
}
function rxEditDrug(i,k,v){if(rxSelected[i])rxSelected[i][k]=v;}  /* 药单内联编辑剂量/频次 */
function rxDelDrug(i){rxSelected.splice(i,1);renderRxSelected();}  /* 药单内移除 */
function rxLiveConflicts(){  /* 当前药单命中的高危组合（blocked 只约束仍留存在药单内的组合） */
  const names=new Set(rxSelected.map(d=>d.name));
  return rxConflicts.filter(c=>(c.pair||[]).every(n=>names.has(n)));}
function rxSyncSubmit(){  /* 「提交开药」按钮可用性：高危组合仍存在且未填联用理由 → 禁用 */
  const btn=document.getElementById('rxSubmitBtn');if(!btn)return;
  const reason=(document.getElementById('rxReason')||{value:''}).value.trim();
  btn.disabled=rxLiveConflicts().length>0&&!reason;
  const banner=document.getElementById('rxEditBanner');
  if(banner){banner.style.display=rxEditId?'':'none';
    if(rxEditId)banner.textContent='正在改写被驳回处方 '+rxEditId+'：修改后提交将自动重新送药剂科审核（不产生新处方）';}
}
async function rxSubmit(){  /* ⑥ 提交开药：新建 POST /prescriptions；改写重提 POST /prescriptions/{rid}/rewrite */
  const caseText=document.getElementById('rxCase').value.trim();
  if(caseText.length<20||caseText.length>5000){toast('病例摘要需 20-5000 字');return;}
  if(!rxSelected.length){toast('请至少选择一种药品');return;}
  const reason=(document.getElementById('rxReason')||{value:''}).value.trim();
  if(rxLiveConflicts().length&&!reason){toast('存在高危联用组合：必须填写联用理由才能提交');return;}
  const outDict=rxSelected.filter(d=>!rxInDict(d.name)).map(d=>d.name);
  if(outDict.length){toast('药品「'+outDict.join('、')+'」不在药品字典，服务端硬校验将拒绝提交，请移除后再试');return;}
  const btn=document.getElementById('rxSubmitBtn');btn.disabled=true;
  const body={case_text:caseText,
    drugs:rxSelected.map(d=>({name:d.name,dose:d.dose,freq:d.freq,note:d.note||''})),
    contraindication_reason:reason};
  try{
    const item=rxEditId
      ?await apiPost('/prescriptions/'+encodeURIComponent(rxEditId)+'/rewrite',body)  /* 改写重提（rejected→draft→pending_pharm） */
      :await apiPost('/prescriptions',body);  /* 新建（直送药剂科 → pending_pharm） */
    if(item&&item.detail){const det=item.detail;throw new Error(Array.isArray(det)?det.map(x=>x.msg||x).join('；'):String(det));}
    toast(rxEditId?'处方已改写并重新送审':'处方已提交，等待药剂科审核');
    rxResetCompose();switchRxTab('mine');refreshRxBadge();  /* 开药审核 UX 问题2：提交/重提后刷新角标（重提使驳回数变化；正看「我的处方」时保持清零态） */
  }catch(e){toast('提交失败：'+e.message);}
  finally{btn.disabled=false;rxSyncSubmit();}
}
function rxResetCompose(){  /* 提交成功后清空工作台表单与状态 */
  document.getElementById('rxCase').value='';
  rxSuggests=[];rxBlocked=false;rxConflicts=[];rxWarnings=[];rxSelected=[];rxEditId='';rxEditForced=false;rxEditReason='';rxManualConfirm='';
  document.getElementById('rxSuggestOut').innerHTML='';
  const tip=document.getElementById('rxOutOfDictTip');if(tip)tip.style.display='none';
  ['rxAddName','rxAddDose','rxAddFreq'].forEach(id=>{const el=document.getElementById(id);if(el)el.value='';});
  renderRxBlock();renderRxSelected();
}
/* ---- 阶段3.3：我的处方（工作台内 tab；GET /prescriptions/mine） ---- */
async function loadRxMine(){
  const el=document.getElementById('rxMineList');if(!el)return;
  el.innerHTML='<div class="card"><div class="sk" style="height:16px;width:30%"></div></div>';
  try{
    const d=await apiGet('/prescriptions/mine');
    rxMineCache=d.items||[];
    el.innerHTML=rxMineCache.length?rxMineCache.map(rxMineCard).join(''):'<div class="card"><div class="src">暂无处方记录。</div></div>';
  }catch(e){el.innerHTML='<div class="card">⚠ 处方加载失败：'+esc(e.message)+'</div>';}
}
function rxMineCard(it){  /* 状态徽章色：pending_pharm 黄/approved 绿/rejected 红/draft 灰；rejected 显示药剂意见+重写；开药审核 UX 问题3：签发/驳回卡显示审核人+操作时间（pharm_reviewed_at，旧记录无值则只显示审核人） */
  const st=RX_STATUS[it.status]||[it.status||'未知',''];
  const rev=((it.status==='approved'||it.status==='rejected')&&it.pharm_reviewer)
    ?`<div class="src" style="margin-top:6px">审核人 ${esc(it.pharm_reviewer)}${it.pharm_reviewed_at?' · '+fmtTs(it.pharm_reviewed_at):''}</div>`:'';
  const rej=it.status==='rejected'
    ?`<div class="src" style="margin-top:6px;border-left:3px solid var(--danger);padding-left:8px">药剂科意见：${esc(it.pharm_opinion||'（未填写）')}</div>
      <button class="btn sm secondary" style="margin-top:6px" onclick="rxStartRewrite('${esc(it.id)}')">重写</button>`:'';
  return `<div class="card" data-rxid="${esc(it.id)}">
    <div style="display:flex;gap:8px;align-items:center;flex-wrap:wrap"><span class="chip ${st[1]}"><span class="dot"></span>${esc(st[0])}</span>
      <b class="num">${esc(it.id)}</b><span class="src">${fmtTs(it.created_at)} · 药品 ${esc(String((it.drugs||[]).length))} 种${it.forced_high_risk?' · <span style="color:var(--danger)">高危强制开立</span>':''}</span></div>
    <div class="txt" style="margin-top:6px">${esc((it.case_text||'').slice(0,80))}${(it.case_text||'').length>80?'…':''}</div>
    <ul class="feed" style="margin-top:4px">${(it.drugs||[]).map(d=>`<li><span class="e">${esc(d.name)}</span><span class="src">${esc(d.dose||'')} ${esc(d.freq||'')}</span></li>`).join('')}</ul>
    ${rev}${rej}</div>`;
}
function rxStartRewrite(id){  /* 重写：回填 case_text/drugs（含原联用理由）到工作台表单 → 提交走 rewrite 自动重提 */
  const it=(rxMineCache||[]).find(x=>x.id===id);if(!it){toast('未找到该处方');return;}
  rxEditId=id;rxEditForced=!!it.forced_high_risk;rxEditReason=it.contraindication_reason||'';
  document.getElementById('rxCase').value=it.case_text||'';
  rxSelected=(it.drugs||[]).map(d=>({name:d.name,dose:d.dose||'',freq:d.freq||'',note:d.note||''}));
  rxSuggests=[];rxBlocked=false;rxConflicts=[];rxWarnings=[];rxManualConfirm='';
  document.getElementById('rxSuggestOut').innerHTML='';
  const tip=document.getElementById('rxOutOfDictTip');if(tip)tip.style.display='none';
  switchRxTab('compose');renderRxBlock();renderRxSelected();
  window.scrollTo(0,0);
  toast('已回填处方内容：修改后点「提交开药」自动重新送审');
}

