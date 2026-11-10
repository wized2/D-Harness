---
name: memory
description: On-device scratch memory, notes and secrets. Local only.
---
# Memory

Three separate stores. Pick by purpose.

| Store | Tools | Use for |
|---|---|---|
| Scratch key-value | `memory.set`, `memory.get`, `memory.list`, `memory.delete`, `memory.clear` | Facts the user asked you to remember across turns |
| Running notes | `memory.append` (`text`) | Append a short line to the agent notes |
| Named notes | `note.set`, `note.get`, `note.list` | Labelled, persistent notes |
| Secrets | `keys.set`, `keys.get`, `keys.list`, `keys.delete` | Tokens and API keys ONLY |

```invoke
<function_calls>
<invoke name="memory.set">
<parameter name="key">preferred_language</parameter>
<parameter name="value">Python</parameter>
</invoke>
<invoke name="memory.get">
<parameter name="key">preferred_language</parameter>
</invoke>
</function_calls>
```

## Rules
- Everything stays on this device. Nothing is synced.
- Never store secrets in `memory.*` or `note.*`; they are not encrypted. Use `keys.*` (encrypted) and never print a value you read with `keys.get`.
- Store only what the user asked to keep or what clearly helps later. Keep entries short.
- `memory.clear` wipes everything. Use it only when the user asks.
