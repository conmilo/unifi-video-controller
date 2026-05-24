/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * MANIFEST.MF surgery.  Three independent actions controlled by the spec:
 *
 *   - rewriteClassPath:    replace the Class-Path attribute with the
 *                          spec-resolved list of post-rename filenames.
 *                          When the filename map passed in is empty (the
 *                          common Phase 3 case -- the lib/ rename happens
 *                          via Dockerfile, not via the patcher), we keep the
 *                          existing Class-Path value verbatim.  The flag
 *                          exists so future patcher extensions can take over
 *                          the lib/ rename entirely.
 *
 *   - stripPerEntryDigests: drop every per-entry section (the SHA-1-Digest /
 *                           MD5-Digest blocks under Name: lines).  Without a
 *                           JAR signature (.SF / .RSA / .DSA) the digests
 *                           aren't enforced by the JVM anyway, and they go
 *                           stale the moment we touch any class.
 *
 *   - addPatchedByLine:    add a Patched-By: line to the main attributes so
 *                          a reviewer can identify the patched JAR with
 *                          `unzip -p ... META-INF/MANIFEST.MF | head -10`.
 *
 * The original manifest may be null (some JARs ship without one); in that
 * case we synthesize a minimal manifest.
 */
public final class ManifestRewriter {

    /** Default Patched-By value when the caller passes null. */
    public static final String PATCHED_BY_VALUE =
            "conmilo/unifi-video-controller uv-patcher 1.0.0 (Phase 3 / v3.10.13-13)";

    public static Manifest rewrite(Manifest original,
                                   RenameSpec spec,
                                   Map<String, String> filenameRenames) {
        return rewrite(original, spec, filenameRenames, null);
    }

    public static Manifest rewrite(Manifest original,
                                   RenameSpec spec,
                                   Map<String, String> filenameRenames,
                                   String patchedByValue) {
        Manifest out = new Manifest();
        Attributes mainAttrs = out.getMainAttributes();

        if (original == null) {
            mainAttrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        } else {
            // Copy main attributes verbatim, EXCEPT the per-section maps
            // (those live in original.getEntries()).
            for (Map.Entry<Object, Object> a : original.getMainAttributes().entrySet()) {
                mainAttrs.put(a.getKey(), a.getValue());
            }
            // Drop the per-entry digest metadata.  Without a signature
            // block the JVM never verifies these; with one, they would
            // already be invalid after class rewriting.
            if (!spec.stripPerEntryDigests()) {
                for (Map.Entry<String, Attributes> e : original.getEntries().entrySet()) {
                    out.getEntries().put(e.getKey(), new Attributes(e.getValue()));
                }
            }
        }

        if (spec.rewriteClassPath()) {
            Attributes.Name cpKey = new Attributes.Name("Class-Path");
            String cp = mainAttrs.getValue(cpKey);
            if (cp != null) {
                String renamed  = filenameRenames.isEmpty()
                        ? cp
                        : rewriteClassPathValue(cp, filenameRenames);
                String appended = appendClassPathEntries(renamed, spec.jarFilenameAdditions());
                if (!appended.equals(cp)) {
                    mainAttrs.put(cpKey, appended);
                }
            }
        }

        if (spec.addPatchedByLine()) {
            String val = (patchedByValue != null) ? patchedByValue : PATCHED_BY_VALUE;
            mainAttrs.put(new Attributes.Name("Patched-By"), val);
        }

        return out;
    }

    static String rewriteClassPathValue(String classPath, Map<String, String> renames) {
        String[] tokens = classPath.split("\\s+");
        StringBuilder out = new StringBuilder(classPath.length() + 64);
        boolean first = true;
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            String replacement = renames.get(tok);
            // Empty-string replacement means "remove this token entirely"
            // (used for the vestigial tomcat-embed-logging-* JARs Phase 3 retires).
            if (replacement != null && replacement.isEmpty()) continue;
            if (!first) out.append(' ');
            first = false;
            out.append(replacement != null ? replacement : tok);
        }
        return out.toString();
    }

    /**
     * Append {@code additions} (deduped against entries already present) to a
     * space-separated Class-Path token list.  Used in Phase 3.2 to add the
     * JAXB + JAF runtime JARs that Java 11+ no longer ships in the JDK but
     * airvision (compiled against Java 8) still imports.
     */
    static String appendClassPathEntries(String classPath, java.util.List<String> additions) {
        if (additions == null || additions.isEmpty()) return classPath;
        java.util.LinkedHashSet<String> existing = new java.util.LinkedHashSet<>();
        for (String tok : classPath.split("\\s+")) {
            if (!tok.isEmpty()) existing.add(tok);
        }
        StringBuilder out = new StringBuilder(classPath);
        for (String add : additions) {
            if (add == null || add.isEmpty()) continue;
            if (existing.contains(add)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(add);
            existing.add(add);
        }
        return out.toString();
    }

    private ManifestRewriter() {}
}
