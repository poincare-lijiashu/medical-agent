import{e as r}from"./html-D0ZdeZKH.js";import{H as n}from"./index-DJwnGVCI.js";const a=`
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
`;function d(t){const e=window.open("","_blank","width=820,height=900");if(!e)return!1;const o=(a+(t.css??"")).replace(/<\s*\/\s*(style|script)/gi,""),i=t.body.replace(/<\s*\/?\s*script/gi,"");return e.document.write(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${r(t.title)}</title><style>${o}</style></head><body>${i}</body></html>`),e.document.close(),e.focus(),e.print(),e.onafterprint=()=>e.close(),!0}const s={receipt:"RECEIPT",consent:"CONSENT",prescription:"PRESCRIPTION",pivasCard:"PIVAS_CARD",inpatientFinance:"INPATIENT_FINANCE",wristband:"WRISTBAND",nursingSheet:"NURSING_SHEET"};function l(t){t.docNo&&n("/clinical/print-events",{docType:s[t.docType],docNo:t.docNo,targetType:t.targetType,targetId:t.targetId,patientNo:t.patientNo}).catch(()=>{})}export{d as p,l as r};
