/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

/**
 * Auto-discovery ASM rewriter for airvision.jar.
 *
 * <p>Two passes:
 *
 * <ol>
 *   <li><b>Scan</b>.  Walk every {@code .class} entry, parse it with ASM,
 *       and accumulate the set of internal names / method names / field names
 *       that {@link ReservedNames} flags as JLS / JVM-Spec 4.2.2 violations.
 *       The output is a {@link RenameMap}.
 *
 *   <li><b>Rewrite</b>.  Walk the JAR a second time; for each {@code .class}
 *       entry, drive a {@link ClassRemapper} backed by the {@code RenameMap},
 *       then write the rewritten class under its new entry name.  Resources
 *       pass through verbatim.  The manifest is rewritten separately via
 *       {@link ManifestRewriter} (Class-Path filenames, per-entry digests,
 *       Patched-By header).
 * </ol>
 *
 * <p>The rewriter has zero hard-coded knowledge of which classes contain
 * spec-illegal identifiers.  This means it survives any future upstream
 * airvision obfuscation pattern change without code modifications.
 *
 * <p>Idempotency: the rewriter inspects the input manifest for a
 * {@code Patched-By: uv-patcher ...} attribute.  If present, it logs
 * "already patched" and returns success without writing.
 */
public final class AirvisionIdentifierRewriter {

    public static final String PATCHED_BY_VALUE =
            "uv-patcher " + UvPatcher.VERSION + " (auto-discovery)";

    private final RenameSpec spec;

    public AirvisionIdentifierRewriter(RenameSpec spec) {
        this.spec = spec;
    }

    public int run(Path input, Path output) throws IOException {
        if (isAlreadyPatched(input)) {
            System.err.println("uv-patcher: airvision: already patched, skipping rewrite.");
            return UvPatcher.EXIT_OK;
        }

        // Pass 1: scan for all spec-illegal identifiers.
        RenameMap renames = scanForRenames(input);
        System.err.println("uv-patcher: discovered "
                + renames.classRenames.size() + " class renames, "
                + renames.memberRenames.size() + " member renames.");

        // Original manifest (then rewrite Class-Path + add Patched-By).
        Manifest originalManifest;
        try (JarInputStream peek = new JarInputStream(Files.newInputStream(input))) {
            originalManifest = peek.getManifest();
        }
        Manifest rewrittenManifest = ManifestRewriter.rewrite(
                originalManifest, spec, spec.jarFilenameRenames(), PATCHED_BY_VALUE);

        // Pass 2: rewrite each class via ClassRemapper using the discovered map.
        // Bootstrap call-site rewriter is chained in *after* the identifier
        // remapper so its INVOKEVIRTUAL detection sees the (un-remapped)
        // org/apache/catalina/startup/Bootstrap owner string -- the airvision
        // rename map only covers airvision's own classes, so the order is
        // actually immaterial here, but writing it explicitly avoids a
        // fragility if a future change ever does add Bootstrap to the map.
        IdentifierRemapper remapper = new IdentifierRemapper(renames);
        int classesProcessed = 0;
        int classEntriesRenamed = 0;
        int bootstrapCallsRewritten = 0;
        boolean log4jConfigRewritten = false;

        try (JarRewriter writer = new JarRewriter(input, output, rewrittenManifest)) {
            for (JarRewriter.Entry entry : writer) {
                String name = entry.name();

                // Special-case: log4j2.json -- inject a real ConsoleAppender if
                // the spec asks for it and the existing config doesn't define
                // one.  Ubiquiti's stock config references "ConsoleAppender" in
                // the root logger's AppenderRef list but never defines it,
                // producing a benign-but-noisy startup error.
                if (spec.addLog4jConsoleAppender() && "log4j2.json".equals(name)) {
                    byte[] rewritten = Log4jConfigRewriter.addConsoleAppenderIfMissing(entry.bytes());
                    if (rewritten != entry.bytes()) {
                        log4jConfigRewritten = true;
                        writer.writeClass(name, rewritten, entry.lastModifiedMillis());
                    } else {
                        writer.writeRaw(entry);
                    }
                    continue;
                }

                if (!name.endsWith(".class")) {
                    writer.writeRaw(entry);
                    continue;
                }
                classesProcessed++;
                ClassReader cr = new ClassReader(entry.bytes());
                ClassWriter cw = new SafeClassWriter(0);
                BootstrapCallSiteRewriter bootstrapRewriter =
                        new BootstrapCallSiteRewriter(cw);
                ClassVisitor cv = new ClassRemapper(bootstrapRewriter, remapper);
                cr.accept(cv, ClassReader.EXPAND_FRAMES);
                bootstrapCallsRewritten += bootstrapRewriter.rewritesDone();

                String oldInternal = cr.getClassName();
                String newInternal = remapper.map(oldInternal);
                String newEntryName = newInternal + ".class";

                if (!newEntryName.equals(name)) {
                    classEntriesRenamed++;
                }
                writer.writeClass(newEntryName, cw.toByteArray(), entry.lastModifiedMillis());
            }
        }

        System.err.println("uv-patcher: airvision rewrite complete: "
                + classesProcessed + " classes processed, "
                + classEntriesRenamed + " class entries renamed, "
                + renames.memberRenames.size() + " method/field references renamed, "
                + bootstrapCallsRewritten + " Bootstrap.setCatalina* call(s) rewritten"
                + (log4jConfigRewritten ? ", log4j2.json ConsoleAppender added" : "")
                + ", output=" + output);
        return UvPatcher.EXIT_OK;
    }

    // ---- pass 1 -----------------------------------------------------------

