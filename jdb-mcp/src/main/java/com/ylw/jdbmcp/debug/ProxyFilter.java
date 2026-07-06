package com.ylw.jdbmcp.debug;

import com.sun.jdi.ClassType;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.ReferenceType;

import java.util.List;

/**
 * Detects generated proxy/synthetic classes so they can be filtered out by default.
 * Spring apps are awash in CGLIB/JDK proxies; hitting them is almost never what the
 * AI wants, so {@code include_proxies=false} is the default.
 *
 * <p>Rules combine class-name patterns with interface/superclass signatures, per the
 * design doc's table (CGLIB, JDK dynamic proxy, javassist, Hibernate).
 */
public final class ProxyFilter {

    private ProxyFilter() {}

    public static boolean isProxy(ReferenceType rt) {
        if (rt == null) return false;
        String name = rt.name();

        // --- Name patterns ---
        if (name.contains("$$EnhancerBySpringCGLIB$$")) return true;
        if (name.contains("$$FastClassBySpringCGLIB$$")) return true;
        if (name.contains("_$$_javassist")) return true;
        if (name.contains("$$_hibernate_interceptor")) return true;
        if (name.contains("$$_hibernate_javassist")) return true;
        // JDK dynamic proxy: package.ClassName$ProxyNNN
        if (name.matches(".*\\$Proxy\\d+$")) return true;
        // Generic synthetic accessors
        if (name.contains("$$Lambda$") || name.contains("$Lambda$")) return true;

        // --- Interface / superclass signatures ---
        try {
            List<InterfaceType> ifaces;
            if (rt instanceof ClassType ct) {
                ifaces = ct.allInterfaces();
            } else {
                ifaces = List.of();
            }
            for (InterfaceType it : ifaces) {
                String in = it.name();
                if ("org.springframework.cglib.proxy.Factory".equals(in)) return true;
                if ("java.lang.reflect.InvocationHandler".equals(in)) return true;
                if ("javassist.util.proxy.ProxyObject".equals(in)) return true;
                if ("org.hibernate.proxy.HibernateProxy".equals(in)) return true;
            }
        } catch (Throwable ignored) {
            // allInterfaces() can throw for incompletely loaded types; treat as non-proxy.
        }

        // JDK dynamic proxy extends java.lang.reflect.Proxy
        if (rt instanceof ClassType ct) {
            ClassType sup = ct.superclass();
            int guard = 0;
            while (sup != null && guard++ < 64) {
                if ("java.lang.reflect.Proxy".equals(sup.name())) return true;
                sup = sup.superclass();
            }
        }
        return false;
    }
}
