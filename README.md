# D-Harness

Native Android shell for [DeepSeek Chat](https://chat.deepseek.com/) with the **DeepSeek Tool Shim v7.6.0-stable** auto-injected on every page load.

## Features

- Full-screen WebView chat (cookies, uploads, geolocation prompts)
- **Shim v7.6.0-stable**: durable tool dedupe, status FAB, management panel, throttled DOM scan
- **Native bridge** tools (no CORS): memory, files, HTTP, clipboard, share, device info, GitHub helpers
- Auto-inject with retries; desktop UA toggle; clear cache; re-inject from menu
- Offline-friendly private storage under the app sandbox

## Agent prompt (paste once per chat)

```
You have tools. To call a tool, reply with ONLY a JSON object (no markdown fences):

{"tool":"run_js","args":{"code":"return await memory.get('key')"}}

After a message starting with TOOL_RESULT:, use that data and continue.
Never invent results. Never repeat a tool that already returned TOOL_RESULT.
One tool call per reply.
```

## Native tools (via `run_js`)

Prefer these over pure browser APIs when available:

| Area | Examples |
|------|----------|
| Memory | `memory.get/set/list` |
| Files | `file.save`, `file.read`, workspace helpers |
| Network | `fetch_url` / HTTP without CORS |
| Device | uptime, storage, memory, locale, sensors |
| System | clipboard, share, toast, vibrate |
| GitHub | branch/compare helpers when configured |

Full catalog: ask the app for `list_tools` from the native bridge.

## Build

```bash
./gradlew :app:assembleRelease
```

## License

MIT — shim adapted from DeepSeek Tool Shim; logo paths from Lawnicons-style DeepSeek SVG.
