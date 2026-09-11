const ICON={
  literature:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 5a2 2 0 0 1 2-2h12v18H6a2 2 0 0 1-2-2Z"/><path d="M8 3v18"/></svg>',
  rx:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="8" width="18" height="8" rx="4" transform="rotate(45 12 12)"/><path d="M8.5 8.5l7 7"/></svg>',
  imaging:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M9 3v18"/></svg>',
  case:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z"/><path d="M9 8h6M9 12h6M9 16h4"/></svg>',
  mdt:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="9" cy="7" r="3"/><circle cx="17" cy="9" r="2.5"/><path d="M3 20a6 6 0 0 1 12 0M14 20a5 5 0 0 1 7-3"/></svg>',
  myconsults:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M4 5h16v14H4z"/><path d="M8 9h8M8 13h5"/><path d="M4 19l5-4 3 2 4-3 4 3"/></svg>',
  review:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M9 11l3 3 8-8"/><path d="M20 12v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h9"/></svg>',
  arch:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M12 3 2 8l10 5 10-5-10-5Z"/><path d="M2 13l10 5 10-5"/><path d="M2 18l10 5 10-5" stroke-dasharray="3 3"/></svg>',
  qc:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M9 12l2 2 4-4"/><circle cx="12" cy="12" r="9"/></svg>',
  kb:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><ellipse cx="12" cy="5.5" rx="8" ry="3"/><path d="M4 5.5v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/><path d="M4 11.5v6c0 1.7 3.6 3 8 3s8-1.3 8-3v-6"/></svg>',
  data:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 8h.01M7 17h.01"/></svg>',
  overview:'<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/></svg>',
  /* 任务2：会诊详情三段式图标（替换原 emoji 图钉/显微镜/医生符号，与全站 ICON SVG 风格统一）——
     doc=摘要/汇总（文件）、analysis=各专科 AI 意见（分析）、staff=各科室医生意见（人员）。
     内联 width/height：h4 内无 CSS 尺寸规则，防 SVG 按替换元素默认尺寸撑爆布局。 */
  doc:'<svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4"/><path d="M9 12h6M9 16h4"/></svg>',
  analysis:'<svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 12h3l2.5-6 4 12 2.5-6h6"/></svg>',
  staff:'<svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/></svg>',
};
const AGENTS={
  literature:{name:'医学文献助手',desc:'查证：答案附文献出处，可点击查原文',endpoint:'/literature/ask',imageOk:false,hint:'例：二甲双胍是一线治疗吗？'},
  imaging:{name:'影像辅助阅片',desc:'上传影像，AI 给出初步所见',endpoint:'/imaging/ask',imageOk:true,hint:'上传 CT/DR/病理图片后提问'},
  case:{name:'多模态病例总结',desc:'粘贴病例，生成摘要与鉴别清单',endpoint:'/case/ask',imageOk:true,hint:'粘贴病例要点，可附图片'},
};
let cur='literature', curImages=[];
let CFG={chat:'模型',vl:'多模态模型'};
let ROLE='doctor';
const ROLE_NAV={doctor:['overview','literature','rx','imaging','case','mdt','myconsults','qc','review'],
  pharmacist:['overview','literature','rx','qc','review'],
  admin:['overview','kb','qc','review','data','arch'],
  qc:['overview','literature','rx','qc','review']};  /* F2：qc 对 doctor/pharmacist 开放（质控面向病案/质控医师，demo 阶段 doctor 兼任）；E 角色细分：qc（质控员）可见质控工作台+审核中心，admin 既有入口不变，doctor 行为不变；阶段3：drug 药物助手独立入口删除，导航位改为 rx 开药工作台 */
const chatHist={}, chatInput={};  /* chatInput：任务2 每个 agent 视图独立的输入框草稿（切视图保存/恢复，不再串视图） */
function pushHist(k,e){(chatHist[k]=chatHist[k]||[]).push(e);}

/* 免责声明常量（与后端 backend/core/drug_dict.py 的 DISCLAIMER 逐字同源，
   由 tests/test_drug_dict_admin.py 锁定一致性）：阶段3 起展示在开药工作台（rx 视图）免责条 */
const DRUG_DISCLAIMER='药品数据由 AI 辅助生成，临床使用前必须经执业药师核对；本系统仅供辅助决策，不构成医疗建议。';
