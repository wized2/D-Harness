/*!
 * DeepSeek Tool Shim
 * @version 8.0.0-theme-projects-queue (upstream dsh.js + D-Harness native bridge tools)
 * @description run_js tool bridge + draggable status dot + management panel
 *
 * 7.6.1:
 *  - tagline shows only description (no result JSON chip)
 * 7.6.0:
 *  - background agent: scan continues when document.hidden; native FGS + wake lock
 *  - optional tool JSON field "description" (alias "discription") for tagline + notification
 * 7.5.0:
 *  - full re-audit for stray symbols: removed the checkmark from the console load banner
 *    and the decorative arrow character from the result chip (now a plain "- "). Nothing
 *    rendered in the UI (tagline, FAB, panel, toasts) uses emoji or pictographic
 *    characters; the only glyphs are the custom terminal SVG icon and a plain arrow SVG
 *    used for expand/collapse.
 *
 * 7.4.1:
 *  - leading icon is a custom terminal glyph (own SVG) instead of trying to clone
 *    DeepSeek's own chevron; it pulses in place while a tool call runs. The small arrow
 *    at the end goes back to being purely an expand/collapse control.
 *
 * 7.4.0:
 *  - tagline no longer uses emoji glyphs; the collapse chevron is now the only status
 *    icon — it spins while a tool runs and rotates on expand/collapse otherwise, the same
 *    two jobs DeepSeek's own "Thought for Ns" header uses its chevron for. When that
 *    native header is present on the page, its actual SVG is cloned so the icon matches
 *    pixel-for-pixel; otherwise a plain fallback chevron is used
 *
 * 7.3.0:
 *  - sendMessage no longer fades the textarea's opacity with a CSS transition; it hides
 *    the whole composer bar (textarea + send button + toggles) with a single, un-animated
 *    visibility:hidden, and pins its height, so no partial paint, button-state flicker,
 *    or layout reflow is visible while a TOOL_RESULT is sent — and no rAF chain on restore
 *
 * 7.2.0 (verified against a live capture, DeepSeek build main.84ce94ca1f):
 *  - stable per-message ID (React fiber messageId / data-virtual-list-item-key) replaces
 *    occurrenceIndex dedupe, so the virtual list recycling nodes can't re-run or skip calls
 *  - only the LAST message is scanned; runs only when generation is finished (stop/spinner
 *    icon gone + text settled) and only for messages newer than what was on screen at load
 *  - tool JSON is read from the ANSWER only (not the thinking block) and must end the message
 *  - result is marked sent only after the send succeeds (unsent ones retry once)
 *  - user's composer draft is preserved while sending TOOL_RESULT
 *  - sandbox iframe is rebuilt on timeout (infinite loops no longer wedge it)
 *  - confirm prompts for clipboard_read / geo_get / non-GET fetch_url; private hosts blocked
 *  - TOOL_RESULT payload is truncated; memory.set reports quota failures
 *  - cheaper scanning (no textContent over every message on every tick)
 */
