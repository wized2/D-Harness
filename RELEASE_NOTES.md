# D-Harness 1.39.0

## Tool calling (fence-only)
- Tools run **only** from closed code fences: `invoke` (preferred), `dsml` (compat), `tool_call` (legacy)
- Plain-text XML / DSML / bare JSON is **not** executed (cleaner chat, reliable hide)
- Fence body may be Claude-style XML or JSON

## Shim
- Faster detection path; hide whole code box → compact tool UI
- Selectors aligned with mobile DeepSeek (`.ds-message`, assistant markdown)

## Native
- Restored full M3 color tokens (CI fix)
- System instructions require ```invoke fences

## Install
Uninstall older builds if signature differs.
