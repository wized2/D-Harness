## 1.13.0

- **Long agent chains:** 6 send retries; failed TOOL_RESULT unlocks message for retry; settle 250ms
- **fs.write / fs.delete** verified; history snapshot before mutation
- **history.list / history.revert** — Cursor-style file version rollback
- **task.add / update / list / clear** — Claude Code TodoWrite-style task state
- **session.save / load / list** — Codex-style session persistence
- **policy.allow / deny / check** — session tool policy
- **dispatch.log / dispatch.errors** — last 50 calls inspectable
- **index.build / index.find** — lightweight workspace file index
- **intent.open_url** safe (NEW_TASK, no crash)
- **toybox.list** real applet list (not help-text words)
- invokeJson routes for all of the above

## 1.12.0

- **Root Cause A fix:** `DHarness.invokeJson(tool, argsJson, requestId)` — all tools unwrap JSONObject fields (no more `[object Object]`)
- **Request IDs** on every dispatch; STALE_RESPONSE if mismatch
- **workspace.write** read-back verification (`verified:true` + sha256 of on-disk bytes)
- **clipboard.write** unwrap + read-back verify; rejects `[object Object]`
- **keys.get** masked by default
- **Busy watchdog** (45s) — stops tool chain from freezing after ~8–9 tools
- Empty tool chips on reload skipped when no preview
- native_bridge exposes `invokeJson`

## 1.11.0

- **DSML V4 parser**: handles `<｜DSML｜ calls>`, mangled `||DSML||`, `toolcalls` typos, multi-parameter invoke blocks
- **Native-first tools**: dispatch routes for apply_patch, replace, grep, head/tail, tree, github, http, time, uuid — `run_js` is one explicit tool only
- Faster tool settle (320ms) + lower scan throttle; theme poll 5s (less main-thread work)
- Stronger agent instructions: read-before-edit, native over run_js, efficiency rules
- native_bridge: replace / head / tail helpers

## 1.10.0

- **workspace.apply_patch** — sequential exact search/replace (Claude Code style); fails if `old` not unique unless `replace_all`
- **Atomic writes** — temp + fsync + rename for `workspace.write` / `write_b64`; always return `sha256`
- Stronger agent instructions: read-before-edit, patch discipline, GitHub vs HTTP, efficiency rules
- README rewritten for production FOSS (real capabilities only)

## 1.9.19

