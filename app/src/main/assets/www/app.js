(function () {
'use strict';

/* ---------- small helpers ---------- */
const $ = (s, r) => (r || document).querySelector(s);
const $$ = (s, r) => Array.from((r || document).querySelectorAll(s));
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* storage unavailable */ } }
};
const pad6 = n => String(n).padStart(6, '0');

const SIZES = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '12', '14'];
const CATS = ['Kurta Pyjama', 'Kurta Jacket Pyjama', 'Kurta Jacket Dhoti Pyjama', 'Indo Western', 'Jacket Dhoti', 'Coat Suit', 'Sherwani', 'Jodhpuri Suit', 'Pathani Suit', 'Nehru Jacket Set', 'Dhoti Kurta', 'Waistcoat Set', 'Bandhgala Suit', 'Achkan Set', 'Shirt Pant', 'Blazer Set', 'Other'];

/* ---------- toast ---------- */
let toastTimer = 0;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg; t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2600);
}

/* ---------- sheets / dialogs (we never use alert/confirm) ---------- */
const stack = [];
function sheet(html, opts) {
  opts = opts || {};
  const scrim = document.createElement('div');
  scrim.className = 'scrim' + (opts.mid ? ' mid' : '');
  scrim.innerHTML = '<div class="sheet">' + html + '</div>';
  $('#overlay').appendChild(scrim);
  const api = {
    el: scrim.firstChild,
    close() {
      const i = stack.indexOf(api);
      if (i >= 0) stack.splice(i, 1);
      scrim.remove();
      if (opts.onClose) opts.onClose();
    }
  };
  scrim.addEventListener('pointerdown', e => { if (e.target === scrim && !opts.modal) api.close(); });
  stack.push(api);
  return api;
}
function ask(o) {
  return new Promise(resolve => {
    const s = sheet('<h3>' + esc(o.title || '') + '</h3><div>' + (o.html || esc(o.body || '')) + '</div><div class="btns">' +
      (o.cancel === false ? '' : '<button class="btn ghost" data-r="0" type="button">' + esc(o.cancel || 'Cancel') + '</button>') +
      '<button class="btn' + (o.danger ? ' danger' : '') + '" data-r="1" type="button">' + esc(o.ok || 'OK') + '</button></div>',
      { mid: true, modal: true, onClose: () => resolve(false) });
    $$('button[data-r]', s.el).forEach(b => b.onclick = () => {
      const r = b.dataset.r === '1';
      stack.splice(stack.indexOf(s), 1); s.el.parentNode.remove(); resolve(r);
    });
  });
}

/* ---------- bridge to the phone (with a browser stand-in for testing) ---------- */
const native = window.AndroidPrinter;
const parse = (s, d) => { try { return JSON.parse(s); } catch (e) { return d; } };
let B;
if (native) {
  B = {
    real: true,
    getState: () => parse(native.getState(), {}),
    listPrinters: () => parse(native.listPrinters(), []),
    connect: a => native.connect(a), disconnect: () => native.disconnect(), cancel: () => native.cancel(),
    print: r => native.print(JSON.stringify(r)),
    setNext: n => native.setNext(n), resolvePending: a => native.resolvePending(a),
    codeSvg: (k, t) => native.codeSvg(k, t),
    saveFile: (n, m, c) => native.saveFile(n, m, c)
  };
} else {
  B = makeMock();
}
window.__bridge = B;

