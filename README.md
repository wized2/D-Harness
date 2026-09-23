# D-Harness

Android shell for [DeepSeek Chat](https://chat.deepseek.com/) with a **tool shim** + **native bridge**.  
The model can call real on-device tools (files, HTTP, GitHub, device, clipboard) without guessing APIs.

## Features

- **Material 3 settings** (dark, compact cards/switches)


- Full-screen WebView chat (cookies, uploads, geolocation)
- **Shim** (upstream dsh.js): last-message scan, stable IDs, settle-before-run, TOOL_RESULT send
- **Native bridge**: workspace FS, HTTP (no CORS), GitHub, memory/keys, device, calc/text/time
- **Claude theme** (optional): latest Claude.js styling for DeepSeek UI
- Auto-inject with retries · desktop UA · clear cache · force re-inject
- Agent instructions built into the app (copy from menu / first-run paste)

## How tools work

1. Model replies with **one** JSON tool call, e.g.

```json
{"tool":"run_js","args":{"code":"return await list_tools()"}}
```

2. Shim runs the tool and posts a user message starting with `TOOL_RESULT:`.
3. Model continues using that result. **One tool per reply.**

### Discover

```json
{"tool":"run_js","args":{"code":"return await list_tools()"}}
{"tool":"run_js","args":{"code":"return await describe('github')"}}
```

### Common tools (inside `run_js`)

| Area | Examples |
|------|----------|
| Memory | `memory.get/set/list` |
| Secrets | `keys.set('github', pat)` · `keys.list()` |
| Files | `workspace.pwd/ls/read/write/mkdir/tree` |
| HTTP | `http_request({url, method, headers, body})` |
| GitHub | `github.me/repos/pr/contents/request/…` (needs PAT) |
| Device | `device.info/battery/network/storage/memory` |
| Text/calc | `calc.eval` · `text.regex` · `time.now` · `uuid.v4` |

Full catalog and paste-ready examples come from **`list_tools()`**.

## GitHub PAT

Settings → store key name **`github`** with a fine-scoped PAT. Never ask the model to echo the token.

## Build

```bash
./gradlew :app:assembleRelease
```

## License

MIT — shim adapted from DeepSeek Tool Shim / dsh.js; Claude theme from Claude.js; logo paths brand-aligned.
