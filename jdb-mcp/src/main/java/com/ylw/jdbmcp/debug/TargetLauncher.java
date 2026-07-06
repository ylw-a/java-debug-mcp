package com.ylw.jdbmcp.debug;

import com.ylw.jdbmcp.Config;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Launches the target JVM as a child process with a JDWP agent, then exposes the
 * transport address (host/port) so {@code DebugSession} can attach via JDI SocketAttach.
 *
 * <p>With {@code port=0} the target picks a free port and prints a "Listening for transport
 * dt_socket at address: ..." line on stderr; we parse it. Both target stdout and stderr are
 * drained by daemon threads for the process lifetime so their OS buffers never fill.
 */
public final class TargetLauncher {

    public record LaunchedTarget(Process process, String host, int port) {}

    public static LaunchedTarget launch(Config.TargetConfig target, String host, int port) throws IOException {
        String javaHome = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaExe = javaHome + File.separator + "bin" + File.separator + "java" + (windows ? ".exe" : "");
        File javaFile = new File(javaExe);
        if (!javaFile.exists()) {
            throw new IOException("java executable not found at " + javaExe
                    + " (set java.home to a JDK 17)");
        }

        String cp = String.join(File.pathSeparator, target.classpath);
        String suspend = target.suspend ? "y" : "n";
        String address = host + ":" + port;

        List<String> cmd = new ArrayList<>();
        cmd.add(javaExe);
        cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=" + suspend + ",address=" + address);
        if (target.jvmArgs != null) cmd.addAll(target.jvmArgs);
        if (!cp.isEmpty()) { cmd.add("-cp"); cmd.add(cp); }
        if (target.mainClass == null || target.mainClass.isBlank()) {
            throw new IOException("target.mainClass is not set in config");
        }
        cmd.add(target.mainClass);
        if (target.args != null) cmd.addAll(target.args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        if (target.workingDir != null && !target.workingDir.isBlank()) {
            pb.directory(new File(target.workingDir));
        }
        Process p = pb.start();

        AtomicReference<Integer> portRef = new AtomicReference<>(null);
        CountDownLatch latch = new CountDownLatch(1);

        // The JDWP "Listening for transport ..." line may appear on stdout OR stderr
        // (JVM-dependent; observed on stdout for JDK 17). Both drain threads watch for it;
        // the first to find it wins. All target output is logged to stderr (never the MCP
        // stdout channel).
        Thread errThread = new Thread(() -> drain(p.getErrorStream(), "[target.err] ", portRef, latch), "target-stderr");
        errThread.setDaemon(true);
        errThread.start();

        Thread outThread = new Thread(() -> drain(p.getInputStream(), "[target.out] ", portRef, latch), "target-stdout");
        outThread.setDaemon(true);
        outThread.start();

        int resolvedPort = port;
        if (port == 0) {
            try {
                if (!latch.await(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("timed out waiting for target JDWP listening line on stderr");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
                throw new IOException("interrupted while waiting for target JDWP port", e);
            }
            Integer parsed = portRef.get();
            if (parsed == null || parsed <= 0) {
                p.destroyForcibly();
                throw new IOException("could not parse JDWP port from target stderr");
            }
            resolvedPort = parsed;
        }
        return new LaunchedTarget(p, host, resolvedPort);
    }

    private static void drain(InputStream in, String prefix, AtomicReference<Integer> portRef, CountDownLatch latch) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (portRef != null && latch != null && latch.getCount() > 0) {
                    Integer parsed = extractPort(line);
                    if (parsed != null) {
                        portRef.set(parsed);
                        latch.countDown();
                    }
                }
                // target output goes to stderr (NEVER stdout - that's the MCP channel)
                System.err.println(prefix + line);
            }
        } catch (IOException e) {
            // stream closed when target exits
        }
    }

    /** Returns the port from a JDWP listening line, or null if the line isn't one. */
    private static Integer extractPort(String line) {
        if (line == null) return null;
        int li = line.indexOf("Listening for transport");
        if (li < 0) return null;
        int a = line.indexOf("address:", li);
        String addr = a >= 0 ? line.substring(a + "address:".length()).trim() : line.substring(li).trim();
        try {
            int c = addr.lastIndexOf(':');
            String portPart = c >= 0 ? addr.substring(c + 1).trim() : addr;
            // strip trailing non-digit junk
            int end = 0;
            while (end < portPart.length() && Character.isDigit(portPart.charAt(end))) end++;
            if (end == 0) return null;
            return Integer.parseInt(portPart.substring(0, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
