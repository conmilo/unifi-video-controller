/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock down JarRewriter's preservation of directory entries.  Jersey 1.x's
 * PackageNamesScanner enumerates a JAR's TOC for entries matching a
 * configured package prefix and ending with '/'.  Without those entries
 * Jersey silently finds zero classes and fails with
 * "ResourceConfig instance does not contain any root resource classes".
 *
 * The pre-Phase-3.3 JarRewriter skipped directory entries in its input
 * iterator, producing patched JARs with zero directory entries -- which
 * broke Jersey scanning on Java 21.  This test prevents regression.
 */
class JarRewriterTest {

    @Test
    void preservesDirectoryEntriesAcrossRewrite() throws IOException {
        Path input  = Files.createTempFile("jartest-in-",  ".jar");
        Path output = Files.createTempFile("jartest-out-", ".jar");
        try {
            // Build a synthetic input JAR: 2 directory entries + 2 file entries.
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(input), new Manifest())) {
                addDirectoryEntry(jos, "com/");
                addDirectoryEntry(jos, "com/example/");
                addFileEntry(jos, "com/example/A.class", new byte[]{1,2,3});
                addFileEntry(jos, "com/example/B.class", new byte[]{4,5,6});
            }

            // Run a minimal rewrite: pass every file entry through writeRaw.
            try (JarRewriter writer = new JarRewriter(input, output, new Manifest())) {
                for (JarRewriter.Entry entry : writer) {
                    writer.writeRaw(entry);
                }
            }

            // Read the output and verify both directory and file entries survived.
            Set<String> entryNames = listEntries(output);
            assertTrue(entryNames.contains("com/"),         "com/ directory entry must survive rewrite");
            assertTrue(entryNames.contains("com/example/"), "com/example/ directory entry must survive rewrite");
            assertTrue(entryNames.contains("com/example/A.class"), "file entry must survive rewrite");
            assertTrue(entryNames.contains("com/example/B.class"), "file entry must survive rewrite");

            // Count: META-INF/MANIFEST.MF is auto-written by JarOutputStream
            // constructor (1) + 2 directory entries + 2 files = 5 total.
            assertEquals(5, entryNames.size(),
                    "exactly 5 entries: manifest + 2 dirs + 2 files (got: " + entryNames + ")");
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    @Test
    void filteredEntriesAreDropped_butDirsAreNotFiltered() throws IOException {
        // Signature files (.SF/.RSA/.DSA) are dropped by JarRewriter.
        // Verify that the filter doesn't accidentally drop the META-INF/
        // directory entry itself (Tomcat / Jersey need it).
        Path input  = Files.createTempFile("jartest-in-",  ".jar");
        Path output = Files.createTempFile("jartest-out-", ".jar");
        try {
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(input), new Manifest())) {
                addDirectoryEntry(jos, "META-INF/");
                addFileEntry(jos, "META-INF/SIGNED.SF", new byte[]{1});
                addFileEntry(jos, "META-INF/SIGNED.RSA", new byte[]{2});
                addDirectoryEntry(jos, "com/");
                addFileEntry(jos, "com/X.class", new byte[]{3});
            }

            try (JarRewriter writer = new JarRewriter(input, output, new Manifest())) {
                for (JarRewriter.Entry entry : writer) {
                    writer.writeRaw(entry);
                }
            }

            Set<String> entryNames = listEntries(output);
            assertTrue(entryNames.contains("META-INF/"),  "META-INF/ directory entry survives");
            assertTrue(entryNames.contains("com/"),       "com/ directory entry survives");
            assertTrue(entryNames.contains("com/X.class"),"file entry survives");
            // Filtered signature files are gone.
            assertTrue(!entryNames.contains("META-INF/SIGNED.SF"),  ".SF must be filtered");
            assertTrue(!entryNames.contains("META-INF/SIGNED.RSA"), ".RSA must be filtered");
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    // ---- helpers ---------------------------------------------------------

    private static void addDirectoryEntry(JarOutputStream jos, String name) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(1577836800000L); // 2020-01-01 (deterministic for the test)
        jos.putNextEntry(e);
        jos.closeEntry();
    }

    private static void addFileEntry(JarOutputStream jos, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(1577836800000L);
        jos.putNextEntry(e);
        jos.write(data);
        jos.closeEntry();
    }

    private static Set<String> listEntries(Path jar) throws IOException {
        Set<String> names = new HashSet<>();
        try (JarInputStream jis = new JarInputStream(Files.newInputStream(jar))) {
            // The Manifest is consumed by JarInputStream's constructor;
            // re-add it explicitly so the test can assert on it.
            if (jis.getManifest() != null) names.add("META-INF/MANIFEST.MF");
            JarEntry je;
            while ((je = jis.getNextJarEntry()) != null) {
                names.add(je.getName());
                jis.closeEntry();
            }
        }
        return names;
    }

    @SuppressWarnings("unused")
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
