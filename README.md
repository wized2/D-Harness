# D-Harness

Android harness for DeepSeek Chat: native tools, workspace sandbox, background agent.

## Highlights (1.7)

- **Research**: `research.web`, URL preview, HTML text extract, research plans
- **Exec languages**: run Python/Node/PHP/Ruby/Lua/Perl/sh when present on device
- **Workspace grep**, regex tools, base64/uuid/time
- **Primary API**: `DHarness.*` (see `selftest()`, `help()`, `capabilities()`)
- Encrypted secrets, sensors, torch, geo, clipboard, CORS-free HTTP

## Tool call

```json
{"tool":"run_js","description":"Web research","args":{"code":"return await research({op:'web',query:'Material 3'})"}}
```

## Docs

- In-app **Send system instructions**
- `list_tools` / `describe` / `help`
- [CHANGELOG.md](CHANGELOG.md)

## License

See repository license file.

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