function makeMock() {
  const m = { real: false, next: store.get('mockNext', 1), pending: store.get('mockPending', null), printer: { state: 'disconnected', name: '', address: '', message: '' }, printing: false, cancel: false, log: [], failAt: 0 };
  const ev = (t, o) => setTimeout(() => window.onAndroidEvent && window.onAndroidEvent(t, JSON.stringify(o)), 0);
  const bc = () => ({ next: m.next > 999999 ? 'LIMIT' : pad6(m.next), pending: m.pending });
  const save = () => { store.set('mockNext', m.next); store.set('mockPending', m.pending); };
  return {
    real: false, mock: m,
    getState: () => ({ printer: m.printer, barcode: bc(), permission: true, bluetooth: true, printing: m.printing }),
    listPrinters: () => [{ name: 'P58D-1234', address: 'AA:BB:CC:00:11:22', likely: true }, { name: 'Headphones', address: 'AA:BB:CC:00:11:33', likely: false }],
    connect(a) { m.printer = { state: 'connecting', name: 'P58D-1234', address: a, message: '' }; ev('printer', m.printer); setTimeout(() => { m.printer = { state: 'connected', name: 'P58D-1234', address: a, message: '' }; ev('printer', m.printer); }, 20); },
    disconnect() { m.printer.state = 'disconnected'; ev('printer', m.printer); },
    cancel() { m.cancel = true; },
    setNext(n) { if (m.pending || n < 1 || n > 999999) return false; m.next = n; save(); ev('barcode', bc()); return true; },
    resolvePending(a) { if (!m.pending) return false; if (a === 'used') m.next = parseInt(m.pending.barcode, 10) + 1; m.pending = null; save(); ev('barcode', bc()); return true; },
    codeSvg(kind, text) {
      if (!text) return '';
      let d = '', x = 0;
      for (let i = 0; i < 40; i++) { const w = 1 + ((text.charCodeAt(i % text.length) + i) % 3); if (i % 2 === 0) d += 'M' + x + ' 0h' + w + 'v1h-' + w + 'z'; x += w; }
      return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + x + ' 1" preserveAspectRatio="none"><path d="' + d + '"/></svg>';
    },
    saveFile(n, mime, c) { m.log.push({ saved: n, size: c.length }); return true; },
    print(r) {
      m.log.push(r);
      if (m.printing) return 'A print is already running.';
      if (!r.items || !r.items.length) return 'Choose a size and quantity.';
      const jobs = [];
      r.items.forEach(it => { for (let i = 0; i < it.quantity; i++) jobs.push(it.size); });
      m.printing = true; m.cancel = false;
      let sent = 0;
      const step = () => {
        if (m.cancel) { m.printing = false; return ev('progress', { state: 'cancelled', sent, total: jobs.length, message: '' }); }
        if (sent >= jobs.length) { m.printing = false; return ev('progress', { state: 'done', sent, total: jobs.length, message: '' }); }
        let code = '';
        if (r.mode === 'reprint') code = r.fixedBarcode;
        else if (r.barcodeMode === 'auto') {
          if (m.next > 999999) { m.printing = false; return ev('progress', { state: 'limit', sent, total: jobs.length, message: 'All 999999 barcode numbers are used.' }); }
          code = pad6(m.next);
          if (m.failAt && sent + 1 === m.failAt) {
            m.pending = { barcode: code, style: r.product.style, model: r.product.model, category: r.product.category, colour: r.product.colour, size: jobs[sent] };
            save(); m.printing = false; m.failAt = 0; ev('barcode', bc());
            return ev('progress', { state: 'uncertain', sent, total: jobs.length, message: 'Connection lost while sending label ' + (sent + 1) + ' of ' + jobs.length + '. Check the last label that came out.' });
          }
          m.next++; save();
        } else if (r.barcodeMode === 'manual') code = r.manualBarcode;
        if (r.recoverPending && m.pending && sent === 0) { m.next = parseInt(m.pending.barcode, 10) + 1; m.pending = null; save(); }
        sent++;
        ev('label', { barcode: code, style: r.product.style, model: r.product.model, category: r.product.category, colour: r.product.colour, size: jobs[sent - 1] });
        ev('barcode', bc());
        ev('progress', { state: 'printing', sent, total: jobs.length, message: '' });
        setTimeout(step, 15);
      };
      ev('progress', { state: 'printing', sent: 0, total: jobs.length, message: '' });
      setTimeout(step, 15);
      return 'ok';
    }
  };
}

/* ---------- app state ---------- */
const S = {
  product: store.get('pls2Product', { style: '', model: '', category: CATS[0], colour: '' }),
  sizes: store.get('pls2Sizes', {}),
  barcodeMode: store.get('pls2BarcodeMode', 'auto'),
  manual: store.get('pls2Manual', ''),
  options: Object.assign({ paperType: 'gap', gapMode: 'ff', gapMm: 3, alignMm: 0, darkness: 160, feedMm: 0, lastFeedMm: 0 }, store.get('pls2OptionsV2', {})),
  design: store.get('pls2Design', null),
  saved: store.get('pls2Products', []),
  history: store.get('pls2History', []),
  bc: { next: '000001', pending: null },
  printer: { state: 'disconnected', name: '', address: '', message: '' },
  permission: true,
  printing: false
};
const persist = () => {
  store.set('pls2Product', S.product); store.set('pls2Sizes', S.sizes); store.set('pls2BarcodeMode', S.barcodeMode);
  store.set('pls2Manual', S.manual); store.set('pls2OptionsV2', S.options); store.set('pls2Products', S.saved);
};

