---
name: shell
description: Primary multi-step tool. Pipelines, argv, script steps with $VAR / $LAST.
---
# Shell (primary)

Prefer **one shell call** over many tiny tools.

## Parameters
- `cmd` / `command` — shell string
- `argv` — JSON array of args (no shell expansion)
- `script` — array of `{ "tool", "args", "as?" }` steps; later steps may use `$VAR` and `$LAST`

## Patterns
- List then read: workspace.ls → workspace.read
- Fetch then save: http.fetch_to_file → workspace.read
- GitHub push: write file → github.push_file

## Failures
- Missing binary → report error; try exec.which or a different path
- Never invent stdout
- Keep pipelines short and check intermediate ok flags when using script