    /**
     * Read every {@code .class} entry in {@code input}, ASM-parse it, and
     * accumulate the rename map.  No writing happens here.
     */
    private RenameMap scanForRenames(Path input) throws IOException {
        Map<String, String> classRenames = new HashMap<>();
        Set<MemberKey> illegalMembers = new LinkedHashSet<>();

        try (JarInputStream jis = new JarInputStream(Files.newInputStream(input))) {
            JarEntry je;
            while ((je = jis.getNextJarEntry()) != null) {
                if (je.isDirectory() || !je.getName().endsWith(".class")) {
                    jis.closeEntry();
                    continue;
                }
                byte[] bytes = readAll(jis);
                jis.closeEntry();
                scanClassBytes(bytes, classRenames, illegalMembers);
            }
        }

        // Materialise member renames.  A member rename applies wherever the
        // member is referenced -- ASM's mapMethodName/mapFieldName is keyed
        // by (owner, name, desc), but the new name we want is a function of
        // the OLD name only (escape rule is deterministic), so we don't need
        // owner-specific scoping in the map.  We do, however, store every
        // (owner, name, desc) tuple we observed so the patcher's startup log
        // can describe exactly what changed.
        Map<MemberKey, String> memberRenames = new HashMap<>();
        for (MemberKey key : illegalMembers) {
            memberRenames.put(key, ReservedNames.escapeMemberName(key.name));
        }

        return new RenameMap(
                Collections.unmodifiableMap(classRenames),
                Collections.unmodifiableMap(memberRenames));
    }

    private void scanClassBytes(byte[] classBytes,
                                Map<String, String> classRenames,
                                Set<MemberKey> illegalMembers) {
        ClassReader cr = new ClassReader(classBytes);
        String oldInternal = cr.getClassName();
        String newInternal = ReservedNames.escapeInternalName(oldInternal);
        if (!newInternal.equals(oldInternal)) {
            classRenames.put(oldInternal, newInternal);
        }

        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            private String visitingOwner;

            @Override
            public void visit(int v, int acc, String name, String sig, String sup, String[] ifs) {
                this.visitingOwner = name;
            }

            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exs) {
                if (ReservedNames.isIllegalMemberName(name)) {
                    illegalMembers.add(new MemberKey(visitingOwner, name, desc, MemberKey.Kind.METHOD));
                }
                return null;
            }

            @Override
            public FieldVisitor visitField(int acc, String name, String desc, String sig, Object val) {
                if (ReservedNames.isIllegalMemberName(name)) {
                    illegalMembers.add(new MemberKey(visitingOwner, name, desc, MemberKey.Kind.FIELD));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    // ---- pass-2 helpers ---------------------------------------------------

    /** ClassWriter that never reflects to resolve common superclasses
     *  (we don't compute frames, so {@link #getCommonSuperClass} is unused). */
    private static final class SafeClassWriter extends ClassWriter {
        SafeClassWriter(int flags) { super(flags); }
    }

    static final class IdentifierRemapper extends Remapper {
        private final RenameMap map;

        IdentifierRemapper(RenameMap map) { this.map = map; }

        @Override
        public String map(String internalName) {
            if (internalName == null) return null;
            String hit = map.classRenames.get(internalName);
            // Strict lookup: ONLY rename classes that pass 1 actually saw in
            // the JAR.  References to JDK types (java/lang/Object) and
            // external libraries (org/apache/...) pass through untouched.
            // This matters because ReservedNames.escapeInternalName would
            // otherwise rewrite "java/lang/Object" -> "java/lang/ZObject"
            // (Object is in the JLS-class-names set), which would dangle the
            // reference because we don't actually rewrite the JDK.
            return hit != null ? hit : internalName;
        }

        @Override
        public String mapMethodName(String owner, String name, String desc) {
            if (!ReservedNames.isIllegalMemberName(name)) return name;
            return ReservedNames.escapeMemberName(name);
        }

        @Override
        public String mapFieldName(String owner, String name, String desc) {
            if (!ReservedNames.isIllegalMemberName(name)) return name;
            return ReservedNames.escapeMemberName(name);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String desc) {
            if (!ReservedNames.isIllegalMemberName(name)) return name;
            return ReservedNames.escapeMemberName(name);
        }
    }

    // ---- idempotency ------------------------------------------------------

    private boolean isAlreadyPatched(Path input) throws IOException {
        Manifest m;
        try (JarInputStream jis = new JarInputStream(Files.newInputStream(input))) {
            m = jis.getManifest();
        }
        if (m == null) return false;
        String patchedBy = m.getMainAttributes().getValue("Patched-By");
        return patchedBy != null && patchedBy.startsWith("uv-patcher");
    }

    // ---- value types ------------------------------------------------------

    /** Result of the scan pass: class-internal-name and member rename maps. */
    static final class RenameMap {
        final Map<String, String> classRenames;
        final Map<MemberKey, String> memberRenames;

        RenameMap(Map<String, String> classRenames, Map<MemberKey, String> memberRenames) {
            this.classRenames = classRenames;
            this.memberRenames = memberRenames;
        }
    }

    /** Key for a single member (method or field) declaration site. */
    static final class MemberKey {
        enum Kind { METHOD, FIELD }
        final String owner;
        final String name;
        final String descriptor;
        final Kind kind;

        MemberKey(String owner, String name, String descriptor, Kind kind) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.kind = kind;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MemberKey)) return false;
            MemberKey k = (MemberKey) o;
            return kind == k.kind
                    && owner.equals(k.owner)
                    && name.equals(k.name)
                    && descriptor.equals(k.descriptor);
        }
        @Override
        public int hashCode() {
            int h = owner.hashCode();
            h = 31 * h + name.hashCode();
            h = 31 * h + descriptor.hashCode();
            h = 31 * h + kind.hashCode();
            return h;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
