# Security Policy

## Supported versions

Only the latest release receives fixes. Update through the in-app checker or the
[Releases page](https://github.com/wized2/D-Harness/releases/latest).

## Reporting a vulnerability

Please report privately through GitHub: **Security → Report a vulnerability** on this
repository. If that option is unavailable, open an issue that says only "security
report, please contact me" and leave out exploit details. You can expect a first
response within a few days.

## Security model (read this before enabling tools)

D-Harness loads the DeepSeek chat site in a WebView and exposes a native bridge
(`DHarness`) to JavaScript on that page. The model's replies can therefore trigger real
actions on your device.

- **Model output is untrusted input.** Web pages, files and search results the model reads
  can contain instructions (prompt injection) that steer its tool calls. Review what the
  agent is doing, especially before it writes files or uses credentials.
- **The command allowlist is a guard rail, not a sandbox.** It limits which programs
  `exec` starts, but several allowed programs can start others. Keep **Settings → shell
  access** off unless you need it.
- **Files stay in the workspace.** Workspace tools resolve paths inside the app's
  workspace folder and reject anything that escapes it.
- **Secrets are encrypted at rest** (Android Keystore-backed `EncryptedSharedPreferences`)
  and excluded from Android backups. Use fine-grained tokens with the fewest scopes and the
  shortest lifetime you can.
- **No analytics or telemetry.** Network access is limited to the chat site, update checks
  against GitHub Releases, and tools you or the model invoke (`http_request`, `github.*`,
  `research.web`).
- **Only open trusted sites.** Links outside the allowed hosts open in your browser, not
  in the app.

## Hardening checklist

1. Use a fine-grained GitHub token scoped to specific repositories.
2. Leave shell access off by default; enable it per session.
3. Install only APKs from this repository's Releases page and check the signing certificate.
