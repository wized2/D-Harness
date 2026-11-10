---
name: device
description: Read device state and use phone features - battery, network, clipboard, location, notifications.
---
# Device

Read-only status calls are safe. Calls that act on the phone (vibrate, torch, notify, clipboard write, volume) affect the user directly; use them only when asked.

## Status

```invoke
<function_calls>
<invoke name="device.info">
</invoke>
<invoke name="device.battery">
</invoke>
<invoke name="device.network">
</invoke>
</function_calls>
```

Also: `device.storage`, `device.memory`, `device.display`, `device.uptime`, `device.sensors`, `sys.meminfo`, `sys.df`.

## Actions
| Tool | Parameters |
|---|---|
| `clipboard.read`, `clipboard.write` | `text` |
| `toast` | `message` |
| `notify` | `title`, `body` |
| `vibrate` | `ms` |
| `torch.set` | `on` |
| `share` | `text` |
| `intent.open_url` | `url` |
| `geo.get` | none |

## Rules
- `geo.get` needs location permission and a trusted page. If denied it returns an error; ask the user to grant it instead of retrying.
- `clipboard.read` may contain private text. Do not repeat it unless the user asked for it.
- Do not change volume, ringer or settings (`settings.put`) without an explicit request.
