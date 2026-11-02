# DeepEase

Everyday personal assistant on [chat.deepseek.com](https://chat.deepseek.com) — flashlight, Wi‑Fi/Bluetooth/data panels, volume, brightness, timers. No shell, no code execution.

Branch of [D-Harness](https://github.com/wized2/D-Harness) focused on **simple, safe, fail-clear** phone actions with a colorful injected UI.

## Name
**DeepEase** — DeepSeek + everyday ease.

## What it does
- Polished CSS theme over DeepSeek chat
- Tools only via fenced ` ```invoke ` blocks
- Curated tools: torch, device info, system panels, volume, brightness, alarm/timer, clipboard

## Safety
- No workspace shell / GitHub / arbitrary exec in the DeepEase catalog
- Toggles that Android blocks open the **system settings panel** and tell you clearly

## Build
Same CI as D-Harness (`deepease` branch). Package id: `com.endroid.deepease`.
