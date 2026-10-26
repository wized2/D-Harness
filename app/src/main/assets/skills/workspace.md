---
name: workspace
description: App workspace files. Read before workspace.* or Read/Write file tools.
---
# Workspace skill

Root is the app workspace directory (not whole device storage).

## Common tools
- `workspace.ls` — list
- `workspace.read` / `workspace.write` — text
- `workspace.read_b64` — binary-safe
- Claude aliases: Read→workspace.read, Write→workspace.write (path from file_path)

## Rules
- Read before edit
- Prefer relative paths under workspace
- Large outputs may spill to `tmp/…` — follow `spill_path`
- After downloads, use `workspace.check_encoding` when encoding matters
