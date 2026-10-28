---
name: safety
description: Safe tool use on-device — destructive actions, secrets, network.
---
# Safety

- Never delete workspace roots or app data unless the user clearly asks.
- Secrets: use `keys.*` storage; do not echo tokens into chat or files unless asked.
- Network: prefer https; report status codes honestly.
- Shell: avoid `rm -rf /` style commands; stay under workspace when possible.
- If a tool fails, surface error.hint — do not invent success.
