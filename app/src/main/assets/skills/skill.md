---
name: skill
description: Closed skill set and how to load them. Read when unsure which skill applies.
---
# Skills

**Closed set** — only these names exist:

| name | when |
|------|------|
| shell | pipelines, multi-step |
| workspace | files under workspace |
| github | GitHub API / push / PR |
| http | fetch / download |
| device | sensors, clipboard, keys |
| protocol | tool-call format only |
| exec | binaries / term / languages |
| memory | on-device notes |
| tasks | checklist helpers |
| research | web research helpers |
| skill | this file |

Flow:
1. `skill.list` if unsure  
2. `skill.read` with `name` = **exactly** one row above  
3. Never invent a skill name (e.g. no coding, no android, no files)

Unknown skill → error includes `available`. Pick from that list.
