# D-Harness

Native Android shell for [DeepSeek Chat](https://chat.deepseek.com/) with **DeepSeek Tool Shim v7.5.0-native** (upstream [`dsh.js`](https://github.com/wized2/dsh) + native bridge).

## Features

- Full-screen WebView chat (cookies, uploads, geolocation)
- **Shim from dsh.js**: last-message scan, stable message IDs, settle-before-run, unsent retry, clean UI (no emoji chrome)
- **Native bridge** (no CORS): memory, files, HTTP, device, GitHub, workspace, clipboard, share
- Auto-inject with retries; desktop UA; clear cache; force re-inject from menu

## Agent prompt (paste once per chat)

```
You have tools. Reply with ONLY a JSON object (no markdown fences required):

{"tool":"run_js","args":{"code":"return await list_tools()"}}

After TOOL_RESULT:, use that data. Never invent results. One tool call per reply.
```

### Examples

```json
{"tool":"run_js","args":{"code":"return await device.info()"}}
{"tool":"run_js","args":{"code":"return await memory.set('k','v')"}}
{"tool":"run_js","args":{"code":"return await workspace.pwd()"}}
```

Direct tool form (also supported):

```json
{"tool":"list_tools","args":{}}
```

## Build

```bash
./gradlew :app:assembleRelease
```

## License

MIT — shim from dsh.js (DeepSeek Tool Shim); native bridge is D-Harness-specific.
