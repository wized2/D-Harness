# D-Harness tools

## Protocol (Claude-style invokes — preferred)

```xml
<function_calls>
  <invoke name="shell">
    <parameter name="cmd">ls -la</parameter>
  </invoke>
</function_calls>
```

- Multiple `<invoke>` blocks = parallel / sequential batch in one message
- After `</function_calls>`, stop and wait for `TOOL_RESULT`
- `Bash` → `shell`; `Read` → `workspace.read`; `Write` → `workspace.write`

### Compat: tool_call fence

```tool_call
{"tool":"list_tools","description":"catalog","args":{}}
~~~```

## Primary tool: shell

| Form | Example |
|------|---------|
| cmd | `<parameter name="cmd">ps -A \| head</parameter>` |
| command | Claude Code alias of cmd |
| argv | JSON array in parameter |
| script | JSON array of `{tool,args,as?}` steps |

Prefer **one** `shell` script with many steps over many separate invokes.

## Discover

`list_tools`, `describe`, `tool.search`, `tools.examples`, `tools.help`

## Roots

- **workspace.*** — app workspace
- **fs.*** — harness_fs
- **github.*** — GitHub API (PAT in Settings)
- **device.*** / **clipboard.*** / **keys.***

See in-app `list_tools` for the live catalog.
