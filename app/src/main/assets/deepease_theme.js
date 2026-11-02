(function () {
  if (window.__DEEPEASE_THEME__ && window.__DEEPEASE_THEME__.version === '1.1.0') return;
  window.__DEEPEASE_THEME__ = { version: '1.1.0' };

  var old = document.getElementById('__deepease_theme_css');
  if (old) old.remove();

  var CSS = `
/* DeepEase 1.1 — personal assistant chrome over DeepSeek mobile */

:root {
  --de-p: #5B5FC7;
  --de-p2: #7C83F5;
  --de-mint: #14B8A6;
  --de-coral: #F97366;
  --de-user: #5B5FC7;
  --de-asst-bg: #ffffff;
  --de-asst-border: rgba(91,95,199,0.10);
  --de-soft: #F4F2FF;
  --de-text: #1B1B23;
  --de-muted: #6B6B7B;
  --de-r: 18px;
  --de-shadow: 0 4px 18px rgba(28, 25, 45, 0.06);
}

html, body {
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif !important;
  -webkit-font-smoothing: antialiased;
}

/* Soft page canvas — do not fight virtual list hard */
body.en_US,
body {
  background-color: var(--de-soft) !important;
}
body.en_US.dark,
body.dark {
  --de-soft: #12121a;
  --de-asst-bg: #1e1e28;
  --de-asst-border: rgba(192,193,255,0.10);
  --de-text: #F2F0FA;
  --de-muted: #A0A0B0;
  background-color: var(--de-soft) !important;
}

/* Top accent only */
#__deepease_bar {
  position: fixed; top: 0; left: 0; right: 0; height: 3px; z-index: 2147483646;
  background: linear-gradient(90deg, var(--de-p), var(--de-mint), var(--de-coral));
  pointer-events: none;
}

/* ---- Messages: surgical, never stretch empty bars ---- */

/* User bubble (from observer: .d29f3d7d.ds-message) */
.d29f3d7d.ds-message {
  background: linear-gradient(135deg, #5B5FC7 0%, #6E74E0 100%) !important;
  color: #fff !important;
  border-radius: 18px 18px 6px 18px !important;
  padding: 10px 14px !important;
  max-width: 88% !important;
  margin-left: auto !important;
  box-shadow: 0 6px 16px rgba(91,95,199,0.22) !important;
  border: none !important;
  width: auto !important;
  display: inline-block !important;
  box-sizing: border-box !important;
}
.d29f3d7d.ds-message,
.d29f3d7d.ds-message * {
  color: #fff !important;
}
.d29f3d7d.ds-message a { text-decoration: underline !important; }

/* Parent of user message: keep layout, no full-width purple strip */
div._9663006 {
  display: flex !important;
  justify-content: flex-end !important;
  background: transparent !important;
  padding: 4px 10px !important;
}

/* Assistant content */
.ds-markdown.ds-assistant-message-main-content {
  background: var(--de-asst-bg) !important;
  color: var(--de-text) !important;
  border: 1px solid var(--de-asst-border) !important;
  border-radius: 18px 18px 18px 6px !important;
  padding: 12px 14px !important;
  max-width: 92% !important;
  box-shadow: var(--de-shadow) !important;
  line-height: 1.55 !important;
}
.ds-assistant-message-main-content,
.ds-assistant-message-main-content * {
  color: inherit;
}
.ds-message:not(.d29f3d7d) {
  background: transparent !important;
  box-shadow: none !important;
}

/* Action row under messages — quieter */
.ds-message + div [class*="icon"],
button[class*="ds-icon-button"] {
  opacity: 0.55;
}
button[class*="ds-icon-button"]:hover { opacity: 1; }

/* Composer dock */
textarea._27c9245,
textarea.ds-scroll-area {
  border-radius: 20px !important;
  border: 1px solid rgba(91,95,199,0.18) !important;
  background: #fff !important;
  padding: 12px 16px !important;
  min-height: 44px !important;
  box-shadow: 0 2px 12px rgba(28,25,45,0.04) !important;
  color: var(--de-text) !important;
}
body.dark textarea._27c9245,
body.en_US.dark textarea._27c9245 {
  background: #1e1e28 !important;
  border-color: rgba(192,193,255,0.14) !important;
  color: #F2F0FA !important;
}
textarea._27c9245::placeholder {
  color: var(--de-muted) !important;
}

/* Primary send */
.ds-button--primary.ds-button--filled {
  background: linear-gradient(135deg, var(--de-p), var(--de-p2)) !important;
  border-radius: 999px !important;
  box-shadow: 0 6px 14px rgba(91,95,199,0.28) !important;
  border: none !important;
}

/* Soften DeepThink / Search for a calmer personal surface */
.ds-button--secondary,
button.ds-button--secondary {
  border-radius: 999px !important;
  opacity: 0.9;
}

/* Footer disclaimer smaller */
[class*="ai-generated"], footer, .ds-footer {
  font-size: 11px !important;
  opacity: 0.65 !important;
}

/* Tool chips from shim */
[data-ds-shim-tagline="1"] {
  border-radius: 14px !important;
  border: 1px solid rgba(91,95,199,0.14) !important;
  background: linear-gradient(180deg, rgba(91,95,199,0.07), rgba(20,184,166,0.05)) !important;
  box-shadow: var(--de-shadow) !important;
  margin: 6px 10px !important;
  max-width: 92% !important;
}
[data-ds-shim-tagline="1"] .ds-shim-chip {
  background: rgba(91,95,199,0.12) !important;
  color: #3d4198 !important;
  border-radius: 8px !important;
  font-size: 12px !important;
}
body.dark [data-ds-shim-tagline="1"] .ds-shim-chip,
body.en_US.dark [data-ds-shim-tagline="1"] .ds-shim-chip {
  color: #c5c7ff !important;
}

/* Gentle entrance */
@keyframes deIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
}
.ds-message { animation: deIn 0.22s ease both; }

/* Sidebar slight blur when open */
.ds-scroll-area { scrollbar-width: thin; }
`;

  var style = document.createElement('style');
  style.id = '__deepease_theme_css';
  style.textContent = CSS;
  (document.head || document.documentElement).appendChild(style);

  if (!document.getElementById('__deepease_bar')) {
    var bar = document.createElement('div');
    bar.id = '__deepease_bar';
    document.documentElement.appendChild(bar);
  }

  console.log('[DeepEase] theme 1.1.0 applied');
})();
