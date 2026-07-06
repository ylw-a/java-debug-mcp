# jdb-mcp — Java Debug MCP Tool

一个基于 **JDI + MCP 0.9.0** 的 Java 调试工具，作为 MCP server 暴露给 AI（如 Claude Code）。
核心思想：**AI 在设断点前就预声明要看的数据**，命中时由程序端按声明精确提取，形成轻量结构化快照——
不把全量调试数据塞给 AI，减少对话轮次、控制快照大小、保证提取时机正确。

## 设计思路

```
分析代码 → 形成假设（"怀疑 user.id 为 null"）→ 设断点 + 声明要看的数据
       → 程序端按声明提取 → AI 拿结构化结果验证假设 → 修正或继续
```

两种模式：
- **模式 A**（默认）：非阻塞。断点后台命中时自动按预声明 capture → 存入 HitBuffer → 自动 resume。
  AI 调 `list_hits` 拉取快照。不实质阻塞目标 JVM。
- **模式 B**：阻塞。`set_breakpoint(mode=B)` 阻塞到命中后返回 hit，JVM 保持挂起，
  AI 可用 `explore`/`eval` 深挖（逃生通道，严格限量），最后 `resume`。

> **非必要不调试。** 调试成本高（目标 JVM 时间 + 上下文）。仅在有具体假设、且日志/检查/测试
> 都无法定位时才用。server instructions 与每个工具描述都强调这一点，并有会话级计数器在
> debug 动作超过 8 次时追加警告，防止 AI "调试上瘾"。

## 构建

要求 JDK 17（`F:\environment\jdk-17.0.0.1`）和 Maven（`F:\maven\apache-maven-3.6.3`）。
默认 PATH 的 JDK 是 1.8，**不要用**，必须显式指定 JDK 17。

```bash
export JAVA_HOME="F:/environment/jdk-17.0.0.1"
export PATH="$JAVA_HOME/bin:$PATH"

# 构建 demo（被调试目标）+ jdb-mcp（shaded 胖包）
cd F:/project/java-debug
F:/maven/apache-maven-3.6.3/bin/mvn.cmd -s F:/maven/apache-maven-3.6.3/conf/settings.xml package -DskipTests
```

产物：
- `demo/target/demo.jar` — 被调试的演示程序
- `jdb-mcp/target/jdb-mcp-1.0.0.jar` — MCP server 胖包（含 Main-Class，`java -jar` 即可运行）

## 配置 `jdb-mcp.json`

放在工作目录（或用环境变量 `JDB_MCP_CONFIG` 指定路径）：

```json
{
  "target": {
    "classpath": ["F:/project/java-debug/demo/target/demo.jar"],
    "mainClass": "com.ylw.demo.DemoApp",
    "args": [], "jvmArgs": [],
    "workingDir": "F:/project/java-debug",
    "suspend": true
  },
  "debug": {
    "host": "127.0.0.1",
    "port": 0,
    "maxHits": 1000,
    "captureDepth": 5,
    "toStringLimit": 500,
    "collectionLimit": 20,
    "includeProxies": false,
    "allowEval": false,
    "modeB": { "exploreBudget": 5, "evalBudget": 2, "defaultTimeoutSec": 60 }
  },
  "logLevel": "INFO"
}
```

- `port: 0` — 目标 JDWP 监听随机端口，server 从子进程输出解析后 attach。
- `suspend: true` — 目标启动后挂起等待调试器附加、设断点、再 resume。
- `allowEval: false` — eval 工具默认关闭（需显式开启 + 用户同意）。

## Claude Code 接线

在 CC 的 MCP 配置中加入：

```json
{
  "mcpServers": {
    "jdb-mcp": {
      "command": "F:\\environment\\jdk-17.0.0.1\\bin\\java.exe",
      "args": ["-jar", "F:\\project\\java-debug\\jdb-mcp\\target\\jdb-mcp-1.0.0.jar"],
      "env": { "JDB_MCP_CONFIG": "F:\\project\\java-debug\\jdb-mcp.json" }
    }
  }
}
```

