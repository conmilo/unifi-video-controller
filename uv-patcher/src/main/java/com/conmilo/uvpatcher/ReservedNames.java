/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import java.util.HashSet;
import java.util.Set;

/**
 * Source of truth for "is this identifier spec-illegal under JLS / JVM Spec
 * 4.2.2 once a strict-parser HotSpot (8u272+, Temurin 21, ...) loads it" and
 * for the deterministic escape rules uv-patcher applies.
 *
 * Three categories of illegal identifiers in obfuscated airvision.jar:
 *
 *   1. JVM-Spec 4.2.2 "unqualified name" violations.  Identifiers may not
 *      contain '.', ';', '[', or '/'.  Method names additionally may not
 *      contain '<' or '>' (with the exceptions of the special names
 *      '<init>' and '<clinit>').  Obfuscator emits method names like
 *      'new.super' which trip this rule.
 *
 *   2. JLS 3.9 reserved words used as identifiers.  Technically the JVM
 *      accepts most of these in the constant pool (they're "unqualified
 *      names", not "Java identifiers" per JLS), but HotSpot's
 *      classFileParser tightened enforcement around 8u272 and rejects a
 *      subset, and any javac-driven downstream tooling treats them as
 *      hard errors.  We rewrite them defensively.
 *
 *   3. java.lang class simple names used as class simple names of
 *      OTHER classes (Object, String).  These are JLS-legal but cause
 *      classloader ambiguity at any future build-time decompile pass.
 *      We rewrite them defensively too.
 *
 * Escape rules (deterministic, lossless on re-application):
 *
 *   - Class simple name that is reserved word or java.lang name:
 *         X       -> "Z" + capitalise(X)               (super -> ZSuper)
 *   - Class simple name containing '.':
 *         X.Y     -> X + "_" + Y                       (new.super -> new_super)
 *   - Package segment that is reserved word:
 *         X       -> "Z" + X (kept lowercase)          (super -> Zsuper)
 *   - Method / field name that is reserved word:
 *         X       -> "z" + X                           (new -> znew)
 *   - Method / field name containing '.':
 *         X.Y     -> X + "_" + Y                       (new.super -> new_super)
 *   - Method special names '&lt;init&gt;' and '&lt;clinit&gt;': preserved verbatim.
 *
 * Idempotent: applying the escape rule a second time to an already-escaped
 * name returns the same string (escape("ZSuper") == "ZSuper" because
 * "ZSuper" is not itself reserved).
 */
public final class ReservedNames {

    private ReservedNames() {}

