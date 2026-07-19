#!/usr/bin/env node

/**
 * jdb-mcp — Java Debug MCP Server entry point.
 *
 * Finds a JDK 17+ on the system, then execs the bundled JDI debug server jar.
 * All stdio is forwarded transparently for the MCP protocol (stdin/stdout).
 * Logs and target output go to stderr (never stdout).
 *
 * Requires: JDK 17+ (JAVA_HOME or java on PATH).
 */

const { execSync, spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

// ---- find Java ----
function findJava() {
    // 1. JAVA_HOME
    let javaHome = process.env.JAVA_HOME;
    if (javaHome) {
        const exe = javaExe(javaHome);
        if (fs.existsSync(exe)) return exe;
    }

    // 2. Check java on PATH
    try {
        const which = process.platform === 'win32'
            ? execSync('where java', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
            : execSync('which java', { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
        const first = which.trim().split(/\r?\n/)[0];
        if (first && fs.existsSync(first)) return first;
    } catch (e) {
        // java not on PATH
    }

    // 3. Common JDK locations
    const common = process.platform === 'win32' ? [
        'C:\\Program Files\\Java\\jdk-17\\bin\\java.exe',
        'C:\\Program Files\\Eclipse Adoptium\\jdk-17-hotspot\\bin\\java.exe',
        'C:\\Program Files\\Microsoft\\jdk-17\\bin\\java.exe',
    ] : [
        '/usr/lib/jvm/java-17-openjdk/bin/java',
        '/usr/lib/jvm/java-17-oracle/bin/java',
        '/usr/local/opt/openjdk@17/bin/java',
        '/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home/bin/java',
    ];
    for (const p of common) {
        if (fs.existsSync(p)) return p;
    }

    return null;
}

function javaExe(javaHome) {
    const ext = process.platform === 'win32' ? '.exe' : '';
    return path.join(javaHome, 'bin', 'java' + ext);
}

// ---- verify Java version ----
function checkVersion(javaBin) {
    try {
        const out = execSync(`"${javaBin}" -version 2>&1`, { encoding: 'utf8' });
        const m = out.match(/version "(\d+)/);
        if (m) {
            const major = parseInt(m[1], 10);
            if (major >= 17) return true;
            console.error(`[jdb-mcp] Java version ${major} found, but JDK 17+ is required.`);
            return false;
        }
    } catch (e) {
        console.error(`[jdb-mcp] Failed to check Java version: ${e.message}`);
    }
    return false;
}

// ---- main ----
const javaBin = findJava();
if (!javaBin) {
    console.error('[jdb-mcp] JDK 17+ not found.');
    console.error('  Set JAVA_HOME or install a JDK 17+ (e.g. Eclipse Adoptium, Oracle, Microsoft).');
    console.error('  https://adoptium.net/');
    process.exit(1);
}

if (!checkVersion(javaBin)) {
    process.exit(1);
}

// Resolve the bundled jar: <npm-package>/jars/jdb-mcp-1.0.0.jar
const jar = path.join(__dirname, '..', 'jars', 'jdb-mcp-1.0.0.jar');
if (!fs.existsSync(jar)) {
    console.error(`[jdb-mcp] Bundled jar not found: ${jar}`);
    process.exit(1);
}

// Forward JDB_MCP_LOG if set, otherwise let the server use its default (INFO)
const env = { ...process.env };
console.error(`[jdb-mcp] Java: ${javaBin}`);
console.error(`[jdb-mcp] Jar:  ${jar}`);
console.error('[jdb-mcp] MCP server starting on stdio...');

const proc = spawn(javaBin, ['-jar', jar], {
    stdio: ['inherit', 'inherit', 'inherit'],
    env,
});

proc.on('exit', (code) => {
    process.exit(code || 0);
});