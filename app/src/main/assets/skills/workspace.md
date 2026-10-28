---
name: workspace
description: Sandbox files — list, read, write, grep.
---
# Workspace

Paths relative to the app workspace root.

## Example — list

```xml
<function_calls>
<invoke name="workspace.ls">
</invoke>
</function_calls>
```

## Example — read / write

```xml
<function_calls>
<invoke name="workspace.write">
<parameter name="path">hello.txt</parameter>
<parameter name="content">hi</parameter>
</invoke>
</function_calls>
```

```xml
<function_calls>
<invoke name="workspace.read">
<parameter name="path">hello.txt</parameter>
</invoke>
</function_calls>
```

## Example — grep

```xml
<function_calls>
<invoke name="workspace.grep">
<parameter name="query">TODO</parameter>
</invoke>
</function_calls>
```
