# Contributing

## Set up

- JDK 17, Android SDK (or just push a branch and let CI build)
- `./gradlew :app:testDebugUnitTest` for unit tests; `assembleDebug` for an APK

## Workflow

1. Branch from `main`; never push to `main` directly.
2. Keep each PR small and focused. Fill in the PR template.
3. CI must be green: JS syntax check, unit tests, R8 release build.
4. Update `CHANGELOG.md`. Bump `versionName` and `versionCode` only for a release; a release needs a `## <version>` section.

## Adding a tool

1. Register it in the catalog in `HarnessBridge.kt` with a clear description, typed parameters and an example.
2. Enforce limits: cap output size, stream large bodies instead of reading them whole, resolve paths through the workspace helper so they cannot escape.
3. A tool that changes state (writes, deletes, merges, settings) must be treated as mutating by the policy check.
4. Add a unit test for any pure logic. Document the tool in the matching skill and in `TOOLS.md` if it is a new group.

## Editing skills

Skills in `app/src/main/assets/skills/` are read by the model. Examples must use an `invoke` fence and real tool names; `SkillsTest` enforces fences and the closed set of names. If you add or rename a skill, update the list in `skill.md`, `HarnessBridge`'s `skill.read` and the system prompt.

## Style

Kotlin: follow the existing style, no wildcard imports, no blocking calls on the main thread. JS: plain ES2017, `node --check` must pass.

## Security

Do not commit secrets or keystores. Report vulnerabilities privately; see [SECURITY.md](SECURITY.md).
