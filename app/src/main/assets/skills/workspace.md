---
name: workspace
description: Sandboxed workspace files - list, read, write, edit, search.
---
# Workspace

Paths are relative to the workspace root (`workspace.pwd` shows it). Paths that escape the root are rejected.

## Quick reference
| Need | Tool | Key parameters |
|---|---|---|
| List / tree | `workspace.ls`, `workspace.tree` | `path`, `depth` |
| Read text | `workspace.read` | `path`, `maxBytes`, `offset` |
| First / last lines | `workspace.head`, `workspace.tail` | `path`, `lines` |
| Write / append | `workspace.write`, `workspace.append` | `path`, `content` |
| Edit in place | `workspace.replace`, `workspace.apply_patch` | `path`, `find`+`replace` or `edits` |
| Search | `workspace.grep`, `workspace.glob` | `query`, `regex`; `pattern` |
| Move / copy / delete | `workspace.mv`, `workspace.cp`, `workspace.rm` | `from`+`to`; `path` |
| Info | `workspace.stat`, `workspace.hash`, `workspace.count` | `path` |
| Binary | `workspace.write_b64`, `workspace.read_b64` | `path`, `contentB64` |

## List, write, read

```invoke
<function_calls>
<invoke name="workspace.write">
<parameter name="path">notes/hello.txt</parameter>
<parameter name="content">hi</parameter>
</invoke>
<invoke name="workspace.read">
<parameter name="path">notes/hello.txt</parameter>
</invoke>
</function_calls>
```

## Edit a file without rewriting it

```invoke
<function_calls>
<invoke name="workspace.replace">
<parameter name="path">notes/hello.txt</parameter>
<parameter name="find">hi</parameter>
<parameter name="replace">hello</parameter>
</invoke>
</function_calls>
```

## Search

```invoke
<function_calls>
<invoke name="workspace.grep">
<parameter name="query">TODO</parameter>
<parameter name="maxHits">50</parameter>
</invoke>
</function_calls>
```

## Limits and tips
- Large files: read in slices with `offset` + `maxBytes` instead of one huge read.
- `workspace.rm` removes a file or an EMPTY directory. Never delete the workspace root.
- Prefer `workspace.replace` / `apply_patch` over rewriting a whole file; it keeps unrelated text intact.
- `workspace.write` overwrites without asking. Check with `workspace.stat` when unsure.
- Code helpers: `code.outline`, `code.search`, `code.slice` (line range), `code.find_todos`.
