## 1.26.1-beta

- Fix tool detection: DeepSeek strips backticks in DOM — detect language-tool_call / pure tool JSON in code blocks
- Accept plain ``` closer as well as ~~~``` and end_tool_call
- Settle/stream wait uses DOM-complete tool calls

