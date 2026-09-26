## 1.5.17

- Tagline shows only tool description (no result JSON chip)
- Fixed Send system instructions
- Explore workspace browse mode

## 1.5.16

- Background agent FGS: tool chains continue when app is backgrounded
- Optional description on tool JSON for tagline + notification

## 1.5.15
- paste_box: **no timeout** on direct tool + any run_js that mentions paste_box
- Explore workspace: DocumentsUI **browse** mode only (FilesActivity) — never OPEN_DOCUMENT_TREE

## 1.5.14
- Explore workspace: DocumentsUI browser mode via document URI (stock Files app)
- **paste_box: no timeout** — user can paste/write as long as needed

## 1.5.13
- Fix **paste_box**: registered in shim toolHandlers + sandbox globals; robust Material dialog
- Settings: **Explore workspace** opens system document UI at app workspace path
- Sandbox also exposes list_tools, workspace, device, keys, github, http_request

## 1.5.12
- Restore **Material 3** settings UI
- Restore previous launcher icon
- Size: R8 + shrinkResources + focused ProGuard keeps for used Material widgets only
- paste_box uses Material dialog again

## 1.5.11
- (reverted direction) AppCompat-only experiment

## 1.5.10
- Settings PAT field / button gaps
