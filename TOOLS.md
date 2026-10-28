# Tools

## Call format

```xml
<function_calls>
<invoke name="TOOL">
<parameter name="ARG">value</parameter>
</invoke>
</function_calls>
```

Then stop and wait for the tool result.

## Core

| Tool | Args | Notes |
|------|------|--------|
| `list_tools` | — | Full catalog |
| `describe` | `tool` | One tool detail |
| `skill.list` | — | Closed skill names |
| `skill.read` | `name` | Full skill body |
| `shell` | `command` / `cmd`, optional `script` | Primary multi-step |
| `workspace.ls` | path? | List sandbox |
| `workspace.read` | `path` | Read text |
| `workspace.write` | `path`, `content` | Write text |
| `workspace.grep` | `query` | Search files |
| `http_request` | `url`, method? | Network |
| `github.request` | path, method, body? | Needs PAT key |

Use `skill.read` before a domain the first time. Never invent tool or skill names.

See skills under `app/src/main/assets/skills/` for examples.
