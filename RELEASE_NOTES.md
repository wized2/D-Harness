# D-Harness 1.37.0

## Highlights

- **Stronger invoke-only agent instructions** — `function_calls` / `invoke` / `parameter` with clear examples  
- **Skills with concrete examples** — every domain skill shows a real invoke  
- **Fern-style README & release notes** — badges, features table, install path  
- **Tool chip UI** — compact, readable Running vs Done labels  
- **Theme** — System / Light / Dark in Settings  

## For users

1. Install or update from [Releases](https://github.com/wized2/D-Harness/releases/latest)  
2. Open Settings → Appearance for theme  
3. Start a new chat so the agent gets the latest instructions  

## For contributors

- Tool surface: [TOOLS.md](TOOLS.md)  
- Skills live in `app/src/main/assets/skills/`  
- Prefer small PRs; keep CI green  

## Notes

The harness prioritizes Claude-style invoke parsing. Other historical shapes may still execute if the model emits them, but instructions only teach invoke.
