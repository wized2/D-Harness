---
name: exec
description: Run allowlisted binaries and short scripts without a shell.
---
# Exec

`exec` runs one allowlisted binary with an argument array. No shell parsing, so there is no globbing, piping or expansion. Use it when `shell` is disabled or you want exact argv.

## Run a binary

```invoke
<function_calls>
<invoke name="exec">
<parameter name="argv">["ls", "-la"]</parameter>
<parameter name="timeout_ms">10000</parameter>
</invoke>
</function_calls>
```

## Discover first
- `exec.allowlist` - binaries that may run
- `exec.which` with `bin` - where a binary lives
- `exec.langs` - script runtimes present on this device

## Short scripts

```invoke
<function_calls>
<invoke name="exec.lang">
<parameter name="lang">sh</parameter>
<parameter name="code">echo ready</parameter>
</invoke>
</function_calls>
```

## Notes
- A binary outside the allowlist fails with `NOT_ALLOWLISTED`. The allowlist is a guard rail against mistakes, not a security boundary; do not try to bypass it.
- `argv` is a JSON array of strings. The first item is the binary.
- Many common commands also exist as dedicated tools (`ls`, `cat`, `grep`, `find`, `sed`, `tar`, `curl`). Prefer `workspace.*` for sandbox files.
