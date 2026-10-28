# Contributing

## Add a native tool

1. Implement `@JavascriptInterface fun myTool(...): String` returning JSON `{ok, ...}`.
2. Route in `dispatchByName` / `invokeJson` `when` branch: `"my.tool" -> myTool(sAny("arg"))`.
3. Register in `listTools()` with `status = "ready"`.
4. Prefer `safeWorkspace` for workspace paths; verify writes.

## Shim

- Tool detection: `extractClaudeInvokes` (function_calls / invoke) plus fence JSON fallback.
- Keep settle/scan timeouts configurable via `CONFIG`.

## Build

```bash
./gradlew :app:assembleRelease
```

Use the repo release keystore so APK updates install over previous builds.
