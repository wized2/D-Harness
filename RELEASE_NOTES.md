# DeepEase 1.1.1

## Fix: black screen
- Settings no longer hides the whole tree (was blanking FAB → Settings)
- Main root/WebView use surface background instead of pure black
- Stop clearing WebView cache on every page finish
- Show a recoverable error page if DeepSeek fails to load
