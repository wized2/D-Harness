(function () {
  // DeepEase: intentionally no CSS/layout changes to chat.deepseek.com
  window.__DEEPEASE_THEME__ = { version: '1.1.2', passthrough: true };
  try {
    var old = document.getElementById('__deepease_theme_css');
    if (old) old.remove();
    var bar = document.getElementById('__deepease_bar');
    if (bar) bar.remove();
  } catch (e) {}
})();
