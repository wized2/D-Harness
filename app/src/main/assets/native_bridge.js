window.__dHarnessFetchPending = window.__dHarnessFetchPending || {};
window.__dHarnessFetchCb = function(id, payloadStr) {
  var p = window.__dHarnessFetchPending[id];
  if (!p) return;
  delete window.__dHarnessFetchPending[id];
  try {
    var data = typeof payloadStr === 'string' ? JSON.parse(payloadStr) : payloadStr;
    if (data && data.ok === false && data.error) p.reject(new Error(data.error));
    else p.resolve(data);
  } catch (e) { p.reject(e); }
};

window.__DHarnessNative = {
  available: typeof DHarness !== 'undefined',
  memory: {
    get: function(k) {
      if (!window.DHarness) return Promise.resolve({ value: null });
      try { return Promise.resolve(JSON.parse(DHarness.memoryGet(k))); } catch (e) { return Promise.reject(e); }
    },
    set: function(k, v) {
      if (!window.DHarness) return Promise.resolve({ ok: false });
      try { return Promise.resolve(JSON.parse(DHarness.memorySet(k, String(v)))); } catch (e) { return Promise.reject(e); }
    },
    delete: function(k) {
      if (!window.DHarness) return Promise.resolve({ ok: false });
      try { return Promise.resolve(JSON.parse(DHarness.memoryDelete(k))); } catch (e) { return Promise.reject(e); }
    },
    list: function() {
      if (!window.DHarness) return Promise.resolve({ keys: [] });
      try { return Promise.resolve(JSON.parse(DHarness.memoryList())); } catch (e) { return Promise.reject(e); }
    },
    clear: function() {
      if (!window.DHarness) return Promise.resolve({ ok: false });
      try { return Promise.resolve(JSON.parse(DHarness.memoryClear())); } catch (e) { return Promise.reject(e); }
    }
  },
  fetch_url: function(url, opts) {
    opts = opts || {};
    if (!window.DHarness) return Promise.reject(new Error('no native bridge'));
    var id = 'f' + Date.now() + Math.random().toString(36).slice(2);
    return new Promise(function(resolve, reject) {
      window.__dHarnessFetchPending[id] = { resolve: resolve, reject: reject };
      DHarness.fetchUrl(url, opts.method || 'GET', opts.body != null ? String(opts.body) : null, id);
    });
  },
  file_save: function(filename, content, mime) {
    if (!window.DHarness) return Promise.reject(new Error('no native bridge'));
    try { return Promise.resolve(JSON.parse(DHarness.saveFile(filename, String(content), mime || 'text/plain'))); }
    catch (e) { return Promise.reject(e); }
  },
  clipboard: {
    copy: function(text) {
      if (!window.DHarness) return Promise.reject(new Error('no native bridge'));
      try { return Promise.resolve(JSON.parse(DHarness.clipboardWrite(String(text)))); }
      catch (e) { return Promise.reject(e); }
    },
    read: function() {
      if (!window.DHarness) return Promise.reject(new Error('no native bridge'));
      try { return Promise.resolve(JSON.parse(DHarness.clipboardRead())); }
      catch (e) { return Promise.reject(e); }
    }
  },
  fs: {
    read: function(path) {
      if (!window.DHarness) return Promise.reject(new Error('no native bridge'));
      try { return Promise.resolve(JSON.parse(DHarness.fsRead(path))); }
      catch (e) { return Promise.reject(e); }
    },
    write: function(path, content) {
      if (!window.DHarness) return Promise.reject(new Error('no native bridge'));
      try { return Promise.resolve(JSON.parse(DHarness.fsWrite(path, String(content)))); }
      catch (e) { return Promise.reject(e); }
    },
    list: function(prefix) {
      if (!window.DHarness) return Promise.reject(new Error('no native bridge'));
      try { return Promise.resolve(JSON.parse(DHarness.fsList(prefix || ''))); }
      catch (e) { return Promise.reject(e); }
    },
    delete: function(path) {
      if (!window.DHarness) return Promise.reject(new Error('no native bridge'));
      try { return Promise.resolve(JSON.parse(DHarness.fsDelete(path))); }
      catch (e) { return Promise.reject(e); }
    }
  },
  toast: function(m) { if (window.DHarness) DHarness.toast(String(m)); },
  vibrate: function(ms) { if (window.DHarness) DHarness.vibrate(ms || 40); },
  share: function(t) { if (window.DHarness) DHarness.shareText(String(t)); },
  appInfo: function() {
    if (!window.DHarness) return Promise.resolve({});
    try { return Promise.resolve(JSON.parse(DHarness.appInfo())); }
    catch (e) { return Promise.reject(e); }
  }
};
