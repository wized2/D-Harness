---
name: exec
description: Binaries, term argv, curl/wget, language runners. Read before exec.* or term.
---
# Exec

- Prefer `shell` with `argv` or `cmd` for simple commands  
- `term` for argv-style runs  
- `exec.which` / `exec.lang` when a runtime is required  
- Large downloads → `http.fetch_to_file` into workspace, then process  

Never invent stdout. On missing binary, report error and fall back if documented.
