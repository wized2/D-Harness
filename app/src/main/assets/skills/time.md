---
name: time
description: Current time, formatting, timezone and delays.
---
# Time

Never guess the date or time; ask the device.

| Need | Tool | Parameters |
|---|---|---|
| Now (ISO + epoch ms) | `time.now` | none |
| Format an epoch | `time.format` | `ms`, `pattern` |
| Timezone and offset | `device.timezone` | none |
| Locale | `device.locale` | none |
| Wait | `time.sleep` | `ms` (max 10000) |

```invoke
<function_calls>
<invoke name="time.now">
</invoke>
<invoke name="time.format">
<parameter name="ms">1767225600000</parameter>
<parameter name="pattern">yyyy-MM-dd HH:mm</parameter>
</invoke>
</function_calls>
```

## Rules
- `time.now` reports UTC; use `device.timezone` for the user's local time.
- Sleeps are capped. For polling, loop with short waits and a retry limit; do not wait blindly.
- `util.time` returns epoch, ISO and timezone in one call.