/* tokens, mirrored from the Java side so preview and print agree */
function tokens(text, ctx) {
  return String(text == null ? '' : text).replace(/\{([A-Za-z]+)\}/g, (all, name) => {
    switch (name.toUpperCase()) {
      case 'STYLE': return ctx.style || '';
      case 'MODEL': return ctx.model || '';
      case 'CATEGORY': return ctx.category || '';
      case 'COLOUR': case 'COLOR': return ctx.colour || '';
      case 'SIZE': return ctx.size || '';
      case 'BARCODE': return ctx.barcode || '';
      default: return all;
    }
  });
}
function previewCtx() {
  const size = SIZES.find(s => (S.sizes[s] | 0) > 0) || '0';
  let barcode = '';
  if (S.barcodeMode === 'auto') barcode = S.bc.next === 'LIMIT' ? '' : S.bc.next;
  else if (S.barcodeMode === 'manual') barcode = S.manual;
  return { style: S.product.style, model: S.product.model, category: S.product.category, colour: S.product.colour, size, barcode };
}

/* ---------- home screen ---------- */
function productFromForm() {
  const cat = $('#fCategory').value;
  S.product = {
    style: $('#fStyle').value.trim(), model: $('#fModel').value.trim(), colour: $('#fColour').value.trim(),
    category: cat === 'Other' && $('#fCustomCat').value.trim() ? $('#fCustomCat').value.trim() : cat
  };
  persist(); renderPreview();
}
function fillForm() {
  $('#fStyle').value = S.product.style || ''; $('#fModel').value = S.product.model || '';
  $('#fColour').value = S.product.colour || '';
  const known = CATS.includes(S.product.category);
  $('#fCategory').value = known ? S.product.category : 'Other';
  $('#customCatWrap').hidden = $('#fCategory').value !== 'Other';
  $('#fCustomCat').value = known ? '' : (S.product.category || '');
}
function renderSaved() {
  const sel = $('#savedProducts');
  sel.innerHTML = '<option value="">Saved products…</option>' + S.saved.map((p, i) => '<option value="' + i + '">' + esc([p.style, p.model, p.colour].filter(Boolean).join(' · ') || 'Untitled') + '</option>').join('');
}
function renderSizes() {
  $('#sizeGrid').innerHTML = SIZES.map(s => {
    const q = S.sizes[s] | 0;
    return '<div class="size' + (q ? ' has' : '') + '" data-s="' + s + '"><label class="pick"><input type="checkbox" class="chk"' + (q ? ' checked' : '') + ' aria-label="Select size ' + s + '"> <b>Size ' + s + '</b></label><div class="q"><button type="button" data-d="-1" aria-label="less">−</button><input inputmode="numeric" value="' + q + '" aria-label="Quantity size ' + s + '"><button type="button" data-d="1" aria-label="more">+</button></div></div>';
  }).join('');
  updateTotal();
}
function total() { return SIZES.reduce((n, s) => n + (S.sizes[s] | 0), 0); }
function updateTotal() {
  const t = total();
  $('#totalText').textContent = t + (t === 1 ? ' label' : ' labels');
  $$('#sizeGrid .size').forEach(el => el.classList.toggle('has', (S.sizes[el.dataset.s] | 0) > 0));
}
function setQty(s, v) {
  v = Math.max(0, Math.min(200, v | 0));
  S.sizes[s] = v; persist();
}
function renderBarcodeUI() {
  $$('#barcodeSeg button').forEach(b => b.classList.toggle('on', b.dataset.mode === S.barcodeMode));
  $('#barcodeAuto').hidden = S.barcodeMode !== 'auto';
  $('#barcodeManual').hidden = S.barcodeMode !== 'manual';
  $('#nextNumber').textContent = S.bc.next;
  const p = S.bc.pending;
  $('#pendingBanner').hidden = !p;
  if (p) $('#pendingBannerText').textContent = 'Label ' + p.barcode + ' may not have printed. Check the printer and decide.';
  renderPreview();
}
function renderPrinterChip() {
  const p = S.printer, chip = $('#printerChip');
  chip.className = 'chip ' + (p.state || '');
  $('#printerChipText').textContent =
    p.state === 'connected' ? p.name || 'Printer connected' :
    p.state === 'connecting' ? 'Connecting…' :
    p.state === 'error' ? 'Not connected' : (p.name ? p.name + ' (off)' : 'No printer');
}
function renderPreview() {
  if (!window.Editor) return;
  const box = $('#homePreview');
  box.innerHTML = '';
  const page = S.design.page;
  const scale = Math.min(300 / (page.w * 10), 150 / (page.h * 10));
  box.appendChild(window.Editor.renderStatic(S.design, previewCtx(), scale));
}

