<h1 align="center">D-Harness</h1>

<p align="center">
  <b>On-device agent harness for DeepSeek Chat</b><br/>
  WebView + native tools + sandboxed workspace.<br/>
  Offline-capable tools · No ads · No trackers
</p>

<p align="center">
  <a href="https://github.com/wized2/D-Harness/actions/workflows/build.yml"><img src="https://github.com/wized2/D-Harness/actions/workflows/build.yml/badge.svg" alt="Build &amp; Release"/></a>
  <a href="https://github.com/wized2/D-Harness/releases/latest"><img src="https://img.shields.io/github/v/release/wized2/D-Harness?label=release" alt="Release"/></a>
  <a href="https://github.com/wized2/D-Harness/stargazers"><img src="https://img.shields.io/github/stars/wized2/D-Harness?style=social" alt="Stars"/></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT"/></a>
  <img src="https://img.shields.io/badge/Android-8%2B-green.svg" alt="Android 8+"/>
  <img src="https://img.shields.io/badge/Material%20You-3-purple.svg" alt="Material You"/>
</p>

<p align="center">
  <a href="https://github.com/wized2/D-Harness/releases/latest"><b>Download APK</b></a> ·
  <a href="#features"><b>Features</b></a> ·
  <a href="TOOLS.md"><b>Tools</b></a> ·
  <a href="#tool-calls"><b>Tool calls</b></a>
</p>

## Features

- **Shell-first agent** — multi-step work in one pipeline when possible  
- **Workspace** — list, read, write, grep under an app sandbox  
- **GitHub & HTTP** — PAT in encrypted keys; fetch and research helpers  
- **Skills** — progressive docs (`skill.list` / `skill.read`) before a domain  
- **Device tools** — clipboard, sensors, notify, and more when permitted  
- **Material You settings** — light / dark / system theme  
- **Clean tool chips** — human-readable steps (Reading… / Read…)  

## Tool calls

The model must emit tools in this shape:

```xml
<function_calls>
<invoke name="shell">
<parameter name="command">ls -la</parameter>
</invoke>
</function_calls>
```

- One or more `<invoke>` blocks inside `<function_calls>`  
- Args as `<parameter name="…">value</parameter>`  
- Optional `antml:` prefix on the same tags  
- Then **stop** and wait for the real tool result  

Compat: a fenced JSON `tool_call` block is still accepted by the harness.

## Skills

| Skill | When to load |
|-------|----------------|
| `shell` | pipelines, multi-step |
| `workspace` | files in the sandbox |
| `github` | API / push / PR |
| `http` | fetch / download |
| `device` | sensors, clipboard, keys |
| `protocol` | tool-call format |
| `exec` | binaries / languages |
| `memory` / `tasks` | notes & checklists |
| `research` | web research |
| `time` / `safety` | clocks & safe use |
| `skill` | how skills work |

Only these names exist. Use `skill.list` if unsure.

## Install

```text
https://github.com/wized2/D-Harness/releases/latest
```

Or build:

```bash
./gradlew assembleRelease
```

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

Release builds use R8 minify + resource shrink. Prefer one long-lived signing key so updates install cleanly.

## Privacy

Tool results and workspace files stay on device unless **you** use network tools (HTTP / GitHub). No analytics SDK. No ads.

## Related

| Project | What |
|---------|------|
| **[Fern](https://github.com/wized2/Fern)** | Live system monitor (Material You) |
| **[Latch](https://github.com/wized2/Latch)** | Privacy-first local automation |

## Contributing

1. Open an issue or pick a labeled issue  
2. Keep PRs focused; run CI green  
3. See [CONTRIBUTING.md](CONTRIBUTING.md) and [TOOLS.md](TOOLS.md)  

## License

MIT — see [LICENSE](LICENSE).
