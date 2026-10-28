---
name: shell
description: Primary multi-step tool. Pipelines, command, script steps.
---
# Shell

Prefer one shell call over many tiny tools.

## Example

```xml
<function_calls>
<invoke name="shell">
<parameter name="command">ls -la</parameter>
</invoke>
</function_calls>
```

## Parameters
- `command` or `cmd` — shell string  
- `argv` — JSON array (no shell expansion)  
- `script` — JSON array of `{tool, args, as?}` steps; later steps may use `$VAR` / `$LAST`

Never invent stdout. On failure, read error and retry with a corrected invoke.
