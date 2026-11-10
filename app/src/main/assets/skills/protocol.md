---
name: protocol
description: Required tool-call format - a fenced invoke block with function_calls XML.
---
# Protocol

Tools run ONLY when they sit inside a markdown code fence whose language tag is `invoke`.
Tool markup in normal text, or inside a fence labelled xml, is shown to the user and never executed.

## Format

```invoke
<function_calls>
<invoke name="shell">
<parameter name="command">ls -la</parameter>
</invoke>
</function_calls>
```

## Rules
- Open the fence with three backticks + `invoke`; close it with three backticks on their own line.
- One `<function_calls>` block per fence. Several `<invoke>` elements may share it; they run in order.
- Arguments are `<parameter name="key">value</parameter>`. Numbers and booleans are plain text. Arrays and objects are JSON text.
- After the closing fence STOP. Wait for the real result; never write or guess it.
- Parameter values are raw text, so escape `<` as `&lt;` and `&` as `&amp;` inside values.

## Example - no arguments

```invoke
<function_calls>
<invoke name="list_tools">
</invoke>
</function_calls>
```

## Example - two calls in one block

```invoke
<function_calls>
<invoke name="workspace.ls">
</invoke>
<invoke name="skill.read">
<parameter name="name">workspace</parameter>
</invoke>
</function_calls>
```

## Reading results
Every result has `ok`. When `ok` is false read `error.code` and `error.hint`, fix the call, and retry at most twice. Do not repeat an identical failing call.

## Discovery
`list_tools` (full catalog), `describe` with `name`, `tool.search` with `query`, `tools.examples`, `tools.help`.