> **重要**：MCP 走 stdio，协议流量在 stdin/stdout。本工具的所有日志和目标程序输出都走 **stderr**，
> 绝不污染 stdout 协议通道。

## 工具列表（10 个）

| 工具 | 作用 |
|---|---|
| `start_session` | 按 config 拉起目标 JVM 并 attach；或传 `attach:{host,port}` 附加已运行 JVM |
| `stop_session` | detach 并终止目标，清空断点与命中 |
| `session_status` | 附件状态、断点列表+命中计数、HitBuffer 大小、剩余 modeB 预算 |
| `list_classes` | 模糊/短名解析已加载类（默认过滤代理类） |
| `set_breakpoint` | **核心**。预声明 `captures`，`mode:"A"` 非阻塞 / `"B"` 阻塞到命中 |
| `list_hits` | 按断点 + 命中序号区间查询快照 |
| `remove_breakpoint` | 禁用并移除断点 |
| `resume` | 模式 B 命中后恢复目标 JVM |
| `explore` | 模式 B 挂起时按读路径深挖（逃生通道，限量） |
| `eval` | 模式 B 任意无参方法调用（默认关，需 allowEval + 用户同意） |

### 典型模式 A 流程

```jsonc
// 1. 启动
start_session({})
// 2. 设断点 + 预声明要看的数据
set_breakpoint({
  "class": "UserService", "method": "processUser",
  "captures": ["args[0].name", "args[0].address.id", "args[1].items", "this.callCount"],
  "mode": "A"
})
// 3. 轮询命中快照
list_hits({ "breakpoint_id": "bp-1", "from_hit": 1, "to_hit": 3 })
```

### 典型模式 B 流程

```jsonc
set_breakpoint({ "class": "UserService", "method": "processUser",
                 "captures": ["args[0].name"], "mode": "B", "timeout": 30 })
// → 阻塞到命中，返回 hit；JVM 保持挂起
explore({ "expr": "args[0].address.city" })   // 限量深挖
resume({})                                      // 继续
```

## 表达式语法（预声明 captures）

```
this                       当前对象
this.id                    当前对象字段
user.id                    局部变量字段
user.address.id            嵌套路径
user.getAddress().getId()  通过 getter
args[0]                    方法参数（按位置）
args[0].name               参数字段
tags[i]                    数组下标
```

**null 中断**：路径中途遇 null 会明确返回中断位置。
例如 `user.address.id`，若 `address` 为 null → `status=null_break, nullBreakAt="address"`。
知道在哪里中断比只知道结果是 null 价值高得多。

**截断与限深**：
- 路径最大展开层数：5（根之后最多 5 次解引用）
- 单个 `toString()` 最大长度：500
- 集合/数组最大取值元素：20（只展开前 20 个）

**安全边界**：
- `capture`（读路径）只允许字段读取 + 无参 getter（`get*`/`is*` 及小安全白名单 `size`/`isEmpty`/`toString` 等）。
- 集合（`java.util.List`）：`[i]` 下标通过调 `get(int)` 实现（只读位置访问，带 `size()` 范围检查）；整集合渲染时调 `size()`+`get(i)` 展开前 N 个。
- `eval` 是任意无参方法调用（可有副作用，如 `delete()`），与 capture 严格分开，默认关闭。

## 代理类过滤

Spring 应用充斥 CGLIB/JDK 动态代理，默认 `include_proxies=false` 过滤掉。识别规则：
- 类名特征：`$$EnhancerBySpringCGLIB$$`、`$$FastClassBySpringCGLIB$$`、`$Proxy<数字>`、`_$$_javassist`、`$$_hibernate_interceptor`、`$$Lambda$`
- 接口/父类：实现 `org.springframework.cglib.proxy.Factory`、`java.lang.reflect.InvocationHandler`、`javassist.util.proxy.ProxyObject`、`org.hibernate.proxy.HibernateProxy`，或父类为 `java.lang.reflect.Proxy`

## 类名解析

AI 说"在 UserService 下断点"时，server 不直接用字面名（Spring 里 UserService 可能是接口，真实 Bean 是 `UserServiceImpl$$EnhancerBySpringCGLIB$$xxx`）。`ClassResolver` 在 `vm.allClasses()` 中模糊匹配短名，过滤代理，多个则返回候选清单（含 fqcn/是否代理/是否接口/是否已加载）让 AI 挑选。

