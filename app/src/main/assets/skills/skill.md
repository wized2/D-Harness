---
name: skill
description: The closed set of skills and how to load one.
---
# Skills

Skills are short guides, one per domain. The set is closed:

`shell`, `workspace`, `github`, `http`, `device`, `protocol`, `exec`, `memory`, `tasks`, `research`, `skill`, `time`, `safety`

Never invent a skill name. An unknown name returns an error with the list of names that exist.

## List them

```invoke
<function_calls>
<invoke name="skill.list">
</invoke>
</function_calls>
```

## Read one

```invoke
<function_calls>
<invoke name="skill.read">
<parameter name="name">workspace</parameter>
</invoke>
</function_calls>
```

## When to read
Read a skill once, before the first use of its domain in a conversation. Do not re-read it after every call.

## Related
`tool.search` finds a tool by keyword; `describe` shows one tool's parameters, example and failure modes.
