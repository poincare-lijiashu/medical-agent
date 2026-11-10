const n={"<":"&lt;",">":"&gt;","&":"&amp;",'"':"&quot;","'":"&#39;"};function r(e){return String(e??"").replace(/[<>&"']/g,t=>n[t]??t)}export{r as e};
