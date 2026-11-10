---
name: shell
description: Primary multi-step tool - run commands, pipelines and scripts in the workspace.
---
# Shell

Prefer one `shell` call over many small tools. The working directory is the workspace root.

**Requires Settings > exec_shell.** If it is off the call fails with an error saying so; tell the user to enable it instead of retrying. Without it use `exec` (allowlisted binaries only), `workspace.*` or `fs.*`.

## Run a command

```invoke
<function_calls>
<invoke name="shell">
<parameter name="command">ls -la</parameter>
</invoke>
</function_calls>
```

## Parameters
- `command` (alias `cmd`) - the shell string
- `timeout_ms` - optional, default is short; raise it for builds or downloads
- `cwd` - optional directory inside the workspace

Aliases: `sh`, `cmd`, `term`. Variants: `shell.pipe` (pipelines), `shell.script` (multi-line `sh -c`), `shell.lines` (stdout as lines, max 2000), `shell.ok` (true only on exit 0), `shell.which`, `shell.env`.

## Multi-line script

```invoke
<function_calls>
<invoke name="shell.script">
<parameter name="script">set -e
cd project
grep -rn TODO src | head -20</parameter>
</invoke>
</function_calls>
```

## Limits and behavior
- Output is truncated; pipe through `head` / `tail` / `grep` for big output.
- stdin is not interactive. Commands that wait for input (for example a bare `cat`) hang until the timeout. Always pass a file or use a here-string.
- Each call is a fresh shell. `cd` does not persist; pass `cwd` or chain with `&&`.
- Never invent stdout. On a non-zero exit read `stderr` and `exitCode`, fix the command, retry at most twice.
