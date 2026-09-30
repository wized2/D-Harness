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

  const VERSION = '1.9.13';
  const getConvId = () => location.pathname.split('/').filter(Boolean).pop() || 'unknown';
  const CONFIG = Object.assign({
    debug: false,
    maxStorageKB: 100,
    sendTimeoutMs: 3000,
    sandboxTimeoutMs: 20000,
    dedupe: true,
    confirmSensitive: true,
    callMustBeLast: true,
    maxResultChars: 20000,
    settleMs: 700,
    scanThrottleMs: 250,
    fallbackScanMs: 900,
    hideFlashMs: 250,
  }, window.__DS_SHIM_CONFIG__ || {});

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

  const collapsedByMsg = new Map();
  for (const v of Object.values(DONE)) {
    if (v && v.mk) collapsedByMsg.set(v.mk, { preview: v.preview || '', err: !v.ok });
  }

  function hashStr(s) {
    let h1 = 0x811c9dc5, h2 = 0x01000193;
    for (let i = 0; i < s.length; i++) { const c = s.charCodeAt(i); h1 = Math.imul(h1 ^ c, 0x01000193); h2 = Math.imul(h2 ^ c, 0x85ebca6b); }
    return (h1 >>> 0).toString(36) + (h2 >>> 0).toString(36);
  }

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

  const style = document.createElement('style');
  style.id = '__ds_shim_style__';
  style.textContent = `
    [data-ds-shim-hidden="1"] { display: none !important; }

    [data-ds-shim-tagline="1"] {
      display: flex !important;
      flex-direction: column;
      align-items: stretch;
      height: auto;
      min-height: 40px;
      padding: 0;
      margin: 8px 0 10px 0;
      cursor: pointer;
      user-select: none;
      width: min(100%, 420px);
      max-width: 100%;
      border-radius: 16px;
      border: 1px solid var(--dsw-alias-border, rgba(120,150,180,0.14));
      background: var(--dsw-alias-bg-elevated, rgba(120,150,180,0.06));
      color: var(--dsw-alias-label-secondary, rgba(180,195,210,0.9));
      font: 13px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;
      transition: background .15s ease, border-color .15s ease;
      -webkit-tap-highlight-color: transparent;
      overflow: hidden;
    }
    [data-ds-shim-tagline="1"]:hover {
      background: var(--dsw-alias-bg-hover, rgba(120,150,180,0.10));
    }
    [data-ds-shim-tagline="1"] .ds-shim-inner {
      display: flex; align-items: center; gap: 8px;
      min-height: 40px; padding: 8px 12px;
    }
    [data-ds-shim-tagline="1"] .ds-shim-ico {
      width: 16px; height: 16px;
      display: inline-flex; align-items: center; justify-content: center;
      opacity: .8; flex-shrink: 0;
    }
    [data-ds-shim-tagline="1"] .ds-shim-ico svg { display: block; width: 100%; height: 100%; }
    [data-ds-shim-tagline="1"][data-ds-shim-running="1"] .ds-shim-ico {
      animation: dsshim-pulse-ico 1.2s ease-in-out infinite;
    }
    @keyframes dsshim-pulse-ico { 0%, 100% { opacity: .35; } 50% { opacity: 1; } }
    [data-ds-shim-tagline="1"] .ds-shim-txt {
      font-weight: 600; font-size: 14px; flex: 1;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
      color: var(--dsw-alias-label-primary, inherit);
    }
    [data-ds-shim-tagline="1"][data-ds-shim-err="1"] .ds-shim-txt { color: #f88; }
    [data-ds-shim-tagline="1"] .ds-shim-sub {
      font-weight: 400; font-size: 12px; opacity: .7;
      margin-left: auto; padding-left: 8px; white-space: nowrap;
    }
    [data-ds-shim-tagline="1"] .ds-shim-chev {
      width: 14px; height: 14px;
      display: inline-flex; align-items: center; justify-content: center;
      opacity: .55; margin-left: 4px; transition: transform .2s ease;
    }
    [data-ds-shim-tagline="1"] .ds-shim-chev svg { display: block; }
    [data-ds-shim-tagline="1"][data-ds-shim-expanded="1"] .ds-shim-chev { transform: rotate(180deg); }
    [data-ds-shim-tagline="1"] .ds-shim-panel {
      display: none;
      border-top: 1px solid var(--dsw-alias-border, rgba(120,150,180,0.12));
      padding: 10px 12px 12px;
      background: var(--dsw-alias-bg-elevated, rgba(0,0,0,0.12));
    }
    [data-ds-shim-tagline="1"][data-ds-shim-expanded="1"] .ds-shim-panel { display: block; }
    [data-ds-shim-tagline="1"] .ds-shim-panel-title {
      font-size: 12px; font-weight: 600; opacity: .75; margin-bottom: 8px;
      letter-spacing: 0.02em;
    }
    [data-ds-shim-tagline="1"] .ds-shim-step {
      display: flex; gap: 8px; align-items: flex-start;
      padding: 6px 0; font-size: 12.5px; line-height: 1.4;
      border-bottom: 1px solid rgba(120,150,180,0.08);
    }
    [data-ds-shim-tagline="1"] .ds-shim-step:last-child { border-bottom: none; }
    [data-ds-shim-tagline="1"] .ds-shim-step-ico {
      width: 14px; flex-shrink: 0; opacity: .65; margin-top: 2px;
    }
    [data-ds-shim-tagline="1"] .ds-shim-step-body { flex: 1; min-width: 0; }
    [data-ds-shim-tagline="1"] .ds-shim-step-name { font-weight: 600; word-break: break-word; }
    [data-ds-shim-tagline="1"] .ds-shim-step-desc { opacity: .7; font-size: 11.5px; margin-top: 2px; }
    [data-ds-shim-tagline="1"] .ds-shim-step.err .ds-shim-step-name { color: #f88; }
    [data-ds-shim-tagline="1"] .ds-shim-chip {
      font-family: ui-monospace,SFMono-Regular,Menlo,monospace;
      font-size: 11px; padding: 1px 6px; border-radius: 5px;
      background: rgba(120,150,180,0.10); color: rgba(190,205,220,0.9);
      display: inline-block; margin-top: 4px; max-width: 100%;
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    @media (pointer: coarse) {
      [data-ds-shim-tagline="1"] .ds-shim-inner { min-height: 44px; }
    }

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

    .dh-artifact {
      margin: 12px 0; border-radius: 16px; overflow: hidden;
      border: 1px solid var(--dh-card-border); background: var(--dh-card-bg);
      color: var(--dh-card-fg); box-shadow: 0 1px 3px rgba(0,0,0,0.08);
    }
    .dh-artifact-bar {
      display: flex; align-items: center; justify-content: space-between; gap: 8px;
      padding: 10px 14px; background: var(--dh-code-bg);
      border-bottom: 1px solid var(--dh-card-border); font-size: 12px; color: var(--dh-muted);
    }
    .dh-artifact-bar b { color: var(--dh-accent); font-weight: 600; }
    .dh-artifact-bar .acts { display: flex; gap: 6px; }
    .dh-artifact-bar button {
      font-size: 11px; padding: 4px 10px; border-radius: 20px; cursor: pointer;
      border: 1px solid var(--dh-card-border); background: var(--dh-card-bg); color: var(--dh-accent);
    }
    .dh-artifact-frame-wrap { position: relative; background: #f8f9fa; min-height: 120px; }
    html.dark .dh-artifact-frame-wrap, body.dark .dh-artifact-frame-wrap { background: #1e1f24; }
    .dh-artifact iframe {
      width: 100%; height: 380px; border: 0; display: block; background: #fff;
    }
    .dh-artifact.dh-artifact-tall iframe { height: 520px; }
    .dh-artifact-loading {
      display: flex; flex-direction: column; align-items: center; justify-content: center;
      gap: 10px; min-height: 140px; padding: 24px; color: var(--dh-muted);
      background: var(--dh-code-bg); font-size: 13px;
    }
    .dh-artifact-loading .spin {
      width: 28px; height: 28px; border-radius: 50%;
      border: 3px solid var(--dh-card-border); border-top-color: var(--dh-accent);
      animation: dh-spin .8s linear infinite;
    }
    @keyframes dh-spin { to { transform: rotate(360deg); } }
    .dh-artifact-fs {
      position: fixed; inset: 0; z-index: 2147483000; background: rgba(0,0,0,0.55);
      display: flex; flex-direction: column; padding: 12px;
    }
    .dh-artifact-fs .dh-artifact-frame-wrap { flex: 1; border-radius: 12px; overflow: hidden; }
    .dh-artifact-fs iframe { height: 100% !important; min-height: 0; }
    .dh-ui-card {
      margin: 10px 0; padding: 12px 14px; border-radius: 16px;
      background: var(--dh-card-bg); border: 1px solid var(--dh-card-border);
      color: var(--dh-card-fg); font-family: inherit;
    }
    .dh-ui-card h4 { margin: 0 0 8px; font-size: 13px; color: var(--dh-accent); font-weight: 600; }
    .dh-chart svg { width: 100%; max-width: 420px; height: auto; display: block; }
    .dh-chart .bar { fill: var(--dh-bar); }
    .dh-chart .axis { stroke: var(--dh-muted); stroke-width: 1; opacity: 0.45; }
    .dh-chart text { fill: var(--dh-muted); font-size: 10px; }
    .dh-chart-loading {
      padding: 16px; text-align: center; color: var(--dh-muted); font-size: 12px;
      border-radius: 16px; border: 1px dashed var(--dh-card-border); background: var(--dh-code-bg);
    }
`;
  document.head.appendChild(style);

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

  const sizeGuard = (s) => {
    const kb = (String(s).length * 2) / 1024;
    if (kb > CONFIG.maxStorageKB) throw new Error(`payload too large: ${kb.toFixed(1)}KB > ${CONFIG.maxStorageKB}KB`);
  };

  async function confirmUser(what) {
    if (!CONFIG.confirmSensitive) return;
    if (!window.confirm('DeepSeek tool wants to ' + what + '.\nAllow?')) throw new Error('denied by user');
  }

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

  const N = () => window.__DHarnessNative;

  async function nativeCall(path, args) {
    const nat = N();
    if (!nat || !nat.available) throw new Error('native bridge unavailable');
    const parts = path.split('.');
    let cur = nat;
    for (const p of parts) {
      if (cur == null || typeof cur[p] === 'undefined') throw new Error('native missing: ' + path);
      cur = cur[p];
    }
    if (typeof cur === 'function') return await cur.apply(nat, args || []);
    return cur;
  }

  Object.assign(toolHandlers, {
    async list_tools() {
      const nat = N();
      if (nat && nat.available && nat.list_tools) return await nat.list_tools();
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
      if (!nat || !nat.calc) throw new Error('calc requires native bridge');
      const op = args.op || 'eval';
      const fn = nat.calc[op];
      if (typeof fn !== 'function') throw new Error('unknown calc op: ' + op);
      if (op === 'eval') return await fn(args.expr != null ? args.expr : args.x);
      if (op === 'convert') return await fn(args.value, args.from, args.to);
      if (op === 'haversine') return await fn(args.lat1, args.lon1, args.lat2, args.lon2);
      if (op === 'clamp') return await fn(args.value, args.min, args.max);
      if (op === 'round') return await fn(args.value, args.digits);
      return await fn(args.expr || args.x || args.value);
    },
    async text(args) {
      const nat = N();
      if (!nat || !nat.text) throw new Error('text requires native bridge');
      const op = args.op;
      if (!op) throw new Error('text requires args.op');
      // aliases
      const op2 = op === 'hash_preview' ? 'stats' : op;
      const fn = nat.text[op2];
      if (typeof fn !== 'function') throw new Error('unknown text op: ' + op);
      if (op2 === 'base64' || op2 === 'url') return await fn(args.mode || args.action || 'encode', args.data || args.text || '');
      if (op2 === 'regex') return await fn(args.action || args.mode || 'find', args.pattern, args.text || '', args.replacement);
      if (op2 === 'stats' || op2 === 'trim') return await fn(args.text || args.data || '');
      if (op2 === 'case') return await fn(args.mode || args.case || 'lower', args.text || '');
      if (op2 === 'split') return await fn(args.text || '', args.sep != null ? args.sep : ',', args.limit || 0);
      if (op2 === 'join') return await fn(args.parts || [], args.sep != null ? args.sep : '');
      return await fn(args.text || args.data || '');
    },
    async crypto_native(args) {
      const nat = N();
      if (nat && nat.crypto && nat.crypto.hash) {
        return await nat.crypto.hash(args.algo || 'sha256', args.data != null ? args.data : (args.text || ''));
      }
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
  const STOP_ICON_PREFIX = 'M2 4.88';
  const SPINNER_ICON_PREFIX = 'M34,18';
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
    const draft = input.value;

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
      await sleep(60);

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

  function normalizeToolText(text) {
    return String(text || '')
      .replace(/[\u201C\u201D\u201E\u201F\u2033\u2036]/g, '"')
      .replace(/[\u2018\u2019\u201A\u201B\u2032\u2035]/g, "'")
      .replace(/```(?:json|JSON|javascript|js)?\s*/g, '\n')
      .replace(/```/g, '\n');
  }

  function looksLikeToolJson(text) {
    const t = normalizeToolText(text);
    if (!/"tool"\s*:/.test(t)) return false;
    // incomplete if has "tool" but no parseable object yet
    return extractToolCall(t) == null;
  }

  function extractToolCall(raw) {
    const text = normalizeToolText(raw);
    let idx = 0;
    while ((idx = text.indexOf('"tool"', idx)) !== -1) {
      // also accept "name" style? no — stick to tool
      let start = text.lastIndexOf('{', idx);
      if (start === -1) { idx += 6; continue; }
      // walk back over whitespace to true object start
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
                if (obj && typeof obj.tool === 'string' && obj.tool.trim()) {
                  // run_js without code is incomplete — keep waiting
                  if (obj.tool === 'run_js' && !(obj.args && typeof obj.args.code === 'string')) {
                    idx = i + 1;
                    break;
                  }
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

  function extractDsmlToolCall(raw) {
    let text = String(raw || '');
    if (!/DSML|invoke\s+name\s*=/i.test(text)) return null;
    // Normalize DeepSeek DSML noise: <|DSML|>, | DSML |, broken tags
    text = text
      .replace(/<\|?\s*DSML\s*\|?>/gi, ' ')
      .replace(/\|\s*DSML\s*\|/gi, ' ')
      .replace(/<\/?\s*\|?\s*DSML\s*\|?\s*>/gi, ' ')
      .replace(/\s+/g, ' ');

    const inv = text.match(/invoke\s+name\s*=\s*["']([^"']+)["']/i)
      || text.match(/invoke\s+name\s*=\s*([a-zA-Z0-9_.]+)/i);
    if (!inv) return null;
    const tool = inv[1].trim();

    let description = '';
    const dMatch = text.match(/parameter\s+name\s*=\s*["']description["'][^>]*>([^<]{0,200})/i)
      || text.match(/name\s*=\s*["']description["'][^>]*string\s*=\s*["']true["'][^>]*>([^<]{0,200})/i)
      || text.match(/description["']\s*string\s*=\s*["']true["']\s*>([^<]{0,200})/i);
    if (dMatch) description = dMatch[1].replace(/<\/?[^>]+>/g, '').trim();

    let args = {};
    // args as JSON blob after name="args"
    const argsJson = text.match(/parameter\s+name\s*=\s*["']args["'][^>]*>(\{[\s\S]*?\})/i)
      || text.match(/name\s*=\s*["']args["'][^>]*>(\{[\s\S]*?\})/i)
      || text.match(/["']args["'][^>]*string\s*=\s*["']false["']\s*>(\{[\s\S]*?\})/i);
    if (argsJson) {
      try {
        args = JSON.parse(argsJson[1]);
      } catch (e) {
        // try to extract code field loosely
        const codeM = argsJson[1].match(/"code"\s*:\s*"((?:\\.|[^"\\])*)"/);
        if (codeM) {
          try { args = { code: JSON.parse('"' + codeM[1] + '"') }; } catch { args = { code: codeM[1] }; }
        }
      }
    }
    // code parameter separately
    if (!args.code) {
      const codeParam = text.match(/parameter\s+name\s*=\s*["']code["'][^>]*>([^<]+)/i)
        || text.match(/"code"\s*:\s*"((?:\\.|[^"\\])*)"/);
      if (codeParam) {
        let c = codeParam[1];
        try { c = JSON.parse('"' + c.replace(/^"/, '').replace(/"$/, '') + '"'); } catch {}
        args.code = c;
      }
    }
    // Common: run_js with code in args
    if (tool === 'run_js' && typeof args.code !== 'string') {
      // last chance: return await ... pattern in text
      const codeLoose = text.match(/return\s+await\s+[a-zA-Z0-9_.]+\([^)]*\)/);
      if (codeLoose) args.code = codeLoose[0];
      else return null; // incomplete
    }

    // Flatten dotted tool with empty args
    if (!args || typeof args !== 'object') args = {};

    const obj = { tool: tool, description: description || tool, args: args };
    return { obj: obj, full: String(raw).slice(0, Math.min(String(raw).length, 800)), end: String(raw).length };
  }

  const _extractToolCallPlain = extractToolCall;
  extractToolCall = function(raw) {
    const a = _extractToolCallPlain(raw);
    if (a) return a;
    return extractDsmlToolCall(raw);
  };

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

  function findWrapper(dsMessage) {
    const p = dsMessage.parentElement;
    if (!p) return null;
    if (p === document.body) return null;
    if (p.id === 'root') return null;
    if (p.classList.contains('ds-virtual-list-visible-items')) return null;
    if (p.classList.contains('ds-virtual-list-items')) return null;
    return p;
  }

  const TERMINAL_SVG = '<svg viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg"><rect x="1" y="2" width="14" height="12" rx="2" stroke="currentColor" stroke-width="1.3"/><path d="M4 6.3L6.5 8.8L4 11.3" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/><path d="M8 11.3H11.5" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"/></svg>';
  const CHEV_SVG = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M4 6l4 4 4-4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';


  let toolChain = null;

  function formatDuration(ms) {
    const s = Math.max(1, Math.round(ms / 1000));
    if (s < 60) return s + 's';
    const m = Math.floor(s / 60);
    return m + 'm ' + (s % 60) + 's';
  }

  function hideMsgBody(dsMessage, keepTagline) {
    const w = findWrapper(dsMessage);
    if (!w) return;
    w.setAttribute('data-ds-shim-wrapper', '1');
    for (const c of w.children) {
      if (c === keepTagline) continue;
      if (c.getAttribute && c.getAttribute('data-ds-shim-tagline') === '1' && c !== keepTagline) {
        c.style.display = 'none';
        continue;
      }
      c.setAttribute('data-ds-shim-hidden', '1');
    }
  }

  function refreshChainHeader(running) {
    if (!toolChain || !toolChain.tagline) return;
    const tl = toolChain.tagline;
    const steps = tl._dsSteps || [];
    const n = steps.length;
    const elapsed = formatDuration(Date.now() - (tl._dsStartAt || toolChain.startAt));
    const anyErr = steps.some(function (s) { return s.err; });
    const anyRun = running || steps.some(function (s) { return s.running; });
    tl.toggleAttribute('data-ds-shim-running', !!anyRun);
    tl.toggleAttribute('data-ds-shim-err', !!anyErr && !anyRun);
    const txt = tl.querySelector('.ds-shim-txt');
    if (txt) {
      txt.textContent = anyRun
        ? ('Working… · ' + n + (n === 1 ? ' step' : ' steps'))
        : ('Tools used · ' + elapsed);
    }
    const sub = tl.querySelector('.ds-shim-sub');
    if (sub) sub.textContent = anyRun ? elapsed : (n + (n === 1 ? ' step' : ' steps'));
    const title = tl.querySelector('.ds-shim-panel-title');
    if (title) title.textContent = anyRun ? 'In progress' : ('Worked for ' + elapsed);
    renderThoughtSteps(tl);
  }

  function getOrCreateChain(dsMessage) {
    const conv = getConvId();
    const now = Date.now();
    if (toolChain && toolChain.conv === conv && toolChain.tagline && toolChain.tagline.isConnected
        && (now - toolChain.lastAt) < 90000) {
      toolChain.lastAt = now;
      hideMsgBody(dsMessage, toolChain.tagline);
      return toolChain;
    }
    const tagline = collapseToolMessage(dsMessage, '', false, true, 'Working…', 'Tools used');
    if (!tagline) return null;
    tagline._dsSteps = [];
    tagline._dsStartAt = now;
    toolChain = { tagline: tagline, startAt: now, conv: conv, lastAt: now };
    hideMsgBody(dsMessage, tagline);
    return toolChain;
  }

    function createTagline(preview = '', isError = false, running = false) {
    const el = document.createElement('div');
    el.setAttribute('data-ds-shim-tagline', '1');
    if (running) el.setAttribute('data-ds-shim-running', '1');
    if (isError) el.setAttribute('data-ds-shim-err', '1');
    el._dsSteps = [];

    const inner = document.createElement('div');
    inner.className = 'ds-shim-inner';

    const ico = document.createElement('span');
    ico.className = 'ds-shim-ico';
    ico.innerHTML = TERMINAL_SVG;
    inner.appendChild(ico);

    const txt = document.createElement('span');
    txt.className = 'ds-shim-txt';
    txt.textContent = running ? 'Running tools…' : 'Tools';
    el._dsRunLabel = 'Running tools…';
    el._dsDoneLabel = 'Tools';
    inner.appendChild(txt);

    const sub = document.createElement('span');
    sub.className = 'ds-shim-sub';
    sub.textContent = '';
    inner.appendChild(sub);

    const chev = document.createElement('span');
    chev.className = 'ds-shim-chev';
    chev.innerHTML = CHEV_SVG;
    inner.appendChild(chev);
    el.appendChild(inner);

    const panel = document.createElement('div');
    panel.className = 'ds-shim-panel';
    panel.innerHTML = '<div class="ds-shim-panel-title">Thoughts</div><div class="ds-shim-steps"></div>';
    el.appendChild(panel);
    return el;
  }

  function renderThoughtSteps(tagline) {
    const box = tagline.querySelector('.ds-shim-steps');
    if (!box) return;
    const steps = tagline._dsSteps || [];
    box.innerHTML = steps.map(function (s) {
      const err = s.err ? ' err' : '';
      const desc = s.desc ? '<div class="ds-shim-step-desc">' + esc(s.desc) + '</div>' : '';
      const chip = s.tool ? '<span class="ds-shim-chip">' + esc(s.tool) + '</span>' : '';
      return '<div class="ds-shim-step' + err + '">' +
        '<span class="ds-shim-step-ico">' + (s.running ? '⏳' : (s.err ? '!' : '•')) + '</span>' +
        '<div class="ds-shim-step-body"><div class="ds-shim-step-name">' + esc(s.title || s.tool || 'step') + '</div>' +
        desc + chip + '</div></div>';
    }).join('');
    const sub = tagline.querySelector('.ds-shim-sub');
    if (sub) {
      const n = steps.length;
      const running = steps.some(function (s) { return s.running; });
      sub.textContent = running ? ('Running ' + n + (n === 1 ? ' tool' : ' tools')) : (n + (n === 1 ? ' step' : ' steps'));
    }
  }

  function updateTagline(tagline, preview, isError, running) {
    tagline.toggleAttribute('data-ds-shim-running', !!running);
    tagline.toggleAttribute('data-ds-shim-err', !!isError);
    const n = (tagline._dsSteps || []).length;
    const headRun = n > 1 ? ('Thought for tools · ' + n) : (tagline._dsRunLabel || 'Running tools…');
    const headDone = n > 1 ? ('Used ' + n + ' tools') : (tagline._dsDoneLabel || 'Tools');
    tagline.querySelector('.ds-shim-txt').textContent = running ? headRun : headDone;
    renderThoughtSteps(tagline);
  }

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
        tagline.setAttribute('data-ds-shim-expanded', expanded ? '0' : '1');
        // Keep raw tool JSON collapsed by default; panel shows structured steps
        for (const c of wrapper.children) {
          if (c === tagline) continue;
          if (!expanded) c.setAttribute('data-ds-shim-hidden', '1');
          else c.setAttribute('data-ds-shim-hidden', '1'); // always hide raw JSON body
        }
      };
    }
    if (runLabel) tagline._dsRunLabel = runLabel;
    if (doneLabel) tagline._dsDoneLabel = doneLabel;
    updateTagline(tagline, preview, isError, running);

    applyHiding(wrapper, tagline);
    return tagline;
  }

  let busy = false;
  const handled = new Set();
  const retried = new Set();
  const baseline = new Map();

  async function processToolCall(dsMessage, tool, mk, sig) {
    const tname = tool.obj.tool;
    const desc = String(tool.obj.description || tool.obj.discription || '').trim();
    const runLabel = desc ? desc : ('Running ' + tname + '…');
    const doneLabel = desc ? desc : 'Tool used';
    log('tool call:', tname, desc || '(no description)', '| msg:', mk);
    const chain = getOrCreateChain(dsMessage);
    if (chain && chain.tagline) {
      chain.tagline._dsSteps = chain.tagline._dsSteps || [];
      chain.tagline._dsSteps.push({
        tool: tname,
        title: desc || tname,
        desc: desc || tname,
        running: true,
        err: false
      });
      refreshChainHeader(true);
    } else {
      collapseToolMessage(dsMessage, desc || tname, false, true, runLabel, doneLabel);
    }

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
    try {
      if (toolChain && toolChain.tagline && toolChain.tagline._dsSteps && toolChain.tagline._dsSteps.length) {
        const last = toolChain.tagline._dsSteps[toolChain.tagline._dsSteps.length - 1];
        if (last && last.running) {
          last.running = false;
          last.err = !res.ok;
          if (!res.ok) last.title = (last.title || last.tool) + ' failed';
        }
        toolChain.lastAt = Date.now();
        refreshChainHeader(false);
      }
    } catch (_) {}


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
      result: resultPayload,
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
    const isChainHost = toolChain && toolChain.tagline && toolChain.tagline.isConnected
      && findWrapper(dsMessage) && findWrapper(dsMessage).contains(toolChain.tagline);
    collapsedByMsg.set(mk, {
      preview: isChainHost ? preview : '',
      err: !res.ok,
      desc: doneLabel,
      chainFollower: !isChainHost && !!(toolChain && toolChain.tagline)
    });
    saveDone(); refreshCounts();

    if (toolChain && toolChain.tagline && toolChain.tagline.isConnected) {
      refreshChainHeader(false);
      hideMsgBody(dsMessage, toolChain.tagline);
      // Remove any accidental empty tagline on this message
      const w = findWrapper(dsMessage);
      if (w) {
        w.querySelectorAll(':scope > [data-ds-shim-tagline="1"]').forEach(function (tl) {
          if (tl !== toolChain.tagline) tl.remove();
        });
      }
    } else {
      collapseToolMessage(dsMessage, preview, !res.ok, false, runLabel, doneLabel);
    }
    setStatus(res.ok ? 'idle' : 'error');
    try {
      const n = typeof N === 'function' ? N() : null;
      if (n && typeof n.agentEnd === 'function') n.agentEnd();
    } catch (e) { log('agentEnd', e); }

    const sent = await sendMessage(payload);
    if (sent && DONE[sig]) { DONE[sig].sent = true; delete DONE[sig].payload; saveDone(); }
  }

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

  function restoreCollapsed(msgs) {
    if (!collapsedByMsg.size) return;
    for (const el of msgs) {
      const wrapper = el.parentElement;
      if (!wrapper) continue;
      if (wrapper.firstElementChild?.getAttribute('data-ds-shim-tagline') === '1') {
        // Drop empty decorative taglines (0 steps, not active chain host)
        const tl = wrapper.firstElementChild;
        const steps = tl._dsSteps || [];
        const isHost = toolChain && toolChain.tagline === tl;
        if (!isHost && steps.length === 0) {
          tl.remove();
          wrapper.removeAttribute('data-ds-shim-wrapper');
        }
        continue;
      }
      const info = msgInfo(el);
      if (!info || !isRealId(info.id)) continue;
      const c = collapsedByMsg.get(mkOf(info));
      if (!c) continue;
      // Followers of a unified chain: hide body only, no new chip
      if (c.chainFollower || (toolChain && toolChain.tagline && toolChain.tagline.isConnected)) {
        hideMsgBody(el, toolChain && toolChain.tagline);
        continue;
      }
      // Only restore a real chip if we have a meaningful preview
      if (!c.preview && !c.desc) continue;
      collapseToolMessage(el, c.preview || c.desc || 'Tools used', c.err, false);
    }
  }

  function pruneEmptyToolChips() {
    document.querySelectorAll('[data-ds-shim-tagline="1"]').forEach(function (tl) {
      const steps = tl._dsSteps || [];
      const isHost = toolChain && toolChain.tagline === tl;
      if (isHost) return;
      if (steps.length === 0) {
        const w = tl.parentElement;
        tl.remove();
        if (w) w.removeAttribute('data-ds-shim-wrapper');
      }
    });
  }

  let settle = { el: null, len: -1, at: 0 };
  function isSettled(el) {
    const len = (el.textContent || '').length;
    const now = performance.now();
    if (settle.el !== el || settle.len !== len) { settle = { el, len, at: now }; return false; }
    return now - settle.at >= CONFIG.settleMs;
  }

  function scanForToolCalls(msgs) {
    if (busy || !msgs.length) return;
    const el = msgs[msgs.length - 1];
    const info = msgInfo(el);
    if (!info || !isRealId(info.id)) return;

    const sid = info.sessionId || getConvId();
    if (!baseline.has(sid)) baseline.set(sid, maxRealId(msgs));
    const mk = sid + ':' + info.id;
    if (handled.has(mk)) return;

    if (isGenerating() || !isSettled(el)) return;

    const main = el.querySelector('div.ds-markdown.ds-assistant-message-main-content')
      || el.querySelector('div.ds-markdown')
      || el;
    if (!main) return;
    const text = (main.textContent || '').trim();
    if (!text) return;
    const tool = extractToolCall(text);
    if (!tool) {
      const norm = normalizeToolText(text);
      // Wait while JSON or DSML tool call is still streaming
      if (/"tool"\s*:/.test(norm) || /DSML|invoke\s+name\s*=/i.test(text)) {
        log('waiting for complete tool call', mk);
        return;
      }
      handled.add(mk);
      return;
    }
    // Allow short trailing text (model chatter); only ignore if large tail after JSON
    const tail = text.slice(text.lastIndexOf(tool.full) + tool.full.length).trim();
    if (CONFIG.callMustBeLast && tail.length > 80) {
      log('ignored: long text after tool call', mk, tail.slice(0, 40));
      // still execute — model often adds a short note; only skip if huge
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

  let lastTickAt = 0;
  let tickScheduled = false;

  function runTick() {
    tickScheduled = false;
    lastTickAt = performance.now();
    try {
      const msgs = document.querySelectorAll('div.ds-message');
      hideUserToolResults(msgs);
      restoreCollapsed(msgs);
      pruneEmptyToolChips();
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

  const observer = new MutationObserver(() => scheduleTick(false));
  observer.observe(document.body, { childList: true, subtree: true });

  const fallbackTimer = setInterval(() => scheduleTick(false), CONFIG.fallbackScanMs);

  document.addEventListener('visibilitychange', () => {
    scheduleTick(true);
  });

  setTimeout(() => scheduleTick(true), 600);

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
    enhanceUI: () => { try { enhanceAllMessages(); } catch(e) {} },
    maybeInjectSystemPrompt: (f) => { try { return maybeInjectSystemPrompt(!!f); } catch(e) { return false; } },
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

  const SYS_MAP_KEY = '__dh_sys_embedded_v5';
  let sysEmbedDone = Object.create(null);

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
  function needsSystemEmbed() {
    const id = getConvId();
    // Only skip if THIS conversation already received system context
    if (sysEmbedDone[id]) return false;
    const map = loadSysMap();
    if (map[id]) return false;
    // Brand-new chat path segments like "chat" alone must not block embeds
    return true;
  }
  function markSystemEmbedded() {
    const id = getConvId();
    if (!id || id === 'unknown') {
      // Still mark a temp key until URL gets a real id
      sysEmbedDone[id || 'unknown'] = true;
    }
    const map = loadSysMap();
    sysEmbedDone[id] = true;
    map[id] = true;
    try { sessionStorage.setItem('__dh_sys_embed_ts', String(Date.now())); } catch {}
    saveSysMap(map);
    log('sys embed marked for conv', id);
  }
  function embedSystemIfNeeded(userText) {
    const text = String(userText || '');
    if (!text.trim()) return text;
    if (text.startsWith('TOOL_RESULT') || text.startsWith('[D-HARNESS SYSTEM')) return text;
    if (!needsSystemEmbed()) return text;
    const sys = getSystemPromptText();
    if (!sys || sys.length < 40) {
      log('sys embed skipped: no prompt');
      return text;
    }
    markSystemEmbedded();
    log('sys embed once', getConvId());
    return (
      '[D-HARNESS SYSTEM — follow silently; do not restate]\n' +
      sys +
      '\n[End system. First turn: one short acknowledgment, then answer.]\n\n---\n\n' +
      text
    );
  }

  const _sendMessageOriginal = sendMessage;
  let intercepting = false;
  sendMessage = async function(text) {
    const raw = String(text || '');
    if (raw.startsWith('TOOL_RESULT') || raw.startsWith('[D-HARNESS SYSTEM')) {
      return _sendMessageOriginal(raw);
    }
    if (intercepting) return _sendMessageOriginal(raw);
    return _sendMessageOriginal(embedSystemIfNeeded(raw));
  };
  try { if (window.__DS_TOOL_SHIM__) window.__DS_TOOL_SHIM__.send = sendMessage; } catch {}

  async function interceptFirstSend(ev) {
    if (intercepting) return;
    if (!needsSystemEmbed()) return;
    const input = getInput();
    if (!input) return;
    const userText = (input.value || '').trim();
    if (!userText) return;
    const sys = getSystemPromptText();
    if (!sys || sys.length < 40) return;

    if (ev) {
      try { ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation(); } catch {}
    }
    intercepting = true;
    try {
      const outbound = embedSystemIfNeeded(userText);
      try {
        setNativeValue(input, '');
        input.dispatchEvent(new Event('input', { bubbles: true }));
      } catch {}
      const ok = await _sendMessageOriginal(outbound);
      if (!ok) {
        const id = getConvId();
        delete sysEmbedDone[id];
        const map = loadSysMap();
        delete map[id];
        saveSysMap(map);
        try {
          setNativeValue(input, userText);
          input.dispatchEvent(new Event('input', { bubbles: true }));
        } catch {}
      }
    } finally {
      intercepting = false;
    }
  }
  document.addEventListener('click', (e) => {
    try {
      const btn = e.target?.closest?.(SEND_SELECTOR) || e.target?.closest?.('div[role="button"].ds-button--primary');
      if (!btn) return;
      if (needsSystemEmbed()) interceptFirstSend(e);
    } catch {}
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' || e.shiftKey || e.isComposing) return;
    const input = getInput();
    if (!input || e.target !== input) return;
    if (needsSystemEmbed()) interceptFirstSend(e);
  }, true);

  let __dhLastPath = location.pathname;
  let __dhLastConv = getConvId();
  setInterval(() => {
    const path = location.pathname;
    const conv = getConvId();
    if (path === __dhLastPath && conv === __dhLastConv) return;
    const prevConv = __dhLastConv;
    __dhLastPath = path;
    __dhLastConv = conv;
    // New conversation id → allow system embed again (do NOT carry mark)
    if (conv && conv !== prevConv && conv !== 'unknown') {
      log('new conv detected', prevConv, '→', conv, '(system embed allowed)');
      // Reset tool chain between chats
      toolChain = null;
    }
  }, 400);

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
      const id = getConvId();
      delete sysEmbedDone[id];
      const map = loadSysMap();
      delete map[id];
      saveSysMap(map);
      showToast('System will attach on next send');
      return true;
    }
    return false;
  }

  function applyThemeTokens() {
    const root = document.documentElement;
    const claude = !!document.getElementById('claude-ds-theme-v3');
    const dark = root.classList.contains('dark') || document.body.classList.contains('dark') ||
      (window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches);
    root.style.setProperty('--dh-card-bg', dark ? (claude ? '#2a2825' : '#1e1f24') : (claude ? '#faf8f5' : '#ffffff'));
    root.style.setProperty('--dh-card-fg', dark ? '#e8eaed' : '#1a1a1a');
    root.style.setProperty('--dh-card-border', dark ? 'rgba(255,255,255,0.12)' : 'rgba(0,0,0,0.10)');
    root.style.setProperty('--dh-accent', claude ? '#da7756' : '#4d6bfe');
    root.style.setProperty('--dh-bar', claude ? '#da7756' : '#4d6bfe');
    root.style.setProperty('--dh-muted', dark ? '#9aa0a6' : '#5f6368');
    root.style.setProperty('--dh-code-bg', dark ? (claude ? '#1f1e1b' : '#2a2b30') : (claude ? '#f3f1ec' : '#f1f3f4'));
  }
  applyThemeTokens();
  setInterval(applyThemeTokens, 2000);

  function chartWrap(title, svg) {
    return `<div class="dh-ui-card dh-chart" data-dh-chart="1"><h4>${esc(title || 'Chart')}</h4>${svg}</div>`;
  }
  function svgBarChart(labels, values, opts) {
    opts = opts || {};
    const w = 360, h = opts.hbar ? Math.max(120, labels.length * 28 + 40) : 180, pad = 28;
    const nums = values.map(Number).map(n => (isNaN(n) ? 0 : n));
    const max = Math.max(...nums, 1);
    let body = '';
    if (opts.hbar) {
      const rowH = (h - pad * 2) / Math.max(nums.length, 1);
      nums.forEach((n, i) => {
        const bw = ((w - pad * 2 - 40) * n) / max;
        const y = pad + i * rowH + 4;
        body += `<text x="4" y="${y + 12}" text-anchor="start">${esc(String(labels[i] ?? i).slice(0, 10))}</text>`;
        body += `<rect class="bar" x="${pad + 36}" y="${y}" width="${Math.max(2, bw)}" height="${Math.max(8, rowH - 10)}" rx="4"/>`;
      });
    } else {
      const bw = (w - pad * 2) / Math.max(nums.length, 1);
      nums.forEach((n, i) => {
        const bh = ((h - pad * 2) * n) / max;
        const x = pad + i * bw + 4;
        const y = h - pad - bh;
        body += `<rect class="bar" x="${x}" y="${y}" width="${Math.max(4, bw - 8)}" height="${Math.max(0, bh)}" rx="4"/>`;
        body += `<text x="${x + bw / 2}" y="${h - 8}" text-anchor="middle">${esc(String(labels[i] ?? i).slice(0, 8))}</text>`;
      });
    }
    return chartWrap(opts.title || 'Bar', `<svg viewBox="0 0 ${w} ${h}" role="img">
      <line class="axis" x1="${pad}" y1="${h - pad}" x2="${w - 8}" y2="${h - pad}"/>
      <line class="axis" x1="${pad}" y1="${pad}" x2="${pad}" y2="${h - pad}"/>
      ${body}</svg>`);
  }
  function svgLineChart(labels, values, opts) {
    opts = opts || {};
    const w = 360, h = 180, pad = 28;
    const nums = values.map(Number).map(n => (isNaN(n) ? 0 : n));
    const max = Math.max(...nums, 1);
    const min = Math.min(...nums, 0);
    const span = Math.max(max - min, 1);
    const n = Math.max(nums.length, 1);
    const pts = nums.map((v, i) => {
      const x = pad + (i * (w - pad * 2)) / Math.max(n - 1, 1);
      const y = h - pad - ((v - min) / span) * (h - pad * 2);
      return [x, y];
    });
    const poly = pts.map(p => p[0].toFixed(1) + ',' + p[1].toFixed(1)).join(' ');
    let area = '';
    if (opts.area && pts.length) {
      const base = h - pad;
      area = `<polygon class="area" fill="var(--dh-bar)" fill-opacity="0.2" points="${pts[0][0]},${base} ${poly} ${pts[pts.length-1][0]},${base}"/>`;
    }
    const dots = pts.map(p => `<circle cx="${p[0]}" cy="${p[1]}" r="3" fill="var(--dh-bar)"/>`).join('');
    const labs = labels.map((lb, i) => {
      const x = pad + (i * (w - pad * 2)) / Math.max(n - 1, 1);
      return `<text x="${x}" y="${h - 8}" text-anchor="middle">${esc(String(lb ?? i).slice(0, 8))}</text>`;
    }).join('');
    return chartWrap(opts.title || (opts.area ? 'Area' : 'Line'), `<svg viewBox="0 0 ${w} ${h}" role="img">
      <line class="axis" x1="${pad}" y1="${h - pad}" x2="${w - 8}" y2="${h - pad}"/>
      <line class="axis" x1="${pad}" y1="${pad}" x2="${pad}" y2="${h - pad}"/>
      ${area}<polyline fill="none" stroke="var(--dh-bar)" stroke-width="2.5" points="${poly}"/>${dots}${labs}</svg>`);
  }
  function svgPieChart(labels, values, opts) {
    opts = opts || {};
    const nums = values.map(Number).map(n => (isNaN(n) || n < 0 ? 0 : n));
    const sum = nums.reduce((a, b) => a + b, 0) || 1;
    const cx = 100, cy = 100, r = 78;
    let angle = -Math.PI / 2;
    const colors = ['#4f46e5','#06b6d4','#22c55e','#f59e0b','#ef4444','#a855f7','#14b8a6','#f97316'];
    let paths = '';
    nums.forEach((n, i) => {
      const a = (n / sum) * Math.PI * 2;
      const x1 = cx + r * Math.cos(angle), y1 = cy + r * Math.sin(angle);
      angle += a;
      const x2 = cx + r * Math.cos(angle), y2 = cy + r * Math.sin(angle);
      const large = a > Math.PI ? 1 : 0;
      paths += `<path d="M${cx},${cy} L${x1},${y1} A${r},${r} 0 ${large} 1 ${x2},${y2} Z" fill="${colors[i % colors.length]}" opacity="0.9"/>`;
    });
    const legend = labels.map((lb, i) =>
      `<div style="display:flex;align-items:center;gap:6px;font-size:11px;margin:2px 0">
        <span style="width:10px;height:10px;border-radius:2px;background:${colors[i % colors.length]}"></span>
        ${esc(String(lb ?? i))} (${nums[i]})
      </div>`).join('');
    return chartWrap(opts.title || 'Pie',
      `<div style="display:flex;gap:12px;align-items:center;flex-wrap:wrap">
        <svg viewBox="0 0 200 200" width="160" height="160" role="img">${paths}</svg>
        <div>${legend}</div>
      </div>`);
  }
  function parseChartBlock(body) {
    try {
      const j = JSON.parse(body);
      const type = (j.type || 'bar').toLowerCase();
      const title = j.title || j.name || '';
      let labels, values;
      if (j.labels && j.values) { labels = j.labels; values = j.values; }
      else if (Array.isArray(j.data)) {
        labels = j.data.map(d => d.label ?? d.x ?? d.name);
        values = j.data.map(d => d.value ?? d.y ?? d.v);
      } else return null;
      const opts = { title };
      if (type === 'line') return svgLineChart(labels, values, opts);
      if (type === 'area') return svgLineChart(labels, values, Object.assign({ area: true }, opts));
      if (type === 'pie' || type === 'donut') return svgPieChart(labels, values, opts);
      if (type === 'hbar' || type === 'horizontal') return svgBarChart(labels, values, Object.assign({ hbar: true }, opts));
      return svgBarChart(labels, values, opts);
    } catch {}
    const labels = [], values = [];
    body.split(/\n+/).forEach(line => {
      const m = line.trim().match(/^([^,]+),\s*([0-9.]+)\s*$/);
      if (m) { labels.push(m[1].trim()); values.push(Number(m[2])); }
    });
    return values.length ? svgBarChart(labels, values, {}) : null;
  }
  function isChartComplete(text) {
    const s = text.trim();
    if (!s) return false;
    if (s.startsWith('{')) {
      try { JSON.parse(s); return true; } catch { return false; }
    }
    return s.split('\n').filter(l => /,\s*[0-9.]+/.test(l)).length >= 1;
  }

  function isHtmlComplete(html) {
    const s = (html || '').trim();
    if (s.length < 20) return false;
    if (/^<!DOCTYPE/i.test(s) || /<html[\s>]/i.test(s)) {
      return /<\/html>/i.test(s);
    }
    const opens = (s.match(/<[a-zA-Z][^>]*>/g) || []).length;
    const closes = (s.match(/<\/[a-zA-Z]+>/g) || []).length;
    return opens >= 1 && closes >= 1 && s.length > 40;
  }

  function wrapArtifactHtml(html) {
    let body = (html || '').trim();
    body = body.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
    const dark = document.documentElement.classList.contains('dark') || document.body.classList.contains('dark');
    const pageBg = dark ? '#1e1f24' : '#ffffff';
    const pageFg = dark ? '#e8eaed' : '#1a1a1a';
    const bridge = `<script>window.dh={toast:function(t){parent.postMessage({type:'dh-artifact',action:'toast',text:String(t)},'*');}};</script>`;
    if (/^<!DOCTYPE/i.test(body) || /<html[\s>]/i.test(body)) {
      if (/<\/body>/i.test(body)) return body.replace(/<\/body>/i, bridge + '</body>');
      return body + bridge;
    }
    return `<!DOCTYPE html><html><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1"/>
<style>html,body{margin:0;padding:12px;font-family:system-ui,-apple-system,sans-serif;background:${pageBg};color:${pageFg};}a{color:#4d6bfe;}</style>
</head><body>${body}${bridge}</body></html>`;
  }

  function loadIframe(ifr, html) {
    const srcdoc = wrapArtifactHtml(html);
    try {
      if (ifr._dhUrl) {
        try { URL.revokeObjectURL(ifr._dhUrl); } catch {}
      }
      const blob = new Blob([srcdoc], { type: 'text/html;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      ifr._dhUrl = url;
      ifr.removeAttribute('srcdoc');
      ifr.src = url;
    } catch {
      try {
        ifr.srcdoc = srcdoc;
      } catch (e) {
        console.warn('artifact load', e);
      }
    }
  }

  function openArtifactFullscreen(html, title) {
    const overlay = document.createElement('div');
    overlay.className = 'dh-artifact-fs';
    overlay.innerHTML = `<div class="dh-artifact-bar"><b>${esc(title || 'Artifact')}</b>
      <button type="button" data-close>Close</button></div>
      <div class="dh-artifact-frame-wrap"></div>`;
    const wrap = overlay.querySelector('.dh-artifact-frame-wrap');
    const ifr = document.createElement('iframe');
    ifr.setAttribute('sandbox', 'allow-scripts allow-forms allow-modals allow-popups');
    ifr.setAttribute('referrerpolicy', 'no-referrer');
    loadIframe(ifr, html);
    wrap.appendChild(ifr);
    overlay.querySelector('[data-close]').onclick = () => {
      try { if (ifr._dhUrl) URL.revokeObjectURL(ifr._dhUrl); } catch {}
      overlay.remove();
    };
    document.body.appendChild(overlay);
  }

  function showLoadingCard(pre, kind) {
    if (pre.getAttribute('data-dh-loading') === '1') return;
    pre.setAttribute('data-dh-loading', '1');
    let card = pre.previousElementSibling;
    if (card && card.getAttribute('data-dh-ph') === '1') return;
    card = document.createElement('div');
    card.className = kind === 'chart' ? 'dh-chart-loading' : 'dh-artifact';
    card.setAttribute('data-dh-ph', '1');
    if (kind === 'chart') {
      card.textContent = 'Building chart…';
    } else {
      card.innerHTML = `<div class="dh-artifact-bar"><b>${kind === 'simulation' ? 'Simulation' : 'Artifact'}</b></div>
        <div class="dh-artifact-loading"><div class="spin"></div><div>Creating…</div></div>`;
    }
    pre.style.display = 'none';
    pre.parentNode?.insertBefore(card, pre);
  }

  function removeLoadingCard(pre) {
    const card = pre.previousElementSibling;
    if (card && card.getAttribute('data-dh-ph') === '1') card.remove();
    pre.style.display = '';
    pre.removeAttribute('data-dh-loading');
  }

  function renderArtifactCard(pre, html, kind) {
    removeLoadingCard(pre);
    if (pre.getAttribute('data-dh-done') === 'artifact') return;
    const title = kind === 'simulation' ? 'Simulation' : (kind === 'interactive' ? 'Interactive' : 'HTML artifact');
    const card = document.createElement('div');
    card.className = 'dh-artifact' + (kind === 'simulation' || kind === 'interactive' ? ' dh-artifact-tall' : '');
    card.innerHTML = `<div class="dh-artifact-bar"><span><b>${title}</b></span>
      <span class="acts">
        <button type="button" data-act="reload">Reload</button>
        <button type="button" data-act="fs">Fullscreen</button>
      </span></div>
      <div class="dh-artifact-frame-wrap"></div>`;
    const wrap = card.querySelector('.dh-artifact-frame-wrap');
    const ifr = document.createElement('iframe');
    ifr.setAttribute('sandbox', 'allow-scripts allow-forms allow-modals allow-popups');
    ifr.setAttribute('referrerpolicy', 'no-referrer');
    const loading = document.createElement('div');
    loading.className = 'dh-artifact-loading';
    loading.innerHTML = '<div class="spin"></div><div>Loading…</div>';
    wrap.appendChild(loading);
    wrap.appendChild(ifr);
    ifr.style.opacity = '0';
    ifr.onload = () => {
      try { loading.remove(); } catch {}
      ifr.style.opacity = '1';
    };
    setTimeout(() => {
      try { loading.remove(); } catch {}
      ifr.style.opacity = '1';
    }, 1200);
    loadIframe(ifr, html);
    card.querySelector('[data-act="reload"]').onclick = () => {
      ifr.style.opacity = '0';
      loadIframe(ifr, html);
      setTimeout(() => { ifr.style.opacity = '1'; }, 400);
    };
    card.querySelector('[data-act="fs"]').onclick = () => openArtifactFullscreen(html, title);
    pre.setAttribute('data-dh-done', 'artifact');
    pre.replaceWith(card);
  }

  function enhanceCodeBlocks(root) {
    if (!CONFIG.uiEnhance || !root) return;
    const pres = Array.from(root.querySelectorAll('pre'));
    for (const pre of pres) {
      if (pre.getAttribute('data-dh-done')) continue;
      if (pre.closest('.dh-artifact') || pre.closest('#__ds_shim_panel')) continue;
      const codeEl = pre.querySelector('code') || pre;
      const text = (codeEl.textContent || '').trim();
      if (!text) continue;
      const lang = ((codeEl.className || '') + ' ' + (pre.className || '')).toLowerCase();

      const isArtifactLang = /html-artifact|language-html|\bhtml\b|artifact|simulation|interactive/.test(lang) ||
        (/^\s*<(!doctype|html|div|section|canvas|svg|body)/i.test(text) && text.length > 30);
      if (isArtifactLang) {
        const kind = /simulation/.test(lang) ? 'simulation' : (/interactive/.test(lang) ? 'interactive' : 'html');
        if (!isHtmlComplete(text) || isGenerating()) {
          if (!isHtmlComplete(text)) {
            showLoadingCard(pre, kind);
            continue;
          }
        }
        try { renderArtifactCard(pre, text, kind); } catch (e) { console.warn('artifact', e); }
        continue;
      }

      const isChartLang = /chart|dh-chart/.test(lang) ||
        (text.startsWith('{') && /"values"\s*:/.test(text)) ||
        (/^[^,\n]+,\s*[0-9.]+/m.test(text) && text.split('\n').filter(l => /,\s*[0-9.]+/.test(l)).length >= 2 && !text.includes('function'));
      if (isChartLang) {
        if (!isChartComplete(text)) {
          showLoadingCard(pre, 'chart');
          continue;
        }
        const html = parseChartBlock(text);
        if (html) {
          removeLoadingCard(pre);
          const div = document.createElement('div');
          div.innerHTML = html;
          pre.setAttribute('data-dh-done', 'chart');
          pre.replaceWith(div.firstChild);
        }
        continue;
      }

      pre.setAttribute('data-dh-done', 'code');
    }
  }

  let enhanceQueued = false;
  function enhanceAllMessages() {
    if (!CONFIG.uiEnhance || enhanceQueued) return;
    enhanceQueued = true;
    requestAnimationFrame(() => {
      enhanceQueued = false;
      applyThemeTokens();
      try {
        document.querySelectorAll('div.ds-message').forEach(el => {
          try { enhanceCodeBlocks(el); } catch {}
        });
      } catch {}
    });
  }

  window.addEventListener('message', (ev) => {
    const d = ev && ev.data;
    if (!d || d.type !== 'dh-artifact') return;
    if (d.action === 'toast') showToast(String(d.text || ''), 2000);
  });

  let enhanceTimer = setInterval(enhanceAllMessages, 350);
  setInterval(() => {
    clearInterval(enhanceTimer);
    enhanceTimer = setInterval(enhanceAllMessages, isGenerating() ? 300 : 1200);
  }, 2000);
  const uiObs = new MutationObserver(() => enhanceAllMessages());
  try { uiObs.observe(document.body, { childList: true, subtree: true }); } catch {}
  enhanceAllMessages();

  panel.querySelector('[data-opt="uiEnhance"]')?.addEventListener('change', (e) => {
    CONFIG.uiEnhance = e.target.checked;
    try { localStorage.setItem('__dh_ui_enhance', CONFIG.uiEnhance ? '1' : '0'); } catch {}
  });
  try {
    CONFIG.uiEnhance = localStorage.getItem('__dh_ui_enhance') !== '0';
    const cb = panel.querySelector('[data-opt="uiEnhance"]');
    if (cb) cb.checked = !!CONFIG.uiEnhance;
  } catch { CONFIG.uiEnhance = true; }

  refreshCounts();
  console.log(`%c[shim] DeepSeek Tool Shim v${VERSION} loaded`, 'color:#0af;font-weight:bold');
  console.log('API: __DS_TOOL_SHIM__.stats() | .inspect() | .showPanel()');
})();
