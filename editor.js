(()=>{if(window.__plsEditorLoaded)return;window.__plsEditorLoaded=true;
const KEY="plsObjectsV1",TMPL="plsTemplatesV1",NS="http://www.w3.org/2000/svg",q=s=>document.querySelector("#pls-editor "+s);
let objects=JSON.parse(localStorage.getItem(KEY)||"null")||[
{id:"brand",type:"text",text:"PRASAD APPARELS",x:18,y:24,w:460,h:36,size:25,bold:true,align:"center"},
{id:"style",type:"text",text:"STYLE: PA-102",x:22,y:95,w:235,h:30,size:19},
{id:"size",type:"text",text:"28",x:350,y:112,w:115,h:65,size:52,bold:true,align:"center"},
{id:"barcode",type:"barcode",text:"PA10228",x:75,y:220,w:350,h:55,size:16}
];
let selected=null,selectedIds=new Set(),multiSelectMode=false,undo=[],redo=[],drag=null,activeTab="object",page={w:50,h:30,name:"Custom 50 × 30 mm"};
const save=()=>localStorage.setItem(KEY,JSON.stringify(objects)),checkpoint=()=>{undo.push(JSON.stringify(objects));if(undo.length>40)undo.shift();redo=[];};
const mk=(tag,attrs={})=>{let n=document.createElementNS(NS,tag);Object.entries(attrs).forEach(([k,v])=>n.setAttribute(k,v));return n;};
function launch(){if(document.querySelector("#pls-launch"))return;let b=document.createElement("button");b.id="pls-launch";b.className="primary";b.textContent="CREATE LABEL  ·  OPEN EDITOR";b.style.cssText="width:100%;padding:16px;margin:12px 0;font-size:15px";let host=document.querySelector(".workflow-panel[data-step='1']");if(host){host.appendChild(b);b.onclick=open;}}
function open(){let root=document.querySelector("#pls-editor");if(root){root.style.display="flex";render();return;}
root=document.createElement("div");root.id="pls-editor";
root.innerHTML=\`<style>
#pls-editor{position:fixed;inset:0;z-index:999999;background:#eef0f3;color:#202124;display:flex;flex-direction:column;font:13px Arial,sans-serif;--ink:#17191c;--line:#dfe2e7}
#pls-editor *{box-sizing:border-box}#pls-editor button{font:600 12px Arial;border:1px solid var(--line);background:#fff;color:#222;border-radius:8px;padding:9px 11px;white-space:nowrap;cursor:pointer}
#pls-editor button:active{background:#e8edf7}#pls-editor .top{height:52px;flex:none;background:#17191c;color:#fff;display:flex;align-items:center;justify-content:space-between;padding:0 12px;gap:8px}
#pls-editor .brandmark{font-size:13px;font-weight:800;letter-spacing:.6px}#pls-editor .top-actions{display:flex;gap:6px}#pls-editor .top button{background:#30343a;color:#fff;border-color:#4b5058}
#pls-editor .top .save{background:#fff;color:#17191c}
#pls-editor .formatbar{background:#fff;border-bottom:1px solid var(--line);padding:7px 10px;display:flex;align-items:center;gap:7px;overflow:auto;flex:none}
#pls-editor .formatbar select{border:1px solid var(--line);border-radius:7px;padding:8px;background:#fff;font-size:12px}
#pls-editor .formatbar .dim{font-size:11px;color:#69717d;white-space:nowrap}
#pls-editor .tools{display:flex;gap:7px;padding:9px 10px;background:#f8f9fb;border-bottom:1px solid var(--line);overflow-x:auto;flex:none}
#pls-editor .tool{min-width:65px;display:flex;flex-direction:column;align-items:center;gap:4px;padding:7px 9px}
#pls-editor .tool .ico{font-size:20px;line-height:21px;font-weight:400}#pls-editor .tool span:last-child{font-size:10px}
#pls-editor .work{display:flex;flex:1;min-height:0;overflow:hidden}
#pls-editor .canvas-area{flex:1;min-width:0;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:12px;overflow:auto;background-color:#e9ebef;background-image:linear-gradient(45deg,#dfe2e7 25%,transparent 25%),linear-gradient(-45deg,#dfe2e7 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#dfe2e7 75%),linear-gradient(-45deg,transparent 75%,#dfe2e7 75%);background-size:18px 18px;background-position:0 0,0 9px,9px -9px,-9px 0}
#pls-editor .ruler{font-size:10px;color:#727985;margin:0 0 7px;letter-spacing:1px}
#pls-canvas{width:min(100%,650px);height:auto;flex:none;background:white;box-shadow:0 3px 18px #0002;touch-action:none;border:1px solid #c9cdd4}
#pls-editor .canvas-hint{font-size:10px;color:#6f7783;margin-top:9px;text-align:center}
#pls-editor .inspector{width:245px;flex:none;background:#fff;border-left:1px solid var(--line);display:flex;flex-direction:column;min-height:0}
#pls-editor .inspect-head{font-weight:800;padding:13px;border-bottom:1px solid var(--line);display:flex;justify-content:space-between}
#pls-editor .inspect-body{padding:12px;overflow:auto;flex:1}
#pls-editor label{display:block;font-size:11px;font-weight:700;color:#555e6b;margin:12px 0 5px}
#pls-editor input:not([type=checkbox]),#pls-editor select{width:100%;border:1px solid #d7dbe1;border-radius:7px;padding:9px;background:#fff;font-size:13px;min-width:0}
#pls-editor input[type=checkbox]{width:17px;height:17px;vertical-align:middle}
#pls-editor .two{display:grid;grid-template-columns:1fr 1fr;gap:8px}
#pls-editor .group-title{font-size:10px;color:#8a919c;font-weight:800;letter-spacing:.8px;margin:17px 0 5px}
#pls-editor .action-grid{display:grid;grid-template-columns:1fr 1fr;gap:7px;margin-top:13px}
#pls-editor .danger{color:#b3261e;background:#fff7f6}
#pls-editor .muted{font-size:11px;color:#727985;line-height:1.45}
#pls-editor .bottom{display:flex;gap:7px;padding:8px 10px;background:#fff;border-top:1px solid var(--line);overflow:auto;flex:none}
#pls-editor .bottom button.active{background:#20242a;color:#fff;border-color:#20242a}
#pls-editor .selected-outline{stroke:#2878ed;stroke-width:2;stroke-dasharray:5 4;fill:none;pointer-events:none}
#pls-editor .handle{fill:#fff;stroke:#2878ed;stroke-width:2}
#pls-editor .hidden{display:none!important}
#pls-editor .modal{position:absolute;inset:0;background:#0008;display:flex;align-items:center;justify-content:center;padding:20px;z-index:5}
#pls-editor .modal-card{background:#fff;border-radius:14px;padding:18px;width:min(100%,360px);box-shadow:0 10px 40px #0003}
#pls-editor .modal-card h3{margin:0 0 12px;font-size:16px}
#pls-editor .modal-card .row{display:flex;gap:8px;margin-top:15px}
@media(max-width:700px){#pls-editor .work{flex-direction:column}#pls-editor .canvas-area{flex:1;min-height:190px;padding:9px}#pls-editor .inspector{width:100%;max-height:38%;border-left:0;border-top:1px solid var(--line)}#pls-editor .inspect-head{padding:8px 12px}#pls-editor .inspect-body{padding:8px 12px}#pls-editor .inspect-body label{margin:7px 0 4px}#pls-editor .inspect-body .group-title{margin:10px 0 4px}#pls-editor .tools{padding:6px}#pls-editor .tool{min-width:61px;padding:5px 8px}#pls-editor .top{height:46px}#pls-editor .inspector{max-height:30%;min-height:142px}#pls-editor .bottom{display:flex;gap:5px;padding:5px 7px}#pls-editor .bottom button{padding:7px 9px;font-size:10px}#pls-editor .inspect-body{padding:7px 10px}}

/* Mobile editing workspace styled to match the supplied MyLabel reference */
#pls-editor{background:#f0f1f5;color:#20242b;font-family:Arial,sans-serif}
#pls-editor .top{height:76px;background:#fff;color:#20242b;padding:5px 6px;overflow-x:auto;justify-content:flex-start;gap:6px;border-bottom:1px solid #e4e6eb}
#pls-editor .brandmark{display:none}
#pls-editor .top-actions{display:flex;gap:6px;align-items:stretch}
#pls-editor .top button{background:#fff;color:#697382;border:1px solid #e5e7eb;min-width:66px;height:64px;padding:5px 7px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px;font-size:11px}
#pls-editor .top button::after{font-size:10px;font-weight:500}
#pls-editor #pls-close::after{content:"Return"}#pls-editor #pls-save::after{content:"Save"}#pls-editor #pls-select-all::after{content:"Select All"}#pls-editor #pls-single-select::after{content:"Single Select"}#pls-editor #pls-multiselect::after{content:"Multi Select"}#pls-editor #pls-undo::after{content:"Undo"}
#pls-editor #pls-close{font-size:0}#pls-editor #pls-close::before{content:"↶";font-size:29px}
#pls-editor #pls-save{font-size:0}#pls-editor #pls-save::before{content:"▣";font-size:29px}
#pls-editor #pls-select-all,#pls-editor #pls-single-select,#pls-editor #pls-multiselect{font-size:0}
#pls-editor #pls-select-all::before{content:"☷";font-size:29px}#pls-editor #pls-single-select::before{content:"☑";font-size:29px}#pls-editor #pls-multiselect::before{content:"▦";font-size:29px}
#pls-editor #pls-undo{font-size:0}#pls-editor #pls-undo::before{content:"↶";font-size:29px}
#pls-editor .formatbar{display:none}
#pls-editor .tools{background:#fff;padding:5px 6px;gap:5px;border-bottom:1px solid #e6e8ed}
#pls-editor .tool{min-width:65px;height:76px;border:1px solid #eef0f3;border-radius:14px;padding:6px 8px;color:#697382}
#pls-editor .tool .ico{font-size:28px;line-height:32px}
#pls-editor .work{position:relative;flex-direction:column;background:#f0f1f5}
#pls-editor .canvas-area{flex:1;min-height:0;justify-content:flex-start;align-items:stretch;padding:0 0 10px;overflow:auto;background:#f0f1f5;background-image:none;position:relative}
#pls-editor .ruler{height:30px;flex:none;margin:0;padding:3px 0 0 34px;background-color:#f7f8fb;background-image:repeating-linear-gradient(90deg,#6976b4 0 1px,transparent 1px 12px);background-size:auto 18px;background-repeat:repeat-x;background-position:left bottom;color:#c22b38;font-size:12px;letter-spacing:0;white-space:nowrap}
#pls-editor .canvas-area::before{content:"";position:absolute;left:33px;top:30px;bottom:0;width:17px;background:repeating-linear-gradient(180deg,#6f7ab2 0 1px,transparent 1px 12px);pointer-events:none}
#pls-canvas{width:calc(100% - 34px);max-width:none;margin-left:34px;margin-right:0;align-self:flex-start;height:auto;box-shadow:none;border:0;display:block}
#pls-editor .canvas-hint{display:none}
#pls-editor .inspector{width:100%;max-height:18%;min-height:100px;flex:none;border:0;border-top:1px solid #e2e5eb;border-radius:18px 18px 0 0;box-shadow:0 -4px 16px #00000008}
#pls-editor .inspect-head{padding:8px 12px}
#pls-editor .inspect-body{padding:7px 12px}
#pls-editor .bottom{display:none}
#pls-editor #pls-print{position:absolute;z-index:8;right:18px;bottom:calc(18% + 24px);width:82px;height:82px;border-radius:50%;background:#f32632;color:#fff;border:6px solid #fff;box-shadow:0 6px 20px #0003;font-size:0}
#pls-editor #pls-print::before{content:"▤";font-size:34px;color:#fff}
#pls-editor #pls-print::after{content:"Print";font-size:10px;color:#fff}
#pls-editor .top-actions #pls-redo{display:none}
@media(min-width:701px){#pls-editor .top{height:62px}#pls-editor .inspector{max-height:none;width:245px}#pls-editor .work{flex-direction:row}#pls-editor .canvas-area{padding:12px;align-items:center;justify-content:center}#pls-canvas{width:min(100%,650px);margin:0;border:1px solid #c9cdd4}#pls-editor .canvas-area::before{display:none}#pls-editor .ruler{height:auto;background:none;padding:0;margin-bottom:7px}#pls-editor .canvas-hint{display:block}#pls-editor .bottom{display:flex}#pls-editor #pls-print{position:static;width:auto;height:auto;border-radius:8px;border:1px solid var(--line);background:#fff;color:#222;box-shadow:none;font-size:12px}#pls-editor #pls-print::before,#pls-editor #pls-print::after{content:none}}

#pls-editor .ruler{position:relative;width:calc(100% - 34px);height:30px;margin-left:34px;padding:0;background:repeating-linear-gradient(90deg,#7580b4 0 1px,transparent 1px 12px);background-position:left bottom;background-repeat:repeat-x;color:#c32d3b;letter-spacing:0}
#pls-editor .ruler span{position:absolute;bottom:13px;transform:translateX(-50%);font-size:12px}
#pls-editor .canvas-row{display:flex;align-items:stretch;width:100%;flex:none}
#pls-editor .ruler-y{width:34px;min-width:34px;position:relative;background:repeating-linear-gradient(180deg,#7580b4 0 1px,transparent 1px 12px);background-position:right top;background-repeat:repeat-y}
#pls-editor .ruler-y span{position:absolute;right:3px;transform:translateY(-50%);font-size:12px;color:#c32d3b}
#pls-editor .canvas-area::before{display:none}
#pls-canvas{margin:0!important;width:calc(100% - 34px)!important}
@media(min-width:701px){#pls-editor .ruler{display:none}#pls-editor .ruler-y{display:none}#pls-editor .canvas-row{justify-content:center}#pls-canvas{width:min(100%,650px)!important;margin:0!important}}
/* Final mobile layout overrides: keep inspector and editor tabs visible */
@media(max-width:700px){
#pls-editor .inspector{max-height:30%;min-height:142px;flex:none}
#pls-editor .bottom{display:flex;gap:5px;padding:5px 7px}
#pls-editor .bottom button{padding:7px 9px;font-size:10px}
#pls-editor #pls-print{bottom:calc(30% + 24px)}
}
</style>
<div class="top"><div class="brandmark">PRASAD LABEL STUDIO</div><div class="top-actions"><button id="pls-close">↶</button><button id="pls-save" class="save">▣</button><button id="pls-select-all">☷</button><button id="pls-single-select">☑</button><button id="pls-multiselect">▦</button><button id="pls-undo">↶</button><button id="pls-redo">↷</button><button id="pls-print" class="save">PRINT ONE</button></div></div>
<div class="formatbar"><select id="pls-page"><option value="50x30">50 × 30 mm</option><option value="50x25">50 × 25 mm</option><option value="40x30">40 × 30 mm</option><option value="60x40">60 × 40 mm</option><option value="custom">Custom size…</option></select><span class="dim" id="pls-dim">Label size: 50 × 30 mm</span><button id="pls-templates">TEMPLATES</button><button id="pls-multiselect-format">MULTI-SELECT: OFF</button><button id="pls-new">NEW LABEL</button></div>
<div class="tools">
<button class="tool" data-add="text"><span class="ico">T</span><span>Text</span></button>
<button class="tool" data-add="icon"><span class="ico">✦</span><span>Icon</span></button>
<button class="tool" data-add="barcode"><span class="ico">▥</span><span>Barcode</span></button>
<button class="tool" data-add="image"><span class="ico">▧</span><span>Insert Image</span></button>
<button class="tool" data-add="qr"><span class="ico">▦</span><span>QR Code</span></button>
<button class="tool" data-add="rect"><span class="ico">□</span><span>Rectangle</span></button>
<button class="tool" data-add="line"><span class="ico">／</span><span>Line</span></button>
<button class="tool" id="pls-delete-tool"><span class="ico">⌫</span><span>Delete</span></button>
</div>
<div class="work"><div class="canvas-area"><div class="ruler" id="pls-ruler"></div><div class="canvas-row"><div class="ruler-y" id="pls-ruler-y"></div><svg id="pls-canvas" viewBox="0 0 500 300" aria-label="Label design canvas"></svg></div><div class="canvas-hint">Tap an item to select · Drag to move · Drag blue corner to resize</div></div>
<aside class="inspector"><div class="inspect-head"><span>Object Properties</span><span id="pls-selection-name">No selection</span></div><div class="inspect-body" id="pls-inspector"><div class="muted">Select an item on the label, or choose a tool above to add a new object.</div></div></aside></div>
<div class="bottom"><button class="active" data-tab="object">OBJECT</button><button data-tab="text">TEXT STYLE</button><button data-tab="arrange">ARRANGE</button><button data-tab="page">PAGE SETUP</button><button id="pls-print-bulk">PRINT BULK</button><button id="pls-export">EXPORT</button><button id="pls-import">IMPORT</button><input id="pls-file" type="file" accept="image/*,.json" hidden></div>
<div id="pls-modal" class="modal hidden"><div class="modal-card" id="pls-modal-card"></div></div>\`;
document.body.appendChild(root);
q("#pls-close").onclick=()=>root.style.display="none";
q("#pls-select-all").onclick=()=>{multiSelectMode=true;selectedIds=new Set(objects.map(o=>o.id));selected=objects.length?objects[objects.length-1].id:null;q("#pls-multiselect").style.background="#fff0f1";q("#pls-multiselect").style.color="#e32e3a";render();};
q("#pls-single-select").onclick=()=>{multiSelectMode=false;selectedIds.clear();if(selected)selectedIds.add(selected);q("#pls-multiselect").style.background="#fff";q("#pls-multiselect").style.color="#697382";const f=q("#pls-multiselect-format");if(f)f.textContent="MULTI-SELECT: OFF";render();};
root.querySelectorAll("[data-add]").forEach(b=>b.onclick=()=>add(b.dataset.add));
root.querySelectorAll("[data-tab]").forEach(b=>b.onclick=()=>{activeTab=b.dataset.tab;root.querySelectorAll("[data-tab]").forEach(x=>x.classList.toggle("active",x===b));renderInspector();});
q("#pls-undo").onclick=()=>{if(undo.length){redo.push(JSON.stringify(objects));objects=JSON.parse(undo.pop());save();render();}};
q("#pls-redo").onclick=()=>{if(redo.length){undo.push(JSON.stringify(objects));objects=JSON.parse(redo.pop());save();render();}};
q("#pls-save").onclick=saveTemplate;q("#pls-templates").onclick=showTemplates;q("#pls-print").onclick=printEditorOne;const toggleMultiSelect=()=>{multiSelectMode=!multiSelectMode;const b=q("#pls-multiselect");b.style.background=multiSelectMode?"#fff0f1":"#fff";b.style.color=multiSelectMode?"#e32e3a":"#697382";const f=q("#pls-multiselect-format");if(f)f.textContent="MULTI-SELECT: "+(multiSelectMode?"ON":"OFF");if(!multiSelectMode&&selected){selectedIds=new Set([selected]);}else if(!multiSelectMode){selectedIds.clear();}render();};q("#pls-multiselect").onclick=toggleMultiSelect;
q("#pls-new").onclick=()=>modal("Create new label",'<label>Label name</label><input id="pls-newname" value="My label"><div class="two"><div><label>Width (mm)</label><input id="pls-neww" type="number" value="'+page.w+'"></div><div><label>Height (mm)</label><input id="pls-newh" type="number" value="'+page.h+'"></div></div>','CREATE',()=>{let w=Number(q("#pls-neww").value),h=Number(q("#pls-newh").value);if(w<10||h<10||w>150||h>150)return alert("Enter a size between 10 and 150 mm.");checkpoint();page={w,h,name:q("#pls-newname").value||"My label"};objects=[];selected=null;save();render();closeModal();});
q("#pls-page").onchange=()=>{let v=q("#pls-page").value;if(v==="custom"){q("#pls-new").click();q("#pls-page").value=page.w+"x"+page.h;return;}let [w,h]=v.split("x").map(Number);checkpoint();page={w,h,name:v};save();render();};
q("#pls-delete-tool").onclick=removeSelected;
q("#pls-print-bulk").onclick=printEditorBulk;q("#pls-export").onclick=exportData;q("#pls-import").onclick=()=>{q("#pls-file").accept=".json";q("#pls-file").click();};
q("#pls-file").onchange=e=>{let f=e.target.files[0];if(!f)return;if(f.type.startsWith("image/")){let r=new FileReader();r.onload=()=>add("image",r.result);r.readAsDataURL(f);}else importData(f);e.target.value="";};
render();
}
function modal(title,body,button,action){let m=q("#pls-modal");m.classList.remove("hidden");q("#pls-modal-card").innerHTML='<h3>'+title+'</h3>'+body+'<div class="row"><button id="pls-modal-cancel">CANCEL</button><button id="pls-modal-ok" style="background:#20242a;color:white">'+button+'</button></div>';q("#pls-modal-cancel").onclick=closeModal;q("#pls-modal-ok").onclick=action;}
function closeModal(){q("#pls-modal").classList.add("hidden");}
function add(type,src){if(type==="image"&&!src){q("#pls-file").accept="image/*";q("#pls-file").click();return;}checkpoint();let o={id:"o"+Date.now(),type,text:type==="text"?"New text":type==="barcode"?"123456789":"",x:Math.round(page.w*5-80),y:Math.round(page.h*5-30),w:type==="qr"?100:type==="barcode"?300:180,h:type==="qr"?100:type==="barcode"?60:45,size:22,bold:type==="text",align:"left",rotation:0};if(type==="image"){o.src=src||"";o.w=130;o.h=100;}if(type==="icon"){o.text="★";o.size=38;}objects.push(o);selected=o.id;save();render();}
function updateRulers(){const rx=q("#pls-ruler"),ry=q("#pls-ruler-y");if(!rx||!ry)return;rx.innerHTML="";ry.innerHTML="";for(let mm=0;mm<=page.w;mm+=10){const e=document.createElement("span");e.textContent=mm;e.style.left=(mm/page.w*100)+"%";rx.appendChild(e);}for(let mm=0;mm<=page.h;mm+=10){const e=document.createElement("span");e.textContent=mm;e.style.top=(mm/page.h*100)+"%";ry.appendChild(e);}}
function render(){let svg=q("#pls-canvas");if(!svg)return;updateRulers();let W=page.w*10,H=page.h*10;svg.setAttribute("viewBox","0 0 "+W+" "+H);svg.style.aspectRatio=page.w+"/"+page.h;svg.innerHTML="";svg.appendChild(mk("rect",{x:0,y:0,width:W,height:H,fill:"#fff"}));svg.appendChild(mk("rect",{x:1,y:1,width:W-2,height:H-2,fill:"none",stroke:"#b9bec7","stroke-width":1}));
objects.forEach(o=>{let g=mk("g",{transform:"translate("+o.x+" "+o.y+") rotate("+(o.rotation||0)+" "+(o.w/2)+" "+(o.h/2)+")","data-id":o.id});let el;
if(o.type==="text"||o.type==="icon"){el=mk("text",{x:o.align==="center"?o.w/2:o.align==="right"?o.w:0,y:Math.min(o.h,(o.size||20)+2),"font-size":o.size||20,"font-family":"Arial","font-weight":o.bold?"bold":"normal","text-anchor":o.align==="center"?"middle":o.align==="right"?"end":"start",fill:"#111"});el.textContent=o.text||"";}
else if(o.type==="rect"){el=mk("rect",{x:0,y:0,width:o.w,height:o.h,fill:o.fill||"none",stroke:o.stroke||"#111","stroke-width":o.strokeWidth||2,rx:o.radius||0});}
else if(o.type==="line"){el=mk("line",{x1:0,y1:0,x2:o.w,y2:o.h,stroke:"#111","stroke-width":o.strokeWidth||3});}
else if(o.type==="image"){el=mk("image",{x:0,y:0,width:o.w,height:o.h,href:o.src||"",preserveAspectRatio:"xMidYMid meet"});}
else if(o.type==="barcode"){let group=mk("g");try{if(window.JsBarcode){let temp=document.createElementNS(NS,"svg");window.JsBarcode(temp,o.text||"123456789",{format:"CODE128",displayValue:true,fontSize:12,margin:0,height:Math.max(20,o.h-18),width:1.4,valid:true});let vb=temp.querySelector("svg")||temp;Array.from(vb.children).forEach(n=>group.appendChild(n.cloneNode(true)));group.setAttribute("transform","scale("+(o.w/Math.max(1,300))+" "+(o.h/Math.max(1,65))+")");}else{let t=mk("text",{x:o.w/2,y:o.h/2,"text-anchor":"middle","font-size":16});t.textContent="CODE 128 · "+(o.text||"");group.appendChild(t);}}catch(e){let t=mk("text",{x:4,y:24,"font-size":14});t.textContent="Invalid barcode data";group.appendChild(t);}el=group;}
else if(o.type==="qr"){el=mk("image",{x:0,y:0,width:o.w,height:o.h,preserveAspectRatio:"none"});if(window.QRCode&&QRCode.toDataURL){QRCode.toDataURL(o.text||"PRASAD APPARELS",{width:256,margin:1,errorCorrectionLevel:"M"}).then(url=>{if(el.isConnected)el.setAttribute("href",url);}).catch(()=>{});}else{let t=mk("text",{x:o.w/2,y:o.h/2,"text-anchor":"middle","font-size":12});t.textContent="QR library unavailable";el= t;}}
g.appendChild(el);
if(selectedIds.has(o.id)||o.id===selected){g.appendChild(mk("rect",{x:-3,y:-3,width:o.w+6,height:o.h+6,class:"selected-outline"}));if(o.id===selected)g.appendChild(mk("rect",{x:o.w-6,y:o.h-6,width:13,height:13,rx:2,class:"handle","data-resize":"1"}));}
g.addEventListener("pointerdown",e=>pointerStart(e,o));svg.appendChild(g);});
q("#pls-dim").textContent="Label size: "+page.w+" × "+page.h+" mm";
q("#pls-page").value=page.w+"x"+page.h;renderInspector();}
function pointerStart(e,o){e.preventDefault();if(multiSelectMode){if(selectedIds.has(o.id)){selectedIds.delete(o.id);if(selected===o.id)selected=Array.from(selectedIds).pop()||null;}else{selectedIds.add(o.id);selected=o.id;}if(!selectedIds.size){selected=o.id;selectedIds.add(o.id);}}else{selected=o.id;selectedIds=new Set([o.id]);}render();let svg=q("#pls-canvas"),p=svg.createSVGPoint();p.x=e.clientX;p.y=e.clientY;p=p.matrixTransform(svg.getScreenCTM().inverse());if(o.locked)return;checkpoint();drag={id:o.id,x:p.x,y:p.y,ox:o.x,oy:o.y,w:o.w,h:o.h,resize:!!e.target.dataset.resize,members:Array.from(selectedIds).map(id=>{let a=objects.find(v=>v.id===id);return a&&!a.locked?{id,x:a.x,y:a.y}:null;}).filter(Boolean)};svg.setPointerCapture(e.pointerId);svg.onpointermove=ev=>{if(!drag)return;let z=svg.createSVGPoint();z.x=ev.clientX;z.y=ev.clientY;z=z.matrixTransform(svg.getScreenCTM().inverse());let a=objects.find(v=>v.id===drag.id);if(drag.resize){a.w=Math.max(15,Math.min(page.w*10-a.x,drag.w+z.x-drag.x));a.h=Math.max(15,Math.min(page.h*10-a.y,drag.h+z.y-drag.y));}else{let dx=z.x-drag.x,dy=z.y-drag.y;drag.members.forEach(m=>{let v=objects.find(x=>x.id===m.id);if(v){v.x=Math.max(0,Math.min(page.w*10-v.w,m.x+dx));v.y=Math.max(0,Math.min(page.h*10-v.h,m.y+dy));}});}render();};const finishPointer=()=>{if(!drag)return;drag=null;svg.onpointermove=null;svg.onpointerup=null;svg.onpointercancel=null;save();};svg.onpointerup=finishPointer;svg.onpointercancel=finishPointer;}
function renderInspector(){let panel=q("#pls-inspector"),o=objects.find(x=>x.id===selected);q("#pls-selection-name").textContent=o?o.type.toUpperCase():"No selection";if(activeTab==="page"){panel.innerHTML='<div class="group-title">LABEL SIZE</div><div class="two"><div><label>Width mm</label><input id="p-w" type="number" value="'+page.w+'"></div><div><label>Height mm</label><input id="p-h" type="number" value="'+page.h+'"></div></div><label>Label name</label><input id="p-name" value="'+page.name+'"><button id="p-apply" style="margin-top:12px">APPLY PAGE SIZE</button><p class="muted">Design workspace uses millimetres for label dimensions. Print calibration is handled separately in Print Settings.</p>';q("#p-apply").onclick=()=>{let w=Number(q("#p-w").value),h=Number(q("#p-h").value);if(w<10||h<10||w>150||h>150)return alert("Use 10–150 mm.");checkpoint();page={w,h,name:q("#p-name").value};render();};return;}
if(!o){panel.innerHTML='<div class="muted">Select an object on the label, or choose Text, Icon, Barcode, Insert Image or QR Code above.</div>';return;}
if(activeTab==="arrange"){panel.innerHTML='<div class="group-title">POSITION & SIZE</div><div class="two"><div><label>X</label><input id="p-x" type="number" value="'+o.x+'"></div><div><label>Y</label><input id="p-y" type="number" value="'+o.y+'"></div><div><label>Width</label><input id="p-w" type="number" value="'+o.w+'"></div><div><label>Height</label><input id="p-h" type="number" value="'+o.h+'"></div></div><label>Rotation (degrees)</label><input id="p-rot" type="number" value="'+(o.rotation||0)+'"><div class="action-grid"><button data-rotate="-90">ROTATE −90°</button><button data-rotate="90">ROTATE +90°</button><button data-rotate="180">ROTATE 180°</button><button data-rotate="0">RESET ROTATION</button></div><div class="action-grid"><button id="p-front">BRING FRONT</button><button id="p-back">SEND BACK</button><button id="p-dup">DUPLICATE</button><button id="p-lock">'+(o.locked?"UNLOCK":"LOCK")+'</button></div><div class="group-title">MULTI-SELECT ALIGN</div><p class="muted">Turn on MULTI-SELECT above, then tap objects to add or remove them. Choose at least two for alignment or distribution.</p><div class="action-grid"><button data-move="left">ALIGN LEFT</button><button data-move="center">CENTER HORIZONTAL</button><button data-move="right">ALIGN RIGHT</button><button data-move="top">ALIGN TOP</button><button data-move="middle">CENTER VERTICAL</button><button data-move="bottom">ALIGN BOTTOM</button><button data-move="distributeX">DISTRIBUTE HORIZONTAL</button><button data-move="distributeY">DISTRIBUTE VERTICAL</button></div><button class="danger" id="p-delete" style="width:100%;margin-top:8px">DELETE OBJECT</button>';panel.querySelectorAll("[data-move]").forEach(b=>b.onclick=()=>alignSelection(b.dataset.move));panel.querySelectorAll("[data-rotate]").forEach(b=>b.onclick=()=>{checkpoint();let d=Number(b.dataset.rotate);o.rotation=d===0?0:((Number(o.rotation)||0)+d)%360;save();render();});["x","y","w","h","rot"].forEach(k=>q("#p-"+k).onchange=()=>{checkpoint();o[{x:"x",y:"y",w:"w",h:"h",rot:"rotation"}[k]]=Number(q("#p-"+k).value);save();render();});q("#p-front").onclick=()=>layer(1);q("#p-back").onclick=()=>layer(-1);q("#p-dup").onclick=duplicate;q("#p-lock").onclick=()=>{checkpoint();o.locked=!o.locked;save();render();};q("#p-delete").onclick=removeSelected;return;}
const isText=o.type==="text"||o.type==="icon";
const isCode=o.type==="barcode"||o.type==="qr";
let html='<div class="group-title">'+(isText?'TEXT CONTENT':isCode?'CODE CONTENT':o.type.toUpperCase()+' STYLE')+'</div>';
if(isText||isCode)html+='<label>'+(isCode?'Barcode / QR data':'Text')+'</label><input id="p-text" value="'+esc(o.text||"")+'">';
if(isText)html+='<div class="two"><div><label>Font size</label><input id="p-size" type="number" min="5" max="120" value="'+(o.size||20)+'"></div><div><label>Alignment</label><select id="p-align"><option '+(o.align==="left"?"selected":"")+' value="left">Left</option><option '+(o.align==="center"?"selected":"")+' value="center">Center</option><option '+(o.align==="right"?"selected":"")+' value="right">Right</option></select></div></div><label><input id="p-bold" type="checkbox" '+(o.bold?"checked":"")+'> Bold text</label>';
if(o.type==="rect")html+='<div class="two"><div><label>Fill colour</label><input id="p-fill" type="color" value="'+(o.fill||"#ffffff")+'"></div><div><label>Border colour</label><input id="p-stroke" type="color" value="'+(o.stroke||"#111111")+'"></div></div><label>Border thickness</label><input id="p-strokeWidth" type="number" min="0" max="20" value="'+(o.strokeWidth||2)+'">';
if(o.type==="line")html+='<label>Line thickness</label><input id="p-strokeWidth" type="number" min="1" max="20" value="'+(o.strokeWidth||3)+'">';
html+='<div class="group-title">SIZE & POSITION</div><div class="two"><div><label>Width</label><input id="p-w" type="number" min="1" value="'+o.w+'"></div><div><label>Height</label><input id="p-h" type="number" min="1" value="'+o.h+'"></div></div><div class="action-grid"><button id="p-dup">DUPLICATE</button><button id="p-lock">'+(o.locked?"UNLOCK":"LOCK")+'</button><button id="p-front">BRING FRONT</button><button id="p-back">SEND BACK</button></div><button class="danger" id="p-delete" style="width:100%;margin-top:8px">DELETE OBJECT</button>';
if(o.type==="barcode")html+='<p class="muted">Code 128 barcode. Keep the data short enough to fit the label.</p>';
if(o.type==="qr")html+='<p class="muted">QR code preview updates from the data above.</p>';
if(o.type==="image")html+='<p class="muted">Image object. Use Insert Image to add another picture.</p>';
panel.innerHTML=html;
["text","size","align","bold","w","h","fill","stroke","strokeWidth"].forEach(k=>{const el=q("#p-"+k);if(!el)return;el.onchange=()=>{checkpoint();o[k]=k==="bold"?el.checked:(["size","w","h","strokeWidth"].includes(k)?Number(el.value):el.value);save();render();};});
q("#p-dup").onclick=duplicate;q("#p-lock").onclick=()=>{checkpoint();o.locked=!o.locked;save();render();};q("#p-front").onclick=()=>layer(1);q("#p-back").onclick=()=>layer(-1);q("#p-delete").onclick=removeSelected;
}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
function alignSelection(mode){let a=objects.filter(o=>selectedIds.has(o.id));if(a.length<2){alert("Turn on MULTI-SELECT, then tap at least two objects.");return;}checkpoint();let minX=Math.min(...a.map(o=>o.x)),maxX=Math.max(...a.map(o=>o.x+o.w),),minY=Math.min(...a.map(o=>o.y)),maxY=Math.max(...a.map(o=>o.y+o.h));if(mode==="left")a.forEach(o=>o.x=minX);if(mode==="center")a.forEach(o=>o.x=minX+(maxX-minX-o.w)/2);if(mode==="right")a.forEach(o=>o.x=maxX-o.w);if(mode==="top")a.forEach(o=>o.y=minY);if(mode==="middle")a.forEach(o=>o.y=minY+(maxY-minY-o.h)/2);if(mode==="bottom")a.forEach(o=>o.y=maxY-o.h);if(mode==="distributeX"||mode==="distributeY"){let k=mode==="distributeX"?"x":"y",sz=k==="x"?"w":"h",sorted=[...a].sort((u,v)=>u[k]-v[k]),total=sorted.reduce((s,o)=>s+o[sz],0),start=sorted[0][k],end=sorted[sorted.length-1][k]+sorted[sorted.length-1][sz],gap=(end-start-total)/(sorted.length-1),pos=start;sorted.forEach(o=>{o[k]=pos;pos+=o[sz]+gap;});}a.forEach(o=>{o.x=Math.max(0,Math.min(page.w*10-o.w,o.x));o.y=Math.max(0,Math.min(page.h*10-o.h,o.y));});save();render();}\nfunction layer(d){let i=objects.findIndex(x=>x.id===selected);if(i<0)return;checkpoint();let j=Math.max(0,Math.min(objects.length-1,i+d));[objects[i],objects[j]]=[objects[j],objects[i]];save();render();}
function duplicate(){let o=objects.find(x=>x.id===selected);if(!o)return;checkpoint();let n=JSON.parse(JSON.stringify(o));n.id="o"+Date.now();n.x+=12;n.y+=12;objects.push(n);selected=n.id;save();render();}
function removeSelected(){if(!selected)return;checkpoint();objects=objects.filter(x=>!selectedIds.has(x.id)&&x.id!==selected);selected=null;selectedIds.clear();save();render();}
function printEditorOne(){
if(!window.AndroidPrinter){alert("Open this editor inside Prasad Label Studio Android app to print.");return;}
const get=id=>document.getElementById(id);
const style=get("style")?.value?.trim()||"";
const model=get("model")?.value||"";
const category=get("category")?.value||"";
const colour=get("colour")?.value?.trim()||"";
const size=get("previewSize")?.value||"28";
const noBarcode=!!get("noBarcode")?.checked;
const auto=!!get("autoBarcode")?.checked&&!noBarcode;
if(!style){alert("Enter the style number in Product Details first.");return;}
const design={editorMode:true,editorPage:page,editorObjects:objects,showBarcode:!noBarcode};
AndroidPrinter.printSingle(JSON.stringify({style,model,category,colour,size,barcode:noBarcode||auto?"":(get("barcodeNumber")?.value||""),autoBarcode:auto,design}));
}
function printEditorBulk(){
if(!window.AndroidPrinter){alert("Open this editor inside Prasad Label Studio Android app to print.");return;}
if(typeof sizes==="undefined"||typeof quantities==="undefined"){alert("Bulk size quantities are unavailable.");return;}
const get=id=>document.getElementById(id);
const style=get("style")?.value?.trim()||"",model=get("model")?.value||"",category=get("category")?.value||"",colour=get("colour")?.value?.trim()||"";
const noBarcode=!!get("noBarcode")?.checked,auto=!!get("autoBarcode")?.checked&&!noBarcode,items=[];
sizes.forEach(size=>{if((quantities[size]||0)>0)items.push({size,quantity:quantities[size]});});
if(!style||!items.length){alert("Enter a style and select size quantities first.");return;}
AndroidPrinter.printBulk(JSON.stringify({style,model,category,colour,barcode:noBarcode||auto?"":(get("barcodeNumber")?.value||""),autoBarcode:auto,items,design:{editorMode:true,editorPage:page,editorObjects:objects,showBarcode:!noBarcode}}));
}
function saveTemplate(){modal("Save this label template",'<label>Template name</label><input id="pls-template-name" maxlength="40" value="'+esc(page.name||"Prasad label")+'"><p class="muted">Saved templates stay on this phone.</p>',"SAVE",()=>{let name=q("#pls-template-name").value.trim();if(!name)return alert("Enter a template name.");let all=JSON.parse(localStorage.getItem(TMPL)||"{}");if(all[name]&&!confirm("A template with this name already exists. Replace it?"))return;all[name]={page:JSON.parse(JSON.stringify(page)),objects:JSON.parse(JSON.stringify(objects))};localStorage.setItem(TMPL,JSON.stringify(all));closeModal();alert("Template saved on this device.");});}
function showTemplates(){
let all=JSON.parse(localStorage.getItem(TMPL)||"{}");
const presets=[
{name:"Prasad Classic · 50×30",w:50,h:30,kind:"classic"},
{name:"Compact · 50×25",w:50,h:25,kind:"compact"},
{name:"Compact · 40×30",w:40,h:30,kind:"compact"},
{name:"Luxury · 60×40",w:60,h:40,kind:"luxury"},
{name:"QR + Barcode · 50×30",w:50,h:30,kind:"qr"}
];
const presetHtml=presets.map((p,i)=>'<button data-preset="'+i+'" style="width:100%;text-align:left;margin:4px 0;padding:11px;white-space:normal"><b>'+p.name+'</b><div class="muted">'+(p.kind==="classic"?"Brand · style · large size · Code 128":p.kind==="compact"?"Compact brand · style · size · barcode":p.kind==="luxury"?"Large premium brand · details · size · barcode":"Brand · QR code · style · size")+'</div></button>').join("");
const names=Object.keys(all);
const savedHtml=names.length?names.map((n,i)=>'<div style="display:flex;gap:6px;align-items:center;margin:5px 0"><button data-saved="'+i+'" style="flex:1;text-align:left;white-space:normal">'+esc(n)+' · '+(all[n].page?.w||50)+'×'+(all[n].page?.h||30)+' mm</button><button data-rename="'+i+'" title="Rename">✎</button><button data-remove="'+i+'" class="danger" title="Delete">×</button></div>').join(""):'<p class="muted">No personal templates saved on this device yet. Use SAVE to add one.</p>';
modal("Choose a label template",'<div class="group-title">READY-MADE TEMPLATES</div>'+presetHtml+'<div class="group-title">MY SAVED TEMPLATES</div>'+savedHtml,'CLOSE',closeModal);
const card=q("#pls-modal-card");
card.querySelectorAll("[data-preset]").forEach(b=>b.onclick=()=>{const p=presets[Number(b.dataset.preset)];checkpoint();page={w:p.w,h:p.h,name:p.name};const W=p.w*10,H=p.h*10;let brand={id:"brand",type:"text",text:"PRASAD APPARELS",x:15,y:10,w:W-30,h:35,size:p.kind==="luxury"?27:22,bold:true,align:"center"};let style={id:"style",type:"text",text:"STYLE: PA-102",x:18,y:p.kind==="luxury"?105:85,w:W*.55,h:28,size:17};let size={id:"size",type:"text",text:"28",x:W*.70,y:p.kind==="luxury"?95:95,w:W*.25,h:58,size:p.kind==="luxury"?58:48,bold:true,align:"center"};let code={id:"barcode",type:"barcode",text:"PA10228",x:W*.13,y:H-(p.kind==="luxury"?105:72),w:W*.72,h:p.kind==="luxury"?75:58,size:14};objects=[brand,style,size,code];if(p.kind==="qr"){objects.push({id:"qr",type:"qr",text:"https://prasadapparels.com",x:W-95,y:Math.max(35,H-200),w:75,h:75,size:18});style.y=90;style.w=W*.48;size.x=W*.55;size.w=W*.25;code.x=18;code.y=H-70;code.w=W-36;}if(p.kind==="compact"){brand.size=18;brand.h=27;style.y=60;style.size=14;size.y=62;size.size=38;code.y=H-55;code.h=43;}if(p.kind==="luxury"){brand.y=15;style.y=115;style.size=19;size.y=110;code.y=H-95;}selected=null;selectedIds.clear();save();render();closeModal();});
card.querySelectorAll("[data-saved]").forEach(b=>b.onclick=()=>{const n=names[Number(b.dataset.saved)];checkpoint();page=all[n].page||page;objects=all[n].objects||[];selected=null;selectedIds.clear();save();render();closeModal();});
card.querySelectorAll("[data-remove]").forEach(b=>b.onclick=()=>{const i=Number(b.dataset.remove),n=names[i];if(!confirm('Delete saved template "'+n+'"?'))return;delete all[n];localStorage.setItem(TMPL,JSON.stringify(all));showTemplates();});
card.querySelectorAll("[data-rename]").forEach(b=>b.onclick=()=>{const i=Number(b.dataset.rename),oldName=names[i],newName=prompt("Rename template",oldName);if(!newName||newName===oldName)return;if(all[newName])return alert("A template with that name already exists.");all[newName]=all[oldName];delete all[oldName];localStorage.setItem(TMPL,JSON.stringify(all));showTemplates();});
}
function exportData(){let blob=new Blob([JSON.stringify({format:"prasad-label-template-v2",page,objects},null,2)],{type:"application/json"}),a=document.createElement("a");a.href=URL.createObjectURL(blob);a.download="prasad-label-template.json";a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);}
function importData(f){let r=new FileReader();r.onload=()=>{try{let d=JSON.parse(r.result);if(!Array.isArray(d.objects||d.items))throw Error();checkpoint();objects=d.objects||d.items;page=d.page||page;selected=null;save();render();alert("Template imported.");}catch(e){alert("This is not a valid Prasad label template.");}};r.readAsText(f);}
document.addEventListener("DOMContentLoaded",launch);setTimeout(launch,500);setTimeout(launch,1300);setTimeout(launch,2300);
})();