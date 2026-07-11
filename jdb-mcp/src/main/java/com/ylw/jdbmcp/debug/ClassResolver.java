package com.ylw.jdbmcp.debug;

import com.sun.jdi.InterfaceType;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves an AI-supplied (possibly short / fuzzy) class name to concrete loaded
 * {@link ReferenceType}s. Spring makes literal names unreliable: "UserService" may be
 * an interface whose real bean is {@code UserServiceImpl$$EnhancerBySpringCGLIB$$xxx}.
 *
 * <p>Flow per the design doc: scan {@code vm.allClasses()} for name matches, drop proxies
 * unless {@code includeProxies}, and return the candidate list when more than one remains.
 */
public final class ClassResolver {

    private ClassResolver() {}

    public static List<ClassCandidate> resolve(VirtualMachine vm, String pattern, boolean includeProxies) {
        List<ClassCandidate> out = new ArrayList<>();
        if (pattern == null || pattern.isBlank()) return out;
        String p = pattern.trim();
        for (ReferenceType rt : vm.allClasses()) {
            if (!matches(rt.name(), p)) continue;
            boolean proxy = ProxyFilter.isProxy(rt);
            if (proxy && !includeProxies) continue;
            boolean prepared;
            try {
                prepared = rt.isPrepared();
            } catch (Throwable t) {
                prepared = false;
            }
            out.add(new ClassCandidate(rt.name(), ClassCandidate.simpleNameOf(rt.name()),
                    proxy, rt instanceof InterfaceType, prepared));
        }
        return out;
    }

    /** Fuzzy short-name support: match if the simple name or fqcn contains the pattern. */
    private static boolean matches(String fqcn, String pattern) {
        if (fqcn.equals(pattern)) return true;
        String simple = ClassCandidate.simpleNameOf(fqcn);
        if (simple.equals(pattern)) return true;
        return simple.contains(pattern) || fqcn.contains(pattern);
    }

    /** Convenience: the single concrete prepared class, or null if ambiguous/none. */
    public static ReferenceType pickOne(VirtualMachine vm, String pattern, boolean includeProxies) {
        List<ReferenceType> hits = new ArrayList<>();
        for (ReferenceType rt : vm.allClasses()) {
            if (!matches(rt.name(), pattern)) continue;
            if (!includeProxies && ProxyFilter.isProxy(rt)) continue;
            try {
                if (!rt.isPrepared()) continue;
            } catch (Throwable t) {
                continue;
            }
            hits.add(rt);
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /** Paginated enumeration of loaded classes (for list_classes). */
    public static EnumerateResult enumerate(VirtualMachine vm, String prefix, boolean includeProxies,
                                            int limit, int offset) {
        List<ClassCandidate> all = new ArrayList<>();
        for (ReferenceType rt : vm.allClasses()) {
            if (prefix != null && !prefix.isBlank() && !rt.name().startsWith(prefix)) continue;
            boolean proxy = ProxyFilter.isProxy(rt);
            if (proxy && !includeProxies) continue;
            boolean prepared;
            try {
                prepared = rt.isPrepared();
            } catch (Throwable t) {
                prepared = false;
            }
            all.add(new ClassCandidate(rt.name(), ClassCandidate.simpleNameOf(rt.name()),
                    proxy, rt instanceof InterfaceType, prepared));
        }
        int total = all.size();
        int from = Math.min(offset, total);
        int to = Math.min(offset + limit, total);
        List<ClassCandidate> page = all.subList(from, to);
        return new EnumerateResult(total, page);
    }

    public static final class EnumerateResult {
        public final int total;
        public final List<ClassCandidate> page;
        public EnumerateResult(int total, List<ClassCandidate> page) {
            this.total = total; this.page = page;
        }
    }
}
