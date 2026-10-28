---
name: http
description: HTTP fetch and download into workspace.
---
# HTTP

## Example

```xml
<function_calls>
<invoke name="http_request">
<parameter name="url">https://example.com</parameter>
<parameter name="method">GET</parameter>
</invoke>
</function_calls>
```

Prefer saving large bodies with fetch-to-file tools when listed in list_tools. Report status codes honestly.
