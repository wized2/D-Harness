## 1.5.8
- Settings: Material 3 UI (cards, MaterialSwitch, outlined fields, toolbar)
- Theme.Material3.Dark; compact buttons to limit layout weight
- Material library + existing minify/shrink to limit APK growth

## 1.5.7
- Claude theme: latest **Claude.js v3.1** from dsh
- New DeepSeek-style logo asset (SVG)
- Agent instructions rewritten: exact call format, copy-paste examples, no guessing
- `list_tools` returns `call`, `notes`, and `examples` for the model
- Docs: README + release notes aligned with harness behavior

## 1.5.6
- Replace shim with upstream **dsh.js** (v7.5.0-native)
- Keep full native bridge tools
- Support direct tool JSON + run_js; force re-inject works

## 1.5.5
- Stronger auto-inject retries; durable DONE + session dedupe