**延迟断点**：若类尚未加载（`suspend=y` 启动时常见），自动注册 `ClassPrepareRequest`，
类加载后实体化断点。短名用 `*.Name` 过滤（任意包下该类名），fqcn 直接用。

## 命中管理

`HitBuffer` 是容量 1000 的有界环形队列，超出滚动丢弃最旧。每个断点维护 `hit_count`，
命中按断点序号（1-based）编号，`list_hits` 可指定区间查询。

## 线程模型

1. **Main 线程**：建 MCP server、注册工具、阻塞保活。
2. **MCP handler 线程**：MCP SDK 调度，处理工具调用。
3. **JDI Event Loop 线程**：attach 后启动，循环 `EventQueue.remove()` 处理断点/类加载事件。

关键约束：
- **JDI 对象不跨线程**：所有 `Value`/`StackFrame` 操作只在 Event Loop 线程执行；产出纯 POJO 快照
  （`Hit`/`ValueNode`）放入 HitBuffer，handler 只读 POJO。
- **resume 并发不混**：模式 A 命中 → Event Loop 自动 `thread.resume()`；模式 B 命中 → 不 resume，
  进入"挂起命令循环"等 `explore`/`eval`/`resume` 指令（经 `CommandBus` 投递到 Event Loop 执行）。
- 模式 B 阻塞：handler 调 `vm.resume()` 后在 `CountDownLatch` 上等待，事件命中后 `countDown` 唤醒。

## 模式 B 限制（防滥用）

- 每次挂起：`explore` 预算 5 次、`eval` 预算 2 次，超限直接拒绝并提示"resume 后用预声明重新武装"。
- 同时只允许一个模式 B 挂起。
- `set_breakpoint(mode=B)` 默认超时 60s，超时后断点保留但降级为模式 A（后续命中进 HitBuffer）。
- `eval` 双闸：config `allowEval=false` 默认关 + 工具描述强制"需用户明确同意"。
- 会话级 debug 动作计数器，超 8 次在结果里追加警告。

## 测试

```bash
export JAVA_HOME="F:/environment/jdk-17.0.0.1"
F:/maven/apache-maven-3.6.3/bin/mvn.cmd -s F:/maven/apache-maven-3.6.3/conf/settings.xml -pl jdb-mcp -am test
```

- `DebugSessionIT`（集成，拉起真实 demo JVM）：
  - 模式 A：null 中断、集合截断（25→20）、getter 路径、`this` 字段、命中计数。
  - 模式 B：阻塞返回 hit、`explore`、`resume`。
  - `list_classes` 解析。
- `ExprCaptureTest`（解析单测，无需 JVM）。

demo 程序（`com.ylw.demo.DemoApp`）覆盖：嵌套对象路径、null 中断（`user.address=null`）、
getter 空中断、集合截断（25 元素）、循环链表（深度限深 + 防环）。

## 项目结构

```
java-debug/
  pom.xml                     # 父 pom（modules: demo, jdb-mcp）
  jdb-mcp.json                # 目标配置
  README.md
  demo/                       # 被调试的演示程序（可执行 jar）
  jdb-mcp/
    pom.xml                   # 依赖 mcp:0.9.0 + slf4j-simple；shade 胖包
    src/main/java/com/ylw/jdbmcp/
      Main.java               # 入口：建 MCP server，注册工具，stdio
      Config.java             # jdb-mcp.json 加载
      mcp/                    # MCP 层（ToolDefs, McpJson, ToolResponses）
      debug/                  # JDI 调试核心（DebugSession, JdiEventLoop,
                             #   BreakpointManager, ClassResolver, ProxyFilter,
                             #   ExprCapture, ExprEval, TargetLauncher, CommandBus）
      snapshot/               # 快照数据模型（Hit, HitBuffer, ValueNode, CaptureResult）
    src/test/java/...         # 集成测试 + 单测
```

包名约定（按概设）：`com.ylw.jdbmcp`（`.mcp` / `.debug` / `.snapshot`）。
