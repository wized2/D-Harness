# D-Harness

Android shell for [DeepSeek Chat](https://chat.deepseek.com/) with a **tool shim** and **native bridge**.  
The model can call real on-device tools (files, HTTP, GitHub, device, paste UI) without guessing APIs.

## Highlights

- Full-screen WebView chat (cookies, uploads, geolocation)
- **Shim** (dsh.js): stable tool IDs, settle-before-run, `TOOL_RESULT` delivery
- **Native tools**: workspace FS, `paste_box`, HTTP (no CORS), GitHub, memory/keys, device
- Optional **Claude theme** (Claude.js)
- Sleek dark settings (**AppCompat only** — no Material library, smaller APK)
- Draggable glass FAB with whale mark → settings

## Tools (exact call)

```json
{"tool":"run_js","args":{"code":"return await list_tools()"}}
```

One tool per reply → wait for `TOOL_RESULT:` → continue.

| Tool | Purpose |
|------|---------|
| `list_tools` / `describe` | Discover APIs + examples |
| `paste_box({path,title?})` | Paste dialog → save under workspace |
| `workspace.*` | Sandbox files |
| `memory.*` / `keys.*` | Scratchpad / secrets (PAT) |
| `http_request` / `github.*` | Network (GitHub needs PAT key `github`) |
| `device.*` | Phone info |

## Size

Release builds use **R8 minify + resource shrink**. UI uses **AppCompat only** (Material Components not linked) to keep the APK lean.

## Build

```bash
./gradlew :app:assembleRelease
```

## License

MIT — shim from dsh.js; Claude theme from Claude.js.
