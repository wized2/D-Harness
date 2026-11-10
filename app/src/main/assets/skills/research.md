---
name: research
description: Web research - multi-source search, page text, previews, plans.
---
# Research

Start with a plan for anything non-trivial, then gather, then answer from what you actually fetched.

| Step | Tool | Parameters |
|---|---|---|
| Plan (no network) | `research.plan` | `topic` |
| Multi-source search | `research.web` | `query`, `maxSources` |
| Read one page | `research.html_text` | `url`, `maxChars` |
| Check a link | `research.preview` | `url` |

```invoke
<function_calls>
<invoke name="research.web">
<parameter name="query">android webview renderer crash handling</parameter>
<parameter name="maxSources">4</parameter>
</invoke>
</function_calls>
```

Then read the best source in full:

```invoke
<function_calls>
<invoke name="research.html_text">
<parameter name="url">https://developer.android.com/reference/android/webkit/WebViewClient</parameter>
<parameter name="maxChars">8000</parameter>
</invoke>
</function_calls>
```

## Rules
- Cite the URLs you used. Say plainly when sources disagree or a page failed to load.
- Search snippets are leads, not evidence; open the page before quoting a fact.
- Prefer primary sources (official docs, repositories) over summaries.
- Do not paste long page text into the chat; summarize and keep the full text in a workspace file if needed.
