package com.ylw.jdbmcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration loaded from jdb-mcp.json. Public fields are read by Jackson.
 * Path resolution / defaults live here; the active config is held by {@link Main}.
 */
public class Config {

    public TargetConfig target = new TargetConfig();
    public DebugConfig debug = new DebugConfig();
    public String logLevel = "INFO";

    public static class TargetConfig {
        /** Classpath entries (jars / dirs). Joined with the OS path separator at launch. */
        public List<String> classpath = new ArrayList<>();
        public String mainClass;
        public List<String> args = new ArrayList<>();
        public List<String> jvmArgs = new ArrayList<>();
        public String workingDir;
        /** Start the target suspended until the debugger attaches and resumes. */
        public boolean suspend = true;
    }

    public static class DebugConfig {
        public String host = "127.0.0.1";
        /** 0 = let the target pick a free port (parsed back from stderr). */
        public int port = 0;
        public int maxHits = 1000;
        public int captureDepth = 5;
        public int toStringLimit = 500;
        public int collectionLimit = 20;
        public boolean includeProxies = false;
        /** Master switch for the dangerous eval tool. Off by default. */
        public boolean allowEval = false;
        public ModeBConfig modeB = new ModeBConfig();
    }

    public static class ModeBConfig {
        public int exploreBudget = 5;
        public int evalBudget = 2;
        public int defaultTimeoutSec = 60;
    }

    public static Config load(Path path) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Config cfg = mapper.readValue(Files.readString(path), Config.class);
            if (cfg.target == null) cfg.target = new TargetConfig();
            if (cfg.debug == null) cfg.debug = new DebugConfig();
            if (cfg.debug.modeB == null) cfg.debug.modeB = new ModeBConfig();
            return cfg;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config from " + path + ": " + e.getMessage(), e);
        }
    }
}
