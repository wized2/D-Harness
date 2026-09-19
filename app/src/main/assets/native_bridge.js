
window.__dHarnessFetchPending = window.__dHarnessFetchPending || {};
window.__dHarnessCb = function (id, payloadStr) {
  var p = window.__dHarnessFetchPending[id];
  if (!p) return;
  delete window.__dHarnessFetchPending[id];
  try {
    var data = typeof payloadStr === 'string' ? JSON.parse(payloadStr) : payloadStr;
    if (data && data.ok === false && data.error && data.status == null) p.reject(new Error(data.error));
    else p.resolve(data);
  } catch (e) { p.reject(e); }
};

function _cbId() { return 'c' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
function _asyncNative(fn) {
  return new Promise(function (resolve, reject) {
    var id = _cbId();
    var timer = setTimeout(function () {
      if (window.__dHarnessFetchPending[id]) {
        delete window.__dHarnessFetchPending[id];
        reject(new Error('timeout'));
      }
    }, 30000);
    window.__dHarnessFetchPending[id] = {
      resolve: function (d) { clearTimeout(timer); resolve(d); },
      reject: function (e) { clearTimeout(timer); reject(e); }
    };
    try { fn(id); } catch (e) { clearTimeout(timer); delete window.__dHarnessFetchPending[id]; reject(e); }
  });
}

window.__DHarnessNative = {
  available: typeof DHarness !== 'undefined',
  list_tools: function () {
    try { return Promise.resolve(JSON.parse(DHarness.listTools())); } catch (e) { return Promise.reject(e); }
  },
  describe: function (name) {
    try { return Promise.resolve(JSON.parse(DHarness.describeTool(String(name)))); } catch (e) { return Promise.reject(e); }
  },
  http_request: function (opts) {
    opts = opts || {};
    var headers = opts.headers ? (typeof opts.headers === 'string' ? opts.headers : JSON.stringify(opts.headers)) : null;
    var body = opts.body != null ? (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)) : null;
    if (opts.json != null && body == null) body = JSON.stringify(opts.json);
    return _asyncNative(function (id) {
      DHarness.httpRequest(opts.url, opts.method || 'GET', headers, body, id);
    });
  },
  fetch_url: function (url, opts) {
    opts = opts || {};
    if (typeof url === 'object') { opts = url; url = opts.url; }
    return window.__DHarnessNative.http_request({
      url: url,
      method: opts.method || 'GET',
      headers: opts.headers || null,
      body: opts.body != null ? opts.body : opts.json
    });
  },
  github: {
    request: function (method, path, body) {
      var b = body != null ? (typeof body === 'string' ? body : JSON.stringify(body)) : null;
      return _asyncNative(function (id) { DHarness.githubRequest(method || 'GET', path, b, id); });
    },
    me: function () { return window.__DHarnessNative.github.request('GET', '/user'); },
    repos: function (perPage) {
      var n = Math.min(Math.max(parseInt(perPage, 10) || 10, 1), 30);
      return window.__DHarnessNative.github.request('GET',
        '/user/repos?per_page=' + n + '&sort=updated&direction=desc').then(function (r) {
        // Compact: avoid 150KB truncation — return name/full_name/html_url only
        if (r && r.json && Array.isArray(r.json)) {
          r.json = r.json.map(function (x) {
            return { id: x.id, name: x.name, full_name: x.full_name, html_url: x.html_url,
              private: x.private, updated_at: x.updated_at };
          });
          r.text = JSON.stringify(r.json);
        }
        return r;
      });
    },
    issues: function (owner, repo, state) {
      return window.__DHarnessNative.github.request('GET',
        '/repos/' + owner + '/' + repo + '/issues?state=' + (state || 'open') + '&per_page=20');
    },
    issue_comment: function (owner, repo, number, body) {
      return window.__DHarnessNative.github.request('POST',
        '/repos/' + owner + '/' + repo + '/issues/' + number + '/comments', { body: body });
    },
    pr: function (owner, repo, number) {
      return window.__DHarnessNative.github.request('GET',
        '/repos/' + owner + '/' + repo + '/pulls/' + number);
    }
  },
  memory: {
    get: function (k) { return Promise.resolve(JSON.parse(DHarness.memoryGet(k))); },
    set: function (k, v) { return Promise.resolve(JSON.parse(DHarness.memorySet(k, String(v)))); },
    delete: function (k) { return Promise.resolve(JSON.parse(DHarness.memoryDelete(k))); },
    list: function () { return Promise.resolve(JSON.parse(DHarness.memoryList())); },
    clear: function () { return Promise.resolve(JSON.parse(DHarness.memoryClear())); }
  },
  keys: {
    get: function (n) { return Promise.resolve(JSON.parse(DHarness.keysGet(n))); },
    set: function (n, v) { return Promise.resolve(JSON.parse(DHarness.keysSet(n, String(v)))); },
    delete: function (n) { return Promise.resolve(JSON.parse(DHarness.keysDelete(n))); },
    list: function () { return Promise.resolve(JSON.parse(DHarness.keysList())); }
  },
  fs: {
    read: function (path) { return Promise.resolve(JSON.parse(DHarness.fsRead(path))); },
    write: function (path, content) { return Promise.resolve(JSON.parse(DHarness.fsWrite(path, String(content)))); },
    list: function (prefix) { return Promise.resolve(JSON.parse(DHarness.fsList(prefix || ''))); },
    delete: function (path) { return Promise.resolve(JSON.parse(DHarness.fsDelete(path))); }
  },
  clipboard: {
    read: function () { return Promise.resolve(JSON.parse(DHarness.clipboardRead())); },
    write: function (text) { return Promise.resolve(JSON.parse(DHarness.clipboardWrite(String(text)))); },
    copy: function (text) { return this.write(text); }
  },
  device: {
    info: function () { return Promise.resolve(JSON.parse(DHarness.deviceInfo())); },
    battery: function () { return Promise.resolve(JSON.parse(DHarness.battery())); },
    network: function () { return Promise.resolve(JSON.parse(DHarness.network())); }
  },
  file_save: function (filename, content, mime) {
    return Promise.resolve(JSON.parse(DHarness.saveFile(filename, String(content), mime || 'text/plain')));
  },
  toast: function (m) { DHarness.toast(String(m)); return Promise.resolve({ ok: true }); },
  vibrate: function (ms) { DHarness.vibrate(ms || 40); return Promise.resolve({ ok: true }); },
  notify: function (title, body) {
    return Promise.resolve(JSON.parse(DHarness.notify(String(title), String(body || ''))));
  },
  share: function (t) { DHarness.shareText(String(t)); return Promise.resolve({ ok: true }); },
  appInfo: function () { return Promise.resolve(JSON.parse(DHarness.appInfo())); },
  geo: {
    get: function () {
      return new Promise(function (resolve, reject) {
        if (!navigator.geolocation) return reject(new Error('geolocation unsupported'));
        navigator.geolocation.getCurrentPosition(
          function (p) {
            resolve({
              lat: p.coords.latitude, lng: p.coords.longitude,
              accuracy: p.coords.accuracy, timestamp: p.timestamp
            });
          },
          function (err) { reject(new Error(err.message || 'geo error')); },
          { timeout: 10000, maximumAge: 60000 }
        );
      });
    }
  }
};
