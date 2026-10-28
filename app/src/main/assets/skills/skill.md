---
name: skill
description: Closed skill set and how to load them.
---
# Skills

**Names that exist:** shell, workspace, github, http, device, protocol, exec, memory, tasks, research, skill, time, safety

## Example

```xml
<function_calls>
<invoke name="skill.list">
</invoke>
</function_calls>
```

```xml
<function_calls>
<invoke name="skill.read">
<parameter name="name">shell</parameter>
</invoke>
</function_calls>
```

Never invent a skill name. Unknown → use `available` from the error.
