/** D-Harness M3 theme — Claude-inspired, follows system light/dark. Always on. */
(function () {
  'use strict';
  var ID = 'dh-m3-theme';
  
  function buildCss() {
    return [
      '',
      ':root{color-scheme:light dark;',
      '--m3-bg:#f3eee6;--m3-surface:#fffcf7;--m3-text:#1c1917;--m3-muted:#78716c;',
      '--m3-accent:#c2410c;--m3-accent-soft:rgba(194,65,12,.12);--m3-border:rgba(28,25,23,.1);',
      '--m3-radius:16px;--m3-font:system-ui,-apple-system,sans-serif}',
      '@media (prefers-color-scheme:dark){:root{',
      '--m3-bg:#1c1917;--m3-surface:#292524;--m3-text:#fafaf9;--m3-muted:#a8a29e;',
      '--m3-accent:#fb923c;--m3-accent-soft:rgba(251,146,60,.16);--m3-border:rgba(250,250,249,.1)}}',
      'html.dark,body.dark,[data-theme=dark]{',
      '--m3-bg:#1c1917;--m3-surface:#292524;--m3-text:#fafaf9;--m3-muted:#a8a29e;',
      '--m3-accent:#fb923c;--m3-accent-soft:rgba(251,146,60,.16);--m3-border:rgba(250,250,249,.1)}',
      'html,body{background:var(--m3-bg)!important;color:var(--m3-text)!important;',
      'font-family:var(--m3-font)!important}',
      'body,body *{border-color:var(--m3-border)}',
      /* surfaces */
      'aside,nav,[class*=sidebar],[class*=SideBar],[class*=side-bar]{',
      'background:var(--m3-surface)!important;color:var(--m3-text)!important;',
      'border-color:var(--m3-border)!important}',
      'header,[class*=header],[class*=Header]{background:var(--m3-bg)!important;color:var(--m3-text)!important;',
      'border-color:var(--m3-border)!important}',
      'main,[role=main],[class*=chat],[class*=Chat],[class*=message],[class*=Message]{',
      'background:transparent!important;color:var(--m3-text)!important}',
      /* composer */
      'textarea,[contenteditable=true],.ds-textarea,[class*=textarea],[class*=composer],[class*=input]{',
      'background:var(--m3-surface)!important;color:var(--m3-text)!important;',
      'border:1px solid var(--m3-border)!important;border-radius:var(--m3-radius)!important;',
      'font-family:var(--m3-font)!important}',
      /* buttons — soft M3 */
      'button,[role=button],.ds-button,[class*=button]{',
      'font-family:var(--m3-font)!important;border-radius:999px!important}',
      '.ds-button--primary,[class*=primary][role=button]{',
      'background:var(--m3-accent)!important;color:#fff!important;border:none!important}',
      /* cards / bubbles */
      '[class*=bubble],[class*=card],[class*=Card],.ds-message{',
      'border-radius:var(--m3-radius)!important}',
      /* links & accents */
      'a{color:var(--m3-accent)!important}',
      '::selection{background:var(--m3-accent)!important;color:#fff!important}',
      /* scrollbars */
      '::-webkit-scrollbar{width:8px;height:8px}',
      '::-webkit-scrollbar-thumb{background:var(--m3-border);border-radius:4px}',
      /* code */
      'pre,code{background:var(--m3-surface)!important;color:var(--m3-text)!important;',
      'border-radius:12px!important;font-family:ui-monospace,monospace!important}',
      /* toggle chips near Search/DeepThink */
      '.ds-toggle-button,[class*=toggle-button]{border-radius:999px!important;',
      'border:1px solid var(--m3-border)!important}',
      '.ds-toggle-button--selected,[class*=toggle-button][class*=selected]{',
      'background:var(--m3-accent-soft)!important;color:var(--m3-accent)!important;',
      'border-color:var(--m3-accent)!important}'
    ].join('');
  }

  function inject() {
    var s = document.getElementById(ID);
    if (!s) {
      s = document.createElement('style');
      s.id = ID;
      (document.head || document.documentElement).appendChild(s);
    }
    s.textContent = buildCss();
  }

  inject();
  // DeepSeek SPA may drop head nodes
  var obs = new MutationObserver(function () {
    if (!document.getElementById(ID)) inject();
  });
  try {
    obs.observe(document.documentElement, { childList: true, subtree: true });
  } catch (e) {}
  setInterval(function () { if (!document.getElementById(ID)) inject(); }, 2000);

  window.__DH_M3_THEME__ = { version: '1.0', reinject: inject };
  console.log('[D-Harness] M3 Claude theme on (system light/dark)');
})();
