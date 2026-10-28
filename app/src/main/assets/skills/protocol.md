---
name: protocol
description: Required tool-call format (function_calls + invoke). Read if format is unclear.
---
# Protocol

## Required format (only)

```
<function_calls>
<invoke name="shell">
<parameter name="cmd">ls -la</parameter>
</invoke>
</function_calls>
```

- `name` on invoke = real tool from list_tools  
- Parameters only via `<parameter name="…">…</parameter>`  
- Then **STOP** and wait for TOOL_RESULT  

## Do not use
DSML / ｜DSML｜ / ||DSML|| wrappers, partial tags, or invented skill/tool names.

## Compat (last resort)
```tool_call
{"tool":"list_tools","description":"catalog","args":{}}
~~~```
