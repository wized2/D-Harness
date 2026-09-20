/*!
 * DeepSeek Tool Shim
 * @version 6.2.0
 * @description run_js tool bridge + draggable status dot + native-style tagline
 */
(function () {
  'use strict';
  if (window.top !== window.self) return;
  // Force re-init when native app re-injects
  if (window.__DS_TOOL_SHIM__) {
    try { window.__DS_TOOL_SHIM__.stop(); } catch (e) {}
    try {
      document.getElementById('__ds_shim_style__')?.remove();
      document.getElementById('__ds_shim_fab')?.remove();
      document.getElementById('__ds_shim_panel')?.remove();
      document.getElementById('__ds_shim_toast')?.remove();
      document.querySelectorAll('iframe').forEach(function(f){
        if (f.srcdoc && f.srcdoc.indexOf('__ds_call_tool') !== -1) f.remove();
      });
    } catch (e) {}
    delete window.__DS_TOOL_SHIM__;
  }

  const VERSION = '6.4.0';
  const CONV_ID = location.pathname.split('/').filter(Boolean).pop() || 'unknown';
  const CONFIG = Object.assign({
    debug: false, maxStorageKB: 100,
    sendTimeoutMs: 5000, sandboxTimeoutMs: 20000, dedupe: true,
  }, window.__DS_SHIM_CONFIG__ || {});

  const LS = {
    done: '__ds_shim__done_v2',
    memory: '__ds_shim__memory_v1',
    fs: '__ds_shim__fs_v1',
    fabPos: '__ds_shim__fab_pos_v1',
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

  const LOGS = [];
  const MAX_LOGS = 300;
  function pushLog(level, ...a) {
    const msg = a.map(x => typeof x === 'object' ? JSON.stringify(x) : String(x)).join(' ');
    LOGS.push({ t: Date.now(), level, msg });
    if (LOGS.length > MAX_LOGS) LOGS.shift();
  }
  const log = (...a) => { pushLog('info', ...a); if (CONFIG.debug) console.log('%c[shim]', 'color:#0af;font-weight:bold', ...a); };
  const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));

  // ---------- Styles ----------
  const style = document.createElement('style');
  style.id = '__ds_shim_style__';
  style.textContent = `
    [data-ds-shim-hidden="1"] { display: none !important; }

    /* ---------- Tagline: matches DeepSeek native "Thought for Ns" ---------- */
    [data-ds-shim-tagline="1"] {
      display: inline-flex !important;
      align-items: center;
      gap: 6px;
      height: 34px;
      padding: 0 10px 0 6px;
      margin: 4px 0;
      border-radius: 10px;
      background: transparent;
      color: var(--dsw-alias-label-secondary, rgba(150,165,180,0.85));
      font: 13px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif;
      cursor: pointer;
      user-select: none;
      width: fit-content;
      max-width: 100%;
      box-sizing: border-box;
      transition: background .15s ease;
      -webkit-tap-highlight-color: transparent;
    }
    [data-ds-shim-tagline="1"]:hover {
      background: var(--dsw-alias-bg-hover, rgba(120,150,180,0.08));
    }

    /* chevron icon (matches native thinking toggle) */
    [data-ds-shim-tagline="1"] .ds-shim-chev {
      width: 16px; height: 16px;
      display: inline-flex; align-items: center; justify-content: center;
      color: currentColor;
      opacity: .8;
      transition: transform .2s ease;
      flex-shrink: 0;
    }
    [data-ds-shim-tagline="1"] .ds-shim-chev svg {
      width: 12px; height: 12px;
      stroke: currentColor; fill: none;
      stroke-width: 2; stroke-linecap: round; stroke-linejoin: round;
    }
    [data-ds-shim-tagline="1"][data-ds-shim-expanded="1"] .ds-shim-chev {
      transform: rotate(90deg);
    }

    [data-ds-shim-tagline="1"] .ds-shim-txt {
      font-weight: 400;
      white-space: nowrap;
    }

    /* running state: shimmer */
    [data-ds-shim-tagline="1"][data-ds-shim-running="1"] .ds-shim-txt {
      background: linear-gradient(
        90deg,
        currentColor 0%,
        currentColor 40%,
        rgba(255,255,255,0.9) 50%,
        currentColor 60%,
        currentColor 100%
      );
      background-size: 200% 100%;
      -webkit-background-clip: text;
      background-clip: text;
      color: transparent;
      animation: dsshim-shimmer 1.6s linear infinite;
    }
    @keyframes dsshim-shimmer {
      from { background-position: 100% 0; }
      to   { background-position: -100% 0; }
    }

    /* result chip (subtle, mono) */
    [data-ds-shim-tagline="1"] .ds-shim-res {
      font-family: ui-monospace,SFMono-Regular,Menlo,monospace;
      font-size: 12px;
      max-width: 240px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      padding: 1px 7px;
      border-radius: 5px;
      background: var(--dsw-alias-bg-tag, rgba(120,150,180,0.10));
      color: var(--dsw-alias-label-tertiary, rgba(190,205,220,0.85));
      margin-left: 2px;
    }
    [data-ds-shim-tagline="1"] .ds-shim-res.err {
      color: #f88;
      background: rgba(240,130,130,0.10);
    }
    [data-ds-shim-tagline="1"] .ds-shim-res::before {
      content: '→ ';
      opacity: .55;
      font-family: system-ui;
    }

    @media (pointer: coarse) {
      [data-ds-shim-tagline="1"] { height: 40px; }
    }

    /* ---------- FAB ---------- */
    #__ds_shim_fab {
      position: fixed;
      z-index: 2147483645;
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

    /* hidden-dot reveal handle */
    #__ds_shim_fab[data-hidden="1"] {
      opacity: 0;
      width: 12px;
      background: linear-gradient(to left, rgba(120,150,180,0.35), transparent);
    }
    #__ds_shim_fab[data-hidden="1"]:hover { opacity: 1; }

    /* ---------- Panel ---------- */
    #__ds_shim_panel {
      position: fixed;
      z-index: 2147483646;
      width: 280px;
      max-width: calc(100vw - 52px);
      max-height: 72vh;
      overflow-y: auto;
      background: rgba(24,28,34,0.95);
      -webkit-backdrop-filter: blur(14px);
      backdrop-filter: blur(14px);
      border: 1px solid rgba(120,150,180,0.22);
      border-radius: 12px;
      color: #dde3ea;
      font: 13px/1.5 system-ui,-apple-system,sans-serif;
      box-shadow: 0 12px 36px rgba(0,0,0,0.45);
      animation: dsshim-panel-in .18s ease;
    }
    @keyframes dsshim-panel-in {
      from { opacity: 0; transform: translateX(10px); }
      to   { opacity: 1; transform: translateX(0); }
    }
    #__ds_shim_panel[hidden] { display: none; }
    .ds-shim-panel-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 12px 14px; border-bottom: 1px solid rgba(120,150,180,0.15);
      font-weight: 500;
    }
    .ds-shim-panel-header b { color: #4db; font-weight: 600; }
    .ds-shim-close {
      background: transparent; border: none; color: #aab; font-size: 22px;
      line-height: 1; cursor: pointer; padding: 0 4px; border-radius: 4px;
    }
    .ds-shim-close:hover { background: rgba(255,255,255,0.08); color: #fff; }
    .ds-shim-panel-body { padding: 8px 14px 14px; }
    .ds-shim-row {
      display: flex; align-items: center; justify-content: space-between;
      padding: 6px 0; font-size: 12.5px;
    }
    .ds-shim-row span { color: rgba(180,195,210,0.7); }
    .ds-shim-row b { color: #dde3ea; font-weight: 500; }
    .ds-shim-row code {
      font-family: ui-monospace,SFMono-Regular,Menlo,monospace;
      font-size: 11.5px; color: #9ab; background: rgba(255,255,255,0.05);
      padding: 1px 5px; border-radius: 3px;
    }
    .ds-shim-status { display: inline-flex; align-items: center; gap: 6px; }
    .ds-shim-status::before {
      content: ''; display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #888;
    }
    .ds-shim-status[data-status="idle"]::before    { background: #4db; }
    .ds-shim-status[data-status="running"]::before { background: #4af; }
    .ds-shim-status[data-status="error"]::before   { background: #f77; }
    .ds-shim-status[data-status="warn"]::before    { background: #fa4; }
    .ds-shim-status[data-status="off"]::before     { background: #888; }
    #__ds_shim_panel hr { border: none; border-top: 1px solid rgba(120,150,180,0.15); margin: 10px 0; }
    .ds-shim-toggle { display: flex; align-items: center; gap: 8px; padding: 7px 0; cursor: pointer; font-size: 12.5px; }
    .ds-shim-toggle input { accent-color: #4af; }
    .ds-shim-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
    .ds-shim-actions button {
      background: rgba(120,150,180,0.10); border: 1px solid rgba(120,150,180,0.20);
      color: #dde3ea; padding: 7px 8px; border-radius: 6px; font-size: 12px;
      cursor: pointer; font-family: inherit; transition: background .15s;
    }
    .ds-shim-actions button:hover { background: rgba(120,150,180,0.22); }
    .ds-shim-actions button.danger { color: #f88; border-color: rgba(240,130,130,0.35); }
    .ds-shim-actions button.danger:hover { background: rgba(240,130,130,0.15); }
    .ds-shim-actions button.wide { grid-column: span 2; }

    /* drag hint toast */
    #__ds_shim_toast {
      position: fixed;
      z-index: 2147483647;
      padding: 6px 12px;
      background: rgba(20,24,30,0.92);
      color: #dde3ea;
      font: 12px/1.4 system-ui,-apple-system,sans-serif;
      border-radius: 8px;
      border: 1px solid rgba(120,150,180,0.25);
      pointer-events: none;
      opacity: 0;
      transition: opacity .2s ease;
      box-shadow: 0 4px 14px rgba(0,0,0,0.35);
    }
    #__ds_shim_toast.show { opacity: 1; }
  `;
  document.head.appendChild(style);

  // ---------- FAB (draggable) ----------
  const fab = document.createElement('div');
  fab.id = '__ds_shim_fab';
  fab.setAttribute('data-status', 'idle');
  fab.setAttribute('role', 'button');
  fab.setAttribute('tabindex', '0');
  fab.setAttribute('aria-label', 'DeepSeek Shim');
  fab.innerHTML = '<div class="ds-shim-dot"></div>';

  // Load saved position (default: right-center)
  const savedPos = lsGet(LS.fabPos, null);
  const applyPos = (pos) => {
    if (pos) {
      fab.style.left = pos.x + 'px';
      fab.style.top = pos.y + 'px';
      fab.style.right = 'auto';
      fab.style.transform = 'none';
    } else {
      fab.style.right = '6px';
      fab.style.left = 'auto';
      fab.style.top = '50%';
      fab.style.transform = 'translateY(-50%)';
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
      <label class="ds-shim-toggle"><input type="checkbox" data-opt="debug" /><span>Debug mode (show TOOL_RESULT)</span></label>
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

  if (!CONFIG.hideFab) document.body.appendChild(fab); else { try { fab.remove(); } catch(e) {} }
  if (!CONFIG.hideFab) document.body.appendChild(panel);

  // ---------- Toast ----------
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
    const tw = 140;
    let x = r.left - tw - 10;
    if (x < 8) x = r.right + 10;
    let y = r.top + r.height / 2 - 14;
    if (y < 8) y = 8;
    if (y > window.innerHeight - 40) y = window.innerHeight - 40;
    toast.style.left = x + 'px';
    toast.style.top = y + 'px';
  }

  // ---------- Drag logic ----------
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
    dragState = {
      startX: p.x, startY: p.y,
      origX: r.left, origY: r.top,
      moved: false,
      pointerId: e.pointerId ?? null,
    };
    fab.classList.add('dragging');
    try { fab.setPointerCapture?.(e.pointerId); } catch {}
    e.preventDefault?.();
  }

  function onPointerMove(e) {
    if (!dragState) return;
    const p = pointFromEvent(e);
    const dx = p.x - dragState.startX;
    const dy = p.y - dragState.startY;
    if (!dragState.moved && Math.hypot(dx, dy) > DRAG_THRESHOLD) {
      dragState.moved = true;
      showToast('Drag to reposition · release near edge to snap');
      positionToast(fab);
    }
    if (!dragState.moved) return;

    let nx = dragState.origX + dx;
    let ny = dragState.origY + dy;

    // clamp inside viewport
    const size = 28;
    nx = Math.max(0, Math.min(window.innerWidth - size, nx));
    ny = Math.max(0, Math.min(window.innerHeight - size, ny));

    fab.style.left = nx + 'px';
    fab.style.top = ny + 'px';
    fab.style.right = 'auto';
    fab.style.transform = 'none';
  }

  function onPointerUp(e) {
    if (!dragState) return;
    const wasMoved = dragState.moved;
    dragState = null;
    fab.classList.remove('dragging');
    try { fab.releasePointerCapture?.(e.pointerId); } catch {}

    if (wasMoved) {
      // snap to nearest horizontal edge
      const r = fab.getBoundingClientRect();
      const cx = r.left + r.width / 2;
      const nearLeft = cx < SNAP_DISTANCE;
      const nearRight = cx > window.innerWidth - SNAP_DISTANCE;
      let finalX = r.left;
      let finalY = r.top;

      if (nearLeft) finalX = 6;
      else if (nearRight) finalX = window.innerWidth - r.width - 6;
      else {
        // free position, but still clamp
        finalX = Math.max(6, Math.min(window.innerWidth - r.width - 6, r.left));
      }
      finalY = Math.max(6, Math.min(window.innerHeight - r.height - 6, r.top));

      fab.style.left = finalX + 'px';
      fab.style.top = finalY + 'px';

      lsSet(LS.fabPos, { x: finalX, y: finalY });
      showToast('Position saved');
      positionToast(fab);
      return;
    }

    // Not moved → it's a tap/click → toggle panel
    togglePanel();
  }

  fab.addEventListener('pointerdown', onPointerDown);
  window.addEventListener('pointermove', onPointerMove, { passive: true });
  window.addEventListener('pointerup', onPointerUp);
  window.addEventListener('pointercancel', onPointerUp);

  // Fallback for very old browsers (touch events)
  if (!window.PointerEvent) {
    fab.addEventListener('touchstart', onPointerDown, { passive: false });
    window.addEventListener('touchmove', onPointerMove, { passive: true });
    window.addEventListener('touchend', onPointerUp);
  }

  // Keyboard access
  fab.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); togglePanel(); }
  });

  // Reposition on resize if not on saved pos (snap to nearest edge)
  window.addEventListener('resize', () => {
    const saved = lsGet(LS.fabPos, null);
    if (!saved) return;
    const size = 28;
    let x = Math.min(saved.x, window.innerWidth - size - 6);
    let y = Math.min(saved.y, window.innerHeight - size - 6);
    x = Math.max(6, x); y = Math.max(6, y);
    fab.style.left = x + 'px'; fab.style.top = y + 'px';
    lsSet(LS.fabPos, { x, y });
  });

  // ---------- Status + counts ----------
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

  // ---------- Panel open/close + positioning ----------
  function positionPanel() {
    const r = fab.getBoundingClientRect();
    const pw = 280;
    const ph = Math.min(window.innerHeight * 0.72, 480);
    let px = r.left - pw - 8;
    if (px < 8) px = r.right + 8;
    if (px + pw > window.innerWidth - 8) px = window.innerWidth - pw - 8;
    let py = r.top + r.height / 2 - ph / 2;
    py = Math.max(8, Math.min(window.innerHeight - ph - 8, py));
    panel.style.left = px + 'px';
    panel.style.top = py + 'px';
    panel.style.right = 'auto';
    panel.style.transform = 'none';
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
        document.querySelectorAll('div.ds-message').forEach(el => {
          const text = (el.innerText || '').trim();
          if (!text.startsWith('TOOL_RESULT:')) return;
          const wrapper = findWrapper(el);
          if (!wrapper) return;
          if (CONFIG.debug) wrapper.removeAttribute('data-ds-shim-hidden');
          else wrapper.setAttribute('data-ds-shim-hidden', '1');
        });
      }
    });
  });

  panel.querySelector('[data-act="resetFab"]').onclick = () => {
    lsSet(LS.fabPos, null);
    applyPos(null);
    showToast('Dot reset to right-center');
    positionToast(fab);
    if (!panel.hidden) positionPanel();
  };
  panel.querySelector('[data-act="hideFab"]').onclick = () => {
    lsSet(LS.fabHidden, true);
    fab.setAttribute('data-hidden', '1');
    showToast('Dot hidden · reload or tap edge to show');
  };
  panel.querySelector('[data-act="clearDone"]').onclick   = () => { DONE = {}; saveDone(); refreshCounts(); log('done cleared'); };
  panel.querySelector('[data-act="clearMemory"]').onclick = () => { lsSet(LS.memory, {}); refreshCounts(); log('memory cleared'); };
  panel.querySelector('[data-act="clearFs"]').onclick     = () => { lsSet(LS.fs, {}); refreshCounts(); log('fs cleared'); };
  panel.querySelector('[data-act="copyLogs"]').onclick = async () => {
    const text = LOGS.map(l => `[${new Date(l.t).toISOString()}] ${l.level}: ${l.msg}`).join('\n');
    try { await navigator.clipboard.writeText(text); log('logs copied (' + LOGS.length + ')'); }
    catch {
      const ta = document.createElement('textarea');
      ta.value = text; document.body.appendChild(ta); ta.select();
      document.execCommand('copy'); ta.remove();
    }
    showToast('Logs copied'); positionToast(fab); refreshCounts();
  };
  panel.querySelector('[data-act="stop"]').onclick = () => {
    if (confirm('Stop shim? Restart with the bookmarklet.')) window.__DS_TOOL_SHIM__.stop();
  };

  // Restore hidden state
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
    window.list_tools = () => window.__ds_call_tool('list_tools', {});
    window.describe = (name) => window.__ds_call_tool('describe', { name });
    window.http_request = (opts) => window.__ds_call_tool('http_request', opts || {});
    window.github = {
      me: () => window.__ds_call_tool('github', { op: 'me' }),
      repos: (n) => window.__ds_call_tool('github', { op: 'repos', per_page: n }),
      issues: (o,r,st) => window.__ds_call_tool('github', { op: 'issues', owner: o, repo: r, state: st }),
      issue_comment: (o,r,n,b) => window.__ds_call_tool('github', { op: 'issue_comment', owner: o, repo: r, number: n, body: b }),
      pr: (o,r,n) => window.__ds_call_tool('github', { op: 'pr', owner: o, repo: r, number: n }),
      pr_files: (o,r,n) => window.__ds_call_tool('github', { op: 'pr_files', owner: o, repo: r, number: n }),
      pr_reviews: (o,r,n) => window.__ds_call_tool('github', { op: 'pr_reviews', owner: o, repo: r, number: n }),
      pr_commits: (o,r,n) => window.__ds_call_tool('github', { op: 'pr_commits', owner: o, repo: r, number: n }),
      issue: (o,r,n) => window.__ds_call_tool('github', { op: 'issue', owner: o, repo: r, number: n }),
      contents: (o,r,path,ref) => window.__ds_call_tool('github', { op: 'contents', owner: o, repo: r, path, ref }),
      search: (q,type) => window.__ds_call_tool('github', { op: 'search', query: q, type }),
      pr_create: (o,r,title,head,base,body,draft) => window.__ds_call_tool('github', { op: 'pr_create', owner: o, repo: r, title, head, base, body, draft }),
      request: (m,path,body) => window.__ds_call_tool('github', { op: 'request', method: m, path, body }),
    };
    window.keys = { get:(n)=>window.__ds_call_tool('keys',{op:'get',name:n}), set:(n,v)=>window.__ds_call_tool('keys',{op:'set',name:n,value:v}), list:()=>window.__ds_call_tool('keys',{op:'list'}), delete:(n)=>window.__ds_call_tool('keys',{op:'delete',name:n}) };
    window.device = { info:()=>window.__ds_call_tool('device',{op:'info'}), battery:()=>window.__ds_call_tool('device',{op:'battery'}), network:()=>window.__ds_call_tool('device',{op:'network'}) };
    window.notify = (title,body)=>window.__ds_call_tool('notify',{title,body});
    window.toast = (message)=>window.__ds_call_tool('toast',{message});
    window.vibrate = (ms)=>window.__ds_call_tool('vibrate',{ms});
    window.share = (text)=>window.__ds_call_tool('share',{text});
    window.appInfo = ()=>window.__ds_call_tool('appInfo',{});
    window.geo = { get: ()=>window.__ds_call_tool('geo',{}) };
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
    async list_tools() {
      if (window.__DHarnessNative && window.__DHarnessNative.list_tools) return window.__DHarnessNative.list_tools();
      return { tools: Object.keys(toolHandlers), native: false };
    },
    async describe(args) {
      const name = typeof args === 'string' ? args : (args && args.name);
      if (window.__DHarnessNative && window.__DHarnessNative.describe) return window.__DHarnessNative.describe(name || '');
      return { error: 'no describe' };
    },
    async http_request(args) {
      if (window.__DHarnessNative && window.__DHarnessNative.http_request) return window.__DHarnessNative.http_request(args || {});
      throw new Error('http_request requires native');
    },
    async github(args) {
      const N = window.__DHarnessNative && window.__DHarnessNative.github;
      if (!N) throw new Error('github requires native + PAT');
      const op = (args && args.op) || 'me';
      if (op === 'me') return N.me();
      if (op === 'repos') return N.repos(args.per_page);
      if (op === 'issues') return N.issues(args.owner, args.repo, args.state);
      if (op === 'issue_comment') return N.issue_comment(args.owner, args.repo, args.number, args.body);
      if (op === 'pr') return N.pr(args.owner, args.repo, args.number);
      if (op === 'pr_files') return N.pr_files(args.owner, args.repo, args.number);
      if (op === 'pr_reviews') return N.pr_reviews(args.owner, args.repo, args.number);
      if (op === 'pr_commits') return N.pr_commits(args.owner, args.repo, args.number);
      if (op === 'issue') return N.issue(args.owner, args.repo, args.number);
      if (op === 'contents') return N.contents(args.owner, args.repo, args.path, args.ref);
      if (op === 'search') return N.search(args.query, args.type);
      if (op === 'pr_create') return N.pr_create(args.owner, args.repo, args.title, args.head, args.base, args.body, args.draft);
      if (op === 'request') return N.request(args.method || 'GET', args.path, args.body);
      throw new Error('unknown github op');
    },

    async toast(args) {
      const m = (args && (args.message || args.m || args.text)) || (typeof args === 'string' ? args : '');
      if (window.__DHarnessNative && window.__DHarnessNative.toast) return window.__DHarnessNative.toast(m);
      throw new Error('toast requires native');
    },
    async vibrate(args) {
      const ms = (args && (args.ms || args.duration)) || 40;
      if (window.__DHarnessNative && window.__DHarnessNative.vibrate) return window.__DHarnessNative.vibrate(ms);
      throw new Error('vibrate requires native');
    },
    async share(args) {
      const t = (args && (args.text || args.message)) || '';
      if (window.__DHarnessNative && window.__DHarnessNative.share) return window.__DHarnessNative.share(t);
      throw new Error('share requires native');
    },
    async appInfo() {
      if (window.__DHarnessNative && window.__DHarnessNative.appInfo) return window.__DHarnessNative.appInfo();
      throw new Error('appInfo requires native');
    },
    async file(args) {
      const N = window.__DHarnessNative && window.__DHarnessNative.file;
      if (!N) throw new Error('file requires native');
      const op = (args && args.op) || 'commit';
      if (op === 'commit') return N.commit(args.path, args.contentB64, args.sha256);
      if (op === 'read_b64') return N.read_b64(args.path);
      if (op === 'verify_roundtrip') return N.verify_roundtrip();
      throw new Error('unknown file op');
    },
    async exec(args) {
      if (!window.__DHarnessNative || !window.__DHarnessNative.exec) throw new Error('exec requires native');
      return window.__DHarnessNative.exec(args.argv || args, args.timeout_ms, args.cwd);
    },
    async sqlite(args) {
      if (!window.__DHarnessNative || !window.__DHarnessNative.sqlite) throw new Error('sqlite requires native');
      return window.__DHarnessNative.sqlite.query(args.path, args.sql, args.args);
    },
    async crypto(args) {
      const C = window.__DHarnessNative && window.__DHarnessNative.crypto;
      if (!C) throw new Error('crypto requires native');
      if ((args && args.op) === 'hmac') return C.hmac(args.key, args.data);
      return C.hash((args && args.algo) || 'sha256', args.data, args.encoding);
    },
    async archive(args) {
      const A = window.__DHarnessNative && window.__DHarnessNative.archive;
      if (!A) throw new Error('archive requires native');
      const op = (args && args.op) || 'zip_list';
      if (op === 'zip_list') return A.zip_list(args.path);
      if (op === 'zip_extract') return A.zip_extract(args.path, args.entry);
      if (op === 'zip_create') return A.zip_create(args.path, args.files);
      throw new Error('unknown archive op');
    },
    async json_query(args) {
      if (!window.__DHarnessNative) throw new Error('json_query requires native');
      return window.__DHarnessNative.json_query(args.json, args.path);
    },
    async net(args) {
      const N = window.__DHarnessNative && window.__DHarnessNative.net;
      if (!N) throw new Error('net requires native');
      if ((args && args.op) === 'port') return N.port(args.host, args.port, args.timeout_ms);
      return N.ping(args.host, args.timeout_ms);
    },
    async process(args) {
      const P = window.__DHarnessNative && window.__DHarnessNative.process;
      if (!P) throw new Error('process requires native');
      if ((args && args.op) === 'kill') return P.kill(args.pid);
      return P.list();
    },
    async fs(args) {
      const F = window.__DHarnessNative && window.__DHarnessNative.fs;
      if (!F) throw new Error('fs requires native');
      const op = (args && args.op) || 'list';
      if (op === 'read') return F.read(args.path);
      if (op === 'write') return F.write(args.path, args.content);
      if (op === 'list') return F.list(args.prefix || '');
      if (op === 'delete') return F.delete(args.path);
      if (op === 'stat') return F.stat(args.path);
      if (op === 'exists') return F.exists(args.path);
      if (op === 'append') return F.append(args.path, args.content);
      if (op === 'mkdir') return F.mkdir(args.path);
      if (op === 'touch') return F.touch(args.path);
      if (op === 'copy') return F.copy(args.from, args.to);
      if (op === 'move') return F.move(args.from, args.to);
      throw new Error('unknown fs op');
    },
    async env(args) {
      if (!window.__DHarnessNative || !window.__DHarnessNative.env) throw new Error('env requires native');
      return window.__DHarnessNative.env.get();
    },
    async json_pretty(args) {
      return window.__DHarnessNative.json.pretty(args.json, args.indent);
    },
    async color(args) {
      return window.__DHarnessNative.color.hex_rgb(args.op || 'to_rgb', args.value);
    },
    async diff(args) {
      return window.__DHarnessNative.diff.lines(args.a, args.b);
    },

    async calc(args) {
      const C = window.__DHarnessNative && window.__DHarnessNative.calc;
      if (!C) throw new Error('calc requires native');
      const op = (args && args.op) || 'eval';
      if (op === 'convert') return C.convert(args.value, args.from, args.to);
      if (op === 'haversine') return C.haversine(args.lat1, args.lon1, args.lat2, args.lon2);
      if (op === 'clamp') return C.clamp(args.value, args.min, args.max);
      if (op === 'round') return C.round(args.value, args.digits || 0);
      return C.eval(args.expr || args);
    },
    async text(args) {
      const T = window.__DHarnessNative && window.__DHarnessNative.text;
      if (!T) throw new Error('text requires native');
      const op = (args && args.op) || 'stats';
      if (op === 'base64') return T.base64(args.mode || args.action || 'encode', args.data);
      if (op === 'url') return T.url(args.mode || args.action || 'encode', args.data);
      if (op === 'regex') return T.regex(args.mode || 'find', args.pattern, args.text, args.replacement);
      if (op === 'case') return T.case(args.mode || args.case || 'lower', args.text);
      if (op === 'trim') return T.trim(args.text);
      if (op === 'split') return T.split(args.text, args.sep || ',', args.limit || 0);
      if (op === 'join') return T.join(args.parts || [], args.sep || '');
      return T.stats(args.text || '');
    },
    async time(args) {
      const T = window.__DHarnessNative && window.__DHarnessNative.time;
      if (!T) throw new Error('time requires native');
      if ((args && args.op) === 'format') return T.format(args.ms, args.pattern);
      return T.now();
    },
    async uuid() {
      return window.__DHarnessNative.uuid.v4();
    },
    async random(args) {
      return window.__DHarnessNative.random.bytes((args && args.n) || 16);
    },
    async intent(args) {
      return window.__DHarnessNative.intent.open_url(args.url || args);
    },


    async geo(args) {
      if (window.__DHarnessNative && window.__DHarnessNative.geo) return window.__DHarnessNative.geo.get();
      throw new Error('geo requires native + location permission');
    },


    async keys({ op, name, value }) {
      if (window.__DHarnessNative && window.__DHarnessNative.keys) {
        const K = window.__DHarnessNative.keys;
        if (op === 'get') return K.get(name);
        if (op === 'set') return K.set(name, value);
        if (op === 'delete') return K.delete(name);
        if (op === 'list') return K.list();
      }
      throw new Error('keys requires native');
    },
    async device({ op }) {
      if (window.__DHarnessNative && window.__DHarnessNative.device) {
        const D = window.__DHarnessNative.device;
        if (op === 'info' || !op) return D.info();
        if (op === 'battery') return D.battery();
        if (op === 'network') return D.network();
      }
      throw new Error('device requires native');
    },
    async notify({ title, body }) {
      if (window.__DHarnessNative && window.__DHarnessNative.notify)
        return window.__DHarnessNative.notify(title || 'D-Harness', body || '');
      return { ok: false };
    },
    async memory({ op, key, value }) {
      if (window.__DHarnessNative && window.__DHarnessNative.available && window.__DHarnessNative.memory) {
        const N = window.__DHarnessNative.memory;
        if (op === 'get') return N.get(key);
        if (op === 'set') return N.set(key, value);
        if (op === 'delete') return N.delete(key);
        if (op === 'list') return N.list();
        if (op === 'clear') return N.clear();
      }
      const m = lsGet(LS.memory, {});
      if (op === 'get')    return { value: key in m ? m[key] : null };
      if (op === 'set')    { sizeGuard(value); m[key] = value; lsSet(LS.memory, m); return { ok: true }; }
      if (op === 'delete') { delete m[key]; lsSet(LS.memory, m); return { ok: true }; }
      if (op === 'list')   return { keys: Object.keys(m) };
      if (op === 'clear')  { lsSet(LS.memory, {}); return { ok: true }; }
      throw new Error('unknown memory op: ' + op);
    },
    async fetch_url(args) {
      const a = args || {};
      if (window.__DHarnessNative && window.__DHarnessNative.fetch_url) {
        return window.__DHarnessNative.fetch_url(a.url || a, a);
      }
      throw new Error('fetch_url requires native');
    },
    async file_save({ filename, content, mime = 'text/plain' }) {
      if (window.__DHarnessNative && window.__DHarnessNative.available && window.__DHarnessNative.file_save) {
        return window.__DHarnessNative.file_save(filename, content, mime);
      }
      const blob = new Blob([content], { type: mime });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = filename;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 5000);
      return { ok: true, filename, bytes: String(content).length };
    },
    async clipboard_copy({ text }) {
      if (window.__DHarnessNative && window.__DHarnessNative.available && window.__DHarnessNative.clipboard) {
        return window.__DHarnessNative.clipboard.copy(text);
      }
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
      if (window.__DHarnessNative && window.__DHarnessNative.available && window.__DHarnessNative.clipboard) {
        return window.__DHarnessNative.clipboard.read();
      }
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
      if (window.__DHarnessNative && window.__DHarnessNative.available && window.__DHarnessNative.fs) {
        const F = window.__DHarnessNative.fs;
        if (op === 'read') return F.read(path);
        if (op === 'write') return F.write(path, content);
        if (op === 'list') return F.list(path || '');
        if (op === 'delete') return F.delete(path);
        if (op === 'exists') {
          try { const r = await F.read(path); return { exists: !!(r && (r.ok !== false) && r.content !== undefined) }; }
          catch { return { exists: false }; }
        }
      }
      const m = lsGet(LS.fs, {});
      if (op === 'read')   { const f = m[path]; if (!f) throw new Error('not found: ' + path); return { content: f.content, mime: f.mime, mtime: f.mtime }; }
      if (op === 'write')  { sizeGuard(content); m[path] = { content: String(content ?? ''), mime, mtime: Date.now() }; if (!lsSet(LS.fs, m)) throw new Error('write failed'); return { ok: true, path, bytes: m[path].content.length }; }
      if (op === 'list')   return { paths: Object.keys(m).filter(p => p.startsWith(path || '')) };
      if (op === 'delete') { delete m[path]; lsSet(LS.fs, m); return { ok: true }; }
      if (op === 'exists') return { exists: !!m[path] };
      throw new Error('unknown fs op: ' + op);
    },
  };

  // ---------- Message bridge ----------
  const pending = new Map();
  let msgId = 0;

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

  function runInSandbox(code, timeoutMs = CONFIG.sandboxTimeoutMs) {
    return new Promise((resolve) => {
      const start = Date.now();
      const trySend = () => {
        if (!iframeReady || !iframe.contentWindow) {
          if (Date.now() - start > 5000) {
            resolve({ ok: false, error: 'iframe not ready' });
            return;
          }
          setTimeout(trySend, 50);
          return;
        }
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
      };
      trySend();
    });
  }

  // ---------- Input / Send (DeepSeek React-controlled composer) ----------
  function getInput() {
    const sels = [
      'textarea[placeholder="Message DeepSeek"]',
      'textarea[placeholder*="DeepSeek" i]',
      'textarea[placeholder*="Message" i]',
      'div.ds-scroll-area textarea',
      'form textarea',
      'textarea[name="search"]',
      'textarea',
    ];
    for (const sel of sels) {
      try {
        const el = document.querySelector(sel);
        if (el && el.offsetParent !== null) return el;
      } catch (e) {}
    }
    // contenteditable fallback
    const ce = document.querySelector('[contenteditable="true"]');
    return ce || null;
  }

  function setNativeValue(el, value) {
    if (!el) return;
    if (el.isContentEditable) {
      el.focus();
      el.textContent = value;
      el.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: value }));
      return;
    }
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const desc = Object.getOwnPropertyDescriptor(proto, 'value')
      || Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
    if (desc && desc.set) desc.set.call(el, value);
    else el.value = value;
    // React 17/18 listen for InputEvent
    try {
      el.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: 'insertText', data: value }));
    } catch (e) {
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  const SEND_SELECTOR = 'div[role="button"].ds-button--primary.ds-button--circle, div[role="button"].ds-button--primary, button[type="submit"]';
  const SEND_ICON_PREFIXES = ['M8.3125', 'M10.5', 'M2 ', 'M3 ']; // tolerate icon path churn
  function findEnabledSendButton() {
    const nodes = document.querySelectorAll(SEND_SELECTOR);
    for (const b of nodes) {
      if (b.classList.contains('ds-button--disabled') || b.getAttribute('aria-disabled') === 'true' || b.disabled) continue;
      if (b.offsetParent === null) continue;
      const d = b.querySelector('svg path')?.getAttribute('d') || '';
      // Prefer icon that looks like send/arrow; else first enabled primary circle
      if (d && SEND_ICON_PREFIXES.some((p) => d.startsWith(p))) return b;
    }
    // fallback: any enabled primary circle button near composer
    for (const b of document.querySelectorAll('div[role="button"].ds-button--circle.ds-button--filled')) {
      if (b.classList.contains('ds-button--disabled')) continue;
      if (b.offsetParent === null) continue;
      return b;
    }
    return null;
  }

  async function sendMessage(text) {
    const input = getInput();
    if (!input) { log('no input'); setStatus('error'); return false; }
    try { input.focus(); } catch (e) {}
    setNativeValue(input, text);
    // wait briefly for React to enable send
    let t0 = Date.now();
    let btn = null;
    while (Date.now() - t0 < Math.min(CONFIG.sendTimeoutMs, 2500)) {
      btn = findEnabledSendButton();
      if (btn) break;
      await new Promise((r) => setTimeout(r, 20));
    }
    if (btn) {
      btn.click();
      // confirm cleared
      t0 = Date.now();
      while (Date.now() - t0 < 800) {
        const v = input.isContentEditable ? (input.textContent || '') : (input.value || '');
        if (!v || v.length < text.length / 2) { log('sent via button'); return true; }
        await new Promise((r) => setTimeout(r, 30));
      }
      log('sent via button (no clear confirm)'); return true;
    }
    // Enter fallback
    const ke = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
    input.dispatchEvent(new KeyboardEvent('keydown', ke));
    input.dispatchEvent(new KeyboardEvent('keypress', ke));
    input.dispatchEvent(new KeyboardEvent('keyup', ke));
    await new Promise((r) => setTimeout(r, 80));
    const v = input.isContentEditable ? (input.textContent || '') : (input.value || '');
    if (!v) { log('sent via Enter'); return true; }
    log('send failed'); setStatus('error'); return false;
  }

  // ---------- Tool-call parsing ----------

  /**
   * Prefer <pre>/<code> text; otherwise walk the DOM and reconstruct
   * *emphasis* so Markdown rendering does not strip multiplication operators
   * from run_js code (DeepSeek renders *word* as <em>word</em>).
   */
  function messageTextForTools(el) {
    try {
      const blocks = el.querySelectorAll('pre code, pre');
      if (blocks.length) {
        return Array.from(blocks).map((b) => b.textContent || '').join('\n').trim();
      }
    } catch (e) {}
    function walk(node) {
      if (!node) return '';
      if (node.nodeType === 3) return node.nodeValue || '';
      if (node.nodeType !== 1) return '';
      const tag = node.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE') return '';
      if (tag === 'BR') return '\n';
      if (tag === 'CODE' || tag === 'PRE') return node.textContent || '';
      let inner = '';
      for (let i = 0; i < node.childNodes.length; i++) inner += walk(node.childNodes[i]);
      if (tag === 'EM' || tag === 'I') return '*' + inner + '*';
      if (tag === 'STRONG' || tag === 'B') return '**' + inner + '**';
      return inner;
    }
    return walk(el).trim();
  }

  function extractToolCall(text) {
    // Normalize smart quotes that DeepSeek/markdown sometimes injects into JSON
    text = text.replace(/[\u201C\u201D\u201E\u201F\u2033\u2036]/g, '"')
               .replace(/[\u2018\u2019\u201A\u201B\u2032\u2035]/g, "'");
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
              try {
                const obj = JSON.parse(cand);
                if (obj && obj.tool === 'run_js' && obj.args && typeof obj.args.code === 'string') return { obj, full: cand };
              } catch {}
              break;
            }
          }
        }
      }
      idx += 6;
    }
    return null;
  }

  // ---------- DOM helpers ----------
  function findWrapper(dsMessage) {
    const p = dsMessage.parentElement;
    if (!p) return null;
    if (p.classList.contains('_4f9bf79') && p.classList.contains('_43c05b5')) return p;
    if (p.classList.contains('_9663006')) return p;
    const siblings = Array.from(p.children).filter(c => c !== dsMessage);
    if (siblings.length <= 1 && siblings[0]?.classList.contains('ds-flex')) return p;
    return null;
  }

  function occurrenceIndex(dsMessage, toolJson) {
    const all = Array.from(document.querySelectorAll('div.ds-message'));
    let n = 0;
    for (const m of all) {
      if (m === dsMessage) return n;
      const txt = messageTextForTools(m);
      if (txt.includes(toolJson)) n++;
    }
    return n;
  }

  const fullSig = (dsMessage, toolJson) => CONV_ID + ':' + hashStr(toolJson) + ':' + occurrenceIndex(dsMessage, toolJson);

  // ---------- Tagline (native-style) ----------
  const CHEV_SVG = '<svg viewBox="0 0 16 16" aria-hidden="true"><polyline points="6 3 11 8 6 13"></polyline></svg>';

  function createTagline(preview = '', isError = false, running = false) {
    const el = document.createElement('div');
    el.setAttribute('data-ds-shim-tagline', '1');
    if (running) el.setAttribute('data-ds-shim-running', '1');
    const resHtml = preview ? `<span class="ds-shim-res${isError ? ' err' : ''}">${esc(preview)}</span>` : '';
    el.innerHTML =
      `<span class="ds-shim-chev">${CHEV_SVG}</span>` +
      `<span class="ds-shim-txt">${running ? 'Running…' : 'Tool'}</span>` +
      resHtml;
    return el;
  }

  function updateTagline(tagline, preview, isError, running) {
    tagline.toggleAttribute('data-ds-shim-running', !!running);
    tagline.querySelector('.ds-shim-txt').textContent = running ? 'Running tool…' : 'Tool used';
    let rEl = tagline.querySelector('.ds-shim-res');
    if (preview !== undefined) {
      if (!rEl && preview) {
        rEl = document.createElement('span');
        rEl.className = 'ds-shim-res';
        tagline.appendChild(rEl);
      }
      if (rEl) {
        rEl.textContent = preview || '';
        rEl.classList.toggle('err', !!isError);
      }
    }
  }

  const processed = new WeakSet();
  const taglineMap = new WeakMap();

  function collapseToolMessage(dsMessage, preview, isError, running) {
    const wrapper = findWrapper(dsMessage);
    if (!wrapper) { log('no wrapper'); return null; }
    Array.from(wrapper.children).forEach(child => {
      if (child.hasAttribute('data-ds-shim-tagline')) return;
      child.setAttribute('data-ds-shim-hidden', '1');
    });
    let tagline = taglineMap.get(wrapper);
    if (!tagline || !tagline.isConnected) {
      tagline = createTagline(preview, isError, running);
      wrapper.insertBefore(tagline, wrapper.firstChild);
      taglineMap.set(wrapper, tagline);
      tagline.onclick = () => {
        const expanded = tagline.getAttribute('data-ds-shim-expanded') === '1';
        Array.from(wrapper.children).forEach(c => {
          if (c === tagline) return;
          if (expanded) c.setAttribute('data-ds-shim-hidden', '1');
          else c.removeAttribute('data-ds-shim-hidden');
        });
        tagline.setAttribute('data-ds-shim-expanded', expanded ? '0' : '1');
      };
    } else updateTagline(tagline, preview, isError, running);
    return tagline;
  }

  // ---------- Process ----------
  let busy = false;
  let busySince = 0;

  async function processToolCall(dsMessage, tool) {
    const sig = fullSig(dsMessage, tool.full);
    const code = tool.obj.args.code;

    if (CONFIG.dedupe && DONE[sig]) {
      const prev = DONE[sig];
      log('deduped:', sig);
      const preview = prev.ok ? String(prev.result).slice(0, 40) : 'error';
      collapseToolMessage(dsMessage, preview, !prev.ok, false);
      setStatus(prev.ok ? 'idle' : 'warn');
      return;
    }

    log('tool call:', code, '| sig:', sig);
    collapseToolMessage(dsMessage, '', false, true);
    setStatus('running');

    const res = await runInSandbox(code);
    log('result:', res);

    DONE[sig] = { ok: res.ok, result: res.result, error: res.error, t: Date.now() };
    saveDone(); refreshCounts();

    const preview = res.ok ? String(res.result).slice(0, 40) : 'error';
    collapseToolMessage(dsMessage, preview, !res.ok, false);
    setStatus(res.ok ? 'idle' : 'error');

    const payload = JSON.stringify({ ok: res.ok, result: res.result, error: res.error, sig: sig, ts: Date.now() });
    await sendMessage('TOOL_RESULT: ' + payload);
  }

  // ---------- Scan ----------
  function hidePass() {
    document.querySelectorAll('div.ds-message').forEach(el => {
      if (el.getAttribute('data-ds-shim-hidden') === '1') return;
      if (processed.has(el)) return;
      const text = messageTextForTools(el) || (el.innerText || '').trim();
      if (!text) return;
      if (text.startsWith('TOOL_RESULT:')) {
        const wrapper = findWrapper(el);
        if (wrapper) {
          processed.add(el);
          if (!CONFIG.debug) wrapper.setAttribute('data-ds-shim-hidden', '1');
        }
      }
    });
  }

  function scanForToolCalls() {
    if (busy) {
      if (busySince && Date.now() - busySince > 60000) { busy = false; busySince = 0; log('busy watchdog reset'); }
      else return;
    }
    // Only walk recent assistant bubbles (cheap + avoids full-DOM thrash)
    const all = document.querySelectorAll('div.ds-message');
    const start = Math.max(0, all.length - 24);
    for (let i = start; i < all.length; i++) {
      const el = all[i];
      if (processed.has(el)) continue;
      if (!el.querySelector('div.ds-markdown.ds-assistant-message-main-content, .ds-assistant-message-main-content, pre code')) continue;
      const wrapper = findWrapper(el);
      if (wrapper?.querySelector(':scope > [data-ds-shim-tagline]')) { processed.add(el); continue; }
      const text = messageTextForTools(el);
      if (!text || text.startsWith('TOOL_RESULT:')) continue;
      const tool = extractToolCall(text);
      if (!tool) continue;
      processed.add(el);
      busy = true; busySince = Date.now();
      processToolCall(el, tool).catch(e => { log('error:', e); setStatus('error'); }).finally(() => { busy = false; busySince = 0; });
      return;
    }
  }

  function tick() {
    try { hidePass(); scanForToolCalls(); }
    catch (e) { log('tick error:', e); setStatus('error'); }
  }

  const debounce = (fn, ms) => { let t; return () => { clearTimeout(t); t = setTimeout(fn, ms); }; };
  const debouncedTick = debounce(tick, 250);
  const observer = new MutationObserver(debouncedTick);
  observer.observe(document.body, { childList: true, subtree: true, characterData: false });
  const loopTimer = setInterval(tick, 2000);
  setTimeout(tick, 600);

  // ---------- Public API ----------
  window.__DS_TOOL_SHIM__ = {
    version: VERSION,
    stop() {
      observer.disconnect();
      clearInterval(loopTimer);
      setStatus('off');
      style.remove();
      document.querySelectorAll('[data-ds-shim-tagline]').forEach(el => el.remove());
      document.querySelectorAll('[data-ds-shim-hidden]').forEach(el => el.removeAttribute('data-ds-shim-hidden'));
      fab.remove(); panel.remove(); toast.remove();
      delete window.__DS_TOOL_SHIM__;
      console.log('%c[shim] stopped', 'color:#0af');
    },
    tick, send: sendMessage, run: runInSandbox,
    showPanel: () => togglePanel(true),
    hidePanel: () => togglePanel(false),
    resetFab: () => { lsSet(LS.fabPos, null); applyPos(null); },
    showFab: () => { lsSet(LS.fabHidden, false); fab.removeAttribute('data-hidden'); },
    stats() {
      const s = { version: VERSION, convId: CONV_ID, done: Object.keys(DONE).length, memoryKeys: Object.keys(lsGet(LS.memory, {})).length, fsFiles: Object.keys(lsGet(LS.fs, {})).length };
      console.log('[shim] stats:', s);
      return s;
    },
    logs: () => LOGS.slice(),
    ping: async () => {
      const r = await runInSandbox('return 1+1');
      return r;
    },
    ready: () => !!(iframeReady && window.__DS_TOOL_SHIM__),
  };

  refreshCounts();
  console.log(`%c✅ D-Harness · DeepSeek Tool Shim v${VERSION} loaded — drag the dot to move, click to open panel`, 'color:#0af;font-weight:bold');

})();