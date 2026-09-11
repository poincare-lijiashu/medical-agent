async function loadConfig(){try{const r=await fetch('/api/v1/medical/config',{headers:auth()});if(r.ok){const c=await r.json();CFG.chat=c.chat_model||CFG.chat;CFG.vl=c.vl_model||CFG.vl;
CFG.full=!!c.qc_auto_sign_full;  /* 任务3：留痕模式开关——qc/admin 审核中心转只读档案 */
const dot=document.getElementById('kbDot');
if(dot){const ok=!!(c.milvus&&c.milvus.connected);dot.style.display='inline-block';dot.style.background=ok?'var(--ok)':'var(--danger)';dot.title=ok?'知识库已连接':'知识库未连接（自动重试中，问答走降级路径）';}}}catch(e){}}

const token=()=>sessionStorage.getItem('med_token')||'';
const auth=()=>({'Content-Type':'application/json','Authorization':'Bearer '+token()});
async function apiPost(path,body){const r=await fetch('/api/v1/medical'+path,{method:'POST',headers:auth(),body:JSON.stringify(body)});if(r.status===401){logout();throw new Error('401');}return r.json();}
function apiGet(p){return fetch('/api/v1/medical'+p,{headers:auth()}).then(r=>{if(r.status===401){logout();throw new Error('401');}return r.json();});}
