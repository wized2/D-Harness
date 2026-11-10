# D-Harness 1.40.0

Hardening release: faster page loads, safer permissions, working background agent, stronger docs and skills.

## Fixes
- Background agent service was never declared, so it could not start; it is now registered (specialUse foreground type) and stops itself when idle
- Encrypted key store could crash every launch after a restore; backups now exclude it and the store resets itself if unreadable
- Key migration no longer risks losing keys (written to disk before the plaintext copy is wiped)
- Renderer crashes no longer kill the app
- Large workspace files no longer freeze the browser (bounded reads)
- Update check ran only once ever; it now runs once per launch

## Performance
- No cache wipe on every page load
- Injection payload built once off the main thread; repeat passes skip work when the shim is live

## Security
- Camera, microphone and location granted only to deepseek.com
- Backup exclusions for secrets; clearer security documentation

## Docs and skills
- All 13 skills rewritten with executable `invoke` examples and real tool names; unit-tested
- New README, tool reference, architecture, contributing guide and roadmap

## Install
Releases are signed with one key. If an older build was signed differently, uninstall it first.
