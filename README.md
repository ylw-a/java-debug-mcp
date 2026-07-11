# jdb-mcp — Java Debug MCP Tool

A **JDI + MCP** Java debugger exposed as an MCP server for AI tool-use (Claude Code, etc.).
Core idea: the AI **pre-declares** the data it wants to see BEFORE the breakpoint fires; at hit
time the tool extracts exactly that data and returns a lightweight structured snapshot. No full
heap dumps are ever handed to the AI.

## Design

```
analyze code -> form hypothesis ("I suspect user.id is null") -> set breakpoint + declare captures
       -> tool extracts exactly what was declared -> AI gets structured result -> verify/fix
```

Two modes:
- **Mode A** (default, non-blocking): breakpoint fires in the background, capture is stored
  into a HitBuffer, thread auto-resumes. AI polls `list_hits` for snapshots.
- **Mode B** (blocking): blocks until the breakpoint hits, JVM stays suspended. AI can
  inspect live state via `explore`, `get_frames`, `get_variables`, `step`, or `eval` (dangerous),
  then `resume`.

> **DEBUG SPARINGLY.** Debugging is costly (target-JVM time + context). Only use with a
> concrete hypothesis that logs, inspection, and tests cannot resolve. The server instructions
> and every tool description emphasize this with a session-level counter that warns after 8+
> debug actions.

## Quick Start

**Zero config.** No config file needed. The server starts with sensible defaults (path depth 5,
toString 500 chars, collection 20 elements, explore budget 5, etc.). All config is passed as
tool parameters — `start_session` for initial setup, `configure` for mid-session adjustments.

Requires JDK 17 (`F:\environment\jdk-17.0.0.1`) and Maven (`F:\maven\apache-maven-3.6.3`).

```bash
export JAVA_HOME="F:/environment/jdk-17.0.0.1"
export PATH="$JAVA_HOME/bin:$PATH"

# Build demo (debug target) + jdb-mcp (shaded fat jar)
cd F:/project/java-debug
F:/maven/apache-maven-3.6.3/bin/mvn.cmd \
  -s F:/maven/apache-maven-3.6.3/conf/settings.xml \
  package -DskipTests
```

Artifacts:
- `demo/target/demo.jar` — debug target
- `jdb-mcp/target/jdb-mcp-1.0.0.jar` — MCP server fat jar

## Claude Code Wiring

```json
{
  "mcpServers": {
    "jdb-mcp": {
      "command": "F:\\environment\\jdk-17.0.0.1\\bin\\java.exe",
      "args": ["-jar", "F:\\project\\java-debug\\jdb-mcp\\target\\jdb-mcp-1.0.0.jar"]
    }
  }
}
```

No config file, no env vars required (optional `JDB_MCP_LOG` for log level). The AI passes
target info directly to `start_session`.

## Tool List (16 tools)

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
| `list_hits` | Poll snapshots for a breakpoint (mode-A retrieval). |
| `remove_breakpoint` | Disable and remove a breakpoint. |
| `resume` | Resume target after mode-B hit. |
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
e.getMessage()               exception root (exception breakpoints only)
```

The root is one of: `this`, `args[N]`, `e` (exception bp), or a named local variable.
**Null-break**: intermediate null → `status=null_break, nullBreakAt="<segment>"` — you know WHERE it broke.

**Limits** (defaults, adjustable via `configure` or `start_session`):
- path depth: 5, toString: 500 chars, collection: 20 elements, fields: 50
- Mode B budgets: explore 5, eval 2, step 5 per suspend

**Safe mode** (`safeMode=true`): no method invocation at all — no getters, no toString, no
List expansion. Only field reads and array indexing. Recommended for production debugging
to avoid unpredictable side effects from toString/equals implementations.

## Tests

```bash
export JAVA_HOME="F:/environment/jdk-17.0.0.1"
F:/maven/apache-maven-3.6.3/bin/mvn.cmd \
  -s F:/maven/apache-maven-3.6.3/conf/settings.xml \
  -pl jdb-mcp -am test
```

- `DebugSessionIT` (integration, 4 tests): mode A (null-break, collection truncation, getter
  paths, List indexing, named param, this fields), mode B (blocking hit + explore + resume),
  resolve_class, exception breakpoint (e.getMessage/e.reason).
- `ExprCaptureTest` (3 unit tests): valid expressions, malformed, whitespace.

## Project Structure

```
java-debug/
  pom.xml                     # parent pom (modules: demo, jdb-mcp)
  README.md
  demo/                       # debug target (executable jar)
  jdb-mcp/
    pom.xml                   # mcp, slf4j-simple; shade fat jar
    src/main/java/com/ylw/jdbmcp/
      Main.java               # entry: stdio MCP server, zero-config
      Defaults.java            # default constants
      StartParams.java         # start_session parameters
      mcp/                    # MCP layer (ToolDefs, McpJson, ToolResponses)
      debug/                  # JDI core (DebugSession, JdiEventLoop, SessionConfig,
                             #   BreakpointManager, Breakpoint, ClassResolver, ProxyFilter,
                             #   ExprCapture, ExprEval, TargetLauncher, TargetSpec, CommandBus)
      snapshot/               # POJO models (Hit, HitBuffer, ValueNode, CaptureResult)
    src/test/java/...         # integration + unit tests
```

Package naming: `com.ylw.jdbmcp` (`.mcp` / `.debug` / `.snapshot`).