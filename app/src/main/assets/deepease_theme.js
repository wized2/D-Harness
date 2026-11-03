(function () {
  // DeepEase input-only polish — does not restyle messages or page chrome
  if (window.__DEEPEASE_THEME__ && window.__DEEPEASE_THEME__.version === '1.1.4-input') return;
  window.__DEEPEASE_THEME__ = { version: '1.1.4-input', scope: 'input-only' };

  try {
    var old = document.getElementById('__deepease_theme_css');
    if (old) old.remove();
    var bar = document.getElementById('__deepease_bar');
    if (bar) bar.remove();
  } catch (e) {}

  var CSS = [
    '/* DeepEase: composer / input dock only */',
    /* Outer dock around composer (mobile) */
    'div._871cbca {',
    '  padding-bottom: max(8px, env(safe-area-inset-bottom, 0px)) !important;',
    '}',
    /* Focused / idle composer shell */
    'div._77cefa5 {',
    '  border-radius: 24px !important;',
    '  border: 1px solid rgba(0,0,0,0.08) !important;',
    '  background: rgba(255,255,255,0.96) !important;',
    '  box-shadow: 0 4px 20px rgba(0,0,0,0.05) !important;',
    '  transition: box-shadow .15s ease, border-color .15s ease !important;',
    '  overflow: hidden !important;',
    '}',
    'div._77cefa5.focused, div._77cefa5._3d616d3.focused {',
    '  border-color: rgba(0,0,0,0.12) !important;',
    '  box-shadow: 0 6px 24px rgba(0,0,0,0.07) !important;',
    '}',
    'body.dark div._77cefa5, body.en_US.dark div._77cefa5 {',
    '  background: rgba(40,40,46,0.96) !important;',
    '  border-color: rgba(255,255,255,0.10) !important;',
    '  box-shadow: 0 4px 20px rgba(0,0,0,0.25) !important;',
    '}',
    /* Textarea */
    'textarea._27c9245, textarea.ds-scroll-area._27c9245 {',
    '  border: none !important;',
    '  outline: none !important;',
    '  box-shadow: none !important;',
    '  background: transparent !important;',
    '  border-radius: 0 !important;',
    '  padding: 12px 14px !important;',
    '  min-height: 44px !important;',
    '  font-size: 16px !important;',
    '  line-height: 1.4 !important;',
    '}',
    'textarea._27c9245::placeholder {',
    '  opacity: 0.55 !important;',
    '}',
    /* Inner action row under textarea */
    'div._77cefa5 .ds-button {',
    '  border-radius: 999px !important;',
    '}',
    'div._77cefa5 .ds-button--primary.ds-button--filled {',
    '  border-radius: 999px !important;',
    '  min-width: 36px !important;',
    '  min-height: 36px !important;',
    '  box-shadow: none !important;',
    '}',
    /* Soften secondary chips (DeepThink / Search) inside dock only */
    'div._77cefa5 .ds-button--secondary {',
    '  border-radius: 999px !important;',
    '  opacity: 0.92 !important;',
    '}',
    /* Reduce clash with keyboard / footer line */
    'div.aaff8b8f {',
    '  background: transparent !important;',
    '}'
  ].join('\n');

  var style = document.createElement('style');
  style.id = '__deepease_theme_css';
  style.setAttribute('data-deepease-scope', 'input-only');
  style.textContent = CSS;
  (document.head || document.documentElement).appendChild(style);
  console.log('[DeepEase] input-only theme 1.1.4');
})();