- Claude theme uses [Anthropic-Fonts](https://github.com/wized2/Anthropic-Fonts) (CDN, zero APK font bloat)
- Optional code highlighting via same repo (highlight + token-highlight)

## 1.9.18

- Bind workspace.grep + exec.lang (were catalog-only)
- workspace.read offset/maxBytes range reads
- cwd no longer doubles absolute workspace paths
- Invalid tool JSON returns parse error (not silent drop); contentB64 write path
- Large TOOL_RESULT → output_too_large error with range hint
- http_request vs github.request auth notes in catalog

## 1.9.17

- **Critical:** TOOL_RESULT send no longer deadlocks on `busy` (was waiting forever mid-tool)
- Tool chips no longer pruned away by React re-renders
- Balanced detect timing (settle 450ms); more reliable send confirmation
- Always create Tools chip before tool execution

## 1.9.16

- Faster tool detection (settle/scan) and quicker TOOL_RESULT send

## 1.9.15

- Aggressive system-prompt UI hide (MutationObserver + text rewrite)
- Shorter efficiency-focused system prompt (less tool spam)

## 1.9.14

- Auto-send TOOL_RESULT: wait for idle, retry send, prefer send button click
- Dotted tools (`research.web`, `workspace.write`, …) resolve correctly
- Hide system-prompt text in UI (show user message only)
- Tighter system prompt: no probing, no mentioning instructions

## 1.9.13

- Fix group tools `text` / `calc` (positional args to native bridge)
- Catalog: `text.stats` (was hash_preview); crypto.hash accepts `text` alias
- Handle `pending_clear_fs` from Settings (clear harness_fs)
- Default `exec_shell` to false (allowlist exec still works)
- Settings tools blurb clarifies shell toggle

## 1.9.12

- System prompt leads with anti-DSML / JSON-only rules and concrete examples
- Robust DSML parser fallback (DeepSeek invoke markup still executes)
- Wait for incomplete DSML while streaming

## 1.9.11

- Expanded system prompt: full tool map, globals, skills/workflows, args, formats, limits

## 1.9.10

- Remove empty Tools chips
- System prompt on each new chat
- DSML fallback parse + JSON-only prompt

## 1.9.9

- One **Tools used · Ns** card for the whole tool chain (not one per tool)
- Expand shows full Thoughts / steps list

## 1.9.8

- Tool UI: Grok-style **Thoughts** panel for multi-tool chains (expandable steps)
- Per-step running/done/error in tagline panel

## 1.9.7

- +28 coding/GitHub tools: code.search/slice/imports, workspace.replace/glob/head/tail, github.pr_list/merge, workflows, releases, tree, gist, …
- System prompt tool map updated

## 1.9.6

- **Critical:** restore shim after broken minify (tool calls were dead)
- Do not mark messages handled while tool JSON is incomplete
- Normalize smart quotes / markdown fences before JSON extract
- Faster settle/scan; allow short trailing text after tool JSON

## 1.9.5

- Accurate unified system prompt (auto-inject + Send instructions)
- Tool catalog descriptions with examples; coding tools: `code.outline`, `code.find_todos`
- GitHub: `github.issue_create`, `github.commits`
- Charts: bar, hbar, line, area, pie
- Workspace browser: path card, new folder, tighter controls
- Asset minify for smaller APK

## 1.9.4

- **Web theme**: restored original Claude.js for chat.deepseek.com (always injected)
- **Native UI only**: M3 DayNight stays for Settings / app chrome (not WebView)
- **Size**: minified assets, disabled viewBinding, R8 fullMode, nonTransitive R, broader packaging excludes

## 1.9.3

- **One M3 theme only** (Claude-inspired), always on, follows phone light/dark
- Removed Claude theme toggle from settings
- Theme uses broad selectors so it actually paints DeepSeek UI
- Size: removed unused logo asset, smaller theme script, tighter packaging/ProGuard

## 1.9.2

- **Theme**: fix Claude theme not showing — allow reinject; apply theme even when shim already live; proper remove when off
- **System prompt**: stop double-send when new chat URL gains an id (3 min global cooldown + path-segment marks + nav transfer)

## 1.9.1

- **Removed** Projects, Queue, and Research UI from the web layer
- **Artifacts**: loading placeholder while model streams; blob URL load (less black/freeze); no copy button; smoother fullscreen
- **Charts**: loading state while incomplete; faster settle
- **System prompt**: single embed per chat (in-memory + session mark; intercept uses original send)
- Theme tokens: Claude accent when theme on, DeepSeek-like M3 when off

## 1.9.0

### Web UI (Better DeepSeek–inspired)
- **Queue panel** above the composer: queued messages with **delete** and **Steer** (stop current + send now)
- **Projects** entry in the chat sidebar (near New chat), opens projects drawer
- **Research** toggle next to Search / DeepThink (prefixes research workflow)
- Research status cards helper for in-chat progress

### Behavior
- While generating/tool-chain busy, Send/Enter queues instead of dropping
- System prompt still embeds once on first message of each chat

## 1.8.2

- Remove token badge and message star bookmarks
- **System prompt**: intercept first native Send/Enter; embed once per chat; persist prompt to localStorage
- **Projects** drawer button on web UI (not only hidden panel)
- Keep prompt **queue** while generating

## 1.8.1

### Fixes
- System instructions **embed once** into the user’s first send (no multi-fire visible messages)
- HTML artifacts: blob/srcdoc load, theme-aware chrome, non-white-empty boxes
- Charts: per-block processing + faster rAF enhance (multiple charts per message)
- Artifact/chart UI follows DeepSeek / Claude theme tokens

### UI (Better DeepSeek inspired)
- Richer **Projects** (files map, active instructions)
- **Prompt queue** while generating
- **Token estimate** badge
- **Bookmarks** on messages
- **Export chat** + **Persona** field

## 1.8.0

### Artifacts (Claude / Gemini style)
- Embed **HTML / simulation / interactive** fences as sandboxed iframes
- Fullscreen, reload, copy HTML; `dh.toast` / `dh.log` from artifact → host

### Auto system prompt
- On **new chat**, inject detailed Claude-style harness instructions once
- Model asked to **acknowledge in one short sentence**, then wait normally

### Agent prompt
- Stronger operating principles, artifacts, projects, research, tool discipline

## 1.7.1

### In-chat UI (Better DeepSeek / Claude inspired)
- **Projects** panel: create, select active, save instructions (localStorage)
- **SVG bar charts** from `chart` fences or JSON `{labels,values}`
- **File-tree** cards, code **Copy** buttons, improved tables
- Zero new native libs (no app size bloat)

### Agent
- Instructions document projects + chart fences

## 1.7.0

### Research & web
- `research.web` — DuckDuckGo + Wikipedia + page text
- `research.preview` / `research.html_text` / `research.plan`

### Exec languages
- `exec.lang` (python3, node, php, ruby, lua, perl, sh)
- `exec.langs` / `exec.which`

### Workspace & text
- `workspace.grep`
- `text.regex_find` / `text.regex_replace`
- `util.base64` / `util.uuid` / `util.time`

### Agent
- Expanded system instructions: research workflow, multi-step tools

## 1.6.4

- **Secrets encrypted at rest** — GitHub PAT and custom keys use EncryptedSharedPreferences + Android Keystore
- One-time migration from plaintext `dharness_keys`; old file wiped
- Removed plaintext `settings.github_pat` fallback

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
