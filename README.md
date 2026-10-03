# D-Harness

**Production Android harness for [DeepSeek Chat](https://chat.deepseek.com/)** — WebView + JS tool shim + native Kotlin bridge.

Not a demo. Tools run on-device against a sandboxed workspace; the model drives them with structured tool calls.

**Current: 1.15.0** — Robust DSML V4 / V4.1 detection (optional), unified workspace root, auto-continue, policy enforcement, multi-file patches, project/memory context.

## What works

| Area | Capabilities |
|------|----------------|
| **Workspace** | `ls`, `read`, `write` (atomic + sha256), `apply_patch`, `apply_patch_multi`, `replace`, `grep`, `glob`, `head`/`tail`, `diff`, `mkdir`/`rm` |
| **Agent loop** | JSON + optional DSML tool detect → native execute → `TOOL_RESULT` → continue / auto-continue |
| **Tasks & memory** | `task.*`, `memory.append`, `project.context` (PROJECT.md / AGENTS.md / CLAUDE.md / MEMORY.md) |
| **Session** | `session.save` / `load` / `list` / `fork` |
| **Index** | `index.build` / `find` / `fresh` |
| **History** | Snapshot on mutations; `history.list` / `revert` |
| **Policy** | `policy.allow` / `deny` / `check` — enforced in dispatcher |
| **Research** | `research.web`, HTML extract, `http_request` |
| **GitHub** | PAT-backed `github.request` and helpers |
| **Code** | `code.search`, `outline`, `slice`, `find_todos`, `count_lines`, … |
| **Archive** | `archive.zip_create` / `list` / `extract` |
| **Device** | info, clipboard, notify, sensors, … |
| **UI** | Claude-style theme, tool chips, FAB panel (DSML / auto-continue toggles) |
| **Background** | Foreground service during multi-step chains |

## Tool call formats

### JSON (recommended)

```json
{"tool":"workspace.apply_patch","description":"fix typo","args":{"path":"Main.kt","edits":[{"old":"foo","new":"bar"}]}}
```

Then stop and wait for `TOOL_RESULT`.

### DSML V4 / V4.1 (optional)

Enable **DSML V4/V4.1 detect** in the FAB panel. The shim accepts:

- V4: `<|DSML|tool_calls>` … `<|DSML|invoke name="…">` … `<|DSML|parameter …>`
- V4.1: spaced tags `<|DSML| calls>` / `<|DSML| invoke>` / `<|DSML| parameter>`
- Mangled web forms: `<||DSML||tool_calls>`, bare `<function_calls>`

JSON inside `invoke` is also supported.

## Build

```bash
./gradlew :app:assembleRelease
```

Release uses R8 minify + resource shrink. Sign with a **single** long-lived keystore so updates install cleanly.

## Settings

- Paste / store secrets (e.g. GitHub PAT under key `github`)
- Send system instructions into the current chat
- Explore workspace files on device
- FAB panel: debug, dedupe, DSML detect, auto-continue

## Architecture

```
DeepSeek WebView  ←→  shim.js (JSON + DSML detect, TOOL_RESULT, auto-continue)
                         ↓
                   native_bridge.js
                         ↓
                   HarnessBridge.kt  (workspace, HTTP, GitHub, device, policy)
```

## Design rules

1. One arg convention: JSONObject handlers via `invokeJson`
2. Request IDs validated client-side (stale response rejected)
3. Verification on mutations (`verified` + sha256)
4. Catalog should match callable tools
5. Policy checked before every dispatch
6. Snapshot before write
7. No arbitrary low tool-call caps

## License

MIT — see repository license.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
