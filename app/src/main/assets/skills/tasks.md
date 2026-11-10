---
name: tasks
description: Visible checklist for multi-step work.
---
# Tasks

For work with three or more steps, keep a checklist the user can see. Skip it for one-step requests.

| Action | Tool | Parameters |
|---|---|---|
| Add | `task.add` | `content` |
| List | `task.list` | none |
| Change status | `task.update` | `id`, `status` |
| Remove one | `task.delete` | `id` |
| Remove all | `task.clear` | none |

Valid `status` values: `pending`, `in_progress`, `completed`, `cancelled`.

```invoke
<function_calls>
<invoke name="task.add">
<parameter name="content">Read the failing test</parameter>
</invoke>
<invoke name="task.add">
<parameter name="content">Fix and re-run</parameter>
</invoke>
</function_calls>
```

Then, as work proceeds (use the `id` returned by `task.add` or `task.list`):

```invoke
<function_calls>
<invoke name="task.update">
<parameter name="id">TASK_ID</parameter>
<parameter name="status">completed</parameter>
</invoke>
</function_calls>
```

## Rules
- Keep titles short and concrete. Mark exactly one task `in_progress` at a time.
- Mark a task `completed` only when it is really done and verified.
- A bad `id` or `status` returns `ok: false`; fix it rather than retrying unchanged.
