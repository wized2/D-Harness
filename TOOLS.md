# D-Harness tools & skills

## Protocol (strongest → compat)

1. **Claude style** (preferred)
```xml
<function_calls>
  <invoke name="shell">
    <parameter name="cmd">ls -la</parameter>
  </invoke>
</function_calls>
```


3. **tool_call fence** (compat)
```tool_call
{"tool":"list_tools","description":"catalog","args":{}}
~~~```

After any closed block: stop and wait for `TOOL_RESULT`.

## Skills (Claude Code style progressive disclosure)

| Skill | When |
|-------|------|
| `protocol` | Format uncertainty |
| `shell` | Pipelines, Bash, term |
| `workspace` | Files under app workspace |
| `github` | Any github.* |
| `http` | http_request / fetch |
| `device` | sensors, clipboard, keys |
| `exec` | term, binaries, lang runners |
| `memory` | on-device notes |
| `tasks` | checklist helpers |
| `research` | web research helpers |
| `skill` | how skills load |

```
skill.list
skill.read  name=github
```

Read the skill **before** the first tool in that domain.

## Primary: shell

One `shell` / `Bash` invoke with `script` steps is preferred over many separate calls.
