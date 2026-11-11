import{e as l}from"./html-D0ZdeZKH.js";import{I as p,J as f,K as m}from"./index-DlJdyFe9.js";const y=`
@page { size: A4; margin: 16mm; }
html, body { margin: 0; padding: 0; }
body {
  font: 14px/1.6 -apple-system, "Microsoft YaHei", "PingFang SC", "Helvetica Neue", sans-serif;
  color: #111;
}
h1 { font-size: 20px; margin: 0 0 4px; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #ddd; vertical-align: top; }
th { width: 32%; color: #444; font-weight: 500; }
.muted { color: #666; }
.foot { margin-top: 24px; font-size: 12px; color: #666; }
@media print {
  body { -webkit-print-color-adjust: exact; print-color-adjust: exact; }
  .no-print { display: none !important; }
}
`;function T(t){const e=window.open("","_blank","width=820,height=900");if(!e)return!1;const n=(y+(t.css??"")).replace(/<\s*\/\s*(style|script)/gi,""),o=t.body.replace(/<\s*\/?\s*script/gi,"");return e.document.write(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${l(t.title)}</title><style>${n}</style></head><body>${o}</body></html>`),e.document.close(),e.focus(),e.print(),e.onafterprint=()=>e.close(),!0}const d={receipt:"RECEIPT",consent:"CONSENT",prescription:"PRESCRIPTION",pivasCard:"PIVAS_CARD",inpatientFinance:"INPATIENT_FINANCE",wristband:"WRISTBAND",nursingSheet:"NURSING_SHEET",dischargeReadiness:"DISCHARGE_READINESS",handover:"HANDOVER"},g=45440,N=6e4;let s=null,r=null;function h(t,e,n){if(r&&r.docType===t)return r.promise;const o=m({docType:t,docNo:e,message:n}).then(i=>(s={docType:t,reason:i,at:Date.now()},i)),a={docType:t,promise:o};return r=a,o.finally(()=>{r===a&&(r=null)}),o}function S(t){t.docNo&&c(t,!0).catch(()=>{})}async function c(t,e){try{await p("/clinical/print-events",{docType:d[t.docType],docNo:t.docNo,targetType:t.targetType,targetId:t.targetId,patientNo:t.patientNo,reason:t.reason})}catch(n){if(e&&n instanceof f&&n.code===g){const o=d[t.docType],a=s&&s.docType===o&&Date.now()-s.at<N?s.reason:null;if(a){await c({...t,reason:a},!1);return}const i=await h(o,t.docNo,n.message);await c({...t,reason:i},!1);return}}}export{T as p,S as r};
