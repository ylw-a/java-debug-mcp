package com.ylw.jdbmcp.debug;

/** A class resolved from a fuzzy name, returned to the AI for selection. */
public class ClassCandidate {

    public final String fqcn;
    public final String simpleName;
    public final boolean isProxy;
    public final boolean isInterface;
    public final boolean prepared;

    public ClassCandidate(String fqcn, String simpleName, boolean isProxy, boolean isInterface, boolean prepared) {
        this.fqcn = fqcn;
        this.simpleName = simpleName;
        this.isProxy = isProxy;
        this.isInterface = isInterface;
        this.prepared = prepared;
    }

    @Override
    public String toString() {
        return fqcn + "{proxy=" + isProxy + ", interface=" + isInterface + ", prepared=" + prepared + "}";
    }

    /** Short name (after the last '.'). */
    public static String simpleNameOf(String fqcn) {
        if (fqcn == null) return "";
        int idx = fqcn.lastIndexOf('.');
        return idx < 0 ? fqcn : fqcn.substring(idx + 1);
    }
}