(function () {
  'use strict';
  if (window.top !== window.self) return;
  if (window.__DS_TOOL_SHIM__) {
    if (!window.__DS_FORCE_REINJECT__) { console.log('[shim] already loaded'); return; }
    try { window.__DS_TOOL_SHIM__.stop && window.__DS_TOOL_SHIM__.stop(); } catch (e) {}
    try {
      document.getElementById('__ds_shim_style__')?.remove();
      document.getElementById('__ds_shim_fab')?.remove();
      document.getElementById('__ds_shim_panel')?.remove();
      document.getElementById('__ds_shim_toast')?.remove();
    } catch (e) {}
    try { delete window.__DS_TOOL_SHIM__; } catch (e) {}
  }

  const VERSION = '8.1.0-bds-ui';
  const getConvId = () => location.pathname.split('/').filter(Boolean).pop() || 'unknown';
  const CONFIG = Object.assign({
    debug: false,
    maxStorageKB: 100,
    sendTimeoutMs: 3000,
    sandboxTimeoutMs: 20000,
    dedupe: true,
    confirmSensitive: true,    // ask before clipboard_read / geo_get / non-GET fetch_url
    callMustBeLast: true,      // tool JSON must end the assistant answer (ignores quoted examples)
    maxResultChars: 20000,     // truncate TOOL_RESULT payload
    settleMs: 1200,            // last message must be unchanged this long before we act
    // perf knobs
    scanThrottleMs: 400,       // min interval between DOM scans
    fallbackScanMs: 1500,      // periodic scan when observer is quiet
    hideFlashMs: 250,          // max time input stays invisible
  }, window.__DS_SHIM_CONFIG__ || {});

  // ---------- Storage ----------
  const LS = {
    done:      '__ds_shim__done_v3',
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

  // message key ("sessionId:messageId") -> tagline info, so collapsed tool calls survive
  // virtual-list re-mounts and page reloads
  const collapsedByMsg = new Map();
  for (const v of Object.values(DONE)) {
    if (v && v.mk) collapsedByMsg.set(v.mk, { preview: v.preview || '', err: !v.ok });
  }

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
  const clip = (s) => {
    if (s == null) return s;
    // CRITICAL: never String(object) → "[object Object]"; always JSON for objects
    let str;
    if (typeof s === 'string') str = s;
    else {
      try { str = JSON.stringify(s); }
      catch (_) { str = Object.prototype.toString.call(s); }
    }
    return str.length > CONFIG.maxResultChars
      ? str.slice(0, CONFIG.maxResultChars) + `…[truncated ${str.length - CONFIG.maxResultChars} chars]`
      : str;
  };

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
      display: flex; align-items: center; gap: 7px; height: 100%;
    }
    [data-ds-shim-tagline="1"] .ds-shim-ico {
      width: 15px; height: 15px;
      display: inline-flex; align-items: center; justify-content: center;
      opacity: .75; flex-shrink: 0;
    }
    [data-ds-shim-tagline="1"] .ds-shim-ico svg { display: block; width: 100%; height: 100%; }
    [data-ds-shim-tagline="1"][data-ds-shim-running="1"] .ds-shim-ico {
      animation: dsshim-pulse-ico 1.2s ease-in-out infinite;
    }
    @keyframes dsshim-pulse-ico { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
    [data-ds-shim-tagline="1"] .ds-shim-txt { font-weight: 400; font-size: 14px; white-space: nowrap; max-width: 70vw; overflow: hidden; text-overflow: ellipsis; }
    [data-ds-shim-tagline="1"][data-ds-shim-err="1"] .ds-shim-txt { color: #f88; }
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
    [data-ds-shim-tagline="1"] .ds-shim-chip::before { content: '- '; opacity: .5; font-family: system-ui; }
    [data-ds-shim-tagline="1"] .ds-shim-chip.err { color: #f88; background: rgba(240,130,130,0.10); }
    /* Collapse arrow: expand/collapse only (no longer doubles as a busy spinner —
       the terminal icon's pulse handles that instead). */
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
  

    /* —— Chat UI (theme-aware: DeepSeek / Claude) —— */
    :root {
      --dh-card-bg: rgba(255,255,255,0.92);
      --dh-card-fg: #1a1a1a;
      --dh-card-border: rgba(0,0,0,0.12);
      --dh-accent: #4d6bfe;
      --dh-muted: #666;
      --dh-code-bg: #f6f8fa;
      --dh-bar: #4d6bfe;
    }
    html.dark, body.dark, [data-theme="dark"], .dark {
      --dh-card-bg: rgba(30,32,40,0.95);
      --dh-card-fg: #e8eaed;
      --dh-card-border: rgba(255,255,255,0.12);
      --dh-accent: #8ab4ff;
      --dh-muted: #9aa0a6;
      --dh-code-bg: #1e1e24;
      --dh-bar: #8ab4ff;
    }
    /* Claude theme present */
    #claude-ds-theme-v3 ~ * , body:has(#claude-ds-theme-v3) {
      --dh-accent: #da7756;
      --dh-bar: #da7756;
    }
    .dh-ui-card {
      margin: 10px 0; padding: 12px 14px; border-radius: 12px;
      background: var(--dh-card-bg); border: 1px solid var(--dh-card-border);
      color: var(--dh-card-fg); font-family: inherit;
    }
    .dh-ui-card h4 { margin: 0 0 8px; font-size: 13px; color: var(--dh-accent); font-weight: 600; }
    .dh-chart svg { width: 100%; max-width: 420px; height: auto; display: block; }
    .dh-chart .bar { fill: var(--dh-bar); }
    .dh-chart .axis { stroke: var(--dh-muted); stroke-width: 1; opacity: 0.5; }
    .dh-chart text { fill: var(--dh-muted); font-size: 10px; }
    .dh-code-wrap { position: relative; margin: 8px 0; border-radius: 10px; overflow: hidden; border: 1px solid var(--dh-card-border); }
    .dh-code-wrap pre { margin: 0; padding: 12px 14px; overflow-x: auto; background: var(--dh-code-bg); font-size: 12px; }
    .dh-code-copy {
      position: absolute; top: 6px; right: 6px; font-size: 11px; padding: 3px 8px;
      border-radius: 6px; border: 1px solid var(--dh-card-border); background: var(--dh-card-bg);
      color: var(--dh-accent); cursor: pointer;
    }
    .dh-table-wrap { overflow-x: auto; margin: 8px 0; border-radius: 10px; border: 1px solid var(--dh-card-border); }
    .dh-table-wrap table { border-collapse: collapse; width: 100%; font-size: 12px; color: var(--dh-card-fg); }
    .dh-table-wrap th, .dh-table-wrap td { border: 1px solid var(--dh-card-border); padding: 6px 10px; text-align: left; }
    .dh-table-wrap th { background: var(--dh-code-bg); color: var(--dh-accent); }
    .dh-file-tree { font-family: ui-monospace, monospace; font-size: 12px; line-height: 1.45; }
    .dh-file-tree .f { color: var(--dh-accent); } .dh-file-tree .d { color: var(--dh-accent); font-weight: 600; opacity: 0.85; }
    .dh-proj-item { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; border-bottom: 1px solid var(--dh-card-border); font-size: 12px; }
    .dh-proj-item button { font-size: 11px; padding: 2px 8px; }
    .dh-artifact {
      margin: 12px 0; border-radius: 14px; overflow: hidden;
      border: 1px solid var(--dh-card-border); background: var(--dh-card-bg);
      color: var(--dh-card-fg);
    }
    .dh-artifact-bar {
      display: flex; align-items: center; justify-content: space-between; gap: 8px;
      padding: 8px 12px; background: var(--dh-code-bg); border-bottom: 1px solid var(--dh-card-border);
      font-size: 12px; color: var(--dh-muted);
    }
    .dh-artifact-bar b { color: var(--dh-accent); }
    .dh-artifact-bar .acts { display: flex; gap: 6px; flex-wrap: wrap; }
    .dh-artifact-bar button {
      font-size: 11px; padding: 3px 9px; border-radius: 6px; cursor: pointer;
      border: 1px solid var(--dh-card-border); background: var(--dh-card-bg); color: var(--dh-accent);
    }
    .dh-artifact iframe {
      width: 100%; height: 380px; border: 0; display: block; background: #fff;
    }
    .dh-artifact.dh-artifact-tall iframe { height: 520px; }
    .dh-artifact-fs {
      position: fixed; inset: 0; z-index: 2147483000; background: rgba(0,0,0,0.72);
      display: flex; flex-direction: column; padding: 12px;
    }
    .dh-artifact-fs iframe { flex: 1; border-radius: 12px; background: #fff; }
    /* Queue panel above composer (BDS-style) */
    .dh-queue-panel {
      margin: 0 0 8px 0; border-radius: 12px; overflow: hidden;
      border: 1px solid var(--dh-card-border); background: var(--dh-card-bg); color: var(--dh-card-fg);
      font-size: 13px; box-shadow: 0 4px 16px rgba(0,0,0,0.08);
    }
    .dh-queue-panel[hidden] { display: none !important; }
    .dh-queue-panel .dh-q-hdr {
      display: flex; align-items: center; justify-content: space-between; gap: 8px;
      padding: 8px 12px; background: var(--dh-code-bg); border-bottom: 1px solid var(--dh-card-border);
      font-weight: 600; font-size: 12px; color: var(--dh-accent);
    }
    .dh-queue-panel .dh-q-hdr .acts { display: flex; gap: 6px; align-items: center; }
    .dh-queue-panel .dh-q-item {
      display: flex; align-items: flex-start; gap: 8px; padding: 8px 12px;
      border-bottom: 1px solid var(--dh-card-border);
    }
    .dh-queue-panel .dh-q-item:last-child { border-bottom: none; }
    .dh-queue-panel .dh-q-text {
      flex: 1; min-width: 0; white-space: pre-wrap; word-break: break-word;
      font-size: 12px; line-height: 1.4; opacity: 0.95; max-height: 4.5em; overflow: hidden;
    }
    .dh-queue-panel button {
      flex-shrink: 0; font-size: 11px; padding: 3px 8px; border-radius: 6px; cursor: pointer;
      border: 1px solid var(--dh-card-border); background: var(--dh-code-bg); color: var(--dh-accent);
    }
    .dh-queue-panel button.danger { color: #e55; }
    /* Sidebar Projects entry */
    .dh-sidebar-projects {
      display: flex; align-items: center; gap: 8px; margin: 6px 10px; padding: 10px 12px;
      border-radius: 10px; cursor: pointer; font-size: 13px; font-weight: 500;
      color: var(--dh-card-fg, inherit); border: 1px solid transparent;
    }
    .dh-sidebar-projects:hover { background: rgba(127,127,127,0.12); }
    .dh-sidebar-projects svg { width: 18px; height: 18px; flex-shrink: 0; opacity: 0.85; }
    /* Research toggle next to DeepThink / Search */
    .dh-research-toggle {
      display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; margin: 0 4px;
      border-radius: 999px; cursor: pointer; user-select: none; font-size: 13px;
      border: 1px solid var(--dh-card-border, rgba(127,127,127,0.25));
      background: transparent; color: inherit;
    }
    .dh-research-toggle.on {
      background: rgba(77,107,254,0.12); border-color: var(--dh-accent, #4d6bfe);
      color: var(--dh-accent, #4d6bfe); font-weight: 600;
    }
    .dh-research-card {
      margin: 10px 0; padding: 12px 14px; border-radius: 12px;
      border: 1px solid var(--dh-card-border); background: var(--dh-card-bg); color: var(--dh-card-fg);
    }
    .dh-research-card h4 { margin: 0 0 6px; font-size: 13px; color: var(--dh-accent); }
    .dh-research-card .step { font-size: 12px; opacity: 0.85; margin: 2px 0; }

    #dh-projects-drawer {
      position: fixed; z-index: 2147482100; top: 0; left: 0; bottom: 0; width: min(340px, 92vw);
      background: var(--dh-card-bg); color: var(--dh-card-fg); border-right: 1px solid var(--dh-card-border);
      box-shadow: 8px 0 32px rgba(0,0,0,0.25); transform: translateX(-105%); transition: transform .2s ease;
      display: flex; flex-direction: column; font-family: inherit;
    }
    #dh-projects-drawer.open { transform: translateX(0); }
    #dh-projects-drawer .hdr {
      display: flex; align-items: center; justify-content: space-between; padding: 14px 16px;
      border-bottom: 1px solid var(--dh-card-border); font-weight: 600; color: var(--dh-accent);
    }
    #dh-projects-drawer .body { padding: 12px 16px; overflow: auto; flex: 1; }
    #dh-projects-drawer input, #dh-projects-drawer textarea {
      width: 100%; box-sizing: border-box; margin: 6px 0; padding: 8px 10px; border-radius: 8px;
      border: 1px solid var(--dh-card-border); background: var(--dh-code-bg); color: var(--dh-card-fg);
      font-size: 13px; font-family: inherit;
    }
    #dh-projects-drawer button {
      margin: 4px 4px 4px 0; padding: 6px 12px; border-radius: 8px; cursor: pointer;
      border: 1px solid var(--dh-card-border); background: var(--dh-code-bg); color: var(--dh-accent); font-size: 12px;
    }
    #dh-projects-drawer .proj-row {
      display: flex; justify-content: space-between; align-items: center; padding: 8px 0;
      border-bottom: 1px solid var(--dh-card-border); font-size: 13px;
    }
    #dh-projects-backdrop {
      position: fixed; inset: 0; z-index: 2147482050; background: rgba(0,0,0,0.35); display: none;
    }
    #dh-projects-backdrop.show { display: block; }
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
      <div class="ds-shim-row"><span>Conversation</span><code class="ds-shim-conv">${esc(getConvId().slice(0, 12))}…</code></div>
      <hr />
      <div class="ds-shim-row"><span>Deduped calls</span><b class="ds-shim-done-count">0</b></div>
      <div class="ds-shim-row"><span>Memory keys</span><b class="ds-shim-mem-count">0</b></div>
      <div class="ds-shim-row"><span>FS files</span><b class="ds-shim-fs-count">0</b></div>
      <hr />
      <label class="ds-shim-toggle"><input type="checkbox" data-opt="debug" /><span>Debug (show TOOL_RESULT)</span></label>
      <label class="ds-shim-toggle"><input type="checkbox" data-opt="dedupe" checked /><span>Dedupe executed tool calls</span></label>
      <label class="ds-shim-toggle"><input type="checkbox" data-opt="confirmSensitive" checked /><span>Confirm clipboard / geo / POST</span></label>
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
      <hr/>
      <div class="ds-shim-section-title">Projects</div>
      <div id="dh-proj-list" style="max-height:140px;overflow:auto;margin-bottom:8px;"></div>
      <div class="ds-shim-row" style="gap:6px;flex-wrap:wrap;">
        <input id="dh-proj-name" placeholder="Project name" style="flex:1;min-width:100px;background:#0d1520;border:1px solid rgba(100,140,180,0.3);color:#def;border-radius:8px;padding:6px 8px;font-size:12px;"/>
        <button data-act="projCreate">New</button>
        <button data-act="projActive">Set active</button>
      </div>
      <textarea id="dh-proj-instr" placeholder="Project instructions (sent with system prompt)" rows="3" style="width:100%;margin-top:6px;background:#0d1520;border:1px solid rgba(100,140,180,0.3);color:#def;border-radius:8px;padding:8px;font-size:12px;resize:vertical;"></textarea>
      <button data-act="projSaveInstr" class="wide" style="margin-top:6px;">Save instructions</button>
      <hr/>
      <div class="ds-shim-section-title">Persona</div>
      <textarea id="dh-persona" placeholder="Optional persona / style" rows="2" style="width:100%;background:#0d1520;border:1px solid rgba(100,140,180,0.3);color:#def;border-radius:8px;padding:8px;font-size:12px;"></textarea>
      <button data-act="savePersona" class="wide" style="margin-top:6px;">Save persona</button>
      <button data-act="exportChat" class="wide" style="margin-top:6px;">Export chat</button>
      <hr/>
      <label class="ds-shim-toggle"><input type="checkbox" data-opt="uiEnhance" checked /><span>In-chat charts / code / tables</span></label>
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
      panel.querySelector('.ds-shim-conv').textContent = getConvId().slice(0, 12) + '…';
      panel.querySelector('[data-opt="debug"]').checked = !!CONFIG.debug;
      panel.querySelector('[data-opt="dedupe"]').checked = !!CONFIG.dedupe;
      panel.querySelector('[data-opt="confirmSensitive"]').checked = !!CONFIG.confirmSensitive;
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

  // first visible text of an element, without building the whole textContent
  function firstText(el) {
    const w = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = w.nextNode())) {
      const t = n.nodeValue.trim();
      if (t) return t.slice(0, 40);
    }
    return '';
  }

  panel.querySelectorAll('[data-opt]').forEach(input => {
    input.addEventListener('change', () => {
      CONFIG[input.dataset.opt] = input.checked;
      log('option', input.dataset.opt, '=', input.checked);
      if (input.dataset.opt === 'debug') {
        for (const el of document.querySelectorAll('div.ds-message')) {
          if (!firstText(el).startsWith('TOOL_RESULT:')) continue;
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
  panel.querySelector('[data-act="clearDone"]').onclick   = () => { DONE = {}; collapsedByMsg.clear(); saveDone(); refreshCounts(); log('done cleared'); };
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
  const SANDBOX_HTML = `<!doctype html><html><body><script>
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
    window.fs = {
      read:   (path)                => window.__ds_call_tool('fs', { op:'read', path }),
      write:  (path, content, mime) => window.__ds_call_tool('fs', { op:'write', path, content, mime }),
      list:   (prefix)              => window.__ds_call_tool('fs', { op:'list', path: prefix }),
      delete: (path)                => window.__ds_call_tool('fs', { op:'delete', path }),
      exists: (path)                => window.__ds_call_tool('fs', { op:'exists', path }),
    };
    window.list_tools = () => window.__ds_call_tool('list_tools', {});
    window.describe = (name) => window.__ds_call_tool('describe', { name: name });
    window.paste_box = (opts) => window.__ds_call_tool('paste_box', opts || {});
    window.workspace = {
      pwd: () => window.__ds_call_tool('workspace', { op:'pwd' }),
      ls: (path) => window.__ds_call_tool('workspace', { op:'ls', path: path }),
      read: (path, maxBytes) => window.__ds_call_tool('workspace', { op:'read', path: path, maxBytes: maxBytes }),
      write: (path, content) => window.__ds_call_tool('workspace', { op:'write', path: path, content: content }),
      mkdir: (path) => window.__ds_call_tool('workspace', { op:'mkdir', path: path }),
      rm: (path) => window.__ds_call_tool('workspace', { op:'rm', path: path }),
      tree: (path, depth) => window.__ds_call_tool('workspace', { op:'tree', path: path, depth: depth }),
    };
    window.device = {
      info: () => window.__ds_call_tool('device', { op:'info' }),
      battery: () => window.__ds_call_tool('device', { op:'battery' }),
      network: () => window.__ds_call_tool('device', { op:'network' }),
    };
    window.keys = {
      list: () => window.__ds_call_tool('keys', { op:'list' }),
      get: (k) => window.__ds_call_tool('keys', { op:'get', key: k }),
      set: (k, v) => window.__ds_call_tool('keys', { op:'set', key: k, value: v }),
    };
    window.github = {
      me: () => window.__ds_call_tool('github', { op:'me' }),
      repos: (n) => window.__ds_call_tool('github', { op:'repos', limit: n }),
      request: (method, path, body) => window.__ds_call_tool('github', { op:'request', method: method, path: path, body: body }),
    };
    window.http_request = (opts) => window.__ds_call_tool('http_request', opts || {});
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

  let iframe = null;
  let iframeReady = false;
  let readyWaiters = [];

  function mountSandbox() {
    if (iframe) iframe.remove();
    iframeReady = false;
    iframe = document.createElement('iframe');
    iframe.sandbox = 'allow-scripts';
    iframe.style.display = 'none';
    iframe.srcdoc = SANDBOX_HTML;
    iframe.onload = () => { iframeReady = true; readyWaiters.splice(0).forEach(f => f()); };
    document.body.appendChild(iframe);
  }

  function waitSandboxReady(ms) {
    if (iframeReady) return Promise.resolve(true);
    return new Promise((resolve) => {
      function done() { clearTimeout(t); resolve(true); }
      const t = setTimeout(() => { readyWaiters = readyWaiters.filter(f => f !== done); resolve(false); }, ms);
      readyWaiters.push(done);
    });
  }

  const pending = new Map();
  let msgId = 0;

  function resetSandbox(reason) {
    log('sandbox reset:', reason);
    for (const [id, cb] of [...pending]) { pending.delete(id); cb({ ok: false, error: 'sandbox reset (' + reason + ')' }); }
    mountSandbox();
  }
  mountSandbox();

  // ---------- Tool handlers ----------
  const sizeGuard = (s) => {
    const kb = (String(s).length * 2) / 1024;
    if (kb > CONFIG.maxStorageKB) throw new Error(`payload too large: ${kb.toFixed(1)}KB > ${CONFIG.maxStorageKB}KB`);
  };

  async function confirmUser(what) {
    if (!CONFIG.confirmSensitive) return;
    if (!window.confirm('DeepSeek tool wants to ' + what + '.\nAllow?')) throw new Error('denied by user');
  }

  // Blocks same-host and private/loopback/link-local targets (cannot stop redirects to them).
  const PRIVATE_HOST = /^(localhost|.*\.localhost|.*\.local|.*\.internal|0\.0\.0\.0|127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|\[::1?\]|\[f[cd][0-9a-f]{2}:|\[fe80:)/i;
  function assertSafeUrl(url) {
    let u;
    try { u = new URL(url); } catch { throw new Error('invalid url'); }
    if (!/^https?:$/.test(u.protocol)) throw new Error('blocked protocol: ' + u.protocol);
    if (u.hostname === location.hostname) throw new Error('blocked: same-origin fetch');
    if (PRIVATE_HOST.test(u.hostname)) throw new Error('blocked private host: ' + u.hostname);
    return u;
  }

  const toolHandlers = {
    async memory({ op, key, value }) {
      const m = lsGet(LS.memory, {});
      if (op === 'get')    return { value: key in m ? m[key] : null };
      if (op === 'set')    { sizeGuard(value); m[key] = value; if (!lsSet(LS.memory, m)) throw new Error('storage full'); return { ok: true }; }
      if (op === 'delete') { delete m[key]; lsSet(LS.memory, m); return { ok: true }; }
      if (op === 'list')   return { keys: Object.keys(m) };
      if (op === 'clear')  { lsSet(LS.memory, {}); return { ok: true }; }
      throw new Error('unknown memory op: ' + op);
    },
    async fetch_url({ url, method = 'GET', headers = {}, body = null, json = null, timeoutMs = 15000 }) {
      const u = assertSafeUrl(url);
      const verb = String(method).toUpperCase();
      if (verb !== 'GET' && verb !== 'HEAD') await confirmUser('send a ' + verb + ' request to ' + u.hostname);
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
      const nat = N();
      if (nat && nat.clipboard && nat.clipboard.write) {
        return await nat.clipboard.write(String(text ?? ''));
      }
      try { await navigator.clipboard.writeText(text); return { ok: true }; }
      catch {
        const ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.top = '-9999px';
        document.body.appendChild(ta); ta.select();
        const ok = document.execCommand('copy'); ta.remove();
        if (!ok) throw new Error('clipboard write failed');
        return { ok: true };
      }
    },
    async clipboard_read() {
      const nat = N();
      if (nat && nat.clipboard && nat.clipboard.read) {
        return await nat.clipboard.read();
      }
      if (!navigator.clipboard?.readText) throw new Error('clipboard read unsupported');
      await confirmUser('read your clipboard');
      return { text: await navigator.clipboard.readText() };
    },
    async geo_get({ timeoutMs = 10000 } = {}) {
      if (!navigator.geolocation) throw new Error('geolocation unsupported');
      await confirmUser('read your location');
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

  // --- D-Harness native bridge tools (preserves HarnessBridge) ---
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
      // argv array, or shell string via {shell:true,cmd:"..."} / {sh:"..."}
      if (args && args.shell && (args.cmd || args.command || args.sh)) {
        const cmd = args.cmd || args.command || args.sh;
        return await nat.exec(['sh', '-c', String(cmd)], args.timeout_ms || args.timeoutMs, args.cwd);
      }
      if (typeof args === 'string') {
        return await nat.exec(['sh', '-c', args], undefined, undefined);
      }
      const argv = args.argv || args.cmd || args;
      return await nat.exec(argv, args.timeout_ms || args.timeoutMs, args.cwd);
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

    async paste_box(args) {
      const nat = N();
      if (!nat || !nat.paste_box) throw new Error('paste_box requires native bridge');
      return await nat.paste_box(args || {});
    },

    async appInfo() {
      const nat = N();
      if (nat && nat.appInfo) return await nat.appInfo();
      return { shim: VERSION };
    },
    async uuid() {
      if (typeof crypto !== 'undefined' && crypto.randomUUID) return { uuid: crypto.randomUUID() };
      return { uuid: 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
        const r = Math.random() * 16 | 0;
        return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
      }) };
    },
    async time_now() {
      return { epochMs: Date.now(), iso: new Date().toISOString() };
    },
    async sensors(args) {
      const nat = N();
      if (!nat || !nat.sensors) throw new Error('sensors requires native');
      const op = (args && (args.op || args.action)) || 'list';
      if (op === 'list') return await nat.sensors.list();
      if (op === 'read') return await nat.sensors.read(args.type || args.name || 'accelerometer');
      throw new Error('unknown sensors op');
    },
    async torch(args) {
      const nat = N();
      if (!nat || !nat.torch) throw new Error('torch requires native');
      return await nat.torch.set(!!(args && (args.on ?? args.enabled ?? args.value)));
    },
    async audio(args) {
      const nat = N();
      if (!nat || !nat.audio) throw new Error('audio requires native');
      const op = args && (args.op || args.action);
      if (op === 'ringer') return await nat.audio.ringer(args.mode);
      return await nat.audio.volume(args.stream, args.level);
    },
    async wakelock(args) {
      const nat = N();
      if (!nat || !nat.wakelock) throw new Error('wakelock requires native');
      const op = (args && (args.op || args.action)) || 'acquire';
      if (op === 'release') return await nat.wakelock.release();
      return await nat.wakelock.acquire(args.ms || 60000);
    },
    async diff(args) {
      const nat = N();
      if (nat && nat.diff_lines) return await nat.diff_lines(args.a || '', args.b || '');
      // JS fallback
      const la = String(args.a || '').split('\n');
      const lb = String(args.b || '').split('\n');
      const changes = [];
      for (let i = 0; i < Math.max(la.length, lb.length); i++) {
        if (la[i] !== lb[i]) changes.push({ line: i + 1, a: la[i] ?? null, b: lb[i] ?? null });
        if (changes.length >= 500) break;
      }
      return { ok: true, data: { linesA: la.length, linesB: lb.length, changed: changes.length, changes } };
    },
    async toybox(args) {
      const nat = N();
      if (!nat || !nat.toybox) throw new Error('toybox requires native');
      const op = (args && (args.op || args.action)) || 'list';
      if (op === 'run') return await nat.toybox.run(args.applet, args.args || []);
      return await nat.toybox.list();
    },
        async research(args) {
      const nat = N();
      if (!nat || !nat.research) throw new Error('research requires native');
      const op = (args && (args.op || args.action)) || 'web';
      if (op === 'web') return await nat.research.web(args.query || args.q, args.maxSources || args.limit || 5);
      if (op === 'preview') return await nat.research.preview(args.url);
      if (op === 'html_text' || op === 'html') return await nat.research.html_text(args.url, args.maxChars);
      if (op === 'plan') return await nat.research.plan(args.topic || args.query);
      throw new Error('unknown research op: ' + op);
    },
    async workspace_grep(args) {
      const nat = N();
      if (nat && nat.workspace_grep) return await nat.workspace_grep(args.query, args.regex, args.maxHits);
      throw new Error('workspace_grep requires native');
    },
    async exec_lang(args) {
      const nat = N();
      if (!nat || !nat.exec_lang) throw new Error('exec_lang requires native');
      if (args && args.op === 'list') return await nat.exec_langs();
      if (args && args.op === 'which') return await nat.exec_which(args.bin);
      return await nat.exec_lang(args.lang, args.code, args.timeout_ms || args.timeoutMs);
    },
async selftest() {
      const nat = N();
      const checks = {};
      const tryCall = async (name, fn) => {
        try { const r = await fn(); checks[name] = { ok: true, sample: typeof r === 'object' ? Object.keys(r || {}).slice(0, 6) : typeof r }; }
        catch (e) { checks[name] = { ok: false, error: String(e && e.message || e) }; }
      };
      await tryCall('list_tools', () => (nat && nat.list_tools ? nat.list_tools() : Promise.reject('no native')));
      await tryCall('workspace.pwd', () => (nat && nat.workspace ? nat.workspace.pwd() : Promise.reject('no workspace')));
      await tryCall('clipboard', () => (nat && nat.clipboard ? nat.clipboard.write('dharness-selftest') : Promise.reject('no clipboard')));
      await tryCall('device.info', () => (nat && nat.device ? nat.device.info() : Promise.reject('no device')));
      return { ok: true, shim: VERSION, native: !!(nat && nat.available), checks };
    },
  });


  window.addEventListener('message', async (e) => {
    if (!iframe || e.source !== iframe.contentWindow) return;
    const d = e.data; if (!d) return;
    if (d.__dsShimToolCall === true) {
      const source = e.source;
      let payload;
      try {
        const h = toolHandlers[d.name];
        if (!h) throw new Error('unknown tool: ' + d.name);
        const result = await h(d.args || {});
        payload = { __dsShimToolResult: true, id: d.id, ok: true, result };
      } catch (err) {
        payload = { __dsShimToolResult: true, id: d.id, ok: false, error: String(err && err.message || err) };
      }
      // sandbox may have been rebuilt while the handler ran
      if (iframe && iframe.contentWindow === source) source.postMessage(payload, '*');
      return;
    }
    if (d.__dsShim === true) {
      const cb = pending.get(d.id);
      if (cb) { pending.delete(d.id); cb({ ok: d.ok, result: d.result, error: d.error, stack: d.stack }); }
    }
  });

  async function runInSandbox(code, timeoutMs = CONFIG.sandboxTimeoutMs) {
    if (!(await waitSandboxReady(3000))) return { ok: false, error: 'sandbox not ready' };
    return new Promise((resolve) => {
      const id = ++msgId;
      // timeoutMs === 0 → unlimited (paste_box waits on user Done/Cancel)
      let timer = null;
      if (timeoutMs !== 0 && timeoutMs !== false) {
        const ms = (timeoutMs == null || timeoutMs === undefined) ? CONFIG.sandboxTimeoutMs : timeoutMs;
        if (ms > 0) {
          timer = setTimeout(() => {
            pending.delete(id);
            resolve({ ok: false, error: 'timeout' });
            resetSandbox('timeout');
          }, ms);
        }
      }
      pending.set(id, (res) => { if (timer) clearTimeout(timer); resolve(res); });
      iframe.contentWindow.postMessage({ type: 'run', id, code }, '*');
    });
  }

  // ============================================================
  // INPUT / SEND
  //
  // Nothing on screen should change while a TOOL_RESULT is sent: no textarea flash,
  // no send-button icon flicker, no composer resize/reflow.
  //
  // v7.2's opacity fade on the textarea alone left two things visible: the send button
  // (outside the faded element) flipping enabled/disabled, and — because the fade used a
  // CSS transition plus two nested requestAnimationFrame steps to restore it — several
  // extra paints stretched over multiple frames, which is what showed up as "lag".
  //
  // v7.3 instead:
  //  - hides the WHOLE composer (textarea + buttons) with visibility:hidden, a single
  //    style write with no transition, so nothing animates and nothing partial paints
  //  - freezes the composer's height for the duration, so the autosize logic reacting to
  //    a large JSON payload can't resize the box and reflow the page underneath it
  //  - restores the draft and un-hides in one synchronous step, not spread across frames
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
  const SEND_ICON_PREFIX = 'M8.3125';       // arrow (idle / ready to send)
  const STOP_ICON_PREFIX = 'M2 4.88';       // rounded square (generating)
  const SPINNER_ICON_PREFIX = 'M34,18';     // ring (request sent, waiting for first token)
  const btnIcon = (b) => b.querySelector('svg path')?.getAttribute('d') || '';

  function findEnabledSendButton() {
    for (const b of document.querySelectorAll(SEND_SELECTOR)) {
      if (b.classList.contains('ds-button--disabled')) continue;
      if (b.offsetParent === null) continue;
      if (!btnIcon(b).startsWith(SEND_ICON_PREFIX)) continue;
      return b;
    }
    return null;
  }

  function isGenerating() {
    for (const b of document.querySelectorAll(SEND_SELECTOR)) {
      const d = btnIcon(b);
      if (d.startsWith(STOP_ICON_PREFIX) || d.startsWith(SPINNER_ICON_PREFIX)) return true;
    }
    return false;
  }

  // Smallest ancestor of the textarea that also contains the send button, i.e. the whole
  // composer bar. Hiding this (not just the textarea) also hides the send button's
  // enabled/disabled flicker and the toggle chips.
  function getComposerRoot(input) {
    let node = input;
    for (let i = 0; i < 8 && node && node !== document.body; i++, node = node.parentElement) {
      if (node.querySelector(SEND_SELECTOR)) return node;
    }
    return input.parentElement || input;
  }

  let sendingLock = false;

  async function sendMessage(text) {
    if (sendingLock) { log('send already in progress'); return false; }
    sendingLock = true;

    const input = getInput();
    if (!input) { sendingLock = false; log('no input'); setStatus('error'); return false; }

    const root = getComposerRoot(input);
    const draft = input.value;   // user's unsent text, restored afterwards

    // One style write, no transition: visibility:hidden removes the element from paint
    // entirely (unlike opacity, nothing partially shows through) while keeping its layout
    // box, so surrounding content doesn't jump. Height is pinned so the textarea's own
    // autosize logic can't grow/shrink the composer while the payload sits in it.
    const prevVisibility = root.style.visibility;
    const prevPointerEvents = root.style.pointerEvents;
    const rect = root.getBoundingClientRect();
    const prevMinHeight = root.style.minHeight;
    const prevMaxHeight = root.style.maxHeight;
    root.style.minHeight = rect.height + 'px';
    root.style.maxHeight = rect.height + 'px';
    root.style.visibility = 'hidden';
    root.style.pointerEvents = 'none';

    try {
      input.focus();
      setNativeValue(input, text);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      await sleep(60);   // let React process onChange (enables the send button)

      input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));
      input.dispatchEvent(new KeyboardEvent('keyup',   { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true }));

      let cleared = false;
      let t0 = Date.now();
      while (Date.now() - t0 < 500) {
        if (input.value.length === 0) { cleared = true; break; }
        await sleep(30);
      }

      if (!cleared) {
        let btn = null;
        t0 = Date.now();
        while (Date.now() - t0 < CONFIG.sendTimeoutMs) {
          btn = findEnabledSendButton();
          if (btn) break;
          await sleep(50);
        }
        if (btn) { btn.click(); cleared = true; await sleep(120); }
      }

      if (!cleared) { log('send failed'); setStatus('error'); return false; }
      log('sent');
      return true;
    } finally {
      // Put the draft back and reveal everything in one go — no intermediate state to see.
      if (draft) {
        setNativeValue(input, draft);
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
      root.style.visibility = prevVisibility;
      root.style.pointerEvents = prevPointerEvents;
      root.style.minHeight = prevMinHeight;
      root.style.maxHeight = prevMaxHeight;
      sendingLock = false;
    }
  }

  // ============================================================
  // PARSING
  // ============================================================
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
              try {
                const obj = JSON.parse(cand);
                if (obj && typeof obj.tool === 'string' && obj.tool) {
                  if (obj.tool === 'run_js' && !(obj.args && typeof obj.args.code === 'string')) { idx = i + 1; break; }
                  return { obj, full: cand, end: i + 1 };
                }
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

  // ============================================================
  // MESSAGE IDENTITY (stable across virtual-list recycling)
  // ============================================================
  // DeepSeek renders each message under a React component with props {sessionId, messageId}.
  // Optimistic messages have NEGATIVE ids until the server confirms; only positive ids are real.
  function msgInfo(el) {
    try {
      const fk = Object.keys(el).find(k => k.startsWith('__reactFiber$'));
      let f = fk ? el[fk] : null;
      for (let i = 0; f && i < 10; i++, f = f.return) {
        const p = f.memoizedProps;
        if (p && typeof p === 'object' && p.messageId != null) {
          return { id: String(p.messageId), sessionId: p.sessionId ? String(p.sessionId) : null };
        }
      }
    } catch {}
    const key = el.closest('[data-virtual-list-item-key]')?.getAttribute('data-virtual-list-item-key');
    return key != null ? { id: key, sessionId: null } : null;
  }
  const isRealId = (id) => /^\d+$/.test(id);
  const mkOf = (info) => (info.sessionId || getConvId()) + ':' + info.id;
  function maxRealId(msgs) {
    let m = -1;
    for (const el of msgs) { const i = msgInfo(el); if (i && isRealId(i.id)) m = Math.max(m, +i.id); }
    return m;
  }

  // ============================================================
  // DOM HELPERS
  // ============================================================
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
  // No emoji/text glyphs. A custom terminal icon identifies "tool call" (it pulses while
  // running), and a separate small arrow handles expand/collapse.
  // ============================================================
  const TERMINAL_SVG = '<svg viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="1" y="2" width="14" height="12" rx="2" stroke="currentColor" stroke-width="1.3"/><path d="M4 6.3L6.5 8.8L4 11.3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/><path d="M8 11.3H11.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>';
  const CHEV_SVG = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 6l4 4 4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  function createTagline(preview = '', isError = false, running = false) {
    const el = document.createElement('div');
    el.setAttribute('data-ds-shim-tagline', '1');
    if (running) el.setAttribute('data-ds-shim-running', '1');
    if (isError) el.setAttribute('data-ds-shim-err', '1');

    const inner = document.createElement('div');
    inner.className = 'ds-shim-inner';

    const ico = document.createElement('span');
    ico.className = 'ds-shim-ico';
    ico.innerHTML = TERMINAL_SVG;
    inner.appendChild(ico);

    const txt = document.createElement('span');
    txt.className = 'ds-shim-txt';
    // Tagline text = human description only (never raw TOOL_RESULT JSON)
    txt.textContent = running ? 'Running tool…' : 'Tool used';
    el._dsRunLabel = 'Running tool…';
    el._dsDoneLabel = 'Tool used';
    inner.appendChild(txt);

    const chev = document.createElement('span');
    chev.className = 'ds-shim-chev';
    chev.innerHTML = CHEV_SVG;
    inner.appendChild(chev);

    el.appendChild(inner);
    return el;
  }

  function updateTagline(tagline, preview, isError, running) {
    tagline.toggleAttribute('data-ds-shim-running', !!running);
    tagline.toggleAttribute('data-ds-shim-err', !!isError);
    // Only description labels — never show result JSON chips
    tagline.querySelector('.ds-shim-txt').textContent = running
      ? (tagline._dsRunLabel || 'Running tool…')
      : (tagline._dsDoneLabel || 'Tool used');
    // Remove legacy result chips if present
    tagline.querySelectorAll('.ds-shim-chip').forEach(function (c) { c.remove(); });
  }

  // ============================================================
  // COLLAPSE
  // ============================================================
  function applyHiding(wrapper, tagline) {
    for (const child of wrapper.children) {
      if (child === tagline) continue;
      if (child.getAttribute('data-ds-shim-hidden') !== '1') {
        child.setAttribute('data-ds-shim-hidden', '1');
      }
    }
  }

  function collapseToolMessage(dsMessage, preview, isError, running, runLabel, doneLabel) {
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
    }
    if (runLabel) tagline._dsRunLabel = runLabel;
    if (doneLabel) tagline._dsDoneLabel = doneLabel;
    updateTagline(tagline, preview, isError, running);

    applyHiding(wrapper, tagline);
    return tagline;
  }

  // ============================================================
  // PROCESS
  // ============================================================
  let busy = false;
  const handled = new Set();   // message keys already examined this page load
  const retried = new Set();
  const baseline = new Map();  // sessionId -> highest real message id on screen when first seen

  async function processToolCall(dsMessage, tool, mk, sig) {
    const tname = tool.obj.tool;
    const desc = String(tool.obj.description || tool.obj.discription || '').trim();
    const runLabel = desc ? desc : ('Running ' + tname + '…');
    const doneLabel = desc ? desc : 'Tool used';
    log('tool call:', tname, desc || '(no description)', '| msg:', mk);
    collapseToolMessage(dsMessage, desc || tname, false, true, runLabel, doneLabel);
    setStatus('running');
    try {
      const n = typeof N === 'function' ? N() : null;
      if (n && typeof n.agentBegin === 'function') n.agentBegin(tname, desc || tname);
    } catch (e) { log('agentBegin', e); }

    let res;
    if (tname === 'paste_box') {
      try {
        const result = await toolHandlers.paste_box(tool.obj.args || {});
        res = { ok: true, result };
      } catch (err) {
        res = { ok: false, error: String(err && err.message || err) };
      }
    } else if (tname === 'run_js') {
      const code = tool.obj.args && tool.obj.args.code;
      const longWait = typeof code === 'string' && code.indexOf('paste_box') !== -1;
      res = await runInSandbox(code, longWait ? 0 : undefined);
    } else {
      const h = toolHandlers[tname];
      if (!h) {
        res = { ok: false, error: 'unknown tool: ' + tname + ' — try run_js with list_tools()' };
      } else {
        try {
          const result = await h(tool.obj.args || {});
          res = { ok: true, result };
        } catch (err) {
          res = { ok: false, error: String(err && err.message || err) };
        }
      }
    }
    log('result:', res);

    let resultPayload = res.result;
    if (typeof resultPayload === 'string') {
      const s = resultPayload.trim();
      if ((s.startsWith('{') && s.endsWith('}')) || (s.startsWith('[') && s.endsWith(']'))) {
        try { resultPayload = JSON.parse(s); } catch (_) {}
      }
    }
    const preview = doneLabel || (res.ok ? tname : 'error');
    const t1 = Date.now();
    let payloadObj = {
      ok: res.ok,
      data: resultPayload,
      result: resultPayload, // back-compat
      meta: { ms: 0, tool: tname }
    };
    if (res.error != null) payloadObj.error = { message: String(res.error) };
    let payload = 'TOOL_RESULT: ' + JSON.stringify(payloadObj);
    if (payload.length > CONFIG.maxResultChars + 20) {
      payload = 'TOOL_RESULT: ' + JSON.stringify({
        ok: res.ok,
        result: clip(resultPayload),
        error: res.error != null ? clip(res.error) : undefined,
        truncated: true
      });
    }
    DONE[sig] = { ok: res.ok, preview, mk, sent: false, payload, t: Date.now(), desc: doneLabel };
    collapsedByMsg.set(mk, { preview, err: !res.ok, desc: doneLabel });
    saveDone(); refreshCounts();

    collapseToolMessage(dsMessage, preview, !res.ok, false, runLabel, doneLabel);
    setStatus(res.ok ? 'idle' : 'error');
    try {
      const n = typeof N === 'function' ? N() : null;
      if (n && typeof n.agentEnd === 'function') n.agentEnd();
    } catch (e) { log('agentEnd', e); }

    const sent = await sendMessage(payload);
    if (sent && DONE[sig]) { DONE[sig].sent = true; delete DONE[sig].payload; saveDone(); }
  }

  // ============================================================
  // OPTIMIZED SCAN LOOP
  // ============================================================
  function hideUserToolResults(msgs) {
    for (const el of msgs) {
      const wrapper = el.parentElement;
      if (!wrapper) continue;
      const isHidden = wrapper.getAttribute('data-ds-shim-hidden') === '1';
      if (isHidden && !CONFIG.debug) continue;
      if (!firstText(el).startsWith('TOOL_RESULT:')) continue;
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

  // Re-collapse tool-call messages after the virtual list re-mounts them or the page reloads.
  function restoreCollapsed(msgs) {
    if (!collapsedByMsg.size) return;
    for (const el of msgs) {
      const wrapper = el.parentElement;
      if (!wrapper || wrapper.firstElementChild?.getAttribute('data-ds-shim-tagline') === '1') continue;
      const info = msgInfo(el);
      if (!info || !isRealId(info.id)) continue;
      const c = collapsedByMsg.get(mkOf(info));
      if (c) collapseToolMessage(el, c.preview, c.err, false);
    }
  }

  // The last message must stop changing for CONFIG.settleMs before we act on it.
  let settle = { el: null, len: -1, at: 0 };
  function isSettled(el) {
    const len = (el.textContent || '').length;
    const now = performance.now();
    if (settle.el !== el || settle.len !== len) { settle = { el, len, at: now }; return false; }
    return now - settle.at >= CONFIG.settleMs;
  }

  function scanForToolCalls(msgs) {
    if (busy || !msgs.length) return;
    const el = msgs[msgs.length - 1];              // tool calls only ever matter on the newest message
    const info = msgInfo(el);
    if (!info || !isRealId(info.id)) return;       // optimistic / unknown: wait for the real id

    const sid = info.sessionId || getConvId();
    if (!baseline.has(sid)) baseline.set(sid, maxRealId(msgs));
    const mk = sid + ':' + info.id;
    if (handled.has(mk)) return;

    if (isGenerating() || !isSettled(el)) return;

    // Read the ANSWER only (the thinking block is a separate .ds-markdown without this class).
    const main = el.querySelector('div.ds-markdown.ds-assistant-message-main-content');
    if (!main) return;
    const text = (main.textContent || '').trim();
    const tool = text ? extractToolCall(text) : null;
    if (!tool) { handled.add(mk); return; }
    if (CONFIG.callMustBeLast && text.slice(text.lastIndexOf(tool.full) + tool.full.length).trim()) {
      handled.add(mk); log('ignored: text after tool call', mk); return;
    }

    const sig = mk + ':' + hashStr(tool.full);
    handled.add(mk);
    const prev = DONE[sig];

    if (CONFIG.dedupe && prev) {
      log('deduped:', sig);
      collapseToolMessage(el, prev.preview || '', !prev.ok, false);
      setStatus(prev.ok ? 'idle' : 'warn');
      if (prev.sent === false && prev.payload && !retried.has(sig)) {
        retried.add(sig);
        busy = true;
        log('resending unsent result', sig);
        sendMessage(prev.payload)
          .then(ok => { if (ok && DONE[sig]) { DONE[sig].sent = true; delete DONE[sig].payload; saveDone(); } })
          .finally(() => { busy = false; });
      }
      return;
    }

    if (+info.id <= baseline.get(sid)) { log('skipped (was already on screen at load):', mk); return; }

    busy = true;
    processToolCall(el, tool, mk, sig)
      .catch(e => { log('error:', e); setStatus('error'); })
      .finally(() => { busy = false; });
  }

  // ---------- Throttled scheduler ----------
  let lastTickAt = 0;
  let tickScheduled = false;

  function runTick() {
    tickScheduled = false;
    lastTickAt = performance.now();
    // Continue while backgrounded so multi-step tool chains keep running
    try {
      const msgs = document.querySelectorAll('div.ds-message');
      hideUserToolResults(msgs);
      restoreCollapsed(msgs);
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

  // Observer: only structural changes (no characterData -> quieter)
  const observer = new MutationObserver(() => scheduleTick(false));
  observer.observe(document.body, { childList: true, subtree: true });

  // Fallback periodic scan (also drives the "settled" timer when the DOM is quiet)
  const fallbackTimer = setInterval(() => scheduleTick(false), CONFIG.fallbackScanMs);

  document.addEventListener('visibilitychange', () => {
    // Keep agent loop alive in background
    scheduleTick(true);
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
      if (iframe) iframe.remove();
      delete window.__DS_TOOL_SHIM__;
      console.log('%c[shim] stopped', 'color:#0af');
    },
    tick: () => scheduleTick(true),
    send: sendMessage,
    run: runInSandbox,
    resetSandbox: () => resetSandbox('manual'),
    showPanel: () => togglePanel(true),
    hidePanel: () => togglePanel(false),
    resetFab: () => { lsSet(LS.fabPos, null); applyPos(null); },
    showFab: () => { lsSet(LS.fabHidden, false); fab.removeAttribute('data-hidden'); },
    stats() {
      const s = {
        version: VERSION,
        convId: getConvId(),
        done: Object.keys(DONE).length,
        unsent: Object.values(DONE).filter(v => v.sent === false).length,
        memoryKeys: Object.keys(lsGet(LS.memory, {})).length,
        fsFiles: Object.keys(lsGet(LS.fs, {})).length,
        generating: isGenerating(),
        baseline: Object.fromEntries(baseline),
        logs: LOGS.length,
      };
      console.log('[shim] stats:', s);
      return s;
    },
    logs: () => LOGS.slice(),
    projects: () => window.__DH_PROJECTS__,
    enhanceUI: () => enhanceAllMessages(),
    maybeInjectSystemPrompt: (f) => maybeInjectSystemPrompt(!!f),
    artifacts: true,
    inspect() {
      const out = [];
      document.querySelectorAll('div.ds-message').forEach((el, i) => {
        const wrapper = el.parentElement;
        const info = msgInfo(el);
        out.push({
          i,
          id: info ? info.id : null,
          textPreview: firstText(el).slice(0, 40),
          wrapperChildren: wrapper ? wrapper.children.length : 0,
          hasTagline: wrapper?.firstElementChild?.getAttribute('data-ds-shim-tagline') === '1',
        });
      });
      console.table(out);
      return out;
    },
  };



  // ============================================================
  // UI enhancer v8: theme-aware artifacts/charts, projects, queue,
  // bookmarks, export, token estimate, persona — BDS-inspired
  // ============================================================
  const PROJ_KEY = '__dh_projects_v2';
  const PROJ_ACTIVE = '__dh_project_active_v2';
  const PROJ_FILES = '__dh_project_files_v2';
  const PERSONA_KEY = '__dh_persona_v1';

  function isDarkTheme() {
    try {
      if (document.getElementById('claude-ds-theme-v3')) {
        return document.documentElement.classList.contains('dark') ||
          document.body.classList.contains('dark') ||
          matchMedia('(prefers-color-scheme: dark)').matches;
      }
      const bg = getComputedStyle(document.body).backgroundColor || '';
      const m = bg.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
      if (m) {
        const lum = (0.299 * +m[1] + 0.587 * +m[2] + 0.114 * +m[3]) / 255;
        return lum < 0.5;
      }
      return document.documentElement.classList.contains('dark') ||
        document.body.classList.contains('dark') ||
        matchMedia('(prefers-color-scheme: dark)').matches;
    } catch { return true; }
  }

  function loadProjects() {
    try { return JSON.parse(localStorage.getItem(PROJ_KEY) || '[]'); } catch { return []; }
  }
  function saveProjects(list) {
    try { localStorage.setItem(PROJ_KEY, JSON.stringify(list)); } catch {}
  }
  function getActiveProjectId() {
    try { return localStorage.getItem(PROJ_ACTIVE) || ''; } catch { return ''; }
  }
  function setActiveProjectId(id) {
    try { localStorage.setItem(PROJ_ACTIVE, id || ''); } catch {}
  }
  function loadProjectFiles() {
    try { return JSON.parse(localStorage.getItem(PROJ_FILES) || '{}'); } catch { return {}; }
  }
  function saveProjectFiles(map) {
    try { localStorage.setItem(PROJ_FILES, JSON.stringify(map)); } catch {}
  }

  function renderProjectList() {
    const box = document.getElementById('dh-proj-list');
    if (!box) return;
    const list = loadProjects();
    const active = getActiveProjectId();
    const files = loadProjectFiles();
    if (!list.length) {
      box.innerHTML = '<div style="opacity:.6;font-size:12px;">No projects — create one below</div>';
      return;
    }
    box.innerHTML = list.map(p => {
      const on = p.id === active;
      const fc = (files[p.id] || []).length;
      return `<div class="dh-proj-item"><span>${on ? '● ' : ''}<b>${esc(p.name)}</b> <span style="opacity:.6">(${fc} files)</span></span>
        <span>
          <button type="button" data-proj-act="select" data-id="${p.id}">${on ? 'Active' : 'Select'}</button>
          <button type="button" data-proj-act="del" data-id="${p.id}">Del</button>
        </span></div>`;
    }).join('');
    box.querySelectorAll('[data-proj-act]').forEach(btn => {
      btn.onclick = () => {
        const id = btn.getAttribute('data-id');
        if (btn.getAttribute('data-proj-act') === 'del') {
          saveProjects(loadProjects().filter(x => x.id !== id));
          const fm = loadProjectFiles();
          delete fm[id];
          saveProjectFiles(fm);
          if (getActiveProjectId() === id) setActiveProjectId('');
        } else {
          setActiveProjectId(id);
          const pr = loadProjects().find(x => x.id === id);
          const ta = document.getElementById('dh-proj-instr');
          if (ta && pr) ta.value = pr.instructions || '';
          const name = document.getElementById('dh-proj-name');
          if (name && pr) name.value = pr.name || '';
        }
        renderProjectList();
            };
    });
  }

  function createProject(name) {
    const n = (name || '').trim() || ('Project ' + (loadProjects().length + 1));
    const list = loadProjects();
    const id = 'p_' + Date.now().toString(36);
    list.push({ id, name: n, instructions: '', description: '', updated: Date.now() });
    saveProjects(list);
    setActiveProjectId(id);
    renderProjectList();
    showToast('Project created');
  }

  panel.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]');
    if (!btn) return;
    const act = btn.getAttribute('data-act');
    if (act === 'projCreate') createProject(document.getElementById('dh-proj-name')?.value);
    else if (act === 'projActive') {
      const name = document.getElementById('dh-proj-name')?.value?.trim();
      const list = loadProjects();
      let pr = list.find(x => x.name === name) || list.find(x => x.id === getActiveProjectId());
      if (pr) { setActiveProjectId(pr.id); renderProjectList(); showToast('Active: ' + pr.name); }
    } else if (act === 'projSaveInstr') {
      const id = getActiveProjectId();
      const list = loadProjects();
      const pr = list.find(x => x.id === id);
      if (!pr) { showToast('Select a project first'); return; }
      pr.instructions = document.getElementById('dh-proj-instr')?.value || '';
      pr.updated = Date.now();
      saveProjects(list);
      showToast('Instructions saved');
    } else if (act === 'exportChat') {
      exportChatMarkdown();
    } else if (act === 'savePersona') {
      try {
        localStorage.setItem(PERSONA_KEY, document.getElementById('dh-persona')?.value || '');
        showToast('Persona saved');
      } catch {}
    }
  });

  panel.querySelector('[data-opt="uiEnhance"]')?.addEventListener('change', (e) => {
    CONFIG.uiEnhance = e.target.checked;
    try { localStorage.setItem('__dh_ui_enhance', CONFIG.uiEnhance ? '1' : '0'); } catch {}
  });
  try {
    CONFIG.uiEnhance = localStorage.getItem('__dh_ui_enhance') !== '0';
    const cb = panel.querySelector('[data-opt="uiEnhance"]');
    if (cb) cb.checked = !!CONFIG.uiEnhance;
  } catch { CONFIG.uiEnhance = true; }

  // Persona field load
  try {
    const pe = document.getElementById('dh-persona');
    if (pe) pe.value = localStorage.getItem(PERSONA_KEY) || '';
  } catch {}

  function svgBarChart(labels, values) {
    const w = 360, h = 160, pad = 28;
    const max = Math.max(...values.map(Number).filter(n => !isNaN(n)), 1);
    const bw = (w - pad * 2) / Math.max(values.length, 1);
    let bars = '';
    values.forEach((v, i) => {
      const n = Number(v) || 0;
      const bh = ((h - pad * 2) * n) / max;
      const x = pad + i * bw + 4;
      const y = h - pad - bh;
      bars += `<rect class="bar" x="${x}" y="${y}" width="${Math.max(4, bw - 8)}" height="${Math.max(0, bh)}" rx="3"/>`;
      bars += `<text x="${x + bw / 2}" y="${h - 8}" text-anchor="middle">${esc(String(labels[i] ?? i).slice(0, 8))}</text>`;
    });
    return `<div class="dh-ui-card dh-chart" data-dh-chart="1"><h4>Chart</h4><svg viewBox="0 0 ${w} ${h}" role="img">
      <line class="axis" x1="${pad}" y1="${h - pad}" x2="${w - 8}" y2="${h - pad}"/>
      <line class="axis" x1="${pad}" y1="${pad}" x2="${pad}" y2="${h - pad}"/>
      ${bars}</svg></div>`;
  }

  function parseChartBlock(body) {
    try {
      const j = JSON.parse(body);
      if (j.labels && j.values) return svgBarChart(j.labels, j.values.map(Number));
      if (Array.isArray(j.data)) return svgBarChart(j.data.map(d => d.label ?? d.x), j.data.map(d => Number(d.value ?? d.y)));
    } catch {}
    const labels = [], values = [];
    body.split(/\n+/).forEach(line => {
      const m = line.trim().match(/^([^,]+),\s*([0-9.]+)\s*$/);
      if (m) { labels.push(m[1].trim()); values.push(Number(m[2])); }
    });
    return values.length ? svgBarChart(labels, values) : null;
  }

  function wrapArtifactHtml(html) {
    let body = (html || '').trim();
    // Unescape common markdown artifacts
    body = body.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
    const dark = isDarkTheme();
    const pageBg = dark ? '#1a1b1e' : '#ffffff';
    const pageFg = dark ? '#e8eaed' : '#1a1a1a';
    const bridge = `<script>
window.dh={toast:function(t){parent.postMessage({type:'dh-artifact',action:'toast',text:String(t)},'*');},
log:function(t){parent.postMessage({type:'dh-artifact',action:'log',text:String(t)},'*');}};
</script>`;
    if (/^<!DOCTYPE/i.test(body) || /<html[\s>]/i.test(body)) {
      // inject bridge before </body> or at end
      if (/<\/body>/i.test(body)) return body.replace(/<\/body>/i, bridge + '</body>');
      return body + bridge;
    }
    return `<!DOCTYPE html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<style>html,body{margin:0;padding:12px;font-family:system-ui,-apple-system,sans-serif;background:${pageBg};color:${pageFg};}a{color:#4d6bfe;}</style></head>
<body>${body}${bridge}</body></html>`;
  }

  function loadIframeContent(ifr, html) {
    const srcdoc = wrapArtifactHtml(html);
    // Prefer srcdoc; fallback blob URL for stubborn WebViews
    try {
      ifr.removeAttribute('src');
      ifr.srcdoc = srcdoc;
    } catch (e) {
      try {
        const blob = new Blob([srcdoc], { type: 'text/html' });
        const url = URL.createObjectURL(blob);
        ifr.src = url;
      } catch (e2) {
        console.warn('iframe load', e2);
      }
    }
    // Force paint
    ifr.style.background = isDarkTheme() ? '#1a1b1e' : '#fff';
  }

  function openArtifactFullscreen(html, title) {
    const overlay = document.createElement('div');
    overlay.className = 'dh-artifact-fs';
    overlay.innerHTML = `<div class="dh-artifact-bar"><b>${esc(title || 'Artifact')}</b>
      <button type="button" data-close>Close</button></div>`;
    const ifr = document.createElement('iframe');
    ifr.setAttribute('sandbox', 'allow-scripts allow-forms allow-modals allow-popups allow-same-origin');
    loadIframeContent(ifr, html);
    overlay.appendChild(ifr);
    overlay.querySelector('[data-close]').onclick = () => overlay.remove();
    document.body.appendChild(overlay);
  }

  function renderArtifactCard(pre, html, kind) {
    if (pre.getAttribute('data-dh-done') === 'artifact') return;
    const title = kind === 'simulation' ? 'Simulation' : (kind === 'interactive' ? 'Interactive' : 'HTML artifact');
    const card = document.createElement('div');
    card.className = 'dh-artifact' + (kind === 'simulation' || kind === 'interactive' ? ' dh-artifact-tall' : '');
    card.innerHTML = `<div class="dh-artifact-bar"><span><b>${title}</b></span>
      <span class="acts">
        <button type="button" data-act="reload">Reload</button>
        <button type="button" data-act="fs">Fullscreen</button>
        <button type="button" data-act="copy">Copy</button>
      </span></div>`;
    const ifr = document.createElement('iframe');
    ifr.setAttribute('sandbox', 'allow-scripts allow-forms allow-modals allow-popups allow-same-origin');
    ifr.setAttribute('referrerpolicy', 'no-referrer');
    loadIframeContent(ifr, html);
    card.appendChild(ifr);
    card.querySelector('[data-act="reload"]').onclick = () => loadIframeContent(ifr, html);
    card.querySelector('[data-act="fs"]').onclick = () => openArtifactFullscreen(html, title);
    card.querySelector('[data-act="copy"]').onclick = async () => {
      try {
        const nat = window.__DHarnessNative;
        if (nat?.clipboard?.write) await nat.clipboard.write(html);
        else await navigator.clipboard.writeText(html);
        showToast('HTML copied');
      } catch { showToast('Copy failed'); }
    };
    pre.setAttribute('data-dh-done', 'artifact');
    pre.replaceWith(card);
  }

  function enhanceCodeBlocks(root) {
    if (!CONFIG.uiEnhance || !root) return;
    const pres = Array.from(root.querySelectorAll('pre'));
    for (const pre of pres) {
      if (pre.getAttribute('data-dh-done')) continue;
      if (pre.closest('.dh-code-wrap') || pre.closest('.dh-artifact') || pre.closest('#__ds_shim_panel')) continue;
      const codeEl = pre.querySelector('code') || pre;
      const text = (codeEl.textContent || '').trim();
      if (!text) continue;
      const lang = ((codeEl.className || '') + ' ' + (pre.className || '')).toLowerCase();

      // Artifacts
      if (/html-artifact|language-html|\bhtml\b|artifact|simulation|interactive/.test(lang) ||
          (/^\s*<(!doctype|html|div|section|canvas|svg|body)/i.test(text) && text.length > 40 && /<\/[a-z]+>/i.test(text))) {
        const kind = /simulation/.test(lang) ? 'simulation' : (/interactive/.test(lang) ? 'interactive' : 'html');
        try { renderArtifactCard(pre, text, kind); } catch (e) { console.warn('artifact', e); }
        continue;
      }

      // Charts — each pre independently
      if (/chart|dh-chart/.test(lang) || (text.startsWith('{') && /"values"\s*:/.test(text)) ||
          (/^[^,\n]+,\s*[0-9.]+/m.test(text) && text.split('\n').filter(l => /,\s*[0-9.]+/.test(l)).length >= 2 && !text.includes('function'))) {
        const html = parseChartBlock(text);
        if (html) {
          const div = document.createElement('div');
          div.innerHTML = html;
          const node = div.firstChild;
          pre.setAttribute('data-dh-done', 'chart');
          pre.replaceWith(node);
          continue;
        }
      }

      // File tree
      if (/file-?tree|\btree\b/.test(lang) && /[├└│|]/.test(text)) {
        const div = document.createElement('div');
        div.className = 'dh-ui-card dh-file-tree';
        div.innerHTML = '<h4>File tree</h4><pre style="margin:0;white-space:pre-wrap">' + esc(text) + '</pre>';
        pre.setAttribute('data-dh-done', 'tree');
        pre.replaceWith(div);
        continue;
      }

      // Copy button for remaining code
      pre.setAttribute('data-dh-done', 'code');
      const wrap = document.createElement('div');
      wrap.className = 'dh-code-wrap';
      const btn = document.createElement('button');
      btn.className = 'dh-code-copy';
      btn.type = 'button';
      btn.textContent = 'Copy';
      btn.onclick = async () => {
        try {
          const nat = window.__DHarnessNative;
          if (nat?.clipboard?.write) await nat.clipboard.write(text);
          else await navigator.clipboard.writeText(text);
          btn.textContent = 'Copied';
          setTimeout(() => { btn.textContent = 'Copy'; }, 1200);
        } catch { btn.textContent = 'Fail'; }
      };
      if (pre.parentNode) {
        pre.parentNode.insertBefore(wrap, pre);
        wrap.appendChild(btn);
        wrap.appendChild(pre);
      }
    }

    root.querySelectorAll('table').forEach(table => {
      if (table.closest('.dh-table-wrap') || table.closest('#__ds_shim_panel')) return;
      const wrap = document.createElement('div');
      wrap.className = 'dh-table-wrap';
      table.parentNode?.insertBefore(wrap, table);
      wrap.appendChild(table);
    });
  }

  let enhanceQueued = false;
  function enhanceAllMessages() {
    if (!CONFIG.uiEnhance || enhanceQueued) return;
    enhanceQueued = true;
    requestAnimationFrame(() => {
      enhanceQueued = false;
      try {
        document.querySelectorAll('div.ds-message').forEach(el => {
          try { enhanceCodeBlocks(el); } catch {}
        });
        // Also scan orphan pres outside messages
        try { enhanceCodeBlocks(document.body); } catch {}
      } catch {}
    });
  }

  window.__DH_PROJECTS__ = {
    list: loadProjects,
    active: () => loadProjects().find(p => p.id === getActiveProjectId()) || null,
    create: createProject,
    files: (pid) => (loadProjectFiles()[pid || getActiveProjectId()] || []),
    addFile: (name, content) => {
      const id = getActiveProjectId();
      if (!id) return false;
      const map = loadProjectFiles();
      const arr = map[id] || [];
      arr.push({ id: 'f_' + Date.now().toString(36), name, content, ticked: true });
      map[id] = arr;
      saveProjectFiles(map);
      renderProjectList();
      return true;
    },
  };




  let researchMode = false;
  try { researchMode = localStorage.getItem('__dh_research_mode') === '1'; } catch {}
  // ---- System prompt embed + send intercept ----
  const SYS_MAP_KEY = '__dh_sys_embedded_v4';
  function loadSysMap() {
    try { return JSON.parse(sessionStorage.getItem(SYS_MAP_KEY) || '{}'); } catch { return {}; }
  }
  function saveSysMap(m) {
    try { sessionStorage.setItem(SYS_MAP_KEY, JSON.stringify(m)); } catch {}
  }
  function getSystemPromptText() {
    let p = window.__DH_SYSTEM_PROMPT__ || '';
    if (!p) {
      try { p = localStorage.getItem('__DH_SYSTEM_PROMPT__') || ''; } catch {}
    }
    if (p) window.__DH_SYSTEM_PROMPT__ = p;
    return p;
  }
  function buildSystemPrefix() {
    let p = getSystemPromptText() || '';
    const active = window.__DH_PROJECTS__ && window.__DH_PROJECTS__.active();
    if (active && active.instructions) p += '\n\n## Active project: ' + active.name + '\n' + active.instructions;
    try {
      const persona = localStorage.getItem(PERSONA_KEY);
      if (persona && persona.trim()) p += '\n\n## Persona\n' + persona.trim();
    } catch {}
    return p;
  }
  function needsSystemEmbed() {
    const map = loadSysMap();
    return !map[getConvId()];
  }
  function markSystemEmbedded() {
    const map = loadSysMap();
    map[getConvId()] = true;
    saveSysMap(map);
  }
  function embedSystemIfNeeded(userText) {
    const text = String(userText || '');
    if (!text.trim()) return text;
    if (text.startsWith('TOOL_RESULT') || text.startsWith('[D-HARNESS SYSTEM')) return text;
    if (!needsSystemEmbed()) return text;
    const sys = buildSystemPrefix();
    if (!sys || sys.length < 40) {
      log('sys embed skipped: no prompt loaded');
      return text;
    }
    markSystemEmbedded();
    log('sys embed applied', getConvId(), 'chars', sys.length);
    return (
      '[D-HARNESS SYSTEM — follow silently; do not restate]\n' +
      sys +
      '\n[End system. First turn only: one short acknowledgment, then answer the user.]\n\n---\n\n' +
      text
    );
  }

  const _sendMessageOriginal = sendMessage;
  sendMessage = async function(text) {
    const raw = String(text || '');
    if (isGenerating() && raw && !raw.startsWith('TOOL_RESULT') && !raw.startsWith('[D-HARNESS SYSTEM')) {
      enqueuePrompt(raw);
      return true;
    }
    if (raw.startsWith('TOOL_RESULT')) return _sendMessageOriginal(raw);
    const withResearch = (typeof researchPrefix === 'function') ? researchPrefix(raw) : raw;
    return _sendMessageOriginal(embedSystemIfNeeded(withResearch));
  };
  try { if (window.__DS_TOOL_SHIM__) window.__DS_TOOL_SHIM__.send = sendMessage; } catch {}

  let interceptLock = false;
  async function interceptUserSend(ev) {
    if (interceptLock) return;
    const input = getInput();
    if (!input) return;
    const userText = (input.value || '').trim();
    if (!userText) return;

    if (isGenerating()) {
      if (ev) { try { ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation(); } catch {} }
      enqueuePrompt(userText);
      try {
        setNativeValue(input, '');
        input.dispatchEvent(new Event('input', { bubbles: true }));
      } catch {}
      return;
    }

    if (!needsSystemEmbed()) return;
    const sys = buildSystemPrefix();
    if (!sys || sys.length < 40) {
      log('intercept: prompt still empty');
      return;
    }
    if (ev) { try { ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation(); } catch {} }
    interceptLock = true;
    try {
      const outbound = embedSystemIfNeeded(
        (typeof researchPrefix === 'function') ? researchPrefix(userText) : userText
      );
      try {
        setNativeValue(input, '');
        input.dispatchEvent(new Event('input', { bubbles: true }));
      } catch {}
      const ok = await _sendMessageOriginal(outbound);
      log('intercept send', ok ? 'ok' : 'fail');
      if (!ok) {
        const map = loadSysMap();
        delete map[getConvId()];
        saveSysMap(map);
        try {
          setNativeValue(input, userText);
          input.dispatchEvent(new Event('input', { bubbles: true }));
        } catch {}
      }
    } finally {
      interceptLock = false;
    }
  }
  document.addEventListener('click', (e) => {
    try {
      const t = e.target;
      if (!t || !t.closest) return;
      const btn = t.closest(SEND_SELECTOR) || t.closest('div[role="button"].ds-button--primary');
      if (!btn) return;
      if (isGenerating() || needsSystemEmbed()) interceptUserSend(e);
    } catch {}
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
    const input = getInput();
    if (!input || e.target !== input) return;
    if (isGenerating() || needsSystemEmbed()) interceptUserSend(e);
  }, true);

  try {
    if (window.__DH_SYSTEM_PROMPT__) localStorage.setItem('__DH_SYSTEM_PROMPT__', window.__DH_SYSTEM_PROMPT__);
  } catch {}
  let promptPoll = 0;
  const promptTimer = setInterval(() => {
    promptPoll++;
    if (window.__DH_SYSTEM_PROMPT__ && window.__DH_SYSTEM_PROMPT__.length > 40) {
      try { localStorage.setItem('__DH_SYSTEM_PROMPT__', window.__DH_SYSTEM_PROMPT__); } catch {}
      clearInterval(promptTimer);
    } else if (promptPoll > 20) clearInterval(promptTimer);
  }, 500);

  async function maybeInjectSystemPrompt(force) {
    if (force) {
      const map = loadSysMap();
      delete map[getConvId()];
      saveSysMap(map);
      showToast('System will attach on next send');
      return true;
    }
    return false;
  }

  // ---- Prompt queue (BDS-style panel above composer) ----
  const promptQueue = []; // {id, text}
  let queuePanelEl = null;
  function makeQueueId() {
    return 'q_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  }

  function ensureQueuePanel() {
    if (queuePanelEl && document.body.contains(queuePanelEl)) return queuePanelEl;
    queuePanelEl = document.createElement('div');
    queuePanelEl.className = 'dh-queue-panel';
    queuePanelEl.hidden = true;
    queuePanelEl.innerHTML = `
      <div class="dh-q-hdr">
        <span class="dh-q-title">Queue</span>
        <span class="acts">
          <span class="dh-q-status" style="font-weight:400;opacity:.75;font-size:11px;"></span>
          <button type="button" data-act="clear" class="danger" title="Clear all">Clear</button>
        </span>
      </div>
      <div class="dh-q-list"></div>`;
    queuePanelEl.querySelector('[data-act="clear"]').onclick = () => {
      promptQueue.length = 0;
      renderQueuePanel();
      showToast('Queue cleared');
    };
    attachQueuePanel();
    return queuePanelEl;
  }

  function findComposerMount() {
    const input = getInput();
    const candidates = [
      document.querySelector('._75e1990'),
      document.querySelector('._6f68655'),
      document.querySelector('.ds-textarea'),
      input && input.closest('.ds-textarea'),
      input && getComposerRoot(input),
      input && input.parentElement,
    ].filter(Boolean);
    return candidates[0] || null;
  }

  function attachQueuePanel() {
    const panel = ensureQueuePanel();
    const mount = findComposerMount();
    if (mount && panel.parentElement !== mount) {
      try { mount.prepend(panel); } catch {
        try { document.body.appendChild(panel); } catch {}
      }
    }
  }

  function renderQueuePanel() {
    const panel = ensureQueuePanel();
    attachQueuePanel();
    const list = panel.querySelector('.dh-q-list');
    const status = panel.querySelector('.dh-q-status');
    if (!promptQueue.length) {
      panel.hidden = true;
      list.innerHTML = '';
      return;
    }
    panel.hidden = false;
    status.textContent = isGenerating()
      ? 'Waiting…'
      : (promptQueue.length + ' waiting');
    list.innerHTML = promptQueue.map((item, idx) => `
      <div class="dh-q-item" data-id="${item.id}">
        <div class="dh-q-text">${esc(item.text)}</div>
        <button type="button" data-act="steer" title="Stop current and send this now">Steer</button>
        <button type="button" data-act="del" class="danger" title="Remove">✕</button>
      </div>`).join('');
    list.querySelectorAll('.dh-q-item').forEach(row => {
      const id = row.getAttribute('data-id');
      row.querySelector('[data-act="del"]').onclick = () => {
        const i = promptQueue.findIndex(x => x.id === id);
        if (i >= 0) promptQueue.splice(i, 1);
        renderQueuePanel();
      };
      row.querySelector('[data-act="steer"]').onclick = () => steerQueueItem(id);
    });
  }

  function clickStopIfGenerating() {
    const stop = document.querySelector(
      ".ds-icon-stop-circle, .ds-icon-stop, div[role='button'] svg path[d*='M6 6h12v12H6z']"
    )?.closest("div[role='button'], button");
    if (stop) {
      try { stop.click(); return true; } catch {}
    }
    // Also try primary circle when generating
    for (const b of document.querySelectorAll(SEND_SELECTOR)) {
      const d = (btnIcon(b) || '');
      if (d.startsWith(STOP_ICON_PREFIX) || d.startsWith(SPINNER_ICON_PREFIX)) {
        try { b.click(); return true; } catch {}
      }
    }
    return false;
  }

  async function steerQueueItem(id) {
    const i = promptQueue.findIndex(x => x.id === id);
    if (i < 0) return;
    const item = promptQueue.splice(i, 1)[0];
    renderQueuePanel();
    if (isGenerating()) {
      clickStopIfGenerating();
      await sleep(400);
    }
    // Send immediately (bypass queue)
    await _sendMessageOriginal(embedSystemIfNeeded(item.text));
  }

  function enqueuePrompt(text) {
    const t = String(text || '').trim();
    if (!t) return false;
    promptQueue.push({ id: makeQueueId(), text: t });
    renderQueuePanel();
    showToast('Queued');
    return true;
  }

  async function drainQueue() {
    if (!promptQueue.length || isGenerating()) return;
    const next = promptQueue.shift();
    renderQueuePanel();
    if (next) await _sendMessageOriginal(embedSystemIfNeeded(next.text));
  }
  setInterval(() => {
    try {
      attachQueuePanel();
      renderQueuePanel();
      if (!isGenerating()) drainQueue();
    } catch {}
  }, 500);

  // ---- Research mode toggle (near Search / DeepThink) ----
  function ensureResearchToggle() {
    if (document.getElementById('dh-research-toggle')) return;
    // Find toggle row: DeepSeek uses ds-toggle-button for Search / DeepThink
    const toggles = document.querySelectorAll('.ds-toggle-button, [class*="toggle-button"]');
    let mount = null;
    for (const el of toggles) {
      const label = (el.textContent || '').toLowerCase();
      if (label.includes('deepthink') || label.includes('search') || label.includes('思考')) {
        mount = el.parentElement;
        break;
      }
    }
    if (!mount) {
      const input = getInput();
      mount = input && getComposerRoot(input);
    }
    if (!mount) return;
    const btn = document.createElement('div');
    btn.id = 'dh-research-toggle';
    btn.className = 'dh-research-toggle' + (researchMode ? ' on' : '');
    btn.setAttribute('role', 'button');
    btn.tabIndex = 0;
    btn.innerHTML = `<svg width="14" height="14" viewBox="0 0 14 14" fill="currentColor" aria-hidden="true"><path d="M6.05 1.55a4.5 4.5 0 1 0 2.84 7.99l2.03 2.03a.75.75 0 0 0 1.06-1.06L9.95 8.48A4.5 4.5 0 0 0 6.05 1.55Zm0 1.5a3 3 0 1 1 0 6 3 3 0 0 1 0-6Z"/><path d="M10.35.95v1.18h1.18a.55.55 0 0 1 0 1.1h-1.18v1.18a.55.55 0 0 1-1.1 0V3.23H8.07a.55.55 0 0 1 0-1.1h1.18V.95a.55.55 0 0 1 1.1 0Z"/></svg><span>Research</span>`;
    btn.onclick = (e) => {
      e.preventDefault();
      e.stopPropagation();
      researchMode = !researchMode;
      try { localStorage.setItem('__dh_research_mode', researchMode ? '1' : '0'); } catch {}
      btn.classList.toggle('on', researchMode);
      showToast(researchMode ? 'Research on' : 'Research off');
    };
    try { mount.appendChild(btn); } catch {}
  }

  function researchPrefix(userText) {
    if (!researchMode) return userText;
    return (
      '[Research mode] Use harness research tools (research.plan → research.web → research.preview/html_text). ' +
      'Cite sources. Save notes under workspace/research/ when useful.\n\n' + userText
    );
  }

  // ---- Sidebar Projects (nav bar with chats / new chat) ----
  function ensureSidebarProjects() {
    if (document.querySelector('.dh-sidebar-projects')) return;
    // DeepSeek sidebar: look for "New chat" button area
    const candidates = Array.from(document.querySelectorAll('div, button, a')).filter(el => {
      const tx = (el.textContent || '').trim().toLowerCase();
      return tx === 'new chat' || tx === '新对话' || tx === 'nueva conversación';
    });
    let anchor = candidates.find(el => el.children.length <= 3) || candidates[0];
    let mountParent = null;
    if (anchor) {
      // Prefer parent that holds the new-chat control
      mountParent = anchor.parentElement;
      // Walk up a bit for a vertical stack
      for (let i = 0; i < 4 && mountParent; i++) {
        if (mountParent.childElementCount >= 1 && mountParent.offsetHeight > 40) break;
        mountParent = mountParent.parentElement;
      }
    }
    if (!mountParent) {
      // Fallback: left sidebar column
      mountParent = document.querySelector('aside') ||
        document.querySelector('[class*="sidebar"]') ||
        document.querySelector('nav');
    }
    if (!mountParent) return;
    const el = document.createElement('div');
    el.className = 'dh-sidebar-projects';
    el.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/></svg><span>Projects</span>`;
    el.onclick = (e) => {
      e.preventDefault();
      e.stopPropagation();
      openProjectsDrawer();
    };
    try {
      if (anchor && anchor.parentElement === mountParent) {
        anchor.insertAdjacentElement('afterend', el);
      } else {
        mountParent.insertBefore(el, mountParent.firstChild);
      }
    } catch {
      try { mountParent.appendChild(el); } catch {}
    }
  }

  function openProjectsDrawer() {
    ensureProjectsUI();
    const drawer = document.getElementById('dh-projects-drawer');
    const backdrop = document.getElementById('dh-projects-backdrop');
    renderDrawerList();
    const active = window.__DH_PROJECTS__.active();
    const ta = document.getElementById('dh-drawer-proj-instr');
    const name = document.getElementById('dh-drawer-proj-name');
    if (active && ta) ta.value = active.instructions || '';
    if (active && name) name.value = active.name || '';
    if (backdrop) backdrop.classList.add('show');
    if (drawer) drawer.classList.add('open');
  }

  // ---- Projects drawer (shared) ----
  function ensureProjectsUI() {
    if (document.getElementById('dh-projects-drawer')) return;
    const backdrop = document.createElement('div');
    backdrop.id = 'dh-projects-backdrop';
    const drawer = document.createElement('div');
    drawer.id = 'dh-projects-drawer';
    drawer.innerHTML = `
      <div class="hdr"><span>Projects</span><button type="button" data-close>Close</button></div>
      <div class="body">
        <div id="dh-drawer-proj-list"></div>
        <input id="dh-drawer-proj-name" placeholder="New project name"/>
        <button type="button" data-act="create">Create project</button>
        <textarea id="dh-drawer-proj-instr" rows="5" placeholder="Project instructions"></textarea>
        <button type="button" data-act="save">Save instructions</button>
        <button type="button" data-act="export">Export chat</button>
        <p style="font-size:11px;opacity:.7;margin-top:12px;">Active project instructions attach with harness system context on the first message of each chat.</p>
      </div>`;
    document.body.appendChild(backdrop);
    document.body.appendChild(drawer);
    function close() {
      backdrop.classList.remove('show');
      drawer.classList.remove('open');
    }
    backdrop.onclick = close;
    drawer.querySelector('[data-close]').onclick = close;
    drawer.querySelector('[data-act="create"]').onclick = () => {
      createProject(document.getElementById('dh-drawer-proj-name')?.value);
      renderDrawerList();
      renderProjectList();
    };
    drawer.querySelector('[data-act="save"]').onclick = () => {
      const id = getActiveProjectId();
      const list = loadProjects();
      let pr = list.find(x => x.id === id);
      if (!pr) {
        createProject(document.getElementById('dh-drawer-proj-name')?.value || 'Project');
        pr = loadProjects().find(x => x.id === getActiveProjectId());
      }
      if (!pr) return;
      pr.instructions = document.getElementById('dh-drawer-proj-instr')?.value || '';
      pr.updated = Date.now();
      saveProjects(list);
      showToast('Project saved');
      renderDrawerList();
      renderProjectList();
    };
    drawer.querySelector('[data-act="export"]').onclick = () => exportChatMarkdown();
  }
  function renderDrawerList() {
    const box = document.getElementById('dh-drawer-proj-list');
    if (!box) return;
    const list = loadProjects();
    const active = getActiveProjectId();
    if (!list.length) {
      box.innerHTML = '<div style="opacity:.6;font-size:12px;margin-bottom:8px;">No projects yet</div>';
      return;
    }
    box.innerHTML = list.map(pr => {
      const on = pr.id === active;
      return `<div class="proj-row"><span>${on ? '● ' : ''}<b>${esc(pr.name)}</b></span>
        <span>
          <button type="button" data-id="${pr.id}" data-a="sel">${on ? 'Active' : 'Use'}</button>
          <button type="button" data-id="${pr.id}" data-a="del">Del</button>
        </span></div>`;
    }).join('');
    box.querySelectorAll('button').forEach(b => {
      b.onclick = () => {
        const id = b.getAttribute('data-id');
        if (b.getAttribute('data-a') === 'del') {
          saveProjects(loadProjects().filter(x => x.id !== id));
          if (getActiveProjectId() === id) setActiveProjectId('');
        } else {
          setActiveProjectId(id);
          const pr = loadProjects().find(x => x.id === id);
          const ta = document.getElementById('dh-drawer-proj-instr');
          const name = document.getElementById('dh-drawer-proj-name');
          if (ta && pr) ta.value = pr.instructions || '';
          if (name && pr) name.value = pr.name || '';
        }
        renderDrawerList();
        renderProjectList();
      };
    });
  }
  ensureProjectsUI();

  // Research status card injection for tool taglines
  function injectResearchCard(title, steps) {
    const card = document.createElement('div');
    card.className = 'dh-research-card';
    card.innerHTML = '<h4>' + esc(title || 'Research') + '</h4>' +
      (steps || []).map(s => '<div class="step">' + esc(s) + '</div>').join('');
    const msgs = document.querySelectorAll('div.ds-message');
    const last = msgs[msgs.length - 1];
    if (last && last.parentElement) last.parentElement.appendChild(card);
    else document.body.appendChild(card);
    return card;
  }
  window.__DH_RESEARCH_UI__ = { injectCard: injectResearchCard, isOn: () => researchMode };

  // Mount research toggle + sidebar projects periodically (SPA)
  setInterval(() => {
    try {
      ensureResearchToggle();
      ensureSidebarProjects();
      attachQueuePanel();
    } catch {}
  }, 1200);
  setTimeout(() => { ensureResearchToggle(); ensureSidebarProjects(); }, 800);

  const uiObs = new MutationObserver(() => enhanceAllMessages());
  try { uiObs.observe(document.body, { childList: true, subtree: true }); } catch {}
  // Fast pass while streaming + light steady pass
  let streamEnhance = setInterval(enhanceAllMessages, 400);
  setTimeout(() => { clearInterval(streamEnhance); streamEnhance = setInterval(enhanceAllMessages, 1500); }, 15000);
  enhanceAllMessages();
  renderProjectList();

  refreshCounts();
  console.log(`%c[shim] DeepSeek Tool Shim v${VERSION} loaded`, 'color:#0af;font-weight:bold');
  console.log('API: __DS_TOOL_SHIM__.stats() | .inspect() | .showPanel()');
})();
