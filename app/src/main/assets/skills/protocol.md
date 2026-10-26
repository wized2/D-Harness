---
name: protocol
description: How to emit tool calls (Claude XML and DSML). Read if unsure about format.
---
# Protocol skill

## Preferred (Claude / Claude Code style)
```
<function_calls>
<invoke name="shell">
<parameter name="cmd">ls -la</parameter>
</invoke>
</function_calls>
```

## Also accepted (DeepSeek DSML — if the model emits it)
```
<｜DSML｜function_calls>
<｜DSML｜invoke name="shell">
<｜DSML｜parameter name="cmd" string="true">ls -la</｜DSML｜parameter>
</｜DSML｜invoke>
</｜DSML｜function_calls>
```

Variants with `tool_calls`, spaced ` calls`, or mangled `||DSML||` are parsed the same.

## Compat
```tool_call
{"tool":"list_tools","description":"catalog","args":{}}
~~~```

After any closed tool block: **STOP** and wait for `TOOL_RESULT`. Never invent results.
