# D-Harness

Native Android shell for [DeepSeek Chat](https://chat.deepseek.com/) with the **DeepSeek Tool Shim v6.2** auto-injected on every load.

## Features

- Full WebView chat experience (cookies, file upload, geolocation prompts)
- **Shim v6.2**: `run_js` tools, dedupe, status FAB (draggable), native-style “Tool used” tagline
- **Native bridge** (SharedPreferences memory, private FS, CORS-free `fetch_url`, clipboard, share, toast, vibrate)
- Desktop UA toggle, reload, re-inject, clear cache
- Icon: DeepSeek mark (Lawnicons), white on black

## Agent prompt (once per chat)

Emit tool calls as raw JSON only:

```json
{"tool":"run_js","args":{"code":"return await memory.get('key')"}}
```

In `code`: `memory`, `fetch_url`, `file.save`, `clipboard`, `geo`, `fs`, and (via native) durable storage without CORS limits.

## Build

```bash
./gradlew :app:assembleRelease
```

## License

MIT — shim logic adapted from the DeepSeek Tool Shim; logo paths from Lawnicons-style DeepSeek SVG.
