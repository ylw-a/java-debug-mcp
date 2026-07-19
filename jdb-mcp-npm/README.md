# jdb-mcp - Let your LLM debug Java (JDB / JDI over MCP, for Claude Code & any MCP client)

**jdb-mcp** turns an LLM into a Java debugger. It exposes **JDI** - the engine behind `jdb` and
the **JDWP** protocol - as an **MCP** server, so AI agents like **Claude Code** can set
breakpoints, step through code, capture exceptions, and inspect a live JVM. The core advantage:
the AI **pre-declares exactly which values it wants *before* the breakpoint fires**, and jdb-mcp
returns only those as a compact structured snapshot - no heap dumps, no stack-dredging, no
context blowup.

> **Keywords:** `LLM` · `AI agent` · `Java DEBUG` · `JDB` · `JDI` · `JDWP` · `MCP` · `Claude Code` · `breakpoint` · `live JVM inspection`

**Pre-declare your data, set one breakpoint, verify, stop.** Anti-addiction built in - a session
counter warns after 8+ debug actions, steering the AI toward logs/tests first.

## Quick Start

```bash
# Requires JDK 17+ (none of the source - just install and go)
npm install -g jdb-mcp

# Or use without installing:
npx -y jdb-mcp
```

Set `JAVA_HOME` if your JDK isn't on the PATH:

```bash
# Linux / macOS
export JAVA_HOME=/path/to/jdk-17

# Windows
set JAVA_HOME=F:\path\to\jdk-17
```

## Claude Code Wiring

**Default Java is JDK 17+** (auto-detected):

```json
{
  "mcpServers": {
    "jdb-mcp": {
      "command": "npx",
      "args": ["-y", "jdb-mcp"]
    }
  }
}
```

**Default Java is NOT JDK 17+** (point `JAVA_HOME` at it):

```json
{
  "mcpServers": {
    "jdb-mcp": {
      "command": "npx",
      "args": ["-y", "jdb-mcp"],
      "env": { "JAVA_HOME": "XXX/jdk17" }
    }
  }
}
```

Or if installed globally (`npm install -g jdb-mcp`), use `"command": "jdb-mcp"` instead
(add `env` if needed, as above).

## How It Works

1. The `jdb-mcp` command finds your JDK 17+ (`JAVA_HOME` -> `PATH` -> common locations).
2. It launches the bundled JDI debug server over MCP stdio.
3. The AI calls `start_session(jar="...")` to launch your target, or `start_session(attach={host, port})` to attach.
4. Set breakpoints with pre-declared captures, get snapshots, step through code, inspect exceptions.

## Requirements

- **JDK 17+** (any distribution: Eclipse Adoptium, Oracle, Microsoft, etc.)
- Node.js 16+ (just to run the wrapper script)
- No source code, no Maven, no build step - just `npm install`

## Tools

17 MCP tools: `start_session`, `stop_session`, `configure`, `session_status`,
`list_debuggable_jvms`, `resolve_class`, `list_classes`, `set_breakpoint`,
`set_exception_breakpoint`, `list_hits`, `remove_breakpoint`, `resume`,
`get_frames`, `get_variables`, `step`, `explore`, `eval`.

See the [full README](https://github.com/ylw/jdb-mcp) for the expression grammar,
modes, limits, and safety policy.

## License

**MIT** - the most permissive open-source license. Use it commercially, fork it, ship it, no
strings attached. See [LICENSE](https://github.com/ylw/jdb-mcp/blob/master/LICENSE).
