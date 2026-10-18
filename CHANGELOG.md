## 1.26.1-beta

## 1.26.2-beta — tool calls actually execute after DeepSeek render

Root cause: DeepSeek strips ```tool_call fences from message textContent after markdown render, so every call became `legacy-bare` + MISSING_TOOL_CALL_FENCE and never ran.

Fix:
- Complete `{ "tool", "args" }` JSON is executed (source: `bare-json`)
- Fence format still preferred when present
- DOM code blocks with tool JSON still scanned
- Local tests: bare JSON, fenced, and mixed prose all extract correctly


- Fix tool detection: DeepSeek strips backticks in DOM — detect language-tool_call / pure tool JSON in code blocks
- Accept plain ``` closer as well as ~~~``` and end_tool_call
- Settle/stream wait uses DOM-complete tool calls

