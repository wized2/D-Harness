---
name: protocol
description: Required tool-call format using function_calls and invoke.
---
# Protocol

## Required format

```
<function_calls>
<invoke name="shell">
<parameter name="command">ls -la</parameter>
</invoke>
</function_calls>
```

- Outer: function_calls
- Tool: invoke with name=
- Args: parameter with name=
- Optional antml: prefix on the same tags is valid
- After </function_calls>: stop and wait for the tool result

## Examples

```
<function_calls>
<invoke name="list_tools">
</invoke>
</function_calls>
```

```
<function_calls>
<invoke name="workspace.read">
<parameter name="path">readme.md</parameter>
</invoke>
</function_calls>
```

Never invent tool results. Never invent tool or skill names.
