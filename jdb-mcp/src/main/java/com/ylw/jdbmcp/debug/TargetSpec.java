package com.ylw.jdbmcp.debug;

import java.util.List;

/**
 * 启动目标 JVM 所需的全部信息（从 {@link com.ylw.jdbmcp.StartParams} 派生）。
 *
 * <p>{@code jar} 与 {@code mainClass} 二选一：给 {@code jar} 走 {@code java -jar}（用 manifest 主类）；
 * 给 {@code mainClass} 走 {@code java -cp classpath mainClass}。
 *
 * @param jar        可执行 jar 路径（-jar 模式），可为 null
 * @param mainClass  主类全名（-cp 模式），可为 null
 * @param classpath  classpath 条目（-cp 模式），可为 null/空
 * @param jvmArgs    额外 JVM 参数
 * @param args       程序参数
 * @param workingDir 工作目录，null 表示当前目录
 * @param suspend    启动后是否挂起等待调试器 attach
 * @param jdkPath    JDK 路径，null 表示用运行时 JDK（java.home）
 */
public record TargetSpec(
        String jar,
        String mainClass,
        List<String> classpath,
        List<String> jvmArgs,
        List<String> args,
        String workingDir,
        boolean suspend,
        String jdkPath
) {}
