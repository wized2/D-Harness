# D-Harness

**Production Android harness for [DeepSeek Chat](https://chat.deepseek.com/)** — WebView + JS tool shim + native Kotlin bridge.

Not a demo. Tools run on-device against a sandboxed workspace; the model drives them with JSON tool calls.

**1.11:** Native-first tool routing (run_js is last-resort only), robust DeepSeek DSML V4 parsing, atomic writes + apply_patch.

## What works

| Area | Capabilities |
|------|----------------|
| **Workspace** | `ls`, `read` (range), `write` (atomic + sha256), `apply_patch`, `replace`, `grep`, `mkdir`, `rm`, `stat`, paste box |
| **Agent loop** | Detect tool JSON → native execute → inject `TOOL_RESULT` → continue |
| **Research** | `research.web`, HTML text extract, generic `http_request` |
| **GitHub** | PAT-backed `github.request` / search / me |
| **Device** | info, clipboard, notify, sensors (where available) |
| **UI** | Claude-style theme, Thoughts / tools chips, projects panel, artifacts & charts |
| **Background** | Foreground agent service during multi-step tool chains |

## Tool call format

```json
{"tool":"workspace.apply_patch","description":"fix typo","args":{"path":"Main.kt","edits":[{"old":"foo","new":"bar"}]}}
```

Then stop and wait for `TOOL_RESULT`.

## Build

```bash
./gradlew :app:assembleRelease
```

Release uses R8 minify + resource shrink. Sign with a **single** long-lived keystore so updates install cleanly.

## Settings

- Paste / store secrets (e.g. GitHub PAT under key `github`)
- Send system instructions into the current chat
- Explore workspace files on device

## Architecture

```
DeepSeek WebView  ←→  shim.js (detect tool JSON, send TOOL_RESULT)
                         ↓
                   native_bridge.js
                         ↓
                   HarnessBridge.kt  (workspace, HTTP, GitHub, device)
```

## License

MIT — see repository license.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
