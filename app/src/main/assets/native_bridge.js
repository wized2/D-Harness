window.__dHarnessFetchPending = window.__dHarnessFetchPending || {};
window.__dHarnessCb = window.__dHarnessFetchCb = function(id, payloadStr) {
  var p = window.__dHarnessFetchPending[id];
  if (!p) return;
  delete window.__dHarnessFetchPending[id];
  try {
    var data = typeof payloadStr === 'string' ? JSON.parse(payloadStr) : payloadStr;
    if (data && data.ok === false && data.error && !data.status) p.reject(new Error(data.error));
    else p.resolve(data);
  } catch (e) { p.reject(e); }
};

window.__DHarnessNative = {
  available: typeof DHarness !== 'undefined',
  list_tools: function() {
    if (!window.DHarness) return Promise.resolve({ tools: [] });
    try { return Promise.resolve(JSON.parse(DHarness.listTools())); } catch (e) { return Promise.reject(e); }
  },
  memory: {
    get: function(k) { try { return Promise.resolve(JSON.parse(DHarness.memoryGet(k))); } catch (e) { return Promise.reject(e); } },
    set: function(k, v) { try { return Promise.resolve(JSON.parse(DHarness.memorySet(k, String(v)))); } catch (e) { return Promise.reject(e); } },
    delete: function(k) { try { return Promise.resolve(JSON.parse(DHarness.memoryDelete(k))); } catch (e) { return Promise.reject(e); } },
    list: function() { try { return Promise.resolve(JSON.parse(DHarness.memoryList())); } catch (e) { return Promise.reject(e); } },
    clear: function() { try { return Promise.resolve(JSON.parse(DHarness.memoryClear())); } catch (e) { return Promise.reject(e); } }
  },
  keys: {
    get: function(n) { try { return Promise.resolve(JSON.parse(DHarness.keysGet(n))); } catch (e) { return Promise.reject(e); } },
    set: function(n, v) { try { return Promise.resolve(JSON.parse(DHarness.keysSet(n, String(v)))); } catch (e) { return Promise.reject(e); } },
    delete: function(n) { try { return Promise.resolve(JSON.parse(DHarness.keysDelete(n))); } catch (e) { return Promise.reject(e); } },
    list: function() { try { return Promise.resolve(JSON.parse(DHarness.keysList())); } catch (e) { return Promise.reject(e); } }
  },
  fetch_url: function(url, opts) {
    opts = opts || {};
    var id = 'f' + Date.now() + Math.random().toString(36).slice(2, 8);
    return new Promise(function(resolve, reject) {
      window.__dHarnessFetchPending[id] = { resolve: resolve, reject: reject };
      setTimeout(function() {
        if (window.__dHarnessFetchPending[id]) {
          delete window.__dHarnessFetchPending[id];
          reject(new Error('fetch timeout'));
        }
      }, (opts.timeoutMs || 25000));
      DHarness.fetchUrl(url, opts.method || 'GET', opts.body != null ? String(opts.body) : null, id);
    });
  },
  file_save: function(filename, content, mime) {
    try { return Promise.resolve(JSON.parse(DHarness.saveFile(filename, String(content), mime || 'text/plain'))); }
    catch (e) { return Promise.reject(e); }
  },
  clipboard: {
    copy: function(text) { try { return Promise.resolve(JSON.parse(DHarness.clipboardWrite(String(text)))); } catch (e) { return Promise.reject(e); } },
    read: function() { try { return Promise.resolve(JSON.parse(DHarness.clipboardRead())); } catch (e) { return Promise.reject(e); } }
  },
  fs: {
    read: function(path) { try { return Promise.resolve(JSON.parse(DHarness.fsRead(path))); } catch (e) { return Promise.reject(e); } },
    write: function(path, content) { try { return Promise.resolve(JSON.parse(DHarness.fsWrite(path, String(content)))); } catch (e) { return Promise.reject(e); } },
    list: function(prefix) { try { return Promise.resolve(JSON.parse(DHarness.fsList(prefix || ''))); } catch (e) { return Promise.reject(e); } },
    delete: function(path) { try { return Promise.resolve(JSON.parse(DHarness.fsDelete(path))); } catch (e) { return Promise.reject(e); } }
  },
  device: {
    info: function() { try { return Promise.resolve(JSON.parse(DHarness.deviceInfo())); } catch (e) { return Promise.reject(e); } },
    battery: function() { try { return Promise.resolve(JSON.parse(DHarness.battery())); } catch (e) { return Promise.reject(e); } },
    network: function() { try { return Promise.resolve(JSON.parse(DHarness.network())); } catch (e) { return Promise.reject(e); } }
  },
  toast: function(m) { if (window.DHarness) DHarness.toast(String(m)); },
  vibrate: function(ms) { if (window.DHarness) DHarness.vibrate(ms || 40); },
  notify: function(title, body) { try { return Promise.resolve(JSON.parse(DHarness.notify(String(title), String(body||'')))); } catch (e) { return Promise.reject(e); } },
  share: function(t) { if (window.DHarness) DHarness.shareText(String(t)); },
  appInfo: function() { try { return Promise.resolve(JSON.parse(DHarness.appInfo())); } catch (e) { return Promise.reject(e); } }
};
