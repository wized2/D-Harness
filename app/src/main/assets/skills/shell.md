---
name: shell
description: Primary execution surface. Run device commands and multi-tool pipelines. Read before shell/Bash/term/exec.
---
# Shell skill

Prefer **one** `shell` (or Bash) invoke over many separate tools.

## Forms
- `cmd` / `command`: shell string (pipes need exec_shell enabled in Settings)
- `argv`: JSON array `["ls","-la"]`
- `script`: JSON array of steps `[{"tool":"…","args":{…},"as":"VAR"}]`

## Pipeline
Later steps may use `$VAR` / `${VAR}` and `$LAST` from previous compact output.
`stop_on_error` defaults true. Nested shell is forbidden.

## When
Use for 2+ steps, file handoff, or anything that is naturally a pipeline.
