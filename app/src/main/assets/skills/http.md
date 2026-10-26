---
name: http
description: HTTP fetch and download. Read before http_request / fetch_url / http.fetch_to_file.
---
# HTTP skill

- `http_request` — method/url/headers/body
- `http.fetch_to_file` — save body into workspace
- Prefer fetch_to_file for large payloads then workspace.read

Respect timeouts; do not invent response bodies.
