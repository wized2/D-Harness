---
name: workspace
description: App workspace files — list, read, write, grep, mkdir.
---
# Workspace

Paths are relative to the app workspace root unless absolute under that root.

- `workspace.ls` — list
- `workspace.read` path — text (large files may spill)
- `workspace.write` path + content
- `workspace.grep` query — search text files
- Prefer shell script only when chaining many file ops

Do not invent file contents. After write, verify with read if the user needs confirmation.
