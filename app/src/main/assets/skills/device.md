---
name: device
description: Device info, clipboard, sensors, notify when permitted.
---
# Device

## Example

```xml
<function_calls>
<invoke name="list_tools">
</invoke>
</function_calls>
```

Then call the specific device tool name from the catalog (clipboard, sensors, notify, …). Respect permission errors; do not invent readings.
