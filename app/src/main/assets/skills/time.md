---
name: time
description: Device time and timestamps.
---
# Time

Read time from the device (shell `date` or device tools). Prefer ISO-8601 in files. Do not invent wall-clock times.

```xml
<function_calls>
<invoke name="shell">
<parameter name="command">date -u +%Y-%m-%dT%H:%M:%SZ</parameter>
</invoke>
</function_calls>
```
