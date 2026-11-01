## Done in 1.39.0
- [x] Fence-only tool detection (invoke/dsml)
- [x] System prompt requires ```invoke
- [x] Color resources restored (CI)

# D-Harness roadmap (execution tracker)

## Done in 1.38.0
- [x] Catalog cache (`CatalogCache`) for `listTools`
- [x] Interruptible bridge delays (`BridgeExec`) — no raw `Thread.sleep` left
- [x] Workspace path helper + unit tests
- [x] Payload truncate helper
- [x] Shim: debounced MutationObserver, slower enhance timers, neutral tool chips, no emoji step icons
- [x] Native typeface from Anthropic Sans + JetBrains Mono assets
- [x] Neutral Material color tokens (light/dark)

## Next
- [ ] Split `HarnessBridge.kt` into feature modules (workspace/shell/github/device)
- [ ] Further shim code-split (core / ui / theme)
- [ ] Settings sectioned list redesign (XML pass)
- [ ] AgentService notification = tool description only
- [ ] Device multi-tool scenario checklist
