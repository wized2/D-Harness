# D-Harness Tools

All tools are invoked as:

```json
{"tool":"NAME","description":"short","args":{}}
```

JSON only (DSML removed). Roots: **workspace.*** → app workspace dir; **fs.*** → harness_fs.

## Meta
| Tool | Status | Notes |
|------|--------|-------|
| list_tools | ready | Catalog + status |
| describe | ready | One tool schema |
| selftest | ready | Binding probe |
| selftest.deep | ready | Write/read/policy/task probes |
| tools.for_task | ready | Suggest tools for a task string |
| tools.groups | ready | Grouped tool names |
| agent.metrics | ready | Trajectory / policy mode sizes |

## Workspace
| Tool | Status |
|------|--------|
| workspace.ls / read / write / apply_patch / apply_patch_multi | ready |
| workspace.replace / grep / glob / head / tail / diff | ready |
| workspace.count / which / stat / mkdir / rm | ready |

## Agent
| Tool | Status |
|------|--------|
| task.add / update / list / clear | ready |
| memory.append / project.context | ready |
| session.save / load / list / fork | ready |
| history.list / revert | ready |
| trajectory.log | ready |
| policy.allow / deny / check / mode | ready |
| dispatch.log / errors | ready |

## Code / exec / archive
exec, exec.lang, code.search/outline/slice/find_todos, archive.zip_* — ready when bound.

## Net
research.web, http_request, github.* — ready (github needs PAT).

## Slash commands (shim)
`/help` `/ls` `/read path` `/grep q` `/glob` `/tasks` `/task text` `/memory text` `/project` `/index` `/log` `/selftest` `/tools query` `/policy tool`
