(function () {
'use strict';
const A = window.App, S = A.S, B = A.B, $ = A.$, $$ = A.$$, esc = A.esc;

/* Label design: page {w,h} in mm; objects in 0.1 mm units (a 50x30 mm label is 500x300).
   types: text, icon, barcode, qr, rect, line, image */

const E = { sel: new Set(), multi: false, undo: [], redo: [], scale: 1, opened: false, typing: false, replaceId: null };
const uid = () => 'o' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
const FONTS = ['sans', 'serif', 'mono', 'cond'];
const FONT_NAMES = { sans: 'Sans', serif: 'Serif', mono: 'Mono', cond: 'Narrow' };
const ICONS = ['★', '✓', '✂', '♻', '☎', '⚠', '♥', '✿', '☀', '✉', '⚑', '●', '■', '▲', '◆', '✔', '✖', '➜'];
const PAGES = [[50, 30], [40, 30], [50, 25], [40, 20], [50, 40], [30, 20]];

function mk(type, o) {
  const base = { id: uid(), type, x: 20, y: 20, w: 200, h: 50, rotation: 0 };
  return Object.assign(base, o);
}
function txt(text, x, y, w, size, extra) {
  return mk('text', Object.assign({ text, x, y, w, h: Math.round(size * 1.25), size, font: 'sans', bold: false, italic: false, align: 'left', color: '#000000' }, extra || {}));
}
function defaultDesign() {
  return {
    page: { w: 50, h: 30 },
    objects: [
      txt('{STYLE}', 20, 20, 460, 40, { bold: true }),
      txt('{MODEL} {COLOUR}', 20, 75, 460, 32),
      txt('Size {SIZE}', 20, 118, 220, 32, { bold: true }),
      txt('{CATEGORY}', 260, 122, 220, 28, { align: 'right' }),
      mk('barcode', { text: '{BARCODE}', x: 20, y: 165, w: 460, h: 120, showText: true })
    ]
  };
}
const PRESETS = {
  standard: () => defaultDesign(),
  barcode: () => ({ page: { w: 50, h: 30 }, objects: [mk('barcode', { text: '{BARCODE}', x: 30, y: 30, w: 440, h: 240, showText: true })] }),
  big: () => ({
    page: { w: 50, h: 30 },
    objects: [
      txt('{STYLE}', 20, 15, 460, 30, { bold: true }),
      txt('{SIZE}', 20, 50, 460, 100, { bold: true, align: 'center' }),
      mk('barcode', { text: '{BARCODE}', x: 60, y: 190, w: 380, h: 95, showText: true })
    ]
  })
};

/* ---------- element building (used by the editor and the home preview) ---------- */
const fam = f => f === 'serif' ? 'Georgia,"Times New Roman",serif' : f === 'mono' ? '"Courier New",monospace' : f === 'cond' ? '"Arial Narrow","Roboto Condensed",sans-serif' : 'system-ui,Roboto,Arial,sans-serif';
const svgCache = {};
function codeUri(kind, text) {
  const k = kind + '\n' + text;
  if (!(k in svgCache)) {
    const svg = B.codeSvg(kind, text) || '';
    svgCache[k] = svg ? 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg) : '';
  }
  return svgCache[k];
}
function buildObj(o, sc, ctx) {
  const d = document.createElement('div');
  d.className = 'obj'; d.dataset.id = o.id;
  const isLine = o.type === 'line';
  const pad = isLine ? 7 : 0;
  d.style.left = o.x * sc + 'px';
  d.style.top = (o.y * sc - pad) + 'px';
  d.style.width = o.w * sc + 'px';
  d.style.height = (Math.abs(o.h) * sc + pad * 2) + 'px';
  d.style.transform = 'rotate(' + (o.rotation || 0) + 'deg)';

  if (o.type === 'text' || o.type === 'icon') {
    const raw = o.text || '', val = A.tokens(raw, ctx);
    const t = document.createElement('div');
    t.className = 't' + (!val && raw ? ' ph' : '');
    t.textContent = val || raw;
    t.style.cssText += ';font-size:' + (o.size * sc) + 'px;font-family:' + fam(o.font) + ';font-weight:' + (o.bold ? 700 : 400) + ';font-style:' + (o.italic ? 'italic' : 'normal') + ';text-align:' + (o.align || 'left') + ';color:' + (o.color || '#000');
    d.appendChild(t);
  } else if (o.type === 'barcode' || o.type === 'qr') {
    const val = A.tokens(o.text || '', ctx).trim();
    const uri = val ? codeUri(o.type, val) : '';
    if (!uri) {
      const e = document.createElement('div'); e.className = 'empty'; e.textContent = (o.type === 'qr' ? 'QR' : 'Barcode') + (o.text ? '' : '');
      d.appendChild(e);
    } else {
      const cap = o.type === 'barcode' && o.showText !== false;
      const img = document.createElement('img'); img.src = uri; img.alt = '';
      if (o.type === 'barcode') { img.style.height = cap ? '72%' : '100%'; img.style.objectFit = 'fill'; }
      d.appendChild(img);
      if (cap) { const c = document.createElement('div'); c.className = 'cap'; c.style.fontSize = (14 * sc) + 'px'; c.textContent = val; d.appendChild(c); }
    }
  } else if (o.type === 'rect') {
    d.style.border = (o.strokeWidth == null ? 3 : o.strokeWidth) * sc + 'px solid ' + (o.stroke || '#111');
    d.style.background = o.fill && o.fill !== 'none' ? o.fill : 'transparent';
    d.style.borderRadius = (o.radius || 0) * sc + 'px';
    d.style.boxSizing = 'border-box';
  } else if (o.type === 'line') {
    const ns = 'http://www.w3.org/2000/svg', svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('width', '100%'); svg.setAttribute('height', '100%'); svg.style.overflow = 'visible'; svg.style.pointerEvents = 'none';
    const l = document.createElementNS(ns, 'line');
    l.setAttribute('x1', 0); l.setAttribute('y1', pad); l.setAttribute('x2', o.w * sc); l.setAttribute('y2', pad + o.h * sc);
    l.setAttribute('stroke', o.stroke || '#111'); l.setAttribute('stroke-width', Math.max(1, (o.strokeWidth || 3) * sc));
    svg.appendChild(l); d.appendChild(svg);
  } else if (o.type === 'image') {
    const img = document.createElement('img'); img.src = o.src || ''; img.alt = ''; img.style.objectFit = 'fill'; d.appendChild(img);
  }
  return d;
}
function renderStatic(design, ctx, sc) {
  const el = document.createElement('div');
  el.style.cssText = 'position:relative;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,.3);overflow:hidden;width:' + design.page.w * 10 * sc + 'px;height:' + design.page.h * 10 * sc + 'px';
  design.objects.forEach(o => { const d = buildObj(o, sc, ctx); d.style.pointerEvents = 'none'; el.appendChild(d); });
  return el;
}

/* ---------- state helpers ---------- */
const D = () => S.design;
const byId = id => D().objects.find(o => o.id === id);
const selected = () => D().objects.filter(o => E.sel.has(o.id));
const first = () => selected()[0];
function snap() { return JSON.stringify(D()); }
function pushUndo(s) { E.undo.push(s || snap()); if (E.undo.length > 60) E.undo.shift(); E.redo = []; }
function changed() { A.designChanged(); drawLabel(); }
function mutate(fn) { pushUndo(); selected().forEach(fn); changed(); drawTray(); }
function addObj(o) { pushUndo(); D().objects.push(o); E.sel = new Set([o.id]); changed(); drawTray(); }
const num = (v, d) => { v = parseFloat(v); return isFinite(v) ? v : d; };

/* ---------- drawing ---------- */
function layout() {
  const area = $('#edArea'), p = D().page, R = 18;
  const aw = area.clientWidth, ah = area.clientHeight;
  const sc = Math.max(0.2, Math.min((aw - R - 56) / (p.w * 10), (ah - R - 64) / (p.h * 10)));
  E.scale = sc;
  const W = p.w * 10 * sc, H = p.h * 10 * sc;
  const stage = $('#edStage');
  stage.style.width = (W + R) + 'px'; stage.style.height = (H + R) + 'px';
  stage.style.left = Math.max(4, (aw - W - R) / 2) + 'px';
  stage.style.top = Math.max(6, (ah - H - R) / 2 - 24) + 'px';
  const lab = $('#edLabel');
  lab.style.left = R + 'px'; lab.style.top = R + 'px'; lab.style.width = W + 'px'; lab.style.height = H + 'px';
  const rt = $('#rulerTop'), rl = $('#rulerLeft');
  rt.style.cssText = 'left:' + R + 'px;top:0;width:' + W + 'px;height:' + R + 'px';
  rl.style.cssText = 'left:0;top:' + R + 'px;width:' + R + 'px;height:' + H + 'px';
  const step = sc * 10 < 4 ? 2 : 1;
  let a = '', b = '';
  for (let mm = 0; mm <= p.w; mm += step) { const big = mm % 10 === 0, mid = mm % 5 === 0; a += '<i style="left:' + mm * 10 * sc + 'px;bottom:0;width:1px;height:' + (big ? 10 : mid ? 7 : 4) + 'px"></i>' + (big ? '<b style="left:' + (mm * 10 * sc + 2) + 'px;top:0">' + mm + '</b>' : ''); }
  for (let mm = 0; mm <= p.h; mm += step) { const big = mm % 10 === 0, mid = mm % 5 === 0; b += '<i style="top:' + mm * 10 * sc + 'px;right:0;height:1px;width:' + (big ? 10 : mid ? 7 : 4) + 'px"></i>' + (big ? '<b style="top:' + (mm * 10 * sc + 1) + 'px;left:1px">' + mm + '</b>' : ''); }
  rt.innerHTML = a; rl.innerHTML = b;
}
function drawLabel() {
  const lab = $('#edLabel'), ctx = A.previewCtx();
  lab.innerHTML = '';
  D().objects.forEach(o => lab.appendChild(buildObj(o, E.scale, ctx)));
  decorate();
}
function decorate() {
  const lab = $('#edLabel');
  $$('.obj', lab).forEach(el => {
    const on = E.sel.has(el.dataset.id);
    el.classList.toggle('sel', on && E.sel.size === 1);
    el.classList.toggle('multi', on && E.sel.size > 1);
    $$('.rz,.info', el).forEach(x => x.remove());
    if (on && E.sel.size === 1) {
      const o = byId(el.dataset.id);
      const i = document.createElement('button'); i.className = 'info'; i.type = 'button'; i.textContent = 'i';
      i.style.transform = 'rotate(' + (-(o.rotation || 0)) + 'deg)';
      const r = document.createElement('div'); r.className = 'rz';
      el.appendChild(i); el.appendChild(r);
    }
  });
  $('#edUndo').disabled = !E.undo.length; $('#edRedo').disabled = !E.redo.length;
  $('#edMulti').classList.toggle('on', E.multi);
  $('#edMultiText').textContent = E.multi ? 'Multi' : 'Single';
}
function selectOnly(ids) { E.sel = new Set(ids); decorate(); drawTray(); }

/* ---------- tray (the bottom property panel) ---------- */
function tile(label, value, act, cls) {
  const b = document.createElement('button'); b.type = 'button'; b.className = 'tile ' + (cls || '');
  b.innerHTML = label + '<b>' + value + '</b>';
  b.onclick = act; return b;
}
function stepTile(label, value, onMinus, onPlus) {
  const d = document.createElement('div'); d.className = 'tile step';
  d.innerHTML = '<button type="button" aria-label="less">−</button><div>' + label + '<b style="display:block">' + value + '</b></div><button type="button" aria-label="more">+</button>';
  const bs = $$('button', d); bs[0].onclick = onMinus; bs[1].onclick = onPlus; return d;
}
function drawTray() {
  const tray = $('#edTray'), o = first();
  tray.innerHTML = '';
  if (!o) { tray.innerHTML = '<div class="hintline">Tap something on the label to edit it,<br>or add Text, Barcode, QR, a picture or a shape from the tools above.</div>'; return; }
  const multi = E.sel.size > 1;
  const hasText = ['text', 'icon', 'barcode', 'qr'].includes(o.type);
  if (hasText && !multi) {
    const row = document.createElement('div'); row.className = 'txt';
    const inp = document.createElement('input'); inp.id = 'trayText'; inp.type = 'text'; inp.value = o.text || '';
    inp.placeholder = o.type === 'barcode' || o.type === 'qr' ? '{BARCODE}' : 'Type text — or {STYLE} {SIZE} …';
    inp.autocomplete = 'off'; inp.autocapitalize = 'off';
    inp.addEventListener('focus', () => { E.typing = false; });
    inp.addEventListener('input', () => {
      if (!E.typing) { pushUndo(); E.typing = true; }
      o.text = inp.value; A.designChanged(); drawLabel();
    });
    inp.addEventListener('blur', () => { E.typing = false; });
    row.appendChild(inp);
    const tk = document.createElement('button'); tk.type = 'button'; tk.className = 'btn small ghost'; tk.textContent = '{ }'; tk.onclick = openTokens; row.appendChild(tk);
    tray.appendChild(row);
  }
  const g = document.createElement('div'); g.className = 'tiles';
  const add = x => g.appendChild(x);
  if (o.type === 'text' || o.type === 'icon') {
    add(tile('Font', FONT_NAMES[o.font] || 'Sans', () => mutate(x => { if (x.size) x.font = FONTS[(FONTS.indexOf(x.font) + 1) % FONTS.length]; })));
    add(stepTile('Size', Math.round(o.size) / 10 + ' mm', () => mutate(sizeBy(-4)), () => mutate(sizeBy(4))));
    add(tile('Bold', o.bold ? 'On' : 'Off', () => mutate(x => { x.bold = !x.bold; }), o.bold ? 'on' : ''));
    add(tile('Italic', o.italic ? 'On' : 'Off', () => mutate(x => { x.italic = !x.italic; }), o.italic ? 'on' : ''));
    add(tile('Align', { left: 'Left', center: 'Centre', right: 'Right' }[o.align || 'left'], () => mutate(x => { x.align = { left: 'center', center: 'right', right: 'left' }[x.align || 'left']; })));
  }
  if (o.type === 'barcode') add(tile('Caption', o.showText === false ? 'Off' : 'On', () => mutate(x => { x.showText = x.showText === false; }), o.showText === false ? '' : 'on'));
  if (o.type === 'rect') {
    add(tile('Fill', o.fill && o.fill !== 'none' ? 'Black' : 'None', () => mutate(x => { x.fill = x.fill && x.fill !== 'none' ? 'none' : '#000000'; })));
    add(stepTile('Border', (o.strokeWidth == null ? 3 : o.strokeWidth) / 10 + ' mm', () => mutate(x => { x.strokeWidth = Math.max(0, (x.strokeWidth == null ? 3 : x.strokeWidth) - 1); }), () => mutate(x => { x.strokeWidth = Math.min(20, (x.strokeWidth == null ? 3 : x.strokeWidth) + 1); })));
    add(tile('Corners', o.radius ? 'Round' : 'Square', () => mutate(x => { x.radius = x.radius ? 0 : 20; })));
  }
  if (o.type === 'line') add(stepTile('Thickness', (o.strokeWidth || 3) / 10 + ' mm', () => mutate(x => { x.strokeWidth = Math.max(1, (x.strokeWidth || 3) - 1); }), () => mutate(x => { x.strokeWidth = Math.min(20, (x.strokeWidth || 3) + 1); })));
  if (o.type === 'image') add(tile('Picture', 'Replace', () => pickImage(o.id)));
  add(tile('Rotate', (o.rotation || 0) + '°', () => mutate(x => { x.rotation = ((x.rotation || 0) + 90) % 360; })));
  add(tile('Centre', 'Across', () => mutate(x => { x.x = Math.round((D().page.w * 10 - x.w) / 2); })));
  add(tile('Copy', 'Duplicate', duplicate));
  add(tile('Delete', '✕', removeSel, 'del'));
  tray.appendChild(g);
}
function sizeBy(d) {
  return x => {
    if (!x.size) return;
    x.size = Math.max(12, Math.min(160, x.size + d));
    x.h = Math.round(x.size * 1.25);
    if (x.type === 'icon') x.w = Math.max(x.w, Math.round(x.size * 1.1));
  };
}
function duplicate() {
  if (!E.sel.size) return;
  pushUndo();
  const copies = selected().map(o => Object.assign(JSON.parse(JSON.stringify(o)), { id: uid(), x: o.x + 20, y: o.y + 20 }));
  D().objects.push(...copies); E.sel = new Set(copies.map(c => c.id)); changed(); drawTray();
}
function removeSel() {
  if (!E.sel.size) return A.toast('Select an item first');
  pushUndo(); D().objects = D().objects.filter(o => !E.sel.has(o.id)); E.sel.clear(); changed(); drawTray();
}

/* ---------- sheets ---------- */
function openTokens() {
  const s = A.sheet('<h3>Insert a detail</h3><p class="hint">It is replaced by the product details of each label when printing.</p><div class="chips">' +
    ['{STYLE}', '{MODEL}', '{CATEGORY}', '{COLOUR}', '{SIZE}', '{BARCODE}'].map(t => '<button type="button" data-t="' + t + '">' + t + '</button>').join('') + '</div>');
  $$('button', s.el).forEach(b => b.onclick = () => {
    const o = first(); if (!o) return s.close();
    if (!E.typing) { pushUndo(); E.typing = true; }
    const inp = $('#trayText');
    const pos = inp && typeof inp.selectionStart === 'number' ? inp.selectionStart : (o.text || '').length;
    const cur = o.text || '';
    o.text = cur.slice(0, pos) + b.dataset.t + cur.slice(pos);
    E.typing = false; s.close(); changed(); drawTray();
  });
}
function openIcons() {
  const s = A.sheet('<h3>Pick an icon</h3><div class="swatches">' + ICONS.map(c => '<button type="button">' + c + '</button>').join('') + '</div>');
  $$('button', s.el).forEach(b => b.onclick = () => { s.close(); addObj(mk('icon', { text: b.textContent, x: 20, y: 20, w: 80, h: 75, size: 60, font: 'sans', bold: false, italic: false, align: 'center', color: '#000000' })); });
}
function openInfo() {
  const o = first(); if (!o) return;
  const f = (k, label, v, step) => '<label>' + label + '<input data-k="' + k + '" type="number" step="' + step + '" value="' + v + '"></label>';
  const s = A.sheet('<h3>' + esc({ text: 'Text', icon: 'Icon', barcode: 'Barcode', qr: 'QR code', rect: 'Rectangle', line: 'Line', image: 'Picture' }[o.type] || 'Item') + '</h3><div class="fields">' +
    f('x', 'Left (mm)', o.x / 10, 0.5) + f('y', 'Top (mm)', o.y / 10, 0.5) + f('w', 'Width (mm)', o.w / 10, 0.5) + f('h', 'Height (mm)', o.h / 10, 0.5) + f('rotation', 'Rotation (°)', o.rotation || 0, 90) +
    '</div><div class="btns"><button class="btn" type="button" id="infoOk">Done</button></div>');
  $('#infoOk', s.el).onclick = () => {
    pushUndo();
    $$('input', s.el).forEach(i => {
      const v = num(i.value, null); if (v == null) return;
      if (i.dataset.k === 'rotation') o.rotation = ((Math.round(v) % 360) + 360) % 360;
      else o[i.dataset.k] = Math.round(Math.max(i.dataset.k === 'w' || i.dataset.k === 'h' ? (o.type === 'line' ? -2000 : 5) : -2000, v * 10));
    });
    if (o.size && o.type === 'text') o.h = Math.max(o.h, 10);
    s.close(); changed(); drawTray();
  };
}
function openMore() {
  const p = D().page;
  const tpls = A.store.get('pls2Templates', []);
  const s = A.sheet('<h3>Label</h3><div id="moreBody"></div>');
  const draw = () => {
    const cur = D().page;
    $('#moreBody', s.el).innerHTML =
      '<label>Label size</label><div class="chips">' + PAGES.map(q => '<button type="button" data-w="' + q[0] + '" data-h="' + q[1] + '" style="' + (cur.w === q[0] && cur.h === q[1] ? 'border-color:#1c7ed6;background:#e7f1ff' : '') + '">' + q[0] + ' × ' + q[1] + '</button>').join('') + '</div>' +
      '<div class="fields"><label>Width (mm)<input id="mW" type="number" min="20" max="80" value="' + cur.w + '"></label><label>Height (mm)<input id="mH" type="number" min="10" max="100" value="' + cur.h + '"></label></div>' +
      '<p class="hint">The printer prints up to 48 mm across; wider designs are scaled to fit.</p>' +
      '<label>Start from a ready design</label><div class="chips"><button type="button" data-p="standard">Style · size · barcode</button><button type="button" data-p="barcode">Barcode only</button><button type="button" data-p="big">Big size</button></div>' +
      '<label>My designs</label><div class="list" id="tplList">' + (tpls.length ? tpls.map((t, i) => '<div><div class="grow">' + esc(t.name) + '</div><button class="btn small ghost" data-load="' + i + '" type="button">Use</button><button class="btn small ghost" data-del="' + i + '" type="button">Delete</button></div>').join('') : '<p class="hint">None saved yet.</p>') + '</div>' +
      '<div class="row"><input id="tplName" placeholder="Name this design" maxlength="30"><button class="btn small" id="tplSave" type="button">Save design</button></div>' +
      '<div class="row"><button class="btn small ghost" id="mClear" type="button">Clear label</button><button class="btn" id="mDone" type="button">Done</button></div>';
    const q = id => $(id, s.el);
    const setPage = (w, h) => { w = Math.max(20, Math.min(80, num(w, p.w))); h = Math.max(10, Math.min(100, num(h, p.h))); pushUndo(); D().page = { w, h }; layout(); changed(); draw(); };
    $$('[data-w]', s.el).forEach(b => b.onclick = () => setPage(+b.dataset.w, +b.dataset.h));
    q('#mW').onchange = () => setPage(q('#mW').value, q('#mH').value); q('#mH').onchange = () => setPage(q('#mW').value, q('#mH').value);
    $$('[data-p]', s.el).forEach(b => b.onclick = async () => {
      if (!(await A.ask({ title: 'Replace this label?', body: 'The current design is replaced. Undo brings it back.', ok: 'Replace' }))) return;
      pushUndo(); const keep = D().page; D().objects = PRESETS[b.dataset.p]().objects; D().page = { w: keep.w, h: keep.h }; E.sel.clear(); changed(); drawTray(); s.close();
    });
    $$('[data-load]', s.el).forEach(b => b.onclick = () => { pushUndo(); const t = JSON.parse(JSON.stringify(tpls[+b.dataset.load].design)); t.objects.forEach(o => { o.id = uid(); }); S.design = t; E.sel.clear(); layout(); changed(); drawTray(); s.close(); });
    $$('[data-del]', s.el).forEach(b => b.onclick = () => { tpls.splice(+b.dataset.del, 1); A.store.set('pls2Templates', tpls); draw(); });
    q('#tplSave').onclick = () => { const n = q('#tplName').value.trim(); if (!n) return A.toast('Give the design a name'); tpls.unshift({ name: n, design: JSON.parse(snap()) }); A.store.set('pls2Templates', tpls); draw(); A.toast('Design saved'); };
    q('#mClear').onclick = async () => { if (await A.ask({ title: 'Clear the label?', body: 'Everything on the label is removed. Undo brings it back.', ok: 'Clear', danger: true })) { pushUndo(); D().objects = []; E.sel.clear(); changed(); drawTray(); s.close(); } };
    q('#mDone').onclick = () => s.close();
  };
  draw();
}

/* ---------- picture insert ---------- */
function pickImage(replaceId) { E.replaceId = replaceId || null; const f = $('#edFile'); f.value = ''; f.click(); }
function loadImage(file) {
  if (!file) return;
  const fr = new FileReader();
  fr.onload = () => {
    const img = new Image();
    img.onload = () => {
      const max = 384, k = Math.min(1, max / Math.max(img.width, img.height));
      const c = document.createElement('canvas'); c.width = Math.max(1, Math.round(img.width * k)); c.height = Math.max(1, Math.round(img.height * k));
      const g = c.getContext('2d'); g.fillStyle = '#fff'; g.fillRect(0, 0, c.width, c.height); g.drawImage(img, 0, 0, c.width, c.height);
      const src = c.toDataURL('image/png');
      if (E.replaceId && byId(E.replaceId)) { pushUndo(); byId(E.replaceId).src = src; changed(); drawTray(); }
      else {
        const w = 160, h = Math.round(160 * c.height / c.width);
        addObj(mk('image', { src, x: 20, y: 20, w, h }));
      }
      E.replaceId = null;
    };
    img.onerror = () => A.toast('That picture could not be read');
    img.src = fr.result;
  };
  fr.readAsDataURL(file);
}

/* ---------- pointer interaction ---------- */
function bindPointer() {
  const lab = $('#edLabel');
  lab.addEventListener('pointerdown', e => {
    const info = e.target.closest('.info'), rz = e.target.closest('.rz'), ob = e.target.closest('.obj');
    if (info) { e.preventDefault(); openInfo(); return; }
    if (!ob) { if (E.sel.size) selectOnly([]); return; }
    const id = ob.dataset.id;
    e.preventDefault();
    if (rz) return startResize(e, id);
    if (E.multi) { if (E.sel.has(id)) E.sel.delete(id); else E.sel.add(id); }
    else if (!E.sel.has(id)) E.sel = new Set([id]);
    decorate(); drawTray();
    if (E.sel.has(id)) startDrag(e);
  });
}
function track(e, move, up) {
  const mv = ev => move(ev), end = ev => { document.removeEventListener('pointermove', mv); document.removeEventListener('pointerup', end); document.removeEventListener('pointercancel', end); up(ev); };
  document.addEventListener('pointermove', mv); document.addEventListener('pointerup', end); document.addEventListener('pointercancel', end);
}
function startDrag(e) {
  const sx = e.clientX, sy = e.clientY, before = snap(), objs = selected().map(o => ({ o, x: o.x, y: o.y }));
  let moved = false;
  track(e, ev => {
    const dx = ev.clientX - sx, dy = ev.clientY - sy;
    if (!moved && Math.hypot(dx, dy) < 4) return;
    if (!moved) { moved = true; pushUndo(before); }
    objs.forEach(m => {
      m.o.x = Math.round(m.x + dx / E.scale); m.o.y = Math.round(m.y + dy / E.scale);
      const el = $('#edLabel .obj[data-id="' + m.o.id + '"]');
      if (el) { el.style.left = m.o.x * E.scale + 'px'; el.style.top = (m.o.y * E.scale - (m.o.type === 'line' ? 7 : 0)) + 'px'; }
    });
  }, () => { if (moved) { A.designChanged(); } });
}
function startResize(e, id) {
  const o = byId(id); if (!o) return;
  const sx = e.clientX, sy = e.clientY, before = snap(), w0 = o.w, h0 = o.h, s0 = o.size, rot = (o.rotation || 0) * Math.PI / 180;
  let moved = false;
  track(e, ev => {
    let dx = (ev.clientX - sx) / E.scale, dy = (ev.clientY - sy) / E.scale;
    // movement measured in the object's own (rotated) frame
    const lx = dx * Math.cos(rot) + dy * Math.sin(rot), ly = -dx * Math.sin(rot) + dy * Math.cos(rot);
    if (!moved && Math.hypot(dx, dy) * E.scale < 3) return;
    if (!moved) { moved = true; pushUndo(before); }
    if (o.type === 'text' || o.type === 'icon') {
      const k = Math.max((h0 + ly) / h0, 0.3);
      o.size = Math.max(12, Math.min(160, Math.round(s0 * k))); o.h = Math.round(o.size * 1.25);
      o.w = Math.max(30, Math.round(w0 + lx));
    } else if (o.type === 'qr') {
      const v = Math.max(40, Math.round(w0 + Math.max(lx, ly))); o.w = v; o.h = v;
    } else if (o.type === 'line') {
      o.w = Math.round(w0 + lx); o.h = Math.round(h0 + ly);
    } else {
      o.w = Math.max(10, Math.round(w0 + lx)); o.h = Math.max(10, Math.round(h0 + ly));
    }
    drawLabel();
  }, () => { if (moved) { A.designChanged(); drawTray(); } });
}

/* ---------- top bar & tools ---------- */
function undo() { if (!E.undo.length) return; E.redo.push(snap()); S.design = JSON.parse(E.undo.pop()); afterHistory(); }
function redo() { if (!E.redo.length) return; E.undo.push(snap()); S.design = JSON.parse(E.redo.pop()); afterHistory(); }
function afterHistory() {
  const ids = new Set(D().objects.map(o => o.id)); E.sel = new Set([...E.sel].filter(i => ids.has(i)));
  layout(); A.designChanged(); drawLabel(); drawTray();
}
function tool(t) {
  if (t === 'text') addObj(txt('Text', 20, 20, 300, 36));
  else if (t === 'icon') openIcons();
  else if (t === 'barcode') addObj(mk('barcode', { text: '{BARCODE}', x: 20, y: 150, w: 460, h: 120, showText: true }));
  else if (t === 'qr') addObj(mk('qr', { text: '{BARCODE}', x: 20, y: 100, w: 120, h: 120 }));
  else if (t === 'insert') pickImage(null);
  else if (t === 'rect') addObj(mk('rect', { x: 10, y: 10, w: 480, h: 280, fill: 'none', stroke: '#111111', strokeWidth: 3, radius: 0 }));
  else if (t === 'line') addObj(mk('line', { x: 20, y: 150, w: 460, h: 0, stroke: '#111111', strokeWidth: 3 }));
  else if (t === 'delete') removeSel();
  else if (t === 'edit') {
    const o = first(); if (!o) return A.toast('Select an item first');
    if (o.type === 'image') pickImage(o.id);
    else { const i = $('#trayText'); if (i) { i.focus(); i.select(); } else openInfo(); }
  }
}
function open() {
  $('#editor').hidden = false; E.opened = true;
  requestAnimationFrame(() => { layout(); drawLabel(); drawTray(); });
}
function close() { $('#editor').hidden = true; E.opened = false; A.designChanged(); }

function init() {
  $('#edReturn').onclick = close;
  $('#edSave').onclick = () => { A.designChanged(); A.toast('Label saved'); };
  $('#edMore').onclick = openMore;
  $('#edAll').onclick = () => selectOnly(D().objects.map(o => o.id));
  $('#edMulti').onclick = () => { E.multi = !E.multi; decorate(); };
  $('#edUndo').onclick = undo; $('#edRedo').onclick = redo;
  $$('.ed-tools button').forEach(b => b.onclick = () => tool(b.dataset.tool));
  $('#edFile').onchange = e => loadImage(e.target.files[0]);
  $('#edPrint').onclick = () => A.printTest();
  bindPointer();
  window.addEventListener('resize', () => { if (E.opened) { layout(); drawLabel(); } });
}
init();

window.Editor = { open, close, isOpen: () => E.opened, renderStatic, defaultDesign, _state: E };
})();
