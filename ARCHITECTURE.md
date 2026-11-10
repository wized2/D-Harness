# Architecture

```
DeepSeek page (WebView)  <-- shim.js: detects invoke fences, renders tool chips
        |  window.DHarness (JavascriptInterface)
        v
HarnessBridge.kt  -->  tool catalog (~330 tools) --> workspace, shell/exec, HTTP, GitHub, device
        |
        +--> SecureStore (encrypted keys)    +--> AgentService (foreground keep-alive)
```

## Components

| Component | Role |
|-----------|------|
| `MainActivity` | Hosts the WebView, injects the shim, handles popups (sign-in), permissions, renderer crashes |
| `assets/shim.js`, `native_bridge.js`, `claude_theme.js` | Page-side: find `invoke` fences, call the bridge, hide raw markup, show results |
| `HarnessBridge` | Native side: tool catalog, argument handling, limits, policy checks, result envelope |
| `bridge/*` | Pure Kotlin helpers (workspace paths, catalog cache) with unit tests |
| `SecureStore` | Keystore-backed `EncryptedSharedPreferences`; recovers if the store is unreadable |
| `AgentService` | Foreground service (specialUse) + wake lock so a chain survives backgrounding; stops itself when idle |
| `SettingsActivity`, `WorkspaceBrowserActivity` | Settings and a file browser for the workspace |
| `assets/skills/*.md` | The 13 skills served by `skill.read` |

## Call flow

1. The model writes an `invoke` fence in its reply.
2. The shim parses it, calls `DHarness.invoke...` with the tool name and JSON arguments.
3. The bridge runs the tool on a worker thread, enforces limits, and returns `{ok, data | error}`.
4. The shim sends the result back into the chat as the next message.

## Trust boundaries

- **Model output is untrusted.** Tools run with the app's own permissions only; the workspace is app-private storage; exec uses an allowlist and `shell` needs an explicit setting.
- **Page content is untrusted.** Every JavaScript bridge method is reachable from any page loaded in the WebView. Google sign-in and captcha pages stay in-app, so the app keeps the allowed hosts short and only deepseek.com gets camera, microphone and location. See [SECURITY.md](SECURITY.md).
- **Secrets** are encrypted at rest, excluded from backups, and never returned by list calls.

## Build and release

Gradle (AGP 8, Kotlin 2.0, minSdk 26). CI: JS syntax check, unit tests, R8 release build on every PR; on `main` the APK is zipaligned, signed with the release key, verified (a debug-signed APK is refused) and attached to a GitHub release.
