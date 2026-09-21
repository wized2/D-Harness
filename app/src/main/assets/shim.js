/*!
 * DeepSeek Tool Shim
 * @version 7.2.0-native
 * @description run_js tool bridge + draggable status dot + management panel
 *              + opacity-flash send + throttled DOM scanning
 */
(function () {
  'use strict';
  if (window.top !== window.self) return;
  if (window.__DS_TOOL_SHIM__) {
    try { window.__DS_TOOL_SHIM__.stop && window.__DS_TOOL_SHIM__.stop(); } catch (e) {}
    try {
      document.getElementById('__ds_shim_style__')?.remove();
      document.getElementById('__ds_shim_fab')?.remove();
      document.getElementById('__ds_shim_panel')?.remove();
      document.getElementById('__ds_shim_toast')?.remove();
    } catch (e) {}
    delete window.__DS_TOOL_SHIM__;
  }

  const VERSION = '7.5.1-tools';
  const CONV_ID = location.pathname.split('/').filter(Boolean).pop() || 'unknown';
  const CONFIG = Object.assign({
    debug: false,
    maxStorageKB: 100,
    sendTimeoutMs: 4000,
    sandboxTimeoutMs: 20000,
    dedupe: false,
    // perf knobs
    scanThrottleMs: 400,       // min interval between DOM scans
    fallbackScanMs: 1500,      // periodic scan when observer is quiet
    hideFlashMs: 120,          // max time input stays invisible
  }, window.__DS_SHIM_CONFIG__ || {});

  // ---------- Storage ----------
  const LS = {
    done:      '__ds_shim__done_v2',
    memory:    '__ds_shim__memory_v1',
    fs:        '__ds_shim__fs_v1',
    fabPos:    '__ds_shim__fab_pos_v1',
    fabHidden: '__ds_shim__fab_hidden_v1',
  };
  const lsGet = (k, fb) => { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : fb; } catch { return fb; } };
  const lsSet = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch { return false; } };

  let DONE = lsGet(LS.done, {});
  const saveDone = () => {
    const e = Object.entries(DONE);
    if (e.length > 1000) { e.sort((a, b) => (b[1].t || 0) - (a[1].t || 0)); DONE = Object.fromEntries(e.slice(0, 1000)); }
    lsSet(LS.done, DONE);
  };

  function hashStr(s) {
    let h1 = 0x811c9dc5, h2 = 0x01000193;
    for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); h1 = Math.imul(h1 ^ c, 0x01000193); h2 = Math.imul(h2 ^ c, 0x85ebca6b); }
    return (h1 >>> 0).toString(36) + (h2 >>> 0).toString(36);
  }

  // ---------- Logs ----------
  const LOGS = []; const MAX_LOGS = 300;
  function pushLog(level, ...a) {
    const msg = a.map(x => typeof x === 'object' ? JSON.stringify(x) : String(x)).join(' ');
    LOGS.push({ t: Date.now(), level, msg });
    if (LOGS.length > MAX_LOGS) LOGS.shift();
  }
  const log = (...a) => { pushLog('info', ...a); if (CONFIG.debug) console.log('%c[shim]', 'color:#0af;font-weight:bold', ...a); };
  const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const sleep = (ms) => new Promise(r => setTimeout(r, ms));

  // ---------- Styles ----------
  const style = document.createElement('style');
  style.id = '__ds_shim_style__';
  style.textContent = `
    [data-ds-shim-hidden="1"] { display: none !important; }

    [data-ds-shim-tagline="1"] {
      display: flex !important;
      align-items: center;
      height: 34px;
      padding: 0 8px;
      margin: 4px 0 6px 0;
      cursor: pointer;
      user-select: none;
      width: fit-content;
      max-width: 100%;
      border-radius: 8px;
      color: var(--dsw-alias-label-secondary, rgba(180,195,210,0.75));
      font: 13px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;
      transition: background .15s ease;
      -webkit-tap-highlight-color: transparent;
    }
    [data-ds-shim-tagline="1"]:hover {
      background: var(--dsw-alias-bg-hover, rgba(120,150,180,0.08));
    }
    [data-ds-shim-tagline="1"] .ds-shim-inner {
      display: flex; align-items: center; gap: 6px; height: 100%;
    }
    [data-ds-shim-tagline="1"] .ds-shim-ico {
      width: 16px; height: 16px;
      display: inline-flex; align-items: center; justify-content: center;
      font-size: 13px; opacity: .85; flex-shrink: 0;
    }
    [data-ds-shim-tagline="1"][data-ds-shim-running="1"] .ds-shim-ico {
      animation: dsshim-spin 1s linear infinite;
    }
    @keyframes dsshim-spin { to { transform: rotate(360deg); } }
    [data-ds-shim-tagline="1"] .ds-shim-txt { font-weight: 400; white-space: nowrap; }
    [data-ds-shim-tagline="1"] .ds-shim-chip {
      font-family: ui-monospace,SFMono-Regular,Menlo,monospace;
      font-size: 12px;
      padding: 1px 7px;
      border-radius: 5px;
      background: rgba(120,150,180,0.10);
      color: rgba(190,205,220,0.9);
      max-width: 240px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      margin-left: 2px;
    }
    [data-ds-shim-tagline="1"] .ds-shim-chip::before { content: '→ '; opacity: .5; font-family: system-ui; }
    [data-ds-shim-tagline="1"] .ds-shim-chip.err { color: #f88; background: rgba(240,130,130,0.10); }
    [data-ds-shim-tagline="1"] .ds-shim-chev {
      width: 14px; height: 14px;
      display: inline-flex; align-items: center; justify-content: center;
      opacity: .55; margin-left: 2px;
      transition: transform .18s ease;
      flex-shrink: 0;
    }
    [data-ds-shim-tagline="1"] .ds-shim-chev svg { display: block; }
    [data-ds-shim-tagline="1"][data-ds-shim-expanded="1"] .ds-shim-chev { transform: rotate(180deg); }
    @media (pointer: coarse) { [data-ds-shim-tagline="1"] { height: 40px; } }

    /* FAB */
    #__ds_shim_fab {
      position: fixed; z-index: 2147483645;
      width: 28px; height: 28px;
      cursor: grab;
      display: flex; align-items: center; justify-content: center;
      opacity: .35;
      transition: opacity .2s ease, transform .2s ease;
      pointer-events: auto;
      touch-action: none;
      -webkit-tap-highlight-color: transparent;
      user-select: none;
    }
    #__ds_shim_fab:hover, #__ds_shim_fab:focus-visible { opacity: 1; }
    #__ds_shim_fab.dragging { opacity: 1; cursor: grabbing; transform: scale(1.15); }
    #__ds_shim_fab .ds-shim-dot {
      width: 11px; height: 11px; border-radius: 50%; background: #888;
      transition: background .25s, box-shadow .25s;
      pointer-events: none;
    }
    #__ds_shim_fab[data-status="idle"]    .ds-shim-dot { background: #4db; }
    #__ds_shim_fab[data-status="running"] .ds-shim-dot { background: #4af; animation: dsshim-pulse 1.2s ease-in-out infinite; }
    #__ds_shim_fab[data-status="error"]   .ds-shim-dot { background: #f77; }
    #__ds_shim_fab[data-status="warn"]    .ds-shim-dot { background: #fa4; }
    #__ds_shim_fab[data-status="off"]     .ds-shim-dot { background: #888; }
    @keyframes dsshim-pulse {
      0%, 100% { box-shadow: 0 0 0 0 rgba(68,170,255,0.55); }
      50%      { box-shadow: 0 0 0 7px rgba(68,170,255,0); }
    }
    #__ds_shim_fab[data-hidden="1"] { opacity: 0; width: 12px; background: linear-gradient(to left, rgba(120,150,180,0.35), transparent); }
    #__ds_shim_fab[data-hidden="1"]:hover { opacity: 1; }

    /* Panel */
    #__ds_shim_panel {
      position: fixed; z-index: 2147483646;
      width: 280px; max-width: calc(100vw - 52px); max-height: 72vh; overflow-y: auto;
      background: rgba(24,28,34,0.95);
      -webkit-backdrop-filter: blur(14px); backdrop-filter: blur(14px);
      border: 1px solid rgba(120,150,180,0.22); border-radius: 12px;
      color: #dde3ea; font: 13px/1.5 system-ui,-apple-system,sans-serif;
      box-shadow: 0 12px 36px rgba(0,0,0,0.45);
      animation: dsshim-panel-in .18s ease;
    }
    @keyframes dsshim-panel-in { from { opacity: 0; transform: translateX(10px); } to { opacity: 1; transform: translateX(0); } }
    #__ds_shim_panel[hidden] { display: none; }
    .ds-shim-panel-header { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; border-bottom: 1px solid rgba(120,150,180,0.15); font-weight: 500; }
    .ds-shim-panel-header b { color: #4db; font-weight: 600; }
    .ds-shim-close { background: transparent; border: none; color: #aab; font-size: 22px; line-height: 1; cursor: pointer; padding: 0 4px; border-radius: 4px; }
    .ds-shim-close:hover { background: rgba(255,255,255,0.08); color: #fff; }
    .ds-shim-panel-body { padding: 8px 14px 14px; }
    .ds-shim-row { display: flex; align-items: center; justify-content: space-between; padding: 6px 0; font-size: 12.5px; }
    .ds-shim-row span { color: rgba(180,195,210,0.7); }
    .ds-shim-row b { color: #dde3ea; font-weight: 500; }
    .ds-shim-row code { font-family: ui-monospace,SFMono-Regular,Menlo,monospace; font-size: 11.5px; color: #9ab; background: rgba(255,255,255,0.05); padding: 1px 5px; border-radius: 3px; }
    .ds-shim-status { display: inline-flex; align-items: center; gap: 6px; }
    .ds-shim-status::before { content: ''; display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #888; }
    .ds-shim-status[data-status="idle"]::before { background: #4db; }
    .ds-shim-status[data-status="running"]::before { background: #4af; }
    .ds-shim-status[data-status="error"]::before { background: #f77; }
    .ds-shim-status[data-status="warn"]::before { background: #fa4; }
    .ds-shim-status[data-status="off"]::before { background: #888; }
    #__ds_shim_panel hr { border: none; border-top: 1px solid rgba(120,150,180,0.15); margin: 10px 0; }
    .ds-shim-toggle { display: flex; align-items: center; gap: 8px; padding: 7px 0; cursor: pointer; font-size: 12.5px; }
    .ds-shim-toggle input { accent-color: #4af; }
    .ds-shim-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
    .ds-shim-actions button { background: rgba(120,150,180,0.10); border: 1px solid rgba(120,150,180,0.20); color: #dde3ea; padding: 7px 8px; border-radius: 6px; font-size: 12px; cursor: pointer; font-family: inherit; transition: background .15s; }
    .ds-shim-actions button:hover { background: rgba(120,150,180,0.22); }
    .ds-shim-actions button.danger { color: #f88; border-color: rgba(240,130,130,0.35); }
    .ds-shim-actions button.danger:hover { background: rgba(240,130,130,0.15); }
    .ds-shim-actions button.wide { grid-column: span 2; }

    #__ds_shim_toast {
      position: fixed; z-index: 2147483647; padding: 6px 12px;
      background: rgba(20,24,30,0.92); color: #dde3ea;
      font: 12px/1.4 system-ui,-apple-system,sans-serif; border-radius: 8px;
      border: 1px solid rgba(120,150,180,0.25);
      pointer-events: none; opacity: 0; transition: opacity .2s ease;
      box-shadow: 0 4px 14px rgba(0,0,0,0.35);
    }
    #__ds_shim_toast.show { opacity: 1; }
  `;
  document.head.appendChild(style);

  // ---------- FAB ----------
  const fab = document.createElement('div');
  fab.id = '__ds_shim_fab';
  fab.setAttribute('data-status', 'idle');
  fab.setAttribute('role', 'button');
  fab.setAttribute('tabindex', '0');
  fab.setAttribute('aria-label', 'DeepSeek Shim');
  fab.innerHTML = '<div class="ds-shim-dot"></div>';

  const savedPos = lsGet(LS.fabPos, null);
  const applyPos = (pos) => {
    if (pos) {
      fab.style.left = pos.x + 'px'; fab.style.top = pos.y + 'px';
      fab.style.right = 'auto'; fab.style.transform = 'none';
    } else {
      fab.style.right = '6px'; fab.style.left = 'auto';
      fab.style.top = '50%'; fab.style.transform = 'translateY(-50%)';
    }
  };
  applyPos(savedPos);

  // ---------- Panel ----------
  const panel = document.createElement('div');
  panel.id = '__ds_shim_panel';
  panel.hidden = true;
  panel.innerHTML = `
    <div class="ds-shim-panel-header">
      <span>DeepSeek Shim <b>v${VERSION}</b></span>
      <button class="ds-shim-close" aria-label="Close">×</button>
    </div>
    <div class="ds-shim-panel-body">
      <div class="ds-shim-row"><span>Status</span><span class="ds-shim-status" data-status="idle">idle</span></div>
      <div class="ds-shim-row"><span>Conversation</span><code>${esc(CONV_ID.slice(0, 12))}…</code></div>
      <hr />
      <div class="ds-shim-row"><span>Deduped calls</span><b class="ds-shim-done-count">0</b></div>
      <div class="ds-shim-row"><span>Memory keys</span><b class="ds-shim-mem-count">0</b></div>
      <div class="ds-shim-row"><span>FS files</span><b class="ds-shim-fs-count">0</b></div>
      <hr />
      <label class="ds-shim-toggle"><input type="checkbox" data-opt="debug" /><span>Debug (show TOOL_RESULT)</span></label>
      <label class="ds-shim-toggle"><input type="checkbox" data-opt="dedupe" checked /><span>Dedupe executed tool calls</span></label>
      <hr />
      <div class="ds-shim-actions">
        <button data-act="resetFab">Reset dot</button>
        <button data-act="hideFab">Hide dot</button>
        <button data-act="clearDone">Clear done</button>
        <button data-act="clearMemory">Clear memory</button>
        <button data-act="clearFs">Clear FS</button>
        <button data-act="copyLogs">Copy logs</button>
        <button data-act="stop" class="danger wide">Stop shim</button>
      </div>
    </div>
  `;

  document.body.appendChild(fab);
  document.body.appendChild(panel);

  const toast = document.createElement('div');
  toast.id = '__ds_shim_toast';
  document.body.appendChild(toast);
  let toastTimer = null;
  function showToast(msg, ms = 1600) {
    toast.textContent = msg;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), ms);
  }
  function positionToast(nearEl) {
    const r = nearEl.getBoundingClientRect();
    const tw = 160;
    let x = r.left - tw - 10;
    if (x < 8) x = r.right + 10;
    let y = r.top + r.height / 2 - 14;
    if (y < 8) y = 8;
    if (y > window.innerHeight - 40) y = window.innerHeight - 40;
    toast.style.left = x + 'px'; toast.style.top = y + 'px';
  }

  // ---------- Drag ----------
  let dragState = null;
  const DRAG_THRESHOLD = 5;
  const SNAP_DISTANCE = 24;
  function pointFromEvent(e) {
    if (e.touches && e.touches[0]) return { x: e.touches[0].clientX, y: e.touches[0].clientY };
    if (e.changedTouches && e.changedTouches[0]) return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY };
    return { x: e.clientX, y: e.clientY };
  }
  function onPointerDown(e) {
    if (e.button !== undefined && e.button !== 0) return;
    const p = pointFromEvent(e);
    const r = fab.getBoundingClientRect();
    dragState = { startX: p.x, startY: p.y, origX: r.left, origY: r.top, moved: false, pointerId: e.pointerId ?? null };
    fab.classList.add('dragging');
    try { fab.setPointerCapture?.(e.pointerId); } catch {}
    e.preventDefault?.();
  }
  function onPointerMove(e) {
    if (!dragState) return;
    const p = pointFromEvent(e);
    const dx = p.x - dragState.startX, dy = p.y - dragState.startY;
    if (!dragState.moved && Math.hypot(dx, dy) > DRAG_THRESHOLD) {
      dragState.moved = true;
      showToast('Drag to reposition', 1200);
      positionToast(fab);
    }
    if (!dragState.moved) return;
    let nx = dragState.origX + dx, ny = dragState.origY + dy;
    const size = 28;
    nx = Math.max(0, Math.min(window.innerWidth - size, nx));
    ny = Math.max(0, Math.min(window.innerHeight - size, ny));
    fab.style.left = nx + 'px'; fab.style.top = ny + 'px';
    fab.style.right = 'auto'; fab.style.transform = 'none';
  }
  function onPointerUp(e) {
    if (!dragState) return;
    const wasMoved = dragState.moved;
    dragState = null;
    fab.classList.remove('dragging');
    try { fab.releasePointerCapture?.(e.pointerId); } catch {}
    if (wasMoved) {
      const r = fab.getBoundingClientRect();
      const cx = r.left + r.width / 2;
      let finalX = r.left, finalY = r.top;
      if (cx < SNAP_DISTANCE) finalX = 6;
      else if (cx > window.innerWidth - SNAP_DISTANCE) finalX = window.innerWidth - r.width - 6;
      else finalX = Math.max(6, Math.min(window.innerWidth - r.width - 6, r.left));
      finalY = Math.max(6, Math.min(window.innerHeight - r.height - 6, r.top));
      fab.style.left = finalX + 'px'; fab.style.top = finalY + 'px';
      lsSet(LS.fabPos, { x: finalX, y: finalY });
      showToast('Position saved');
      positionToast(fab);
      return;
    }
    togglePanel();
  }
  fab.addEventListener('pointerdown', onPointerDown);
  window.addEventListener('pointermove', onPointerMove, { passive: true });
  window.addEventListener('pointerup', onPointerUp);
  window.addEventListener('pointercancel', onPointerUp);
  if (!window.PointerEvent) {
    fab.addEventListener('touchstart', onPointerDown, { passive: false });
    window.addEventListener('touchmove', onPointerMove, { passive: true });
    window.addEventListener('touchend', onPointerUp);
  }
  fab.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); togglePanel(); }
  });
  window.addEventListener('resize', () => {
    const saved = lsGet(LS.fabPos, null);
    if (!saved) return;
    const size = 28;
    let x = Math.max(6, Math.min(saved.x, window.innerWidth - size - 6));
    let y = Math.max(6, Math.min(saved.y, window.innerHeight - size - 6));
    fab.style.left = x + 'px'; fab.style.top = y + 'px';
    lsSet(LS.fabPos, { x, y });
  });

  // ---------- Status / counts ----------
  let statusResetTimer = null;
  function setStatus(s) {
    fab.setAttribute('data-status', s);
    const st = panel.querySelector('.ds-shim-status');
    st.setAttribute('data-status', s);
    st.textContent = s;
    clearTimeout(statusResetTimer);
    if (s === 'error' || s === 'warn') {
      statusResetTimer = setTimeout(() => setStatus('idle'), 5000);
    }
  }
  function refreshCounts() {
    panel.querySelector('.ds-shim-done-count').textContent = Object.keys(DONE).length;
    panel.querySelector('.ds-shim-mem-count').textContent = Object.keys(lsGet(LS.memory, {})).length;
    panel.querySelector('.ds-shim-fs-count').textContent = Object.keys(lsGet(LS.fs, {})).length;
  }

  // ---------- Panel open/close ----------
  function positionPanel() {
    const r = fab.getBoundingClientRect();
    const pw = 280;
    const ph = Math.min(window.innerHeight * 0.72, 480);
    let px = r.left - pw - 8;
    if (px < 8) px = r.right + 8;
    if (px + pw > window.innerWidth - 8) px = window.innerWidth - pw - 8;
    let py = r.top + r.height / 2 - ph / 2;
    py = Math.max(8, Math.min(window.innerHeight - ph - 8, py));
    panel.style.left = px + 'px'; panel.style.top = py + 'px';
    panel.style.right = 'auto'; panel.style.transform = 'none';
  }
  function togglePanel(force) {
    const show = force !== undefined ? force : panel.hidden;
    panel.hidden = !show;
    if (show) {
      positionPanel();
      refreshCounts();
      panel.querySelector('[data-opt="debug"]').checked = !!CONFIG.debug;
      panel.querySelector('[data-opt="dedupe"]').checked = !!CONFIG.dedupe;
    }
  }
  panel.querySelector('.ds-shim-close').onclick = () => togglePanel(false);
  document.addEventListener('click', (e) => {
    if (panel.hidden) return;
    if (panel.contains(e.target) || fab.contains(e.target)) return;
    togglePanel(false);
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !panel.hidden) togglePanel(false);
  });
  panel.querySelectorAll('[data-opt]').forEach(input => {
    input.addEventListener('change', () => {
      CONFIG[input.dataset.opt] = input.checked;
      log('option', input.dataset.opt, '=', input.checked);
      if (input.dataset.opt === 'debug') {
        const msgs = document.querySelectorAll('div.ds-message');
        for (const el of msgs) {
          const text = (el.textContent || '').trim();
          if (!text.startsWith('TOOL_RESULT:')) continue;
          const wrapper = el.parentElement;
          if (!wrapper) continue;
          if (CONFIG.debug) wrapper.removeAttribute('data-ds-shim-hidden');
          else wrapper.setAttribute('data-ds-shim-hidden', '1');
        }
      }
    });
  });
  panel.querySelector('[data-act="resetFab"]').onclick = () => {
    lsSet(LS.fabPos, null); applyPos(null);
    showToast('Dot reset'); positionToast(fab);
    if (!panel.hidden) positionPanel();
  };
  panel.querySelector('[data-act="hideFab"]').onclick = () => {
    lsSet(LS.fabHidden, true); fab.setAttribute('data-hidden', '1');
    showToast('Dot hidden');
  };
  panel.querySelector('[data-act="clearDone"]').onclick   = () => { DONE = {}; saveDone(); refreshCounts(); log('done cleared'); };
  panel.querySelector('[data-act="clearMemory"]').onclick = () => { lsSet(LS.memory, {}); refreshCounts(); log('memory cleared'); };
  panel.querySelector('[data-act="clearFs"]').onclick     = () => { lsSet(LS.fs, {}); refreshCounts(); log('fs cleared'); };
  panel.querySelector('[data-act="copyLogs"]').onclick = async () => {
    const text = LOGS.map(l => `[${new Date(l.t).toISOString()}] ${l.level}: ${l.msg}`).join('\n');
    try { await navigator.clipboard.writeText(text); log('logs copied'); }
    catch {
      const ta = document.createElement('textarea');
      ta.value = text; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); ta.remove();
    }
    showToast('Logs copied'); positionToast(fab); refreshCounts();
  };
  panel.querySelector('[data-act="stop"]').onclick = () => {
    if (confirm('Stop shim?')) window.__DS_TOOL_SHIM__.stop();
  };
  if (lsGet(LS.fabHidden, false)) fab.setAttribute('data-hidden', '1');

  // ---------- Sandbox ----------
  const iframe = document.createElement('iframe');
  iframe.sandbox = 'allow-scripts';
  iframe.style.display = 'none';
  iframe.srcdoc = `<!doctype html><html><body><script>
    const pendingTool = new Map();
    let toolId = 0;
    window.__ds_call_tool = (name, args) => new Promise((resolve, reject) => {
      const id = ++toolId;
      pendingTool.set(id, { resolve, reject });
      parent.postMessage({ __dsShimToolCall: true, id, name, args }, '*');
    });
    window.addEventListener('message', (e) => {
      const d = e.data;
      if (!d || d.__dsShimToolResult !== true) return;
      const p = pendingTool.get(d.id); if (!p) return;
      pendingTool.delete(d.id);
      if (d.ok) p.resolve(d.result); else p.reject(new Error(d.error || 'tool error'));
    });
    window.memory = {
      get:    (k)    => window.__ds_call_tool('memory', { op:'get', key:k }),
      set:    (k, v) => window.__ds_call_tool('memory', { op:'set', key:k, value:v }),
      delete: (k)    => window.__ds_call_tool('memory', { op:'delete', key:k }),
      list:   ()     => window.__ds_call_tool('memory', { op:'list' }),
      clear:  ()     => window.__ds_call_tool('memory', { op:'clear' }),
    };
    window.fetch_url = (url, opts) => window.__ds_call_tool('fetch_url', Object.assign({ url }, opts || {}));
    window.file = { save: (filename, content, mime) => window.__ds_call_tool('file_save', { filename, content, mime }) };
    window.clipboard = {
      copy: (text) => window.__ds_call_tool('clipboard_copy', { text }),
      read: ()     => window.__ds_call_tool('clipboard_read', {}),
    };
    window.geo = { get: (opts) => window.__ds_call_tool('geo_get', opts || {}) };
    window.workspace = {
      pwd: () => window.__ds_call_tool('workspace', { op:'pwd' }),
      ls: (path) => window.__ds_call_tool('workspace', { op:'ls', path }),
      read: (path, maxBytes) => window.__ds_call_tool('workspace', { op:'read', path, maxBytes }),
      write: (path, content) => window.__ds_call_tool('workspace', { op:'write', path, content }),
      write_b64: (path, contentB64) => window.__ds_call_tool('workspace', { op:'write_b64', path, contentB64 }),
      read_b64: (path) => window.__ds_call_tool('workspace', { op:'read_b64', path }),
      mkdir: (path) => window.__ds_call_tool('workspace', { op:'mkdir', path }),
      rm: (path) => window.__ds_call_tool('workspace', { op:'rm', path }),
      stat: (path) => window.__ds_call_tool('workspace', { op:'stat', path }),
      tree: (path, depth) => window.__ds_call_tool('workspace', { op:'tree', path, depth }),
    };
    window.list_tools = () => window.__ds_call_tool('list_tools', {});
    window.describe = (name) => window.__ds_call_tool('describe', { name });
    window.http_request = (opts) => window.__ds_call_tool('http_request', opts || {});
    window.github = {
      pull: (o,r,path,ref,dest) => window.__ds_call_tool('github', { op:'pull', owner:o, repo:r, path, ref, dest }),
      push_file: (o,r,path,branch,message,localPath) => window.__ds_call_tool('github', { op:'push_file', owner:o, repo:r, path, branch, message, localPath }),
      me: () => window.__ds_call_tool('github', { op:'me' }),
      repos: (limit) => window.__ds_call_tool('github', { op:'repos', limit }),
      pr: (o,r,n) => window.__ds_call_tool('github', { op:'pr', owner:o, repo:r, number:n }),
      pr_files: (o,r,n) => window.__ds_call_tool('github', { op:'pr_files', owner:o, repo:r, number:n }),
      pr_reviews: (o,r,n) => window.__ds_call_tool('github', { op:'pr_reviews', owner:o, repo:r, number:n }),
      pr_commits: (o,r,n) => window.__ds_call_tool('github', { op:'pr_commits', owner:o, repo:r, number:n }),
      issue: (o,r,n) => window.__ds_call_tool('github', { op:'issue', owner:o, repo:r, number:n }),
      contents: (o,r,path,ref) => window.__ds_call_tool('github', { op:'contents', owner:o, repo:r, path, ref }),
      search: (q,type) => window.__ds_call_tool('github', { op:'search', query:q, type }),
      request: (m,path,body) => window.__ds_call_tool('github', { op:'request', method:m, path, body }),
      issue_comment: (o,r,n,body) => window.__ds_call_tool('github', { op:'issue_comment', owner:o, repo:r, number:n, body }),
    };
    window.keys = {
      get: (k) => window.__ds_call_tool('keys', { op:'get', key:k }),
      set: (k,v) => window.__ds_call_tool('keys', { op:'set', key:k, value:v }),
      delete: (k) => window.__ds_call_tool('keys', { op:'delete', key:k }),
      list: () => window.__ds_call_tool('keys', { op:'list' }),
    };
    window.exec = (argv) => window.__ds_call_tool('exec', { argv });
    window.device = { info: () => window.__ds_call_tool('device', { op:'info' }) };
    window.env = { get: () => window.__ds_call_tool('env', {}) };
    window.toast = (msg) => window.__ds_call_tool('toast', { message: msg });
    window.vibrate = (ms) => window.__ds_call_tool('vibrate', { ms });
    window.notify = (title, body) => window.__ds_call_tool('notify', { title, body });
    window.share = (text) => window.__ds_call_tool('share', { text });
    window.appInfo = () => window.__ds_call_tool('appInfo', {});
    window.file.commit = (path, contentB64, sha256) => window.__ds_call_tool('file', { op:'commit', path, contentB64, sha256 });
    window.file.read_b64 = (path) => window.__ds_call_tool('file', { op:'read_b64', path });
    window.file.verify_roundtrip = () => window.__ds_call_tool('file', { op:'verify_roundtrip' });

    window.fs = {
      read:   (path)                => window.__ds_call_tool('fs', { op:'read', path }),
      write:  (path, content, mime) => window.__ds_call_tool('fs', { op:'write', path, content, mime }),
      list:   (prefix)              => window.__ds_call_tool('fs', { op:'list', path: prefix }),
      delete: (path)                => window.__ds_call_tool('fs', { op:'delete', path }),
      exists: (path)                => window.__ds_call_tool('fs', { op:'exists', path }),
    };
    window.onmessage = async (e) => {
      const d = e.data;
      if (!d || d.type !== 'run') return;
      try {
        const result = await eval('(async()=>{' + d.code + '})()');
        let out; try { out = JSON.stringify(result); } catch { out = String(result); }
        parent.postMessage({ __dsShim: true, id: d.id, ok: true, result: out }, '*');
      } catch (err) {
        parent.postMessage({ __dsShim: true, id: d.id, ok: false, error: String(err && err.message || err), stack: err && err.stack || null }, '*');
      }
    };
  <\/script></body></html>`;
  document.body.appendChild(iframe);
  let iframeReady = false;
  iframe.onload = () => { iframeReady = true; };

  // ---------- Tool handlers ----------
  const sizeGuard = (s) => {
    const kb = (String(s).length * 2) / 1024;
    if (kb > CONFIG.maxStorageKB) throw new Error(`payload too large: ${kb.toFixed(1)}KB > ${CONFIG.maxStorageKB}KB`);
  };
  const toolHandlers = {
    async memory({ op, key, value }) {
      const m = lsGet(LS.memory, {});
      if (op === 'get')    return { value: key in m ? m[key] : null };
      if (op === 'set')    { sizeGuard(value); m[key] = value; lsSet(LS.memory, m); return { ok: true }; }
      if (op === 'delete') { delete m[key]; lsSet(LS.memory, m); return { ok: true }; }
      if (op === 'list')   return { keys: Object.keys(m) };
      if (op === 'clear')  { lsSet(LS.memory, {}); return { ok: true }; }
      throw new Error('unknown memory op: ' + op);
    },
    async fetch_url({ url, method = 'GET', headers = {}, body = null, json = null, timeoutMs = 15000 }) {
      if (/^https?:\/\/chat\.deepseek\.com/i.test(url)) throw new Error('blocked: same-origin fetch');
      const ctrl = new AbortController();
      const t = setTimeout(() => ctrl.abort(), timeoutMs);
      try {
        const opts = { method, headers: { ...headers }, signal: ctrl.signal };
        if (json !== null && json !== undefined) {
          opts.headers['Content-Type'] = opts.headers['Content-Type'] || 'application/json';
          opts.body = JSON.stringify(json);
        } else if (body !== null && body !== undefined) opts.body = body;
        const res = await fetch(url, opts);
        const text = await res.text();
        let parsed = null; try { parsed = JSON.parse(text); } catch {}
        return { status: res.status, ok: res.ok, headers: Object.fromEntries(res.headers.entries()), text: text.slice(0, 100000), json: parsed };
      } finally { clearTimeout(t); }
    },
    async file_save({ filename, content, mime = 'text/plain' }) {
      const blob = new Blob([content], { type: mime });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = filename;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 5000);
      return { ok: true, filename, bytes: String(content).length };
    },
    async clipboard_copy({ text }) {
      try { await navigator.clipboard.writeText(text); return { ok: true }; }
      catch {
        const ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.top = '-9999px';
        document.body.appendChild(ta); ta.select();
        const ok = document.execCommand('copy'); ta.remove();
        if (!ok) throw new Error('clipboard write failed');
        return { ok: true, fallback: true };
      }
    },
    async clipboard_read() {
      if (!navigator.clipboard?.readText) throw new Error('clipboard read unsupported');
      return { text: await navigator.clipboard.readText() };
    },
    async geo_get({ timeoutMs = 10000 } = {}) {
      if (!navigator.geolocation) throw new Error('geolocation unsupported');
      return await new Promise((resolve, reject) => {
        navigator.geolocation.getCurrentPosition(
          p => resolve({ lat: p.coords.latitude, lng: p.coords.longitude, accuracy: p.coords.accuracy, timestamp: p.timestamp }),
          err => reject(new Error(err.message || 'geo error')),
          { timeout: timeoutMs, maximumAge: 60000 }
        );
      });
    },
    async fs({ op, path, content, mime = 'text/plain' }) {
      const m = lsGet(LS.fs, {});
      if (op === 'read')   { const f = m[path]; if (!f) throw new Error('not found: ' + path); return { content: f.content, mime: f.mime, mtime: f.mtime }; }
      if (op === 'write')  { sizeGuard(content); m[path] = { content: String(content ?? ''), mime, mtime: Date.now() }; if (!lsSet(LS.fs, m)) throw new Error('write failed'); return { ok: true, path, bytes: m[path].content.length }; }
      if (op === 'list')   return { paths: Object.keys(m).filter(p => p.startsWith(path || '')) };
      if (op === 'delete') { delete m[path]; lsSet(LS.fs, m); return { ok: true }; }
      if (op === 'exists') return { exists: !!m[path] };
      throw new Error('unknown fs op: ' + op);
    },
  };

  const pending = new Map();
  let msgId = 0;


  // ---- Native Android bridge (D-Harness) ---------------------------------
  // Prefer Kotlin tools when window.__DHarnessNative is present.
  const N = () => window.__DHarnessNative;

  async function nativeCall(path, args) {
    const nat = N();
    if (!nat || !nat.available) throw new Error('native bridge unavailable');
    // path like "github.pr" or "list_tools"
    const parts = path.split('.');
    let cur = nat;
    for (const p of parts) {
      if (cur == null || typeof cur[p] === 'undefined') throw new Error('native missing: ' + path);
      cur = cur[p];
    }
    if (typeof cur === 'function') return await cur.apply(nat, args || []);
    return cur;
  }

  // Extend toolHandlers with native-backed tools (and list_tools / describe)
  Object.assign(toolHandlers, {
    async list_tools() {
      const nat = N();
      if (nat && nat.available && nat.list_tools) return await nat.list_tools();
      // fallback: enumerate JS handlers
      return { tools: Object.keys(toolHandlers).sort(), source: 'shim-js' };
    },
    async describe({ name }) {
      const nat = N();
      if (nat && nat.available && nat.describe) return await nat.describe(name);
      return { name, available: !!toolHandlers[name], source: 'shim-js' };
    },
    async http_request(args) {
      const nat = N();
      if (nat && nat.http_request) return await nat.http_request(args);
      return toolHandlers.fetch_url(args);
    },
    async github(args) {
      const nat = N();
      if (!nat || !nat.github) throw new Error('github requires native bridge + PAT');
      const op = args.op || args.action;
      if (!op) throw new Error('github requires op');
      const g = nat.github;
      if (typeof g[op] === 'function') {
        // map common ops
        if (op === 'request') return await g.request(args.method || 'GET', args.path, args.body);
        if (op === 'me') return await g.me();
        if (op === 'repos') return await g.repos(args.limit);
        if (op === 'pr') return await g.pr(args.owner, args.repo, args.number);
        if (op === 'pr_files') return await g.pr_files(args.owner, args.repo, args.number);
        if (op === 'pr_reviews') return await g.pr_reviews(args.owner, args.repo, args.number);
        if (op === 'pr_commits') return await g.pr_commits(args.owner, args.repo, args.number);
        if (op === 'issue') return await g.issue(args.owner, args.repo, args.number);
        if (op === 'contents') return await g.contents(args.owner, args.repo, args.path, args.ref);
        if (op === 'search') return await g.search(args.query, args.type);
        if (op === 'pr_create') return await g.pr_create(args.owner, args.repo, args.title, args.head, args.base, args.body, args.draft);
        if (op === 'issue_comment') return await g.issue_comment(args.owner, args.repo, args.number, args.body);
        if (op === 'branch_create') return await g.branch_create(args.owner, args.repo, args.branch, args.from);
        if (op === 'compare') return await g.compare(args.owner, args.repo, args.base, args.head);
        if (op === 'pull') return await g.pull(args.owner, args.repo, args.path, args.ref, args.dest);
        if (op === 'push_file') return await g.push_file(args.owner, args.repo, args.path, args.branch, args.message, args.localPath);
        return await g[op](args);
      }
      throw new Error('unknown github op: ' + op);
    },
    async keys(args) {
      const nat = N();
      if (!nat || !nat.keys) throw new Error('keys requires native bridge');
      const op = args.op;
      if (op === 'get') return await nat.keys.get(args.key);
      if (op === 'set') return await nat.keys.set(args.key, args.value);
      if (op === 'delete') return await nat.keys.delete(args.key);
      if (op === 'list') return await nat.keys.list();
      throw new Error('unknown keys op');
    },
    async exec(args) {
      const nat = N();
      if (!nat || !nat.exec) throw new Error('exec requires native bridge');
      return await nat.exec(args.argv || args.cmd || args);
    },
    async device(args) {
      const nat = N();
      if (!nat) throw new Error('device requires native bridge');
      const op = args && (args.op || args.action);
      if (op === 'uptime') return await (nat.device_uptime ? nat.device_uptime() : nat.device?.uptime?.());
      if (op === 'storage') return await (nat.device_storage ? nat.device_storage() : nat.device?.storage?.());
      if (op === 'memory') return await (nat.device_memory ? nat.device_memory() : nat.device?.memory?.());
      if (op && nat.device && typeof nat.device[op] === 'function') return await nat.device[op]();
      if (nat.device && typeof nat.device.info === 'function') return await nat.device.info();
      return nat.device || { error: 'no device surface' };
    },
    async env(args) {
      const nat = N();
      if (nat && nat.env && nat.env.get) return await nat.env.get();
      return { platform: 'webview', native: !!(nat && nat.available) };
    },
    async toast(args) {
      const nat = N();
      if (nat && nat.toast) return await nat.toast(args.message || args.text || String(args));
      showToast(args.message || args.text || String(args));
      return { ok: true };
    },
    async vibrate(args) {
      const nat = N();
      if (nat && nat.vibrate) return await nat.vibrate(args.ms || 50);
      return { ok: false, error: 'no vibrate' };
    },
    async notify(args) {
      const nat = N();
      if (nat && nat.notify) return await nat.notify(args.title || 'D-Harness', args.body || args.message || '');
      return { ok: false, error: 'no notify' };
    },
    async share(args) {
      const nat = N();
      if (nat && nat.share) return await nat.share(args.text || args);
      return { ok: false, error: 'no share' };
    },
    async file(args) {
      const nat = N();
      const op = args.op;
      if (nat && nat.file) {
        if (op === 'commit') return await nat.file.commit(args.path, args.contentB64 || args.content, args.sha256);
        if (op === 'read_b64') return await nat.file.read_b64(args.path);
        if (op === 'verify_roundtrip') return await nat.file.verify_roundtrip();
      }
      if (op === 'save') return toolHandlers.file_save(args);
      throw new Error('unknown file op: ' + op);
    },
    async calc(args) {
      const nat = N();
      if (nat && nat.calc) {
        const op = args.op || 'eval';
        if (typeof nat.calc[op] === 'function') return await nat.calc[op](args.expr || args.x || args);
      }
      throw new Error('calc requires native bridge');
    },
    async text(args) {
      const nat = N();
      if (nat && nat.text) {
        const op = args.op;
        if (typeof nat.text[op] === 'function') return await nat.text[op](args);
      }
      throw new Error('text requires native bridge');
    },
    async crypto_native(args) {
      const nat = N();
      if (nat && nat.crypto && nat.crypto.hash) return await nat.crypto.hash(args.algo || 'sha256', args.data || args.text);
      throw new Error('crypto native unavailable');
    },

    async workspace(args) {
      const nat = N();
      if (!nat || !nat.workspace) throw new Error('workspace requires native');
      const op = args.op || args.action;
      const w = nat.workspace;
      if (op === 'pwd') return await w.pwd();
      if (op === 'ls') return await w.ls(args.path);
      if (op === 'read') return await w.read(args.path, args.maxBytes);
      if (op === 'write') return await w.write(args.path, args.content);
      if (op === 'write_b64') return await w.write_b64(args.path, args.contentB64);
      if (op === 'read_b64') return await w.read_b64(args.path);
      if (op === 'mkdir') return await w.mkdir(args.path);
      if (op === 'rm') return await w.rm(args.path);
      if (op === 'stat') return await w.stat(args.path);
      if (op === 'tree') return await w.tree(args.path, args.depth);
      throw new Error('unknown workspace op: ' + op);
    },

    async appInfo() {
      const nat = N();
      if (nat && nat.appInfo) return await nat.appInfo();
      return { shim: VERSION };
    },
  });

  window.addEventListener('message', async (e) => {
    if (e.source !== iframe.contentWindow) return;
    const d = e.data; if (!d) return;
    if (d.__dsShimToolCall === true) {
      let payload;
      try {
        const h = toolHandlers[d.name];
        if (!h) throw new Error('unknown tool: ' + d.name);
        const result = await h(d.args || {});
        payload = { __dsShimToolResult: true, id: d.id, ok: true, result };
      } catch (err) {
        payload = { __dsShimToolResult: true, id: d.id, ok: false, error: String(err && err.message || err) };
      }
      iframe.contentWindow.postMessage(payload, '*');
      return;
    }
    if (d.__dsShim === true) {
      const cb = pending.get(d.id);
      if (cb) { pending.delete(d.id); cb({ ok: d.ok, result: d.result, error: d.error, stack: d.stack }); }
    }
  });

  function runInSandboxOnce(code, timeoutMs) {
    if (!iframeReady) return Promise.resolve({ ok: false, error: 'iframe not ready' });
    return new Promise((resolve) => {
      const id = ++msgId;
      const timer = setTimeout(() => { pending.delete(id); resolve({ ok: false, error: 'timeout' }); }, timeoutMs);
      pending.set(id, (res) => { clearTimeout(timer); resolve(res); });
      try {
        iframe.contentWindow.postMessage({ type: 'run', id, code }, '*');
      } catch (e) {
        clearTimeout(timer);
        pending.delete(id);
        resolve({ ok: false, error: String(e && e.message || e) });
      }
    });
  }

  async function runInSandbox(code, timeoutMs = CONFIG.sandboxTimeoutMs) {
    // Bounded wait if iframe is still booting (avoids false "iframe not ready" probes)
    for (let attempt = 0; attempt < 10; attempt++) {
      if (iframeReady) {
        const res = await runInSandboxOnce(code, timeoutMs);
        if (!(res && res.error === 'iframe not ready')) return res;
      }
      await new Promise(function (r) { setTimeout(r, 150); });
    }
    return { ok: false, error: 'iframe not ready' };
  }

  // ============================================================
  // INPUT / SEND (with opacity flash)
  // ============================================================
  const getInput = () =>
    document.querySelector('textarea[placeholder="Message DeepSeek"]') ||
    document.querySelector('textarea[name="search"]') ||
    document.querySelector('textarea');

  function setNativeValue(el, value) {
    const desc = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
    if (desc && desc.set) desc.set.call(el, value); else el.value = value;
  }

  const SEND_SELECTOR = 'div[role="button"].ds-button--primary.ds-button--circle.ds-button--filled';
  const SEND_ICON_PREFIX = 'M8.3125';

  function findEnabledSendButton() {
    for (const b of document.querySelectorAll(SEND_SELECTOR)) {
      if (b.classList.contains('ds-button--disabled')) continue;
      if (b.offsetParent === null) continue;
      const d = b.querySelector('svg path')?.getAttribute('d') || '';
      if (!d.startsWith(SEND_ICON_PREFIX)) continue;
      return b;
    }
    return null;
  }

  let sendingLock = false;

  async function sendMessage(text) {
    if (sendingLock) { log('send already in progress'); return false; }
    sendingLock = true;

    const input = getInput();
    if (!input) { sendingLock = false; log('no input'); setStatus('error'); return false; }

    // --- Opacity flash: hide composer while we fill+send ---
    const prevOpacity = input.style.opacity;
    const prevPointer = input.style.pointerEvents;
    const prevTransition = input.style.transition;
    input.style.transition = 'none';
    input.style.opacity = '0';
    input.style.pointerEvents = 'none';

    const restore = () => {
      // Restore on next frame to avoid flicker with React's clear
      requestAnimationFrame(() => {
        input.style.opacity = prevOpacity;
        input.style.pointerEvents = prevPointer;
        requestAnimationFrame(() => {
          input.style.transition = prevTransition;
        });
      });
    };

    try {
      input.focus();
      setNativeValue(input, text);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      await sleep(60);

      // Try Enter
      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
      input.dispatchEvent(new KeyboardEvent('keyup',   { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));

      // Wait for clear
      let cleared = false;
      let t0 = Date.now();
      while (Date.now() - t0 < 500) {
        if (input.value.length === 0) { cleared = true; break; }
        await sleep(30);
      }

      if (!cleared) {
        // Fallback: click button
        let btn = null;
        t0 = Date.now();
        while (Date.now() - t0 < CONFIG.sendTimeoutMs) {
          btn = findEnabledSendButton();
          if (btn) break;
          await sleep(50);
        }
        if (btn) { btn.click(); cleared = true; }
      }

      if (!cleared) { log('send failed'); setStatus('error'); return false; }
      log('sent');
      return true;
    } finally {
      restore();
      sendingLock = false;
    }
  }

  // ============================================================
  // PARSING
  // ============================================================
  function messageSourceText(el) {
    // Prefer <pre>/<code> so Markdown emphasis (*word*) and quotes stay intact.
    try {
      const blocks = el.querySelectorAll('pre, code');
      if (blocks && blocks.length) {
        const joined = Array.from(blocks).map(function (n) { return (n.textContent || ''); }).join('\n');
        if (joined.indexOf('"tool"') !== -1) return joined;
      }
    } catch (e) {}
    return (el.textContent || '').trim();
  }

  function normalizeToolJson(raw) {
    // Smart quotes / common MD artifacts that break JSON.parse
    return String(raw)
      .replace(/[\u201C\u201D\u201E\u201F\u2033\u2036]/g, '"')
      .replace(/[\u2018\u2019\u201A\u201B\u2032\u2035]/g, "'");
  }

  function tryParseToolCandidate(cand) {
    const norm = normalizeToolJson(cand);
    try {
      const obj = JSON.parse(norm);
      if (obj && typeof obj.tool === 'string') {
        if (obj.tool === 'run_js') {
          if (obj.args && typeof obj.args.code === 'string') return { obj, full: cand };
        } else {
          return { obj, full: cand };
        }
      }
    } catch (e) {}
    return null;
  }

  function extractToolCall(text) {
    let idx = 0;
    while ((idx = text.indexOf('"tool"', idx)) !== -1) {
      const start = text.lastIndexOf('{', idx);
      if (start === -1) { idx += 6; continue; }
      let depth = 0, str = false, e2 = false;
      for (let i = start; i < text.length; i++) {
        const c = text[i];
        if (str) {
          if (e2) e2 = false;
          else if (c === '\\') e2 = true;
          else if (c === '"') str = false;
        } else {
          if (c === '"') str = true;
          else if (c === '{') depth++;
          else if (c === '}') {
            depth--;
            if (depth === 0) {
              const cand = text.slice(start, i + 1);
              const parsed = tryParseToolCandidate(cand);
              if (parsed) return parsed;
              break;
            }
          }
        }
      }
      idx += 6;
    }
    return null;
  }

  
  function findWrapper(dsMessage) {
    const p = dsMessage.parentElement;
    if (!p) return null;
    if (p === document.body) return null;
    if (p.id === 'root') return null;
    if (p.classList.contains('ds-virtual-list-visible-items')) return null;
    if (p.classList.contains('ds-virtual-list-items')) return null;
    return p;
  }

  // ============================================================
  // TAGLINE
  // ============================================================
  const CHEV_SVG = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 6l4 4 4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  function createTagline(preview = '', isError = false, running = false) {
    const el = document.createElement('div');
    el.setAttribute('data-ds-shim-tagline', '1');
    if (running) el.setAttribute('data-ds-shim-running', '1');

    const inner = document.createElement('div');
    inner.className = 'ds-shim-inner';

    const ico = document.createElement('div');
    ico.className = 'ds-shim-ico';
    ico.textContent = running ? '⏳' : '🔧';

    const txt = document.createElement('span');
    txt.className = 'ds-shim-txt';
    txt.textContent = running ? 'Running tool…' : 'Tool used';

    inner.appendChild(ico);
    inner.appendChild(txt);

    if (preview) {
      const chip = document.createElement('span');
      chip.className = 'ds-shim-chip' + (isError ? ' err' : '');
      chip.textContent = preview;
      inner.appendChild(chip);
    }

    const chev = document.createElement('span');
    chev.className = 'ds-shim-chev';
    chev.innerHTML = CHEV_SVG;
    inner.appendChild(chev);

    el.appendChild(inner);
    return el;
  }

  function updateTagline(tagline, preview, isError, running) {
    tagline.toggleAttribute('data-ds-shim-running', !!running);
    tagline.querySelector('.ds-shim-ico').textContent = running ? '⏳' : '🔧';
    tagline.querySelector('.ds-shim-txt').textContent = running ? 'Running tool…' : 'Tool used';
    let chip = tagline.querySelector('.ds-shim-chip');
    if (preview !== undefined) {
      if (!chip && preview) {
        chip = document.createElement('span');
        chip.className = 'ds-shim-chip';
        tagline.querySelector('.ds-shim-chev').before(chip);
      }
      if (chip) {
        chip.textContent = preview || '';
        chip.classList.toggle('err', !!isError);
      }
    }
  }

  // ============================================================
  // COLLAPSE
  // ============================================================
  const processed = new WeakSet();

  function applyHiding(wrapper, tagline) {
    for (const child of wrapper.children) {
      if (child === tagline) continue;
      if (child.getAttribute('data-ds-shim-hidden') !== '1') {
        child.setAttribute('data-ds-shim-hidden', '1');
      }
    }
  }

  function collapseToolMessage(dsMessage, preview, isError, running) {
    const wrapper = findWrapper(dsMessage);
    if (!wrapper) { log('no wrapper'); return null; }
    wrapper.setAttribute('data-ds-shim-wrapper', '1');

    let tagline = wrapper.querySelector(':scope > [data-ds-shim-tagline="1"]');
    if (!tagline) {
      tagline = createTagline(preview, isError, running);
      wrapper.insertBefore(tagline, wrapper.firstChild);
      tagline.onclick = () => {
        const expanded = tagline.getAttribute('data-ds-shim-expanded') === '1';
        for (const c of wrapper.children) {
          if (c === tagline) continue;
          if (expanded) c.setAttribute('data-ds-shim-hidden', '1');
          else c.removeAttribute('data-ds-shim-hidden');
        }
        tagline.setAttribute('data-ds-shim-expanded', expanded ? '0' : '1');
      };
    } else {
      updateTagline(tagline, preview, isError, running);
    }

    applyHiding(wrapper, tagline);
    return tagline;
  }

  // ============================================================
  // DEDUP SIG
  // ============================================================
  function occurrenceIndex(dsMessage, toolJson, cachedMsgs) {
    const all = cachedMsgs || document.querySelectorAll('div.ds-message');
    let n = 0;
    for (const m of all) {
      if (m === dsMessage) return n;
      const txt = m.textContent || '';
      if (txt.includes(toolJson)) n++;
    }
    return n;
  }
  const fullSig = (dsMessage, toolJson, cachedMsgs) =>
    CONV_ID + ':' + hashStr(toolJson) + ':' + occurrenceIndex(dsMessage, toolJson, cachedMsgs);

  // ============================================================
  // PROCESS
  // ============================================================
  let busy = false;

  async function processToolCall(dsMessage, tool, cachedMsgs) {
    const sig = fullSig(dsMessage, tool.full, cachedMsgs);
    const toolName = tool.obj.tool;
    const args = tool.obj.args || {};
    const code = args.code;

    if (CONFIG.dedupe && DONE[sig]) {
      const prev = DONE[sig];
      log('deduped:', sig);
      const preview = prev.ok ? String(prev.result).slice(0, 40) : 'error';
      collapseToolMessage(dsMessage, preview, !prev.ok, false);
      setStatus(prev.ok ? 'idle' : 'warn');
      return;
    }

    log('tool call:', toolName, code ? code.slice(0, 80) : JSON.stringify(args).slice(0, 80), '| sig:', sig);
    collapseToolMessage(dsMessage, '', false, true);
    setStatus('running');

    let res;
    if (toolName === 'run_js' && typeof code === 'string') {
      res = await runInSandbox(code);
    } else if (toolHandlers[toolName]) {
      try {
        const result = await toolHandlers[toolName](args);
        res = { ok: true, result: result };
      } catch (e) {
        res = { ok: false, error: String(e && e.message || e) };
      }
    } else {
      res = { ok: false, error: 'unknown tool: ' + toolName + ' (use list_tools call field for correct form)' };
    }
    log('result:', res);

    const preview = res.ok ? String(res.result).slice(0, 80) : ('error: ' + (res.error || ''));
    collapseToolMessage(dsMessage, preview, !res.ok, false);

    const payload = JSON.stringify({ ok: res.ok, result: res.result, error: res.error, sig: sig, ts: Date.now() });
    let okSend = false;
    try { okSend = await sendMessage('TOOL_RESULT: ' + payload); } catch (e) { log('send threw', e); }

    if (okSend) {
      DONE[sig] = { ok: res.ok, result: res.result, error: res.error, t: Date.now() };
      saveDone(); refreshCounts();
      setStatus(res.ok ? 'idle' : 'error');
    } else {
      processed.delete(dsMessage);
      setStatus('error');
      showToast('Result not sent — retrying…');
      setTimeout(async () => {
        try {
          const again = await sendMessage('TOOL_RESULT: ' + payload);
          if (again) {
            DONE[sig] = { ok: res.ok, result: res.result, error: res.error, t: Date.now() };
            saveDone(); refreshCounts();
            processed.add(dsMessage);
            setStatus(res.ok ? 'idle' : 'error');
            showToast('TOOL_RESULT sent');
          }
        } catch (e) { log('retry send failed', e); }
      }, 600);
    }
  }

  // ============================================================
  // OPTIMIZED SCAN LOOP
  // ============================================================
  function hideUserToolResults(msgs) {
    for (const el of msgs) {
      const wrapper = el.parentElement;
      if (!wrapper) continue;

      // Fast skip: already hidden correctly
      const isHidden = wrapper.getAttribute('data-ds-shim-hidden') === '1';
      if (isHidden && !CONFIG.debug) continue;

      const text = (el.textContent || '').trim();
      if (!text.startsWith('TOOL_RESULT:')) continue;

      if (CONFIG.debug) wrapper.removeAttribute('data-ds-shim-hidden');
      else wrapper.setAttribute('data-ds-shim-hidden', '1');
    }
  }

  function reapplyHiding() {
    const wrappers = document.querySelectorAll('[data-ds-shim-wrapper="1"]');
    if (!wrappers.length) return;
    for (const wrapper of wrappers) {
      const tagline = wrapper.firstElementChild;
      if (!tagline || tagline.getAttribute('data-ds-shim-tagline') !== '1') continue;
      if (tagline.getAttribute('data-ds-shim-expanded') === '1') continue;
      for (const c of wrapper.children) {
        if (c === tagline) continue;
        if (c.getAttribute('data-ds-shim-hidden') !== '1') {
          c.setAttribute('data-ds-shim-hidden', '1');
        }
      }
    }
  }

  function scanForToolCalls(msgs) {
    if (busy) return;
    for (const el of msgs) {
      if (processed.has(el)) continue;

      const wrapper = el.parentElement;
      if (wrapper?.firstElementChild?.getAttribute('data-ds-shim-tagline') === '1') {
        processed.add(el);
        continue;
      }

      const text = messageSourceText(el);
      if (!text || text.startsWith('TOOL_RESULT:')) continue;
      if (!el.querySelector('div.ds-markdown.ds-assistant-message-main-content')) continue;

      const tool = extractToolCall(text);
      if (!tool) continue;

      processed.add(el);
      busy = true;
      processToolCall(el, tool, msgs)
        .catch(e => { log('error:', e); setStatus('error'); })
        .finally(() => { busy = false; });
      return;
    }
  }

  // ---------- Throttled scheduler ----------
  let lastTickAt = 0;
  let tickScheduled = false;

  function runTick() {
    tickScheduled = false;
    lastTickAt = performance.now();
    if (document.hidden) return;
    try {
      const msgs = document.querySelectorAll('div.ds-message');
      hideUserToolResults(msgs);
      reapplyHiding();
      scanForToolCalls(msgs);
    } catch (e) {
      log('tick error:', e);
      setStatus('error');
    }
  }

  function scheduleTick(urgent = false) {
    if (tickScheduled) return;
    tickScheduled = true;
    const now = performance.now();
    const wait = urgent ? 0 : Math.max(0, CONFIG.scanThrottleMs - (now - lastTickAt));
    if (wait === 0) {
      requestAnimationFrame(runTick);
    } else {
      setTimeout(() => requestAnimationFrame(runTick), wait);
    }
  }

  // Observer: only structural changes (no characterData → quieter)
  const observer = new MutationObserver(() => scheduleTick(false));
  observer.observe(document.body, { childList: true, subtree: true });

  // Fallback periodic scan
  const fallbackTimer = setInterval(() => scheduleTick(false), CONFIG.fallbackScanMs);

  // Resume fresh when tab becomes visible
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) scheduleTick(true);
  });

  // Initial
  setTimeout(() => scheduleTick(true), 600);

  // ============================================================
  // PUBLIC API
  // ============================================================
  window.__DS_TOOL_SHIM__ = {
    version: VERSION,
    stop() {
      observer.disconnect();
      clearInterval(fallbackTimer);
      setStatus('off');
      style.remove();
      document.querySelectorAll('[data-ds-shim-tagline]').forEach(el => el.remove());
      document.querySelectorAll('[data-ds-shim-hidden]').forEach(el => el.removeAttribute('data-ds-shim-hidden'));
      document.querySelectorAll('[data-ds-shim-wrapper]').forEach(el => el.removeAttribute('data-ds-shim-wrapper'));
      fab.remove(); panel.remove(); toast.remove();
      delete window.__DS_TOOL_SHIM__;
      console.log('%c[shim] stopped', 'color:#0af');
    },
    tick: () => scheduleTick(true),
    send: sendMessage,
    run: runInSandbox,
    showPanel: () => togglePanel(true),
    hidePanel: () => togglePanel(false),
    resetFab: () => { lsSet(LS.fabPos, null); applyPos(null); },
    showFab: () => { lsSet(LS.fabHidden, false); fab.removeAttribute('data-hidden'); },
    stats() {
      const s = {
        version: VERSION,
        convId: CONV_ID,
        done: Object.keys(DONE).length,
        memoryKeys: Object.keys(lsGet(LS.memory, {})).length,
        fsFiles: Object.keys(lsGet(LS.fs, {})).length,
        logs: LOGS.length,
      };
      console.log('[shim] stats:', s);
      return s;
    },
    logs: () => LOGS.slice(),
    inspect() {
      const out = [];
      document.querySelectorAll('div.ds-message').forEach((el, i) => {
        const wrapper = el.parentElement;
        out.push({
          i,
          textPreview: (el.textContent || '').slice(0, 50),
          wrapperChildren: wrapper ? wrapper.children.length : 0,
          hasTagline: wrapper?.firstElementChild?.getAttribute('data-ds-shim-tagline') === '1',
          hidden: el.getAttribute('data-ds-shim-hidden'),
        });
      });
      console.table(out);
      return out;
    },
  };

  refreshCounts();
  console.log(`%c✅ DeepSeek Tool Shim v${VERSION} loaded`, 'color:#0af;font-weight:bold');
  console.log('API: __DS_TOOL_SHIM__.stats() | .inspect() | .showPanel()');
})();
