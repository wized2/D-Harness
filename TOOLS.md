# Tools

The live catalog is authoritative: call `list_tools` (all tools), `describe` with `name` (one tool: parameters, example, failure modes) or `tool.search` with `query`. This page is the map.

## Call format

````text
```invoke
<function_calls>
<invoke name="TOOL">
<parameter name="ARG">value</parameter>
</invoke>
</function_calls>
```
````

Only `invoke` fences run (`dsml` and `tool_call` are accepted for compatibility). After the fence the model stops and waits for the result. Results carry `ok`; on failure read `error.code` and `error.hint`.

## Core

| Tool | Parameters | Notes |
|------|------------|-------|
| `list_tools` | none | Full catalog |
| `describe` | `name` | One tool or group |
| `tool.search` | `query` | Find by keyword |
| `skill.list` / `skill.read` | `name` | Closed skill set |
| `selftest` | none | Which tools are actually bound |

## Files

| Group | Highlights |
|-------|-----------|
| `workspace.*` | `ls`, `tree`, `read` (offset/maxBytes), `write`, `append`, `replace`, `apply_patch`, `grep`, `glob`, `mv`, `cp`, `rm`, `stat`, `hash`, `head`, `tail` |
| `fs.*` | Plain sandbox file API: `read`, `write`, `list`, `delete`, `copy`, `move` |
| `code.*` | `outline`, `search`, `slice`, `find_todos`, `imports`, `detect_lang` |
| `archive.*` | `zip_list`, `zip_extract`, `zip_create` |
| `json.*`, `text.*` | Parse, query, merge, regex, split, count |

## Commands

| Tool | Parameters | Notes |
|------|------------|-------|
| `shell` | `command` or `cmd`, `timeout_ms`, `cwd` | Needs *Settings > exec_shell* |
| `shell.script`, `shell.pipe`, `shell.lines`, `shell.ok` | `script` / `cmd` | Variants |
| `exec` | `argv`, `timeout_ms`, `cwd` | Allowlisted binaries, no shell |
| `exec.lang`, `exec.langs`, `exec.allowlist` | | Script runtimes |
| `ls`, `cat`, `grep`, `find`, `sed`, `tar`, `curl`, `diff` | | Thin wrappers over allowlisted binaries |

## Network

| Tool | Parameters | Notes |
|------|------------|-------|
| `http_request` | `url`, `method`, `headers`, `body` | No GitHub token is attached |
| `http.fetch_to_file` | `url`, `dest` | Streams to a workspace file |
| `research.web`, `research.html_text`, `research.preview`, `research.plan` | | Web research |
| `github.*` | `owner`, `repo`, ... | About 40 tools; needs the `github` key. Includes PRs, issues, contents, push, releases, workflows |
| `net.*`, `dns.lookup` | | Reachability checks |

## Agent state

| Group | Highlights |
|-------|-----------|
| `task.*` | `add`, `list`, `update` (pending, in_progress, completed, cancelled), `delete`, `clear` |
| `memory.*` | Scratch key-value: `set`, `get`, `list`, `delete`, `clear`, plus `append` |
| `note.*` | Named persistent notes |
| `keys.*` | Encrypted secrets: `set`, `get`, `list`, `delete`. Never print values |

## Device

| Group | Highlights |
|-------|-----------|
| `device.*` | `info`, `battery`, `network`, `storage`, `memory`, `display`, `timezone`, `locale` |
| `sys.*`, `pkg.*` | Properties, memory, disk, processes, packages |
| `clipboard.*`, `share`, `toast`, `notify`, `vibrate`, `torch.set` | Act on the phone; use only when asked |
| `geo.get` | Needs location permission |
| `time.*`, `util.*`, `calc.*` | Clock, formatting, math, conversions |

## Escape hatch

`run_js` runs custom JavaScript in the tool sandbox. Use it only when no native tool fits.

Skills with worked examples live in `app/src/main/assets/skills/`. A unit test keeps their examples valid.
