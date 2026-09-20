/**
 * Claude Theme for DeepSeek Chat
 * Load as a bookmarklet: javascript:(function(){var s=document.createElement('script');s.src='YOUR_URL_HERE/claude-deepseek-theme.js';document.body.appendChild(s)})()
 */
(function () {
  'use strict';

  // ============================================================
  // 1. CONFIG
  // ============================================================
  const STYLE_ID = 'claude-ds-theme-v3';
  const FONT_ID  = 'claude-ds-fonts-v3';

  const FONT_SERIF = "'Source Serif 4', 'Tiempos Text', 'Iowan Old Style', Georgia, serif";
  const FONT_SANS  = "'Inter', 'Styrene B', system-ui, -apple-system, sans-serif";
  const FONT_MONO  = "'JetBrains Mono', 'SFMono-Regular', Consolas, monospace";

  // Official Anthropic design tokens (from design system)
  const LIGHT = {
    bg:         '#FAF9F5',
    bgSoft:     '#F5F3ED',
    text:       '#1F1915',
    muted:      '#8A8279',
    card:       '#FFFFFE',
    border:     '#EBE8E2',
    accent:     '#DA7756',
    accentHover:'#C4553D',
    accentSoft: 'rgba(218, 119, 86, 0.10)',
    userBubble: '#FFFFFE',
    asstBubble: '#F5F3ED',
    codeBg:     '#1F1D1B',
    codeText:   '#F5F3EF',
  };

  const DARK = {
    bg:         '#1F1F1E',
    bgSoft:     '#242220',
    text:       '#F5F3EF',
    muted:      '#A09D96',
    card:       '#242220',
    border:     '#3A3937',
    accent:     '#DA7756',
    accentHover:'#E88B6A',
    accentSoft: 'rgba(218, 119, 86, 0.15)',
    userBubble: '#373737',
    asstBubble: '#242220',
    codeBg:     '#181715',
    codeText:   '#F5F3EF',
  };

  // ============================================================
  // 2. LOAD FONTS
  // ============================================================
  if (!document.getElementById(FONT_ID)) {
    const link = document.createElement('link');
    link.id = FONT_ID;
    link.rel = 'stylesheet';
    link.href = 'https://fonts.googleapis.com/css2?family=Source+Serif+4:opsz,wght@8..60,400;8..60,500&family=Inter:wght@400;500;580&family=JetBrains+Mono:wght@400;500&display=swap';
    document.head.appendChild(link);
  }

  // ============================================================
  // 3. CSS VARIABLES BUILDER
  // ============================================================
  const vars = (t) => `
    --claude-bg: ${t.bg};
    --claude-bg-soft: ${t.bgSoft};
    --claude-text: ${t.text};
    --claude-muted: ${t.muted};
    --claude-card: ${t.card};
    --claude-border: ${t.border};
    --claude-accent: ${t.accent};
    --claude-accent-hover: ${t.accentHover};
    --claude-accent-soft: ${t.accentSoft};
    --claude-user-bubble: ${t.userBubble};
    --claude-asst-bubble: ${t.asstBubble};
    --claude-code-bg: ${t.codeBg};
    --claude-code-text: ${t.codeText};
  `;

  // ============================================================
  // 4. BUILD CSS
  // ============================================================
  function buildCss() {
    return `
    /* ===== THEME TOKENS (DeepSeek puts theme on <body>) ===== */
    body.light { ${vars(LIGHT)} }
    body.dark  { ${vars(DARK)} }

    /* ===== BASE ===== */
    html, body, #root,
    .cb86951c, .c3ecdb44, ._7780f2e, ._765a5cd, ._2bd7b35 {
      background: var(--claude-bg) !important;
      color: var(--claude-text) !important;
    }
    body { font-family: ${FONT_SANS} !important; }

    /* ===== SIDEBAR ===== */
    .dc04ec1d, .b8812f16.a2f3d50e {
      background: var(--claude-bg-soft) !important;
      border-right: 1px solid var(--claude-border) !important;
    }
    .cddfb2ed, .c3ecdb44 { background: transparent !important; }

    /* Sidebar group / date header */
    ._3098d02 { background: transparent !important; padding: 2px 12px !important; }
    .f3d18f6a {
      color: var(--claude-muted) !important;
      font-family: ${FONT_SANS} !important;
      font-weight: 500 !important;
      font-size: 11px !important;
      background: transparent !important;
      padding: 6px 8px !important;
    }

    /* Sidebar chat item */
    ._546d736 {
      background: transparent !important;
      border-radius: 8px !important;
      color: var(--claude-text) !important;
      transition: background 150ms ease !important;
      margin: 1px 8px !important;
    }
    ._546d736:hover { background: var(--claude-accent-soft) !important; }
    ._546d736.b64fb9ae {
      background: var(--claude-accent-soft) !important;
      color: var(--claude-accent) !important;
    }
    ._546d736.b64fb9ae .c08e6e93 { color: var(--claude-accent) !important; }
    .c08e6e93 {
      color: var(--claude-text) !important;
      font-family: ${FONT_SANS} !important;
      font-size: 14px !important;
      font-weight: 400 !important;
    }
    ._254829d { color: var(--claude-muted) !important; }

    /* "New chat" pill — terracotta fill (Claude style) */
    ._5a8ac7a {
      background: var(--claude-accent) !important;
      color: #FFFFFE !important;
      border: none !important;
      border-radius: 8px !important;
      font-family: ${FONT_SANS} !important;
      font-weight: 500 !important;
      font-size: 14px !important;
      padding: 10px 16px !important;
    }
    ._5a8ac7a:hover { background: var(--claude-accent-hover) !important; }
    ._5a8ac7a .ds-icon { color: #FFFFFE !important; }

    /* User profile row */
    ._2afd28d { color: var(--claude-text) !important; padding: 8px 12px !important; }
    ._9d8da05 { color: var(--claude-text) !important; font-family: ${FONT_SANS} !important; font-size: 13px !important; }
    ._39cc453 { color: var(--claude-muted) !important; }
    .ede5bc47 { background: var(--claude-accent-soft) !important; }

    /* ===== HEADER ===== */
    ._2be88ba, ._1aa2651.the-header {
      background: var(--claude-bg) !important;
      border-bottom: 1px solid var(--claude-border) !important;
      color: var(--claude-text) !important;
    }
    .d00ed9c9, ._9986c0c, .afa34042 {
      color: var(--claude-text) !important;
      font-family: ${FONT_SANS} !important;
      font-weight: 500 !important;
      font-size: 14px !important;
    }

    /* ===== BUTTONS ===== */
    .ds-button {
      font-family: ${FONT_SANS} !important;
      color: var(--claude-text) !important;
      border-radius: 8px !important;
    }
    .ds-button__background {
      background: transparent !important;
      border-radius: 8px !important;
      transition: background 150ms ease !important;
    }
    .ds-button:hover .ds-button__background { background: var(--claude-accent-soft) !important; }
    .ds-button--primary .ds-button__background,
    .ds-button--filled .ds-button__background { background: var(--claude-accent) !important; }
    .ds-button--primary:hover .ds-button__background,
    .ds-button--filled:hover .ds-button__background { background: var(--claude-accent-hover) !important; }
    .ds-button--primary, .ds-button--filled { color: #FFFFFE !important; }
    .ds-button--disabled { opacity: 0.45 !important; }

    /* ===== USER MESSAGE ===== */
    .fbb737a4 {
      background: var(--claude-user-bubble) !important;
      color: var(--claude-text) !important;
      border-radius: 16px !important;
      border: 1px solid var(--claude-border) !important;
      font-family: ${FONT_SERIF} !important;
      font-size: 15px !important;
      line-height: 1.65 !important;
      padding: 12px 16px !important;
      max-width: 75% !important;
    }
    .ds-collapsible-text, .ds-collapsible-text span {
      font-family: ${FONT_SERIF} !important;
      color: var(--claude-text) !important;
    }

    /* ===== ASSISTANT MESSAGE ===== */
    .ds-assistant-message-main-content {
      font-family: ${FONT_SERIF} !important;
      color: var(--claude-text) !important;
      font-size: 16px !important;
      line-height: 1.65 !important;
      letter-spacing: -0.005em !important;
      max-width: 720px !important;
      margin: 0 auto !important;
    }
    .ds-assistant-message-main-content p,
    .ds-assistant-message-main-content li,
    .ds-assistant-message-main-content span {
      font-family: ${FONT_SERIF} !important;
      color: var(--claude-text) !important;
    }
    .ds-assistant-message-main-content h1,
    .ds-assistant-message-main-content h2,
    .ds-assistant-message-main-content h3 {
      font-family: ${FONT_SERIF} !important;
      color: var(--claude-text) !important;
      font-weight: 400 !important;
      line-height: 1.2 !important;
      margin-top: 24px !important;
      margin-bottom: 8px !important;
    }
    .ds-assistant-message-main-content a {
      color: var(--claude-accent) !important;
      text-decoration: none !important;
    }
    .ds-assistant-message-main-content a:hover {
      color: var(--claude-accent-hover) !important;
      text-decoration: underline !important;
    }
    .ds-assistant-message-main-content strong, b {
      font-weight: 580 !important;
      color: var(--claude-text) !important;
    }
    .ds-assistant-message-main-content blockquote {
      border-left: 3px solid var(--claude-accent) !important;
      padding-left: 14px !important;
      color: var(--claude-muted) !important;
    }

    /* ===== CODE ===== */
    .ds-markdown code, .ds-markdown pre, code, pre, kbd, samp {
      font-family: ${FONT_MONO} !important;
    }
    .ds-markdown code {
      background: var(--claude-bg-soft) !important;
      color: var(--claude-accent-hover) !important;
      padding: 2px 6px !important;
      border-radius: 4px !important;
      border: 1px solid var(--claude-border) !important;
      font-size: 13px !important;
    }
    .ds-markdown pre {
      background: var(--claude-code-bg) !important;
      border: none !important;
      border-radius: 10px !important;
      padding: 16px !important;
      color: var(--claude-code-text) !important;
      font-size: 13px !important;
      line-height: 1.6 !important;
    }
    .ds-markdown pre code {
      background: transparent !important;
      border: none !important;
      padding: 0 !important;
      color: inherit !important;
    }

    /* ===== THINKING PANEL — the missing piece ===== */
    .e1675d8b.ds-think-content {
      background: transparent !important;
      font-family: ${FONT_SANS} !important;
      padding: 4px 0 4px 0 !important;
    }
    /* "Thought for N seconds" collapsible header */
    ._5255ff8._4d41763 {
      color: var(--claude-muted) !important;
      font-family: ${FONT_SANS} !important;
      font-size: 14px !important;
      font-weight: 400 !important;
      padding: 4px 0 !important;
      gap: 6px !important;
    }
    /* The chevron icon next to the header */
    ._5255ff8._4d41763 .ds-icon {
      color: var(--claude-muted) !important;
      width: 14px !important;
      height: 14px !important;
    }
    /* Vertical guide line */
    ._9ecc93a {
      border-color: var(--claude-border) !important;
      border-left-color: var(--claude-border) !important;
    }
    /* Animated dot ring */
    .ddd26891._9b52f6c {
      color: var(--claude-accent) !important;
      border-color: var(--claude-accent) !important;
    }
    /* Dot core */
    .a510c7ce._0652043 {
      background: var(--claude-accent) !important;
    }
    /* Thinking markdown body — muted sans */
    .ds-think-content .ds-markdown,
    .ds-think-content .ds-markdown-paragraph,
    .ds-think-content .ds-markdown span {
      color: var(--claude-muted) !important;
      font-family: ${FONT_SANS} !important;
      font-size: 13px !important;
      line-height: 1.6 !important;
    }
    /* Thinking markdown links */
    .ds-think-content .ds-markdown a {
      color: var(--claude-accent) !important;
    }

    /* ===== COMPOSER ===== */
    ._871cbca {
      background: linear-gradient(transparent 0%, transparent 30%, var(--claude-bg) 30%, var(--claude-bg) 100%) !important;
    }
    ._77cefa5._3d616d3 {
      background: var(--claude-bg) !important;
      border: 1px solid var(--claude-border) !important;
      border-radius: 16px !important;
      box-shadow: none !important;
    }
    ._020ab5b, ._24fad49, .b13855df { background: transparent !important; }
    textarea._27c9245, .d96f2d2a {
      background: transparent !important;
      color: var(--claude-text) !important;
      font-family: ${FONT_SERIF} !important;
      font-size: 16px !important;
      line-height: 1.5 !important;
      border: none !important;
      outline: none !important;
      padding: 12px 16px 0 16px !important;
    }
    textarea._27c9245::placeholder {
      color: var(--claude-muted) !important;
      font-family: ${FONT_SERIF} !important;
    }
    .ec4f5d61 { background: transparent !important; padding: 8px 12px 10px 12px !important; }

    /* Toggle chips (DeepThink / Search) */
    .ds-toggle-button {
      background: transparent !important;
      color: var(--claude-muted) !important;
      border: 1px solid var(--claude-border) !important;
      border-radius: 8px !important;
      font-family: ${FONT_SANS} !important;
      font-weight: 500 !important;
      font-size: 13px !important;
      padding: 6px 12px !important;
      gap: 6px !important;
      transition: all 150ms ease !important;
    }
    .ds-toggle-button:hover {
      border-color: var(--claude-accent) !important;
      color: var(--claude-accent) !important;
    }
    .ds-toggle-button--selected {
      background: var(--claude-accent-soft) !important;
      border-color: var(--claude-accent) !important;
      color: var(--claude-accent) !important;
    }
    ._6dbc175 { color: inherit !important; font-family: ${FONT_SANS} !important; font-size: 13px !important; }
    /* Recolor inline SVG icons */
    .ds-toggle-button svg path[fill] { fill: currentColor !important; }
    .ds-toggle-button svg path[stroke] { stroke: currentColor !important; }

    /* Attach + send */
    .f02f0e25 { color: var(--claude-text) !important; }
    .f02f0e25:hover .ds-button__background { background: var(--claude-accent-soft) !important; }

    /* ===== DISCLAIMER ===== */
    ._0fcaa63 {
      color: var(--claude-muted) !important;
      background: var(--claude-bg) !important;
      font-family: ${FONT_SANS} !important;
      font-size: 11px !important;
      padding: 8px 0 12px 0 !important;
    }

    /* ===== ACTION ICONS (copy, retry, edit) ===== */
    .db183363, .d4910adc {
      color: var(--claude-muted) !important;
      border-radius: 6px !important;
      transition: color 150ms ease, background 150ms ease !important;
    }
    .db183363:hover, .d4910adc:hover {
      color: var(--claude-accent) !important;
      background: var(--claude-accent-soft) !important;
    }
    .ds-button--iconLabelTertiary { color: var(--claude-muted) !important; }

    /* ===== MISC ===== */
    hr { border-color: var(--claude-border) !important; }

    ::-webkit-scrollbar { width: 8px; height: 8px; }
    ::-webkit-scrollbar-track { background: var(--claude-bg); }
    ::-webkit-scrollbar-thumb {
      background: var(--claude-border);
      border-radius: 4px;
      border: 2px solid var(--claude-bg);
    }
    ::selection { background: var(--claude-accent) !important; color: #FFFFFE !important; }

    .ds-notification {
      background: var(--claude-card) !important;
      color: var(--claude-text) !important;
      border: 1px solid var(--claude-border) !important;
      border-radius: 12px !important;
      font-family: ${FONT_SANS} !important;
    }
    `;
  }

  // ============================================================
  // 5. INJECT / RE-INJECT
  // ============================================================
  function inject() {
    let style = document.getElementById(STYLE_ID);
    if (!style) {
      style = document.createElement('style');
      style.id = STYLE_ID;
      document.head.appendChild(style);
    }
    style.textContent = buildCss();
  }

  inject();

  // Re-inject when DeepSeek toggles theme or adds dynamic content
  let scheduled = false;
  const rescan = () => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => { scheduled = false; inject(); });
  };

  new MutationObserver(rescan).observe(document.body, {
    attributes: true,
    attributeFilter: ['class']
  });
  new MutationObserver(rescan).observe(document.documentElement, {
    childList: true,
    subtree: true
  });

  console.log('%cClaude theme v3 applied to DeepSeek.', 'color:#DA7756;font-weight:bold;');
})();
