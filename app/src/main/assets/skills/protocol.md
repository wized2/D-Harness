---
name: protocol
description: Required tool-call format using function_calls and invoke.
---
# Protocol

## Format

```xml
<function_calls>
<invoke name="shell">
<parameter name="command">ls -la</parameter>
</invoke>
</function_calls>
```

Rules: wrap in function_calls; one invoke per tool; args as parameter tags; then stop.

## Example — list tools

```xml
<function_calls>
<invoke name="list_tools">
</invoke>
</function_calls>
```

## Example — parallel-style (two invokes)

```xml
<function_calls>
<invoke name="workspace.ls">
</invoke>
<invoke name="skill.read">
<parameter name="name">workspace</parameter>
</invoke>
</function_calls>
```
