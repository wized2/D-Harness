---
name: github
description: GitHub API and helpers. Requires a saved PAT (key github).
---
# GitHub

Save a PAT in Settings (stored encrypted). Load this skill before github.* tools.

## Example

```xml
<function_calls>
<invoke name="github.request">
<parameter name="method">GET</parameter>
<parameter name="path">/user</parameter>
</invoke>
</function_calls>
```

Push helpers (when available) take path + content or repo fields from list_tools. Do not invent endpoints; prefer documented helpers or REST paths from GitHub API docs.
