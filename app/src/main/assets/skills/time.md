---
name: time
description: Time, timezone, and scheduling helpers when available via device tools.
---
# Time

Prefer device clock tools when present (`device.time`, sensors time, or shell `date`).

- Use ISO-8601 when writing timestamps into files.
- Do not invent wall-clock times; read from the device.
- For delays inside shell pipelines use `sleep` via shell script steps, not fake waiting in chat.
