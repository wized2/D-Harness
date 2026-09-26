## 1.6.3

- **Primary API documented**: `DHarness.*` + four conventions in list_tools / agent instructions
- **DHarness.selftest()** — live method list + probes
- **DHarness.help(name)** / **capabilities()**
- **describe()** adds `bound` + `dharnessMethod`
- **Native geoGet** via LocationManager (not WebView)
- **toyboxList** returns real applet names
- **torchBlink**, **sensorsWatch**, **execPipeline**

## 1.6.2

- **In-app Workspace browser** — open, view, share, rename, delete, new file, copy path
- Result envelope `{ok, data, error, meta}` in TOOL_RESULT
- **sensors.list / sensors.read**, **torch.set**, **audio.volume/ringer**, **wakelock**
- **diff.lines**, **toybox.list/run**, **exec stdin**
- Catalog: selftest, conventions, honest tooling

## 1.6.1

- **Fix TOOL_RESULT serialization** — objects no longer become `[object Object]`
- Native **clipboard** read/write (ClipboardManager)
- **exec**: `sh -c` shell, exitCode/stdout/stderr, allowlist published, stream drain
- **list_tools** version matches app; documents calling conventions
- **Explore workspace** in-app file list + copy path + Files app attempt
- selftest / uuid / time helpers in shim

## 1.6.0

Ideas from [better-deepseek](https://github.com/EdgeTypE/better-deepseek) Android host:

- Chrome-like mobile UA (strip `; wv`) + improved desktop UA
- Keyboard/IME padding so the chat composer stays visible
- Smart link routing (DeepSeek / Google auth / hCaptcha in-app; other links external)
- Popup WebView for sign-in / OAuth (`target=_blank`)
- Back closes popup first, then history
- Cookie flush on pause / resume / destroy
- Built-in zoom controls (pinch) + optional larger text (110%)
- Check for updates (GitHub Releases)

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
