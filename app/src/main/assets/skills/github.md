---
name: github
description: GitHub API and file push/PR. Read before any github.* tool.
---
# GitHub skill

Requires a PAT in D-Harness Settings (repo scope for push; workflow if needed).

## Preferred tools
- `github.push_file` — use `localPath` from workspace; nested repo paths OK
- `github.pull` / `github.contents` — read remote
- `github.request` — raw API; large JSON may spill
- `github.pr_create` / `github.diff` — PRs

## Rules
- Never invent commit SHAs or PR numbers
- On AUTH_MISSING — stop and tell user to set PAT
- Prefer push_file + localPath over embedding huge content in args
