---
name: http
description: Plain HTTP requests and downloads to the workspace.
---
# HTTP

Use `http_request` for public web APIs and pages. For api.github.com use the `github.*` tools; they carry the stored token and `http_request` never does.

## GET

```invoke
<function_calls>
<invoke name="http_request">
<parameter name="url">https://example.com/api/items</parameter>
</invoke>
</function_calls>
```

## POST JSON

```invoke
<function_calls>
<invoke name="http_request">
<parameter name="url">https://example.com/api/items</parameter>
<parameter name="method">POST</parameter>
<parameter name="headers">{"Content-Type":"application/json"}</parameter>
<parameter name="body">{"name":"demo"}</parameter>
</invoke>
</function_calls>
```

## Large downloads
`http.fetch_to_file` streams the body to a workspace file and returns only metadata.

```invoke
<function_calls>
<invoke name="http.fetch_to_file">
<parameter name="url">https://example.com/data.zip</parameter>
<parameter name="dest">downloads/data.zip</parameter>
</invoke>
</function_calls>
```

## Rules
- https only in practice; cleartext http is blocked by the app.
- Response bodies are truncated in the result (about 400k characters). Download to a file when you need everything.
- Check `ok` and the HTTP status before using the body. Report failures; never fabricate a response.
- Send secrets only to the service they belong to. Read them with `keys.get`; never print them.
