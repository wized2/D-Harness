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
function _j(fn) {
  try { return Promise.resolve(JSON.parse(fn())); } catch (e) { return Promise.reject(e); }
}

window.__DHarnessNative = {
  available: typeof DHarness !== 'undefined',
  list_tools: function () { return _j(function () { return DHarness.listTools(); }); },
  describe: function (name) { return _j(function () { return DHarness.describeTool(String(name)); }); },
  http_request: function (opts) {
    opts = opts || {};
    var headers = opts.headers ? (typeof opts.headers === 'string' ? opts.headers : JSON.stringify(opts.headers)) : null;
    var body = opts.body != null ? (typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)) : null;
    if (opts.json != null && body == null) body = JSON.stringify(opts.json);
    return _asyncNative(function (id) { DHarness.httpRequest(opts.url, opts.method || 'GET', headers, body, id); });
  },
  fetch_url: function (url, opts) {
    opts = opts || {};
    if (typeof url === 'object') { opts = url; url = opts.url; }
    return window.__DHarnessNative.http_request({ url: url, method: opts.method || 'GET', headers: opts.headers || null, body: opts.body != null ? opts.body : opts.json });
  },
  github: {
    request: function (method, path, body) {
      var b = body != null ? (typeof body === 'string' ? body : JSON.stringify(body)) : null;
      return _asyncNative(function (id) { DHarness.githubRequest(method || 'GET', path, b, id); });
    },
    me: function () { return window.__DHarnessNative.github.request('GET', '/user'); },
    repos: function (perPage) {
      var n = Math.min(Math.max(parseInt(perPage, 10) || 10, 1), 30);
      return window.__DHarnessNative.github.request('GET', '/user/repos?per_page=' + n + '&sort=updated&direction=desc').then(function (r) {
        if (r && r.json && Array.isArray(r.json)) {
          r.json = r.json.map(function (x) {
            return { id: x.id, name: x.name, full_name: x.full_name, html_url: x.html_url, private: x.private, updated_at: x.updated_at };
          });
          r.text = JSON.stringify(r.json);
        }
        return r;
      });
    },
    issues: function (owner, repo, state) {
      return window.__DHarnessNative.github.request('GET', '/repos/' + owner + '/' + repo + '/issues?state=' + (state || 'open') + '&per_page=20');
    },
    issue_comment: function (owner, repo, number, body) {
      return window.__DHarnessNative.github.request('POST', '/repos/' + owner + '/' + repo + '/issues/' + number + '/comments', { body: body });
    },
    pr: function (owner, repo, number) {
      return window.__DHarnessNative.github.request('GET', '/repos/' + owner + '/' + repo + '/pulls/' + number);
    },
    pr_files: function (owner, repo, number) {
      return window.__DHarnessNative.github.request('GET', '/repos/' + owner + '/' + repo + '/pulls/' + number + '/files?per_page=100');
    },
    pr_reviews: function (owner, repo, number) {
      return window.__DHarnessNative.github.request('GET', '/repos/' + owner + '/' + repo + '/pulls/' + number + '/reviews?per_page=50');
    },
    pr_commits: function (owner, repo, number) {
      return window.__DHarnessNative.github.request('GET', '/repos/' + owner + '/' + repo + '/pulls/' + number + '/commits?per_page=50');
    },
    issue: function (owner, repo, number) {
      return window.__DHarnessNative.github.request('GET', '/repos/' + owner + '/' + repo + '/issues/' + number);
    },
    contents: function (owner, repo, path, ref) {
      var q = ref ? ('?ref=' + encodeURIComponent(ref)) : '';
      return window.__DHarnessNative.github.request('GET', '/repos/' + owner + '/' + repo + '/contents/' + path.replace(/^\/+/, '') + q).then(function (r) {
        // Normalize contents envelope
        if (r && r.json && typeof r.json === 'object' && !Array.isArray(r.json)) {
          r.content = r.json.content || null;
          r.sha = r.json.sha || null;
          r.encoding = r.json.encoding || null;
          r.download_url = r.json.download_url || null;
          r.name = r.json.name || null;
          r.path = r.json.path || null;
        }
        return r;
      });
    },
    search: function (query, type) {
      var t = type || 'issues';
      var path = t === 'code' ? '/search/code' : (t === 'repositories' ? '/search/repositories' : '/search/issues');
      return window.__DHarnessNative.github.request('GET', path + '?q=' + encodeURIComponent(query) + '&per_page=20');
    },
    pr_create: function (owner, repo, title, head, base, body, draft) {
      return window.__DHarnessNative.github.request('POST', '/repos/' + owner + '/' + repo + '/pulls', {
        title: title, head: head, base: base || 'main', body: body || '', draft: !!draft
      });
    },
    pr_comment: function (owner, repo, number, body) {
      // Issue comments endpoint works for PR discussion comments
      return window.__DHarnessNative.github.request('POST', '/repos/' + owner + '/' + repo + '/issues/' + number + '/comments', { body: body });
    }
  },
  memory: {
    get: function (k) { return _j(function () { return DHarness.memoryGet(k); }); },
    set: function (k, v) { return _j(function () { return DHarness.memorySet(k, String(v)); }); },
    delete: function (k) { return _j(function () { return DHarness.memoryDelete(k); }); },
    list: function () { return _j(function () { return DHarness.memoryList(); }); },
    clear: function () { return _j(function () { return DHarness.memoryClear(); }); }
  },
  keys: {
    get: function (n) { return _j(function () { return DHarness.keysGet(n); }); },
    set: function (n, v) { return _j(function () { return DHarness.keysSet(n, String(v)); }); },
    delete: function (n) { return _j(function () { return DHarness.keysDelete(n); }); },
    list: function () { return _j(function () { return DHarness.keysList(); }); }
  },

  calc: {
    eval: function (expr) { return _j(function () { return DHarness.calcEval(String(expr)); }); },
    convert: function (value, from, to) { return _j(function () { return DHarness.calcConvert(Number(value), String(from), String(to)); }); },
    haversine: function (lat1, lon1, lat2, lon2) {
      return _j(function () { return DHarness.calcHaversine(Number(lat1), Number(lon1), Number(lat2), Number(lon2)); });
    },
    clamp: function (value, min, max) { return _j(function () { return DHarness.calcClamp(Number(value), Number(min), Number(max)); }); },
    round: function (value, digits) { return _j(function () { return DHarness.calcRound(Number(value), digits|0); }); }
  },
  text: {
    base64: function (op, data) { return _j(function () { return DHarness.textBase64(op, String(data)); }); },
    url: function (op, data) { return _j(function () { return DHarness.textUrl(op, String(data)); }); },
    regex: function (op, pattern, text, replacement) {
      return _j(function () { return DHarness.textRegex(op, pattern, text, replacement != null ? String(replacement) : null); });
    },
    stats: function (text) { return _j(function () { return DHarness.textStats(String(text)); }); },
    case: function (op, text) { return _j(function () { return DHarness.textCase(op, String(text)); }); },
    trim: function (text) { return _j(function () { return DHarness.textTrim(String(text)); }); },
    split: function (text, sep, limit) { return _j(function () { return DHarness.textSplit(String(text), String(sep), limit|0); }); },
    join: function (parts, sep) { return _j(function () { return DHarness.textJoin(JSON.stringify(parts), String(sep)); }); }
  },
  json: {
    pretty: function (json, indent) { return _j(function () { return DHarness.jsonPretty(String(json), indent|2); }); },
    parse: function (json) { return _j(function () { return DHarness.jsonParse(String(json)); }); },
    query: function (json, path) { return window.__DHarnessNative.json_query(json, path); }
  },
  color: {
    hex_rgb: function (op, value) { return _j(function () { return DHarness.colorHexRgb(op, String(value)); }); }
  },
  diff: {
    lines: function (a, b) { return _j(function () { return DHarness.diffLines(String(a), String(b)); }); }
  },
  time: {
    now: function () { return _j(function () { return DHarness.timeNow(); }); },
    format: function (ms, pattern) { return _j(function () { return DHarness.timeFormat(Number(ms), pattern || null); }); }
  },
  uuid: { v4: function () { return _j(function () { return DHarness.uuidV4(); }); } },
  random: { bytes: function (n) { return _j(function () { return DHarness.randomBytes(n || 16); }); } },
  intent: { open_url: function (url) { return _j(function () { return DHarness.openUrl(String(url)); }); } },
  fs: {
    read: function (path) { return _j(function () { return DHarness.fsRead(path); }); },
    write: function (path, content) { return _j(function () { return DHarness.fsWrite(path, String(content)); }); },
    list: function (prefix) { return _j(function () { return DHarness.fsList(prefix || ''); }); },
    delete: function (path) { return _j(function () { return DHarness.fsDelete(path); }); },
    stat: function (path) { return _j(function () { return DHarness.fsStat(path); }); },
    exists: function (path) { return _j(function () { return DHarness.fsExists(path); }); },
    append: function (path, content) { return _j(function () { return DHarness.fsAppend(path, String(content)); }); },
    mkdir: function (path) { return _j(function () { return DHarness.fsMkdir(path); }); },
    touch: function (path) { return _j(function () { return DHarness.fsTouch(path); }); },
    copy: function (from, to) { return _j(function () { return DHarness.fsCopy(from, to); }); },
    move: function (from, to) { return _j(function () { return DHarness.fsMove(from, to); }); }
  },
  file: {
    commit: function (path, contentB64, sha256) {
      return _j(function () { return DHarness.fileCommit(path, contentB64, sha256 || null); });
    },
    read_b64: function (path) { return _j(function () { return DHarness.fileReadB64(path); }); },
    verify_roundtrip: function () { return _j(function () { return DHarness.fileVerifyRoundtrip(); }); },
    save: function (filename, content, mime) {
      return _j(function () { return DHarness.saveFile(filename, String(content), mime || 'text/plain'); });
    }
  },
  exec: function (argv, timeoutMs, cwd) {
    var a = Array.isArray(argv) ? argv : (argv && argv.argv) || [];
    var t = timeoutMs || (argv && argv.timeout_ms) || 15000;
    var c = cwd || (argv && argv.cwd) || null;
    return _j(function () { return DHarness.exec(JSON.stringify(a), t, c); });
  },
  sqlite: {
    query: function (path, sql, args) {
      return _j(function () { return DHarness.sqliteQuery(path, sql, args ? JSON.stringify(args) : null); });
    }
  },
  crypto: {
    hash: function (algo, data, encoding) {
      return _j(function () { return DHarness.cryptoHash(algo, data, encoding || 'utf8'); });
    },
    hmac: function (key, data) { return _j(function () { return DHarness.cryptoHmac(key, data); }); }
  },
  archive: {
    zip_list: function (path) { return _j(function () { return DHarness.zipList(path); }); },
    zip_extract: function (path, entry) { return _j(function () { return DHarness.zipExtract(path, entry); }); },
    zip_create: function (path, files) {
      return _j(function () { return DHarness.zipCreate(path, typeof files === 'string' ? files : JSON.stringify(files)); });
    }
  },
  json_query: function (json, path) { return _j(function () { return DHarness.jsonQuery(json, path); }); },
  net: {
    ping: function (host, timeoutMs) { return _j(function () { return DHarness.netPing(host, timeoutMs || 3000); }); },
    port: function (host, port, timeoutMs) { return _j(function () { return DHarness.netPort(host, port, timeoutMs || 3000); }); }
  },
  process: {
    list: function () { return _j(function () { return DHarness.processList(); }); },
    kill: function (pid) { return _j(function () { return DHarness.processKill(pid); }); }
  },
  env: { get: function () { return _j(function () { return DHarness.envGet(); }); } },
  clipboard: {
    read: function () { return _j(function () { return DHarness.clipboardRead(); }); },
    write: function (text) { return _j(function () { return DHarness.clipboardWrite(String(text)); }); },
    copy: function (text) { return this.write(text); }
  },
  device: {
    info: function () { return _j(function () { return DHarness.deviceInfo(); }); },
    battery: function () { return _j(function () { return DHarness.battery(); }); },
    network: function () { return _j(function () { return DHarness.network(); }); },
    display: function () { return _j(function () { return DHarness.deviceDisplay(); }); }
  },
  toast: function (m) { DHarness.toast(String(m)); return Promise.resolve({ ok: true }); },
  vibrate: function (ms) { DHarness.vibrate(ms || 40); return Promise.resolve({ ok: true }); },
  notify: function (title, body) { return _j(function () { return DHarness.notify(String(title), String(body || '')); }); },
  share: function (t) { DHarness.shareText(String(t)); return Promise.resolve({ ok: true }); },
  appInfo: function () { return _j(function () { return DHarness.appInfo(); }); },
  geo: {
    get: function () {
      return new Promise(function (resolve, reject) {
        if (!navigator.geolocation) return reject(new Error('geolocation unsupported'));
        navigator.geolocation.getCurrentPosition(
          function (p) { resolve({ lat: p.coords.latitude, lng: p.coords.longitude, accuracy: p.coords.accuracy, timestamp: p.timestamp }); },
          function (err) { reject(new Error(err.message || 'geo error')); },
          { timeout: 10000, maximumAge: 60000 }
        );
      });
    }
  }
};
