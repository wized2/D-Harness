---
name: device
description: Device sensors, clipboard, keys, sys props. Read before device.* / clipboard.* / keys.*.
---
# Device skill

On-device only. No cloud.

- `device.*` — battery, network, sensors as exposed
- `clipboard.get` / `clipboard.set`
- `keys.*` — secure key store when available

Never fabricate sensor values; if a probe fails, report the error.
