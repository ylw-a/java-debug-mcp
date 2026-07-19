# jdb-mcp — Let your LLM debug Java (JDB / JDI over MCP, for Claude Code & any MCP client)

**jdb-mcp** turns an LLM into a Java debugger. It exposes **JDI** — the same engine behind `jdb`
and the **JDWP** protocol — as an **MCP** server, so AI agents like **Claude Code** can set
breakpoints, step through code, capture exceptions, and inspect a live JVM. The core advantage:
the AI **pre-declares exactly which values it wants *before* the breakpoint fires**, and jdb-mcp
returns only those as a compact structured snapshot — no heap dumps, no stack-dredging, no
context blowup. Just the data the hypothesis needs, on demand.

> **Keywords:** `LLM` · `AI agent` · `Java DEBUG` · `JDB` · `JDI` · `JDWP` · `MCP` · `Claude Code` · `breakpoint` · `live JVM inspection`

### Why jdb-mcp?

- **LLM-friendly by design.** Capture-then-snapshot means the model never sees a raw heap — only
  the few fields/paths it named. Keeps token cost and hallucination risk low.
- **Two debug modes.** Mode A: fire-and-buffer in the background, poll snapshots later. Mode B:
  arm a breakpoint non-blocking, then `resume` blocks until the hit so the AI can `explore` /
  `step` / `get_variables` interactively.
- **Expression engine.** Read paths like `this.user.id`, `args[0].items`, `arr.length`,
  `list[i]`, `sorted[mid]`, `obj.getX()` — with null-break reporting (you learn *where* a path
  went null).
- **Anti-addiction.** A session counter warns after 8+ debug actions — debugging is costly, so
  the tool steers the AI toward logs/tests first.
- **Zero-config.** No config file, no env vars. Pass target info to `start_session`; the npm
  launcher auto-detects your JDK 17+.
- **Production-safe option.** `safeMode=true` disables all method invocation (no toString/getter
  side effects) — field reads and array indexing only.

## Design

```
analyze code -> form hypothesis ("I suspect user.id is null") -> set breakpoint + declare captures
       -> tool extracts exactly what was declared -> AI gets structured result -> verify/fix
```

Two modes:
- **Mode A** (default, non-blocking): breakpoint fires in the background, capture is stored
  into a HitBuffer, thread auto-resumes. AI polls `list_hits` for snapshots.
- **Mode B** (interactive): `set_breakpoint(mode=B)` arms **non-blocking** and returns
  immediately. The AI triggers its request (curl/manual), then calls `resume` which **blocks
  until the hit** — the JVM suspends for inspection via `explore`, `get_frames`,
  `get_variables`, `step`, or `eval` (dangerous), then `resume` again to continue. One
  interactive hit per `set_breakpoint(mode=B)`; later hits degrade to mode A (buffered) —
  re-arm mode B to inspect again. `set_breakpoint` never blocks; `resume` does the waiting.

> **DEBUG SPARINGLY.** Debugging is costly (target-JVM time + context). Only use with a
> concrete hypothesis that logs, inspection, and tests cannot resolve. The server instructions
> and every tool description emphasize this with a session-level counter that warns after 8+
> debug actions.

## Quick Start

### Via npm (recommended — no source, no build)

```bash
npm install -g jdb-mcp
```

Requires: **JDK 17+** and **Node.js 16+**. That's it — no Maven, no source, no config file.

The `jdb-mcp` command auto-detects your JDK (`JAVA_HOME` → `PATH` → common install locations) and
launches the bundled debug server over stdio. If your JDK isn't on the PATH, point `JAVA_HOME`
at it:

```bash
# Linux / macOS
export JAVA_HOME=/path/to/jdk-17
jdb-mcp

# Windows (Git Bash / cmd / PowerShell)
set JAVA_HOME=F:\path\to\jdk-17
jdb-mcp
```

### Via `npx` (no global install)

```bash
npx -y jdb-mcp
```

### From source (for development)

Requires JDK 17+ and Maven 3.6+ on your PATH.

```bash
git clone https://github.com/ylw/jdb-mcp.git
cd java-debug
export JAVA_HOME=/path/to/jdk-17
mvn package -DskipTests
# fat jar: jdb-mcp/target/jdb-mcp-1.0.0.jar
java -jar jdb-mcp/target/jdb-mcp-1.0.0.jar
```

## Claude Code Wiring

### With npx (auto-install, recommended)

No `npm install` needed — `npx -y` fetches `jdb-mcp` from the registry on first run and caches it.

**Default Java is JDK 17+** (auto-detected by the launcher):

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

**Default Java is NOT JDK 17+** — point `JAVA_HOME` at your JDK 17 with the `env` field:

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

> Replace `XXX/jdk17` with your actual JDK 17 path. The launcher checks `JAVA_HOME` first
> (highest priority), then `java` on `PATH`, then common install locations.

### With global install

After `npm install -g jdb-mcp`:

```json
{
  "mcpServers": {
    "jdb-mcp": {
      "command": "jdb-mcp"
    }
  }
}
```

If your default Java is not JDK 17, add `"env": { "JAVA_HOME": "XXX/jdk17" }` as above.

### Direct jar

```json
{
  "mcpServers": {
    "jdb-mcp": {
      "command": "XXX/jdk17/bin/java",
      "args": ["-jar", "XXX/jdb-mcp-1.0.0.jar"]
    }
  }
}
```

