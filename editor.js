(()=>{if(window.__plsEditorLoaded)return;window.__plsEditorLoaded=true;
const KEY="plsObjectsV1",TMPL="plsTemplatesV1",NS="http://www.w3.org/2000/svg",q=s=>document.querySelector("#pls-editor "+s);
let objects=JSON.parse(localStorage.getItem(KEY)||"null")||[
{id:"brand",type:"text",text:"PRASAD APPARELS",x:18,y:24,w:460,h:36,size:25,bold:true,align:"center"},
{id:"style",type:"text",text:"STYLE: PA-102",x:22,y:95,w:235,h:30,size:19},
{id:"size",type:"text",text:"28",x:350,y:112,w:115,h:65,size:52,bold:true,align:"center"},
{id:"barcode",type:"barcode",text:"PA10228",x:75,y:220,w:350,h:55,size:16}
];
let selected=null,undo=[],redo=[],drag=null,activeTab="object",page={w:50,h:30,name:"Custom 50 × 30 mm"};
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
@media(max-width:700px){#pls-editor .work{flex-direction:column}#pls-editor .canvas-area{flex:1;min-height:190px;padding:9px}#pls-editor .inspector{width:100%;max-height:38%;border-left:0;border-top:1px solid var(--line)}#pls-editor .inspect-head{padding:8px 12px}#pls-editor .inspect-body{padding:8px 12px}#pls-editor .inspect-body label{margin:7px 0 4px}#pls-editor .inspect-body .group-title{margin:10px 0 4px}#pls-editor .tools{padding:6px}#pls-editor .tool{min-width:61px;padding:5px 8px}#pls-editor .top{height:46px}}
</style>
<div class="top"><div class="brandmark">PRASAD LABEL STUDIO</div><div class="top-actions"><button id="pls-undo">↶</button><button id="pls-redo">↷</button><button id="pls-print" class="save">PRINT ONE</button><button id="pls-save" class="save">SAVE</button><button id="pls-close">✕</button></div></div>
<div class="formatbar"><select id="pls-page"><option value="50x30">50 × 30 mm</option><option value="50x25">50 × 25 mm</option><option value="40x30">40 × 30 mm</option><option value="60x40">60 × 40 mm</option><option value="custom">Custom size…</option></select><span class="dim" id="pls-dim">Label size: 50 × 30 mm</span><button id="pls-templates">TEMPLATES</button><button id="pls-new">NEW LABEL</button></div>
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
<div class="work"><div class="canvas-area"><div class="ruler" id="pls-ruler">0 ───── 10 ───── 20 ───── 30 ───── 40 ───── 50 mm</div><svg id="pls-canvas" viewBox="0 0 500 300" aria-label="Label design canvas"></svg><div class="canvas-hint">Tap an item to select · Drag to move · Drag blue corner to resize</div></div>
<aside class="inspector"><div class="inspect-head"><span>Object Properties</span><span id="pls-selection-name">No selection</span></div><div class="inspect-body" id="pls-inspector"><div class="muted">Select an item on the label, or choose a tool above to add a new object.</div></div></aside></div>
<div class="bottom"><button class="active" data-tab="object">OBJECT</button><button data-tab="text">TEXT STYLE</button><button data-tab="arrange">ARRANGE</button><button data-tab="page">PAGE SETUP</button><button id="pls-print-bulk">PRINT BULK</button><button id="pls-export">EXPORT</button><button id="pls-import">IMPORT</button><input id="pls-file" type="file" accept="image/*,.json" hidden></div>
<div id="pls-modal" class="modal hidden"><div class="modal-card" id="pls-modal-card"></div></div>\`;
document.body.appendChild(root);
q("#pls-close").onclick=()=>root.style.display="none";
root.querySelectorAll("[data-add]").forEach(b=>b.onclick=()=>add(b.dataset.add));
root.querySelectorAll("[data-tab]").forEach(b=>b.onclick=()=>{activeTab=b.dataset.tab;root.querySelectorAll("[data-tab]").forEach(x=>x.classList.toggle("active",x===b));renderInspector();});
q("#pls-undo").onclick=()=>{if(undo.length){redo.push(JSON.stringify(objects));objects=JSON.parse(undo.pop());save();render();}};
q("#pls-redo").onclick=()=>{if(redo.length){undo.push(JSON.stringify(objects));objects=JSON.parse(redo.pop());save();render();}};
q("#pls-save").onclick=saveTemplate;q("#pls-templates").onclick=showTemplates;q("#pls-print").onclick=printEditorOne;
q("#pls-new").onclick=()=>modal("Create new label",'<label>Label name</label><input id="pls-newname" value="My label"><div class="two"><div><label>Width (mm)</label><input id="pls-neww" type="number" value="'+page.w+'"></div><div><label>Height (mm)</label><input id="pls-newh" type="number" value="'+page.h+'"></div></div>','CREATE',()=>{let w=Number(q("#pls-neww").value),h=Number(q("#pls-newh").value);if(w<10||h<10||w>150||h>150)return alert("Enter a size between 10 and 150 mm.");checkpoint();page={w,h,name:q("#pls-newname").value||"My label"};objects=[];selected=null;save();render();closeModal();});
q("#pls-page").onchange=()=>{let v=q("#pls-page").value;if(v==="custom"){q("#pls-new").click();q("#pls-page").value=page.w+"x"+page.h;return;}let [w,h]=v.split("x").map(Number);checkpoint();page={w,h,name:v};save();render();};
q("#pls-delete-tool").onclick=removeSelected;
q("#pls-print-bulk").onclick=printEditorBulk;q("#pls-export").onclick=exportData;q("#pls-import").onclick=()=>{q("#pls-file").accept=".json";q("#pls-file").click();};
q("#pls-file").onchange=e=>{let f=e.target.files[0];if(!f)return;if(f.type.startsWith("image/")){let r=new FileReader();r.onload=()=>add("image",r.result);r.readAsDataURL(f);}else importData(f);e.target.value="";};
render();
}
function modal(title,body,button,action){let m=q("#pls-modal");m.classList.remove("hidden");q("#pls-modal-card").innerHTML='<h3>'+title+'</h3>'+body+'<div class="row"><button id="pls-modal-cancel">CANCEL</button><button id="pls-modal-ok" style="background:#20242a;color:white">'+button+'</button></div>';q("#pls-modal-cancel").onclick=closeModal;q("#pls-modal-ok").onclick=action;}
function closeModal(){q("#pls-modal").classList.add("hidden");}
function add(type,src){checkpoint();let o={id:"o"+Date.now(),type,text:type==="text"?"New text":type==="barcode"?"123456789":"",x:Math.round(page.w*5-80),y:Math.round(page.h*5-30),w:type==="qr"?100:type==="barcode"?300:180,h:type==="qr"?100:type==="barcode"?60:45,size:22,bold:type==="text",align:"left",rotation:0};if(type==="image"){o.src=src||"";o.w=130;o.h=100;}if(type==="icon"){o.text="★";o.size=38;}objects.push(o);selected=o.id;save();render();}
function render(){let svg=q("#pls-canvas");if(!svg)return;let W=page.w*10,H=page.h*10;svg.setAttribute("viewBox","0 0 "+W+" "+H);svg.style.aspectRatio=page.w+"/"+page.h;svg.innerHTML="";svg.appendChild(mk("rect",{x:0,y:0,width:W,height:H,fill:"#fff"}));svg.appendChild(mk("rect",{x:1,y:1,width:W-2,height:H-2,fill:"none",stroke:"#b9bec7","stroke-width":1}));
objects.forEach(o=>{let g=mk("g",{transform:"translate("+o.x+" "+o.y+") rotate("+(o.rotation||0)+" "+(o.w/2)+" "+(o.h/2)+")","data-id":o.id});let el;
if(o.type==="text"||o.type==="icon"){el=mk("text",{x:o.align==="center"?o.w/2:o.align==="right"?o.w:0,y:Math.min(o.h,(o.size||20)+2),"font-size":o.size||20,"font-family":"Arial","font-weight":o.bold?"bold":"normal","text-anchor":o.align==="center"?"middle":o.align==="right"?"end":"start",fill:"#111"});el.textContent=o.text||"";}
else if(o.type==="rect"){el=mk("rect",{x:0,y:0,width:o.w,height:o.h,fill:o.fill||"none",stroke:o.stroke||"#111","stroke-width":o.strokeWidth||2,rx:o.radius||0});}
else if(o.type==="line"){el=mk("line",{x1:0,y1:0,x2:o.w,y2:o.h,stroke:"#111","stroke-width":o.strokeWidth||3});}
else if(o.type==="image"){el=mk("image",{x:0,y:0,width:o.w,height:o.h,href:o.src||"",preserveAspectRatio:"xMidYMid meet"});}
else if(o.type==="barcode"){let group=mk("g");try{if(window.JsBarcode){let temp=document.createElementNS(NS,"svg");window.JsBarcode(temp,o.text||"123456789",{format:"CODE128",displayValue:true,fontSize:12,margin:0,height:Math.max(20,o.h-18),width:1.4,valid:true});let vb=temp.querySelector("svg")||temp;Array.from(vb.children).forEach(n=>group.appendChild(n.cloneNode(true)));group.setAttribute("transform","scale("+(o.w/Math.max(1,300))+" "+(o.h/Math.max(1,65))+")");}else{let t=mk("text",{x:o.w/2,y:o.h/2,"text-anchor":"middle","font-size":16});t.textContent="CODE 128 · "+(o.text||"");group.appendChild(t);}}catch(e){let t=mk("text",{x:4,y:24,"font-size":14});t.textContent="Invalid barcode data";group.appendChild(t);}el=group;}
else if(o.type==="qr"){el=mk("image",{x:0,y:0,width:o.w,height:o.h,preserveAspectRatio:"none"});if(window.QRCode&&QRCode.toDataURL){QRCode.toDataURL(o.text||"PRASAD APPARELS",{width:256,margin:1,errorCorrectionLevel:"M"}).then(url=>{if(el.isConnected)el.setAttribute("href",url);}).catch(()=>{});}else{let t=mk("text",{x:o.w/2,y:o.h/2,"text-anchor":"middle","font-size":12});t.textContent="QR library unavailable";el= t;}}
g.appendChild(el);
if(o.id===selected){g.appendChild(mk("rect",{x:-3,y:-3,width:o.w+6,height:o.h+6,class:"selected-outline"}));g.appendChild(mk("rect",{x:o.w-6,y:o.h-6,width:13,height:13,rx:2,class:"handle","data-resize":"1"}));}
g.addEventListener("pointerdown",e=>pointerStart(e,o));svg.appendChild(g);});
q("#pls-dim").textContent="Label size: "+page.w+" × "+page.h+" mm";q("#pls-ruler").textContent="0 ───── "+Math.round(page.w*.2)+" ───── "+Math.round(page.w*.4)+" ───── "+Math.round(page.w*.6)+" ───── "+Math.round(page.w*.8)+" ───── "+page.w+" mm";
q("#pls-page").value=page.w+"x"+page.h;renderInspector();}
function pointerStart(e,o){e.preventDefault();selected=o.id;renderInspector();let svg=q("#pls-canvas"),p=svg.createSVGPoint();p.x=e.clientX;p.y=e.clientY;p=p.matrixTransform(svg.getScreenCTM().inverse());if(o.locked)return;checkpoint();drag={id:o.id,x:p.x,y:p.y,ox:o.x,oy:o.y,w:o.w,h:o.h,resize:!!e.target.dataset.resize};svg.setPointerCapture(e.pointerId);svg.onpointermove=ev=>{if(!drag)return;let z=svg.createSVGPoint();z.x=ev.clientX;z.y=ev.clientY;z=z.matrixTransform(svg.getScreenCTM().inverse());let a=objects.find(v=>v.id===drag.id);if(drag.resize){a.w=Math.max(15,Math.min(page.w*10,drag.w+z.x-drag.x));a.h=Math.max(15,Math.min(page.h*10,drag.h+z.y-drag.y));}else{a.x=Math.max(0,Math.min(page.w*10-a.w,drag.ox+z.x-drag.x));a.y=Math.max(0,Math.min(page.h*10-a.h,drag.oy+z.y-drag.y));}render();};svg.onpointerup=()=>{drag=null;svg.onpointermove=null;svg.onpointerup=null;save();};}
function renderInspector(){let panel=q("#pls-inspector"),o=objects.find(x=>x.id===selected);q("#pls-selection-name").textContent=o?o.type.toUpperCase():"No selection";if(activeTab==="page"){panel.innerHTML='<div class="group-title">LABEL SIZE</div><div class="two"><div><label>Width mm</label><input id="p-w" type="number" value="'+page.w+'"></div><div><label>Height mm</label><input id="p-h" type="number" value="'+page.h+'"></div></div><label>Label name</label><input id="p-name" value="'+page.name+'"><button id="p-apply" style="margin-top:12px">APPLY PAGE SIZE</button><p class="muted">Design workspace uses millimetres for label dimensions. Print calibration is handled separately in Print Settings.</p>';q("#p-apply").onclick=()=>{let w=Number(q("#p-w").value),h=Number(q("#p-h").value);if(w<10||h<10||w>150||h>150)return alert("Use 10–150 mm.");checkpoint();page={w,h,name:q("#p-name").value};render();};return;}
if(!o){panel.innerHTML='<div class="muted">Select an object on the label, or choose Text, Icon, Barcode, Insert Image or QR Code above.</div>';return;}
if(activeTab==="arrange"){panel.innerHTML='<div class="group-title">POSITION & SIZE</div><div class="two"><div><label>X</label><input id="p-x" type="number" value="'+o.x+'"></div><div><label>Y</label><input id="p-y" type="number" value="'+o.y+'"></div><div><label>Width</label><input id="p-w" type="number" value="'+o.w+'"></div><div><label>Height</label><input id="p-h" type="number" value="'+o.h+'"></div></div><label>Rotation (degrees)</label><input id="p-rot" type="number" value="'+(o.rotation||0)+'"><div class="action-grid"><button id="p-front">BRING FRONT</button><button id="p-back">SEND BACK</button><button id="p-dup">DUPLICATE</button><button id="p-lock">'+(o.locked?"UNLOCK":"LOCK")+'</button></div><button class="danger" id="p-delete" style="width:100%;margin-top:8px">DELETE OBJECT</button>';["x","y","w","h","rot"].forEach(k=>q("#p-"+k).onchange=()=>{checkpoint();o[{x:"x",y:"y",w:"w",h:"h",rot:"rotation"}[k]]=Number(q("#p-"+k).value);save();render();});q("#p-front").onclick=()=>layer(1);q("#p-back").onclick=()=>layer(-1);q("#p-dup").onclick=duplicate;q("#p-lock").onclick=()=>{checkpoint();o.locked=!o.locked;save();render();};q("#p-delete").onclick=removeSelected;return;}
panel.innerHTML='<div class="group-title">CONTENT</div><label>Text / data</label><input id="p-text" value="'+esc(o.text||"")+'"><div class="two"><div><label>Font size</label><input id="p-size" type="number" min="5" max="120" value="'+(o.size||20)+'"></div><div><label>Alignment</label><select id="p-align"><option>left</option><option '+(o.align==="center"?"selected":"") +'>center</option><option '+(o.align==="right"?"selected":"") +'>right</option></select></div></div><label><input id="p-bold" type="checkbox" '+(o.bold?"checked":"")+'> Bold text</label><div class="group-title">OBJECT</div><div class="two"><div><label>Width</label><input id="p-w" type="number" value="'+o.w+'"></div><div><label>Height</label><input id="p-h" type="number" value="'+o.h+'"></div></div><div class="action-grid"><button id="p-dup">DUPLICATE</button><button id="p-lock">'+(o.locked?"UNLOCK":"LOCK")+'</button><button id="p-front">BRING FRONT</button><button id="p-back">SEND BACK</button></div><button class="danger" id="p-delete" style="width:100%;margin-top:8px">DELETE OBJECT</button><p class="muted">For barcode objects, enter the barcode data. The preview uses Code 128 when the barcode library is loaded.</p>';
["text","size","align","bold","w","h"].forEach(k=>q("#p-"+k).onchange=()=>{checkpoint();let v=q("#p-"+k);o[k==="w"?"w":k==="h"?"h":k]=k==="bold"?v.checked:k==="size"||k==="w"||k==="h"?Number(v.value):v.value;save();render();});q("#p-dup").onclick=duplicate;q("#p-lock").onclick=()=>{checkpoint();o.locked=!o.locked;save();render();};q("#p-front").onclick=()=>layer(1);q("#p-back").onclick=()=>layer(-1);q("#p-delete").onclick=removeSelected;
}
function esc(s){return String(s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
function layer(d){let i=objects.findIndex(x=>x.id===selected);if(i<0)return;checkpoint();let j=Math.max(0,Math.min(objects.length-1,i+d));[objects[i],objects[j]]=[objects[j],objects[i]];save();render();}
function duplicate(){let o=objects.find(x=>x.id===selected);if(!o)return;checkpoint();let n=JSON.parse(JSON.stringify(o));n.id="o"+Date.now();n.x+=12;n.y+=12;objects.push(n);selected=n.id;save();render();}
function removeSelected(){if(!selected)return;checkpoint();objects=objects.filter(x=>x.id!==selected);selected=null;save();render();}
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
function saveTemplate(){let name=prompt("Enter template name");if(!name)return;let all=JSON.parse(localStorage.getItem(TMPL)||"{}");all[name]={page,objects};localStorage.setItem(TMPL,JSON.stringify(all));alert("Template saved on this device.");}
function showTemplates(){let all=JSON.parse(localStorage.getItem(TMPL)||"{}"),names=Object.keys(all);let html=names.length?names.map((n,i)=>'<button data-template-index="'+i+'" style="width:100%;text-align:left;margin:4px 0">'+esc(n)+' · '+all[n].page.w+'×'+all[n].page.h+' mm</button>').join(""):'<p class="muted">No saved templates yet. Use SAVE to save this design.</p>';modal("My label templates",html,'CLOSE',closeModal);q("#pls-modal-card").querySelectorAll("[data-template-index]").forEach((b,i)=>b.onclick=()=>{let n=names[i];checkpoint();page=all[n].page||page;objects=all[n].objects||[];selected=null;save();render();closeModal();});}
function exportData(){let blob=new Blob([JSON.stringify({format:"prasad-label-template-v2",page,objects},null,2)],{type:"application/json"}),a=document.createElement("a");a.href=URL.createObjectURL(blob);a.download="prasad-label-template.json";a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);}
function importData(f){let r=new FileReader();r.onload=()=>{try{let d=JSON.parse(r.result);if(!Array.isArray(d.objects||d.items))throw Error();checkpoint();objects=d.objects||d.items;page=d.page||page;selected=null;save();render();alert("Template imported.");}catch(e){alert("This is not a valid Prasad label template.");}};r.readAsText(f);}
document.addEventListener("DOMContentLoaded",launch);setTimeout(launch,500);setTimeout(launch,1300);setTimeout(launch,2300);
})();