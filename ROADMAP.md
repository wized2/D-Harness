# Roadmap

Planned work, most important first. No dates.

## Security
- Restrict the JavaScript bridge to the chat origin (WebMessage-based channel instead of `addJavascriptInterface`)
- Strict policy for state-changing tools, based on an explicit list rather than name matching
- Stream and cap all reads (HTTP, files, archives) so no tool can exhaust memory
- Send the GitHub token only to api.github.com
- Give `exec` and `shell` children a closed stdin and bounded output

## Reliability
- Shut down worker pools and child processes when the activity is destroyed
- Clean up spill and temp files
- Android instrumented tests for the WebView and bridge

## Product
- Tool chips: collapsible long output, copy result
- More skills as tool groups grow, each with tested examples
- In-app update download (verified against the release signing certificate)

## Docs
- Troubleshooting guide for the most common tool errors
