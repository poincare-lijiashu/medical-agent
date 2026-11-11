import{e as d}from"./html-D0ZdeZKH.js";import{I as l,J as p,K as m}from"./index-Dde1ozpj.js";const f=`
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
`;function E(t){const e=window.open("","_blank","width=820,height=900");if(!e)return!1;const o=(f+(t.css??"")).replace(/<\s*\/\s*(style|script)/gi,""),a=t.body.replace(/<\s*\/?\s*script/gi,"");return e.document.write(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${d(t.title)}</title><style>${o}</style></head><body>${a}</body></html>`),e.document.close(),e.focus(),e.print(),e.onafterprint=()=>e.close(),!0}const c={receipt:"RECEIPT",consent:"CONSENT",prescription:"PRESCRIPTION",pivasCard:"PIVAS_CARD",inpatientFinance:"INPATIENT_FINANCE",wristband:"WRISTBAND",nursingSheet:"NURSING_SHEET"},g=45440,y=6e4;let n=null;function T(t){t.docNo&&r(t,!0).catch(()=>{})}async function r(t,e){try{await l("/clinical/print-events",{docType:c[t.docType],docNo:t.docNo,targetType:t.targetType,targetId:t.targetId,patientNo:t.patientNo,reason:t.reason})}catch(o){if(e&&o instanceof p&&o.code===g){const a=c[t.docType],s=n&&n.docType===a&&Date.now()-n.at<y?n.reason:null;if(s){await r({...t,reason:s},!1);return}const i=await m({docType:a,docNo:t.docNo,message:o.message});n={docType:a,reason:i,at:Date.now()},await r({...t,reason:i},!1);return}}}export{E as p,T as r};
