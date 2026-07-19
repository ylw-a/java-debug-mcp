package com.ylw.jdbmcp;

import java.util.List;

/**
 * start_session 入参：target（launch 或 attach）+ 可选初始会话配置覆盖。
 *
 * <p>模式推断：{@code attachHost} 非空 -> attach 到已运行 JVM；否则 launch（需 {@code jar} 或 {@code mainClass}）。
 * <p>所有 limits/budgets 字段可空 -> 走 {@link Defaults}。MCP 层从工具入参解析填充此对象，
 * 再交由 {@code DebugSession.start} 应用。
 */
public final class StartParams {

    // ---- launch 模式 ----
    /** 可执行 jar 路径（-jar 模式），与 mainClass 互斥。 */
    public String jar;
    /** 主类全名（配合 classpath），与 jar 互斥。 */
    public String mainClass;
    /** classpath 条目（-cp 模式）。 */
    public List<String> classpath;
    /** 额外 JVM 参数。 */
    public List<String> jvmArgs;
    /** 程序参数。 */
    public List<String> args;
    /** 工作目录，null 表示当前目录。 */
    public String workingDir;
    /** 启动后是否挂起等待调试器 attach（默认 true）。 */
    public boolean suspend = true;
    /** JDK 路径，null 表示用运行时 JDK（java.home）。 */
    public String jdkPath;

    // ---- attach 模式 ----
    /** attach 目标主机，非空表示 attach 模式。 */
    public String attachHost;
    /** attach 目标端口。 */
    public Integer attachPort;

    // ---- 可选初始会话配置覆盖（null -> Defaults）----
    public Integer maxDepth;
    public Integer maxStrLen;
    public Integer maxCollSize;
    public Integer maxFields;
    /** 单次 capture 渲染节点预算（防大对象爆炸）。 */
    public Integer maxRenderNodes;
    public Integer exploreBudget;
    public Integer evalBudget;
    public Integer stepBudget;
    public Integer modeBTimeoutSec;
    public Boolean allowEval;
    public Boolean safeMode;
}