No config file required (optional `JDB_MCP_LOG` for log level). The AI passes target info
directly to `start_session`.

## Tool List (17 tools)

| Tool | Purpose |
|---|---|
| `start_session` | **Step 1.** Launch target (jar or mainClass+classpath) or attach to running JVM. Zero-config: pass params directly. |
| `stop_session` | Detach and terminate target. Clears all breakpoints and hits. |
| `configure` | Adjust session limits/budgets mid-session (anti-addiction: raises above default are tracked). |
| `session_status` | Attachment state, breakpoints, hit counts, limits, budgets. |
| `list_debuggable_jvms` | **Pre-attach discovery.** Scan running JVMs with JDWP agents (jps -lv). No session needed. |
| `resolve_class` | Fuzzy-resolve a class name to confirm FQCN before set_breakpoint. |
| `list_classes` | Paginated enumeration of loaded classes (prefix filter, offset/limit). |
| `set_breakpoint` | **Core.** Pre-declare captures + mode A/B. Per-bp limit overrides supported. |
| `set_exception_breakpoint` | Fire when an exception is thrown. Captures use `e` root for the exception object. |
| `list_hits` | Poll snapshots for a breakpoint (mode-A retrieval). `limit` + `compact` to keep output small. |
| `remove_breakpoint` | Disable and remove a breakpoint (also clears its captured hits). |
| `resume` | After `set_breakpoint(mode=B)`: block until the hit. After inspecting: continue. |
| `get_frames` | Mode B. Full call stack of suspended thread. |
| `get_variables` | Mode B. Local variables for a frame, with optional expand_paths. |
| `step` | Mode B. Step over/out/into (budgeted, default 5/suspend). |
| `explore` | Mode B. Evaluate a read-path on suspended frame (budgeted). |
| `eval` | Mode B. Dangerous: invoke arbitrary no-arg method (disabled by default, needs allowEval=true). |

## Expression Grammar (captures, explore, expand_paths)

```
this                        current object
this.field / obj.field      field read
a.b.c.d.e                   nested path (default max 5 dereferences)
obj.getX() / obj.isX()      no-arg getter (get*/is* + size/isEmpty/length/toString)
args[N]                     method parameter by position
arr[i] / list[i]            array index, or java.util.List index (uses get(int))
arr.length                  array length (mirrored as int)
sorted[mid]                 index by named local variable (resolved at runtime)
e.getMessage()              exception root (exception breakpoints only)
```

The root is one of: `this`, `args[N]`, `e` (exception bp), or a named local variable.
**Null-break**: intermediate null -> `status=null_break, nullBreakAt="<segment>"` — you know WHERE it broke.

**Limits** (defaults, adjustable via `configure` or `start_session`):
- path depth: 5, toString: 500 chars, collection: 20 elements, fields: 50
- render node budget: 1000 per capture (explosion guard; bare `this` renders shallow — direct fields only)
- Mode B budgets: explore 5, eval 2, step 5 per suspend

**Safe mode** (`safeMode=true`): no method invocation at all — no getters, no toString, no
List expansion. Only field reads and array indexing. Recommended for production debugging
to avoid unpredictable side effects from toString/equals implementations.

## Tests

```bash
export JAVA_HOME=/path/to/jdk-17
mvn -pl jdb-mcp -am test
```

- `DebugSessionIT` (integration, 17 tests): mode A (null-break, collection truncation, getter
  paths, List indexing, named param, this fields, boxed primitives, safe_mode), mode B
  (non-blocking arm + resume blocks + explore + get_frames/get_variables/step), resolve_class,
  exception breakpoint (e.getMessage/e.reason), list_debuggable_jvms, configure,
  remove_breakpoint clears hits, list_classes substring filter, shallow bare-root render,
  render-budget truncation, array.length capture, `[var]` index resolution.
- `ExprCaptureTest` (3 unit tests): valid expressions, malformed, whitespace.

## Project Structure

```
java-debug/
  pom.xml                     # parent pom (modules: demo, jdb-mcp)
  README.md
  LICENSE                     # MIT
  demo/                       # debug target (executable jar)
  jdb-mcp/
    pom.xml                   # mcp, slf4j-simple; shade fat jar
    src/main/java/com/ylw/jdbmcp/
      Main.java               # entry: stdio MCP server, zero-config
      Defaults.java           # default constants
      StartParams.java        # start_session parameters
      mcp/                    # MCP layer (ToolDefs, McpJson, ToolResponses)
      debug/                  # JDI core (DebugSession, JdiEventLoop, SessionConfig,
                             #   BreakpointManager, Breakpoint, ClassResolver, ProxyFilter,
                             #   ExprCapture, ExprEval, TargetLauncher, TargetSpec, CommandBus)
      snapshot/               # POJO models (Hit, HitBuffer, ValueNode, CaptureResult)
    src/test/java/...         # integration + unit tests
```

Package naming: `com.ylw.jdbmcp` (`.mcp` / `.debug` / `.snapshot`).

## License

**MIT License** — the most permissive open-source license. Use it commercially, fork it, modify
it, ship it, no strings attached. Attribution appreciated but not required.

See [LICENSE](LICENSE) for the full text.

Copyright © 2026 ylw.