/* ---------- printer sheet ---------- */
function openPrinterSheet() {
  const s = sheet('<h3>Printer</h3><div id="prState"></div><div class="list" id="prList"></div><div class="btns"><button class="btn ghost" id="prClose" type="button">Close</button><button class="btn" id="prRecon" type="button">Reconnect</button></div>');
  const draw = () => {
    if (!s.el.isConnected) return;
    const p = S.printer;
    $('#prState', s.el).innerHTML = '<p class="hint">' + (p.state === 'connected' ? 'Connected to <b>' + esc(p.name) + '</b>.' : p.state === 'connecting' ? 'Connecting…' : esc(p.message) || 'Not connected. Pair the P58D in Android Bluetooth settings first, then pick it below.') + '</p>';
    const list = B.listPrinters();
    $('#prList', s.el).innerHTML = list.length ? list.map(d => '<div><div class="grow">' + esc(d.name) + '<small>' + esc(d.address) + '</small></div>' + (d.likely ? '<span class="pill">printer</span>' : '') + '<button class="btn small" data-a="' + esc(d.address) + '" type="button">' + (p.address === d.address && p.state === 'connected' ? 'Connected' : 'Connect') + '</button></div>').join('') : '<p class="hint">No paired Bluetooth devices found' + (S.permission ? '.' : ' — allow the Bluetooth permission.') + '</p>';
    $$('#prList button', s.el).forEach(b => b.onclick = () => { B.connect(b.dataset.a); });
  };
  s.redraw = draw; draw();
  $('#prClose', s.el).onclick = () => s.close();
  $('#prRecon', s.el).onclick = () => { if (S.printer.address) B.connect(S.printer.address); else toast('Pick a printer first'); };
  S.printerSheet = s;
}

/* ---------- print settings ---------- */
function openSettings() {
  const o = S.options;
  const s = sheet('<h3>Print settings</h3>' +
    '<label>Paper<select id="oPaper"><option value="gap">Gap labels (die-cut)</option><option value="continuous">Continuous paper</option></select></label>' +
    '<label>Gap method (gap paper)<select id="oMode"><option value="rows">Fixed gap — blank rows (default)</option><option value="gsff">Printer finds the gap — command GS FF</option><option value="ff">Printer finds the gap — command FF</option></select></label>' +
    '<div class="fields"><label>Gap between labels (mm) — fixed gap only<input id="oGap" type="number" step="0.5" min="0" max="10"></label>' +
    '<label>Shift left/right (mm)<input id="oAlign" type="number" step="0.5" min="-3" max="3"></label>' +
    '<label>Darkness (80–220)<input id="oDark" type="number" step="10" min="80" max="220"></label>' +
    '<label>Feed after label (mm, continuous)<input id="oFeed" type="number" step="1" min="0" max="10"></label>' +
    '<label>Feed after last label (mm)<input id="oLast" type="number" step="1" min="0" max="10"></label></div>' +
    '<p class="hint">Gap paper: the printer moves on by the gap after each label. If labels creep UP the roll, increase the gap by 0.5 mm; if they creep DOWN, decrease it. Feed fields below are for continuous paper only.</p>' +
    '<div class="btns"><button class="btn ghost" id="oTest" type="button">Gap test (3 methods × 2 labels)</button><button class="btn" id="oOk" type="button">Done</button></div>');
  $('#oTest', s.el).onclick = () => {
    const cur = { paperType: $('#oPaper', s.el).value, gapMode: $('#oMode', s.el).value, gapMm: n2('#oGap', 3), alignMm: n2('#oAlign', 0), darkness: n2('#oDark', 160), feedMm: 0, lastFeedMm: 0 };
    s.close();
    S.gapTest = { queue: ['rows', 'gsff', 'ff'], restore: S.options };
    S.options = Object.assign({}, cur, { paperType: 'gap' });
    nextGapTest();
  };
  function n2(id, d) { const v = parseFloat($(id, s.el).value); return isFinite(v) ? v : d; }
  $('#oPaper', s.el).value = o.paperType; $('#oMode', s.el).value = o.gapMode || 'rows'; $('#oGap', s.el).value = o.gapMm; $('#oAlign', s.el).value = o.alignMm; $('#oDark', s.el).value = o.darkness;
  $('#oFeed', s.el).value = o.feedMm; $('#oLast', s.el).value = o.lastFeedMm;
  $('#oOk', s.el).onclick = () => {
    const n = (id, lo, hi, d) => { const v = parseFloat($(id, s.el).value); return isFinite(v) ? Math.max(lo, Math.min(hi, v)) : d; };
    S.options = { paperType: $('#oPaper', s.el).value, gapMode: $('#oMode', s.el).value, gapMm: n('#oGap', 0, 10, 3), alignMm: n('#oAlign', -3, 3, 0), darkness: n('#oDark', 80, 220, 160), feedMm: n('#oFeed', 0, 10, 0), lastFeedMm: n('#oLast', 0, 10, 0) };
    persist(); s.close(); toast('Settings saved');
  };
}

