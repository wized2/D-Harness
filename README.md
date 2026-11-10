<h1 align="center">D-Harness</h1>

<p align="center">
  <b>On-device agent harness for DeepSeek Chat</b><br/>
  WebView + native tools + sandboxed workspace.<br/>
  No ads · No trackers · No analytics
</p>

<p align="center">
  <a href="https://github.com/wized2/D-Harness/actions/workflows/build-release.yml"><img src="https://github.com/wized2/D-Harness/actions/workflows/build-release.yml/badge.svg" alt="CI &amp; Release"/></a>
  <a href="https://github.com/wized2/D-Harness/releases/latest"><img src="https://img.shields.io/github/v/release/wized2/D-Harness?label=release" alt="Release"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT"/></a>
  <img src="https://img.shields.io/badge/Android-8%2B-green.svg" alt="Android 8+"/>
</p>

<p align="center">
  <a href="https://github.com/wized2/D-Harness/releases/latest"><b>Download APK</b></a> ·
  <a href="#features"><b>Features</b></a> ·
  <a href="TOOLS.md"><b>Tools</b></a> ·
  <a href="ARCHITECTURE.md"><b>Architecture</b></a> ·
  <a href="SECURITY.md"><b>Security</b></a>
</p>

D-Harness wraps the DeepSeek chat site in an Android app and gives the model real tools: files, shell, HTTP, GitHub, device features. The model asks for a tool in a special code fence, the app runs it on your phone, and the result goes back into the chat.

## Features

- **Shell-first agent**: multi-step work in one pipeline (needs *Settings > exec_shell*)
- **Sandboxed workspace**: list, read, write, edit, search files in an app-private folder
- **GitHub and HTTP**: the token is stored encrypted; fetch, push and PR helpers
- **Skills**: 13 short guides the model loads on demand (`skill.list` / `skill.read`)
- **Device tools**: clipboard, battery, network, sensors, notifications, location (when permitted)
- **Background agent**: a foreground service keeps a tool chain alive while the app is in the background
- **Material You settings**: light, dark or system theme

## Requirements

- Android 8.0 (API 26) or newer
- A DeepSeek account (the app loads chat.deepseek.com)

## Install

Download the APK from the [latest release](https://github.com/wized2/D-Harness/releases/latest) and open it. Allow installs from your browser or file manager when Android asks.

Releases are signed with one long-lived key, so updates install over the previous version. If an older build was signed with a different key, uninstall it first.

## Tool calls

The harness executes tools **only** from a fenced code block tagged `invoke`:

````text
```invoke
<function_calls>
<invoke name="shell">
<parameter name="command">ls -la</parameter>
</invoke>
</function_calls>
```
````

- One or more `<invoke>` elements per `<function_calls>`; arguments are `<parameter name="...">` tags
- After the closing fence the model stops and waits for the real result
- Tool markup outside an `invoke` fence is shown but never run. `dsml` and `tool_call` fences are still accepted for compatibility

An invalid call returns `ok: false` with `error.code` and `error.hint`, so the model can correct itself.

## Skills

| Skill | Load before |
|-------|-------------|
| `protocol` | writing any tool call |
| `shell`, `exec` | running commands |
| `workspace` | files in the sandbox |
| `github`, `http` | network and GitHub work |
| `device` | phone state and features |
| `memory`, `tasks` | notes and checklists |
| `research` | web research |
| `time`, `safety`, `skill` | clocks, safe use, how skills work |

Only these names exist. See [TOOLS.md](TOOLS.md) for the tool reference.

## Settings worth knowing

| Setting | Effect |
|---------|--------|
| exec_shell | Allows the `shell` tool. Off by default |
| Auto inject | Injects the tool shim into each page |
| GitHub PAT | Stored in encrypted key storage; used by `github.*` |
| Explore workspace | Browse, share, rename, delete the agent's files |

## Security and privacy

- No analytics SDK, ads or trackers. Workspace files and tool results stay on the device unless *you* use a network tool
- Secrets live in Android Keystore-backed encrypted storage and are excluded from backups
- Camera, microphone and location are granted only to deepseek.com pages
- The shell and exec allowlists reduce mistakes; they are **not** a security boundary. Only enable `exec_shell` if you trust the model's output and the pages you browse. Details and reporting: [SECURITY.md](SECURITY.md)

## Build

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew assembleDebug            # debug APK
./gradlew assembleRelease          # R8-shrunk release (signed when RELEASE_* env vars are set)
```

CI runs the JS syntax check, unit tests and the release build on every pull request. Pushes to `main` also sign and publish a GitHub release. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Related

| Project | What |
|---------|------|
| **[Fern](https://github.com/wized2/Fern)** | Live system monitor (Material You) |
| **[Latch](https://github.com/wized2/Latch)** | Privacy-first local automation |

## License

MIT. See [LICENSE](LICENSE).
