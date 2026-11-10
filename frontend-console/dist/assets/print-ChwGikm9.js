import{e as n}from"./html-D0ZdeZKH.js";const r=`
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
`;function a(e){const t=window.open("","_blank","width=820,height=900");if(!t)return!1;const o=(r+(e.css??"")).replace(/<\s*\/\s*(style|script)/gi,""),i=e.body.replace(/<\s*\/?\s*script/gi,"");return t.document.write(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${n(e.title)}</title><style>${o}</style></head><body>${i}</body></html>`),t.document.close(),t.focus(),t.print(),t.onafterprint=()=>t.close(),!0}export{a as p};