/* ---------- barcode manager ---------- */
function openBarcodeManager() {
  const s = sheet('<div id="bmBody"></div>');
  S.bcSheet = s;
  drawBarcodeManager();
}
function drawBarcodeManager() {
  const s = S.bcSheet;
  if (!s || !s.el.isConnected) return;
  const p = S.bc.pending;
  let h = '<h3>Barcode numbers</h3>';
  if (p) {
    h += '<div class="banner warn" style="display:block"><b>Label ' + esc(p.barcode) + ' is waiting for you</b><p class="hint" style="margin:4px 0 8px">The connection dropped while this label was being sent. Look at the printer: did the label come out complete?</p>' +
      '<div class="btns" style="flex-wrap:wrap"><button class="btn small" id="bmUsed" type="button">It printed</button><button class="btn small ghost" id="bmReprint" type="button">Reprint it</button><button class="btn small danger" id="bmDiscard" type="button">It did not print — reuse number</button></div></div>';
  }
  h += '<div class="row"><div class="grow">Next number <b class="mono">' + esc(S.bc.next) + '</b></div></div>' +
    '<div class="row"><input id="bmSet" inputmode="numeric" placeholder="Set next number (1–999999)" ' + (p ? 'disabled' : '') + '><button class="btn small" id="bmSetBtn" type="button" ' + (p ? 'disabled' : '') + '>Set</button></div>' +
    '<div class="row"><h3 class="grow" style="margin:8px 0 0">History</h3><button class="btn small ghost" id="bmExport" type="button">Export</button><button class="btn small ghost" id="bmClear" type="button">Clear</button></div>' +
    '<div class="list" id="bmList">' + (S.history.length ? S.history.slice(0, 60).map((e, i) => '<div><div class="grow"><b class="mono">' + esc(e.barcode) + '</b><small>' + esc([e.style, e.model, e.colour, 'size ' + e.size].filter(Boolean).join(' · ')) + ' · ' + esc(new Date(e.time).toLocaleString()) + '</small></div><button class="btn small ghost" data-i="' + i + '" type="button">Reprint</button></div>').join('') : '<p class="hint">Nothing printed yet.</p>') + '</div>' +
    '<div class="btns"><button class="btn ghost" id="bmClose" type="button">Close</button></div>';
  $('#bmBody', s.el).innerHTML = h;
  const q = id => $(id, s.el);
  q('#bmClose').onclick = () => s.close();
  if (p) {
    q('#bmUsed').onclick = () => { B.resolvePending('used'); toast('Counted as printed'); };
    q('#bmDiscard').onclick = () => { B.resolvePending('discard'); toast('Number ' + p.barcode + ' will be used again'); };
    q('#bmReprint').onclick = () => { s.close(); reprintPending(p); };
  }
  q('#bmSetBtn').onclick = async () => {
    const n = parseInt(q('#bmSet').value, 10);
    if (!(n >= 1 && n <= 999999)) return toast('Enter a number from 1 to 999999');
    if (!(await ask({ title: 'Set next number?', body: 'The next label will be ' + pad6(n) + '. Labels with the same number may already exist.', ok: 'Set number' }))) return;
    if (!B.setNext(n)) toast('Cannot change the number right now');
  };
  q('#bmExport').onclick = () => {
    const rows = ['barcode,style,model,category,colour,size,time'].concat(S.history.map(e => [e.barcode, e.style, e.model, e.category, e.colour, e.size, new Date(e.time).toISOString()].map(v => '"' + String(v == null ? '' : v).replace(/"/g, '""') + '"').join(',')));
    toast(B.saveFile('barcode-history.csv', 'text/csv', rows.join('\n')) ? 'Saved to Downloads' : 'Could not save the file');
  };
  q('#bmClear').onclick = async () => {
    if (await ask({ title: 'Clear history?', body: 'Only the list is cleared. The next number does not change.', ok: 'Clear', danger: true })) { S.history = []; store.set('pls2History', []); drawBarcodeManager(); }
  };
  $$('#bmList button', s.el).forEach(b => b.onclick = () => { const e = S.history[+b.dataset.i]; s.close(); reprintEntry(e); });
}

/* ---------- printing ---------- */
function request(mode, items, extra) {
  return Object.assign({ mode, product: S.product, barcodeMode: S.barcodeMode, manualBarcode: S.manual, items, design: S.design, options: S.options }, extra || {});
}
function endGapTest(msg) {
  const t = S.gapTest;
  if (t) S.options = t.restore;
  S.gapTest = null; persist();
  if (msg) toast(msg);
}
function nextGapTest() {
  const t = S.gapTest;
  if (!t) return;
  const mode = t.queue.shift();
  if (!mode) return endGapTest('Gap test finished. Your settings are restored.');
  S.options.gapMode = mode; persist();
  toast('Gap test: ' + ({ rows: 'fixed gap', gsff: 'GS FF', ff: 'FF' })[mode] + ' — 2 labels');
  // A fixed test barcode, so the test never uses up numbers from the real sequence.
  const res = B.print(request('bulk', [{ size: '0', quantity: 2 }], { barcodeMode: 'manual', manualBarcode: '000000' }));
  if (res !== 'ok') { endGapTest(''); ask({ title: 'Gap test stopped', body: String(res), ok: 'OK', cancel: false }); }
}
function send(r) {
  const res = B.print(r);
  if (res !== 'ok') ask({ title: 'Could not start', body: String(res), ok: 'OK', cancel: false });
}
function itemsFromQuantities() {
  return SIZES.filter(s => (S.sizes[s] | 0) > 0).map(s => ({ size: s, quantity: S.sizes[s] | 0 }));
}
function checkBarcodeReady() {
  if (S.barcodeMode === 'manual' && !S.manual.trim()) { toast('Type the barcode text first'); return false; }
  if (S.barcodeMode === 'auto' && S.bc.pending) { toast('Resolve the waiting label first'); openBarcodeManager(); return false; }
  if (S.barcodeMode === 'auto' && S.bc.next === 'LIMIT') { toast('All barcode numbers are used'); return false; }
  return true;
}
async function printBulk() {
  if (S.printing) return;
  const items = itemsFromQuantities(), n = total();
  if (!n) return toast('Set a quantity for at least one size');
  if (n > 500) return toast('At most 500 labels in one print');
  if (!checkBarcodeReady()) return;
  const sizesText = items.map(i => i.quantity + '× size ' + i.size).join(', ');
  const range = S.barcodeMode === 'auto' ? '<br>Barcode numbers ' + S.bc.next + ' onward.' : '';
  if (!(await ask({ title: 'Print ' + n + ' labels?', html: esc(sizesText) + range, ok: 'Continue' }))) return;
  if (n > 1 && !(await ask({ title: 'Really print ' + n + ' labels?', html: 'This will use ' + n + ' labels' + (S.barcodeMode === 'auto' ? ' and ' + n + ' barcode numbers' : '') + '. You can cancel while printing.', ok: 'Yes, print ' + n, danger: true }))) return;
  send(request('bulk', items));
}
function printTest() {
  if (S.printing) return;
  if (!checkBarcodeReady()) return;
  const size = SIZES.find(s => (S.sizes[s] | 0) > 0) || '0';
  send(request('single', [{ size, quantity: 1 }]));
}
function reprintEntry(e) {
  if (!e) return;
  ask({ title: 'Reprint ' + e.barcode + '?', body: 'One label, same number. The next number does not change.', ok: 'Reprint' }).then(ok => {
    if (!ok) return;
    send(Object.assign(request('reprint', [{ size: e.size || '0', quantity: 1 }], { fixedBarcode: e.barcode }), { product: { style: e.style, model: e.model, category: e.category, colour: e.colour } }));
  });
}
function reprintPending(p) {
  send(Object.assign(request('reprint', [{ size: p.size || '0', quantity: 1 }], { fixedBarcode: p.barcode, recoverPending: true }), { product: { style: p.style, model: p.model, category: p.category, colour: p.colour } }));
}

/* ---------- events from the phone ---------- */
window.onAndroidEvent = function (type, json) {
  const d = parse(json, {});
  if (type === 'printer') {
    S.printer = d; renderPrinterChip();
    if (S.printerSheet && S.printerSheet.redraw) S.printerSheet.redraw();
    if (d.state === 'error' && d.message) toast(d.message);
  } else if (type === 'barcode') {
    S.bc = { next: d.next, pending: d.pending || null };
    renderBarcodeUI(); drawBarcodeManager();
  } else if (type === 'label') {
    if (d.barcode) {
      S.history.unshift({ barcode: d.barcode, style: d.style, model: d.model, category: d.category, colour: d.colour, size: d.size, time: Date.now() });
      if (S.history.length > 2000) S.history.length = 2000;
      store.set('pls2History', S.history);
    }
  } else if (type === 'progress') {
    onProgress(d);
  } else if (type === 'permission') {
    S.permission = !!d.granted;
    if (!d.granted) toast('Bluetooth permission is needed to print');
  } else if (type === 'toast') {
    toast(d.message || '');
  }
};
function onProgress(d) {
  S.printing = d.state === 'printing';
  $('#progress').hidden = !S.printing && !(d.state === 'done');
  $('#printBulk').disabled = S.printing; $('#printTest').disabled = S.printing;
  if (S.printing) {
    $('#progressText').textContent = 'Printing ' + d.sent + ' of ' + d.total;
    $('#progressBar').style.width = (d.total ? 100 * d.sent / d.total : 0) + '%';
    $('#progressCancel').hidden = false;
    return;
  }
  $('#progress').hidden = true;
  if (S.gapTest && d.state === 'done') { setTimeout(nextGapTest, 1200); return; }
  if (S.gapTest && d.state !== 'printing') { endGapTest('Gap test stopped: ' + (d.message || d.state)); }
  if (d.state === 'done') toast('Printed ' + d.sent + (d.sent === 1 ? ' label' : ' labels'));
  else if (d.state === 'cancelled') toast('Cancelled after ' + d.sent + ' of ' + d.total);
  else if (d.state === 'uncertain') ask({ title: 'Check the last label', body: d.message + ' Open the barcode manager to say whether it printed.', ok: 'Open manager', cancel: 'Later' }).then(ok => { if (ok) openBarcodeManager(); });
  else ask({ title: d.state === 'limit' ? 'Numbers used up' : 'Printing stopped', body: d.message + (d.sent ? ' (' + d.sent + ' of ' + d.total + ' printed.)' : ''), ok: 'OK', cancel: false });
}

/* ---------- back button ---------- */
window.onAndroidBack = function () {
  if (stack.length) { const t = stack[stack.length - 1]; t.close(); return true; }
  if (window.Editor && window.Editor.isOpen()) { window.Editor.close(); return true; }
  return false;
};

/* ---------- wire up ---------- */
function init() {
  $('#fCategory').innerHTML = CATS.map(c => '<option>' + esc(c) + '</option>').join('');
  if (!S.design) S.design = window.Editor.defaultDesign();
  fillForm(); renderSaved(); renderSizes(); $('#fManual').value = S.manual;

  ['#fStyle', '#fModel', '#fColour', '#fCustomCat'].forEach(id => $(id).addEventListener('input', productFromForm));
  $('#fCategory').onchange = () => { $('#customCatWrap').hidden = $('#fCategory').value !== 'Other'; productFromForm(); };
  $('#fManual').addEventListener('input', () => { S.manual = $('#fManual').value; persist(); renderPreview(); });

  $('#savedProducts').onchange = e => { const p = S.saved[+e.target.value]; if (p) { S.product = Object.assign({}, p); persist(); fillForm(); renderPreview(); } };
  $('#saveProduct').onclick = () => {
    productFromForm();
    if (!S.product.style && !S.product.model) return toast('Enter a style or model first');
    const key = [S.product.style, S.product.model, S.product.colour].join('|').toLowerCase();
    S.saved = S.saved.filter(p => [p.style, p.model, p.colour].join('|').toLowerCase() !== key);
    S.saved.unshift(Object.assign({}, S.product)); persist(); renderSaved(); toast('Product saved');
  };
  $('#deleteProduct').onclick = async () => {
    const i = $('#savedProducts').value; if (i === '') return toast('Pick a saved product first');
    if (await ask({ title: 'Delete saved product?', body: 'It is removed from the list only.', ok: 'Delete', danger: true })) { S.saved.splice(+i, 1); persist(); renderSaved(); }
  };

  $$('#barcodeSeg button').forEach(b => b.onclick = () => { S.barcodeMode = b.dataset.mode; persist(); renderBarcodeUI(); });
  $('#manageBarcodes').onclick = openBarcodeManager;
  $('#pendingBannerBtn').onclick = openBarcodeManager;

  const grid = $('#sizeGrid');
  grid.addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    const box = b.closest('.size'), s = box.dataset.s;
    setQty(s, (S.sizes[s] | 0) + (+b.dataset.d)); $('input[inputmode]', box).value = S.sizes[s]; $('.chk', box).checked = S.sizes[s] > 0; updateTotal();
  });
  grid.addEventListener('change', e => {
    if (!e.target.classList.contains('chk')) return;
    const box = e.target.closest('.size'), s = box.dataset.s;
    if (e.target.checked) setQty(s, S.sizes[s] > 0 ? S.sizes[s] : (S.lastQty && S.lastQty[s]) || 1);
    else { S.lastQty = S.lastQty || {}; S.lastQty[s] = S.sizes[s] | 0; setQty(s, 0); }
    $('input[inputmode]', box).value = S.sizes[s]; updateTotal(); renderPreview();
  });
  grid.addEventListener('input', e => {
    const box = e.target.closest('.size'); if (!box || e.target.classList.contains('chk')) return;
    setQty(box.dataset.s, parseInt(e.target.value, 10) || 0); $('.chk', box).checked = (S.sizes[box.dataset.s] | 0) > 0; updateTotal(); renderPreview();
  });
  grid.addEventListener('focusout', e => { const box = e.target.closest('.size'); if (box && !e.target.classList.contains('chk')) e.target.value = S.sizes[box.dataset.s] | 0; });
  $('#selAll').onclick = () => { SIZES.forEach(s => { if (!(S.sizes[s] > 0)) S.sizes[s] = 1; }); persist(); renderSizes(); renderPreview(); };
  $('#qtyAll1').onclick = () => { const q = Math.max(0, Math.min(200, parseInt($('#qtyAllVal').value, 10) || 1)); SIZES.forEach(s => S.sizes[s] = q); persist(); renderSizes(); renderPreview(); };
  $('#qtyClear').onclick = () => { S.sizes = {}; persist(); renderSizes(); renderPreview(); };

  $('#printerChip').onclick = openPrinterSheet;
  $('#openSettings').onclick = openSettings;
  $('#openTemplates').onclick = () => window.Editor.openTemplates();
  $('#openEditor').onclick = () => window.Editor.open();
  $('#printBulk').onclick = printBulk;
  $('#printTest').onclick = printTest;
  $('#progressCancel').onclick = () => { B.cancel(); $('#progressCancel').hidden = true; };

  const st = B.getState();
  if (st.printer) { S.printer = st.printer; }
  if (st.barcode) S.bc = { next: st.barcode.next, pending: st.barcode.pending || null };
  S.permission = st.permission !== false;
  renderPrinterChip(); renderBarcodeUI();
  if (st.printing) onProgress({ state: 'printing', sent: 0, total: 0 });
}

window.App = {
  S, B, SIZES, esc, $, $$, store, sheet, ask, toast, tokens, previewCtx, persist, renderPreview,
  printTest, designChanged() { store.set('pls2Design', S.design); renderPreview(); }
};
window.addEventListener('DOMContentLoaded', () => window.setTimeout(init, 0));
})();
