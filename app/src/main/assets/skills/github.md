---
name: github
description: GitHub REST via the stored token - repos, issues, PRs, files, releases.
---
# GitHub

Authenticated with the token stored as key `github` (Settings > GitHub PAT). Never print or echo the token. If a call returns 401/403, tell the user to check the token and its scopes.

## Read

```invoke
<function_calls>
<invoke name="github.me">
</invoke>
<invoke name="github.repo">
<parameter name="owner">octocat</parameter>
<parameter name="repo">hello-world</parameter>
</invoke>
</function_calls>
```

| Need | Tools |
|---|---|
| Repos, branches, tags, tree | `github.repos`, `github.branches`, `github.tags`, `github.tree` |
| Issues | `github.issues`, `github.issue`, `github.issue_create`, `github.issue_update`, `github.issue_comment` |
| Pull requests | `github.pr_list`, `github.pr`, `github.pr_files`, `github.pr_commits`, `github.pr_reviews`, `github.pr_create`, `github.pr_ready`, `github.pr_comment`, `github.pr_merge` |
| Files | `github.contents`, `github.pull` (to workspace), `github.push_file`, `github.push_files_batch` |
| Commits, compare | `github.commits`, `github.compare` |
| CI and releases | `github.workflows`, `github.workflow_runs`, `github.releases`, `github.release_latest` |
| Search | `github.search` with `query`, `type` |
| Anything else | `github.request` with `method`, `path`, `body` |

## Push work (workspace to GitHub)
Work on a branch, not the default branch, unless the user says otherwise.

```invoke
<function_calls>
<invoke name="github.branch_create">
<parameter name="owner">me</parameter>
<parameter name="repo">app</parameter>
<parameter name="branch">fix/readme</parameter>
<parameter name="from">main</parameter>
</invoke>
<invoke name="github.push_files_batch">
<parameter name="owner">me</parameter>
<parameter name="repo">app</parameter>
<parameter name="branch">fix/readme</parameter>
<parameter name="message">docs: fix readme</parameter>
<parameter name="files">[{"path":"README.md","localPath":"README.md"}]</parameter>
</invoke>
</function_calls>
```

## Rules
- `github.push_files_batch` makes ONE commit for many files; prefer it over many `push_file` calls.
- `github.pr_merge` and `github.issue_update` with `state=closed` change shared state. Do them only when the user asked.
- Use `github.request` only for endpoints without a dedicated tool. It is for api.github.com; other hosts belong to `http_request`.
- Results are compact. Fetch a single item (`github.pr`, `github.issue`) for details.
