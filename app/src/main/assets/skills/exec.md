---
name: exec
description: Binaries, term argv, language runners.
---
# Exec

## Example

```xml
<function_calls>
<invoke name="shell">
<parameter name="command">uname -a</parameter>
</invoke>
</function_calls>
```

Use term/exec tools from list_tools when argv-style is clearer. Missing binary → report error.
