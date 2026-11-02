(function () {
  if (window.__DEEPEASE_THEME__) return;
  window.__DEEPEASE_THEME__ = { version: '1.0.0' };
  var CSS = `
  :root {
    --de-accent: #5B5FC7;
    --de-accent2: #FF6B6B;
    --de-mint: #00B4A6;
    --de-radius: 16px;
    --de-shadow: 0 8px 28px rgba(91,95,199,0.12);
  }
  body { font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif !important; }
  /* Soft app chrome */
  #root, body { background: linear-gradient(180deg, #f7f5ff 0%, #fff 40%) !important; }
  body.dark, body.en_US.dark, html.dark body {
    background: linear-gradient(180deg, #14131a 0%, #1b1a22 50%) !important;
  }
  /* Messages */
  .ds-message {
    border-radius: var(--de-radius) !important;
    transition: transform .15s ease, box-shadow .2s ease;
  }
  .d29f3d7d.ds-message {
    background: linear-gradient(135deg, #5B5FC7 0%, #7B7FE0 100%) !important;
    color: #fff !important;
    box-shadow: var(--de-shadow) !important;
    padding: 10px 14px !important;
  }
  .d29f3d7d.ds-message * { color: #fff !important; }
  .ds-assistant-message-main-content {
    background: rgba(255,255,255,0.9) !important;
    border: 1px solid rgba(91,95,199,0.12) !important;
    border-radius: var(--de-radius) !important;
    padding: 12px 14px !important;
    box-shadow: 0 4px 16px rgba(0,0,0,0.04) !important;
  }
  body.dark .ds-assistant-message-main-content,
  body.en_US.dark .ds-assistant-message-main-content {
    background: rgba(32,31,40,0.95) !important;
    border-color: rgba(192,193,255,0.12) !important;
  }
  /* Composer */
  textarea._27c9245, textarea.ds-scroll-area {
    border-radius: 18px !important;
    border: 1px solid rgba(91,95,199,0.2) !important;
    padding: 12px 14px !important;
    background: #fff !important;
  }
  body.dark textarea._27c9245, body.en_US.dark textarea._27c9245 {
    background: #201f28 !important;
    border-color: rgba(192,193,255,0.15) !important;
    color: #eee !important;
  }
  .ds-button--primary.ds-button--filled {
    background: linear-gradient(135deg, #5B5FC7, #00B4A6) !important;
    border-radius: 999px !important;
    box-shadow: 0 6px 16px rgba(91,95,199,0.35) !important;
  }
  /* Hide noisy chrome lightly */
  [class*="footer"], [class*="Footer"] { opacity: 0.85; }
  /* Tool chips from shim — match DeepEase */
  [data-ds-shim-tagline="1"] {
    border-radius: 14px !important;
    border: 1px solid rgba(91,95,199,0.18) !important;
    background: linear-gradient(180deg, rgba(91,95,199,0.08), rgba(0,180,166,0.06)) !important;
  }
  [data-ds-shim-tagline="1"] .ds-shim-chip {
    background: rgba(91,95,199,0.15) !important;
    border-radius: 8px !important;
    color: #3d3f9a !important;
  }
  body.dark [data-ds-shim-tagline="1"] .ds-shim-chip { color: #c0c1ff !important; }
  @keyframes de-fade-in { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
  .ds-message { animation: de-fade-in .25s ease both; }
  `;
  var style = document.createElement('style');
  style.id = '__deepease_theme_css';
  style.textContent = CSS;
  (document.head || document.documentElement).appendChild(style);
  // Subtle top brand bar
  if (!document.getElementById('__deepease_bar')) {
    var bar = document.createElement('div');
    bar.id = '__deepease_bar';
    bar.textContent = 'DeepEase';
    bar.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483000;height:3px;background:linear-gradient(90deg,#5B5FC7,#00B4A6,#FF6B6B);pointer-events:none;';
    document.documentElement.appendChild(bar);
  }
  console.log('[DeepEase] theme applied');
})();
