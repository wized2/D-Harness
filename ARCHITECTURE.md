# D-Harness Architecture

```
chat.deepseek.com (WebView)
        │
        ▼
   shim.js  ── detect JSON / DSML tool calls
        │      settle + requestId validation
        │      TOOL_RESULT inject + auto-continue
        │      slash commands, task bar, compaction
        ▼
 native_bridge.js
        ▼
 HarnessBridge.kt  (@JavascriptInterface + invokeJson)
        │
        ├─ workspace / fs / history
        ├─ policy + trajectory
        ├─ research / github / device
        └─ AgentService (foreground)
```

## Dispatch

1. Shim assigns `requestId`, calls `invokeJson(tool, argsJson, requestId)`.
2. Native: policy check → mode gates → `dispatchByName` → envelope with `requestId`, `tool`, `durationMs`.
3. Shim rejects mismatched `requestId` / tool (`STALE_RESPONSE`).
4. Result posted as `TOOL_RESULT:…` user message.

## DSML

Optional. Parses V4, V4.1 spaced tags, mangled `||DSML||`, bare `function_calls`, JSON-in-invoke.

## Design rules

See README. Snapshot before write; verify mutations; catalog status; no low chain caps.
