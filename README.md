# D-Harness

Android shell for [DeepSeek Chat](https://chat.deepseek.com/) with a **tool shim** and **native bridge**.

## 1.5.17 highlights

- Background agent tool loop
- Tool call  drives tagline (no JSON chip)
- Send instructions + explore workspace fixes

## Features

- Full-screen WebView chat + native tool bridge
- Material 3 settings (dark, compact)
- Tools: workspace, paste_box, GitHub, HTTP, memory/keys, device
- Optional Claude theme · draggable FAB → settings

## Size

Release: **R8 minify + resource shrink**. Only Material widgets used by settings are retained; unused Material code is stripped.

## Tools

```json
{"tool":"run_js","args":{"code":"return await list_tools()"}}
```

See in-app settings for the quick map. GitHub needs PAT key `github`.

## Build

```bash
./gradlew :app:assembleRelease
```

## License

MIT