    /** JLS 3.9 keywords + 'true' / 'false' / 'null'. */
    private static final Set<String> JAVA_RESERVED = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private",
            "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while",
            "true", "false", "null"
    );

    /**
     * java.lang simple names that obfuscators use as class names, creating
     * fully-qualified-name ambiguity once the JAR's own internal name
     * happens to match a JDK type.  Rewritten defensively.
     */
    private static final Set<String> JAVA_LANG_TYPE_NAMES = setOf(
            "Object", "String"
    );

    /** Method names that must NOT be renamed (JVM-spec special names). */
    private static final Set<String> METHOD_SPECIAL_NAMES = setOf(
            "<init>", "<clinit>"
    );

    /** @return true if {@code segment} would trigger a JLS / JVM-spec violation
     *  if used as the simple name of a class (last segment of internal name). */
    public static boolean isIllegalClassSimpleName(String segment) {
        if (segment == null || segment.isEmpty()) return false;
        if (containsUnqualifiedNameChar(segment)) return true;
        if (JAVA_RESERVED.contains(segment)) return true;
        if (JAVA_LANG_TYPE_NAMES.contains(segment)) return true;
        return false;
    }

    /** @return true if {@code segment} would trigger a violation if used as a
     *  package segment (any non-last segment of internal name). */
    public static boolean isIllegalPackageSegment(String segment) {
        if (segment == null || segment.isEmpty()) return false;
        if (containsUnqualifiedNameChar(segment)) return true;
        if (JAVA_RESERVED.contains(segment)) return true;
        return false;
    }

    /** @return true if {@code name} would trigger a violation if used as a
     *  method or field name.  Excludes the JVM special names which are legal. */
    public static boolean isIllegalMemberName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (METHOD_SPECIAL_NAMES.contains(name)) return false;
        if (containsUnqualifiedNameChar(name)) return true;
        // Methods may not contain '<' or '>' (except for the special names
        // already filtered above).
        if (name.indexOf('<') >= 0 || name.indexOf('>') >= 0) return true;
        if (JAVA_RESERVED.contains(name)) return true;
        return false;
    }

    /** Escape {@code segment} into a JLS-legal class simple name. */
    public static String escapeClassSimpleName(String segment) {
        if (!isIllegalClassSimpleName(segment)) return segment;
        if (containsUnqualifiedNameChar(segment)) {
            // Replace each illegal-char with '_'.
            return replaceIllegalChars(segment);
        }
        // Reserved word / java.lang name: capitalise first letter and prepend 'Z'.
        return "Z" + Character.toUpperCase(segment.charAt(0)) + segment.substring(1);
    }

    /** Escape {@code segment} into a JLS-legal package segment. */
    public static String escapePackageSegment(String segment) {
        if (!isIllegalPackageSegment(segment)) return segment;
        if (containsUnqualifiedNameChar(segment)) {
            return replaceIllegalChars(segment);
        }
        // Reserved word: prepend 'Z' (preserve lowercase, packages are
        // conventionally lowercase).
        return "Z" + segment;
    }

    /** Escape {@code name} into a JLS-legal method or field name. */
    public static String escapeMemberName(String name) {
        if (!isIllegalMemberName(name)) return name;
        if (METHOD_SPECIAL_NAMES.contains(name)) return name;
        if (containsUnqualifiedNameChar(name) || name.indexOf('<') >= 0 || name.indexOf('>') >= 0) {
            return replaceIllegalChars(name);
        }
        // Reserved word: prepend 'z' (members are conventionally lowercase).
        return "z" + name;
    }

    /**
     * Walk an internal class name (slash-separated) and apply
     * {@link #escapePackageSegment} to every non-last segment, then
     * {@link #escapeClassSimpleName} to the last segment.  The last segment
     * may contain '$' for inner classes; each '$'-separated piece is
     * escaped independently.
     *
     * Returns the input unchanged if no segment needs escaping.
     */
    public static String escapeInternalName(String internalName) {
        if (internalName == null || internalName.isEmpty()) return internalName;
        // Array descriptors leak in via Type.getInternalName() of array types
        // (e.g. "[Ljava/lang/Object;") -- ASM's Remapper handles these by
        // recursing on the element type, so we don't see them here, but
        // defend anyway.
        if (internalName.charAt(0) == '[' || internalName.indexOf(';') >= 0) {
            return internalName;
        }
        int lastSlash = internalName.lastIndexOf('/');
        String pkg     = (lastSlash >= 0) ? internalName.substring(0, lastSlash) : "";
        String simple  = (lastSlash >= 0) ? internalName.substring(lastSlash + 1) : internalName;

        StringBuilder out = new StringBuilder();
        boolean changed = false;

        if (!pkg.isEmpty()) {
            String[] segs = pkg.split("/", -1);
            for (int i = 0; i < segs.length; i++) {
                if (i > 0) out.append('/');
                String esc = escapePackageSegment(segs[i]);
                if (!esc.equals(segs[i])) changed = true;
                out.append(esc);
            }
            out.append('/');
        }

        // Inner classes are encoded as Outer$Inner$Innermost in the internal
        // name's last segment.  Each piece may itself be reserved.
        String[] parts = simple.split("\\$", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('$');
            String esc = escapeClassSimpleName(parts[i]);
            if (!esc.equals(parts[i])) changed = true;
            out.append(esc);
        }

        return changed ? out.toString() : internalName;
    }

    /** True if the segment contains any of JVM Spec 4.2.2's forbidden chars. */
    private static boolean containsUnqualifiedNameChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/') return true;
        }
        return false;
    }

    /** Replace each '.', ';', '[', '/', '<', '>' with '_'. */
    private static String replaceIllegalChars(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/' || c == '<' || c == '>') {
                b.append('_');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static Set<String> setOf(String... values) {
        Set<String> s = new HashSet<>(values.length * 2);
        for (String v : values) s.add(v);
        return java.util.Collections.unmodifiableSet(s);
    }
}
