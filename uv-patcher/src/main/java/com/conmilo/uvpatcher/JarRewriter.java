/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Streaming JAR I/O wrapper.  Iterates over input entries lazily and writes
 * rewritten entries to a JarOutputStream.  Signature files (.SF/.RSA/.DSA)
 * and the original MANIFEST.MF entry are filtered from both input and
 * output -- the manifest is already passed to the constructor and written as
 * the first output entry by JarOutputStream.
 *
 * Designed for the AirvisionIdentifierRewriter flow:
 *
 *   try (JarRewriter writer = new JarRewriter(input, output, manifest)) {
 *       for (JarRewriter.Entry entry : writer) {
 *           if (entry.name().endsWith(".class")) {
 *               writer.writeClass(newName, transform(entry.bytes()), entry.lastModifiedMillis());
 *           } else {
 *               writer.writeRaw(entry);
 *           }
 *       }
 *   }
 */
public final class JarRewriter implements AutoCloseable, Iterable<JarRewriter.Entry> {

    private final JarInputStream input;
    private final JarOutputStream output;
    private final OutputStream outputRaw;

    public JarRewriter(Path inputPath, Path outputPath, Manifest manifest) throws IOException {
        InputStream is = Files.newInputStream(inputPath);
        try {
            this.input = new JarInputStream(is);
        } catch (IOException e) {
            is.close();
            throw e;
        }
        this.outputRaw = Files.newOutputStream(outputPath);
        try {
            this.output = (manifest != null)
                    ? new JarOutputStream(outputRaw, manifest)
                    : new JarOutputStream(outputRaw);
        } catch (IOException e) {
            outputRaw.close();
            input.close();
            throw e;
        }
    }

    @Override
    public Iterator<Entry> iterator() {
        return new InputIterator();
    }

    /** Pass an input entry through to the output unchanged (raw bytes). */
    public void writeRaw(Entry entry) throws IOException {
        if (isFiltered(entry.name())) return;
        ZipEntry out = new ZipEntry(entry.name());
        if (entry.lastModifiedMillis() > 0) out.setTime(entry.lastModifiedMillis());
        output.putNextEntry(out);
        output.write(entry.bytes());
        output.closeEntry();
    }

    /** Write a rewritten class entry (always under name + ".class"). */
    public void writeClass(String entryName, byte[] classBytes, long lastModifiedMillis) throws IOException {
        if (isFiltered(entryName)) return;
        ZipEntry out = new ZipEntry(entryName);
        if (lastModifiedMillis > 0) out.setTime(lastModifiedMillis);
        output.putNextEntry(out);
        output.write(classBytes);
        output.closeEntry();
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        try { output.close(); }    catch (IOException e) { first = e; }
        try { outputRaw.close(); } catch (IOException e) { if (first == null) first = e; }
        try { input.close(); }     catch (IOException e) { if (first == null) first = e; }
        if (first != null) throw first;
    }

    /**
     * Entries we never copy through: existing JAR signature blocks (which
     * would be invalidated by any rewrite anyway), and any second copy of
     * MANIFEST.MF (JarOutputStream's constructor already wrote the manifest
     * we passed in).
     */
    private static boolean isFiltered(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("META-INF/MANIFEST.MF")) return true;
        if (upper.startsWith("META-INF/") && (upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC"))) {
            return true;
        }
        return false;
    }

    /** Snapshot of one input entry: name + already-read bytes + mtime. */
    public static final class Entry {
        private final String name;
        private final byte[] bytes;
        private final long lastModifiedMillis;

        Entry(String name, byte[] bytes, long lastModifiedMillis) {
            this.name = name;
            this.bytes = bytes;
            this.lastModifiedMillis = lastModifiedMillis;
        }

        public String name()              { return name; }
        public byte[] bytes()              { return bytes; }
        public long lastModifiedMillis()   { return lastModifiedMillis; }
    }

    /** Lazy single-pass iterator over input entries.
     *
     *  Directory entries are passed straight through to the output and NOT
     *  yielded to the consumer (no rewrite needed; they're empty marker
     *  entries).  Preserving them is critical: Jersey 1.x's
     *  PackageNamesScanner and similar classpath-scanning libraries iterate
     *  the JAR's TOC looking for directory entries whose names match a
     *  configured package prefix.  Without those entries the package
     *  scanner finds nothing and silently skips the JAR, producing the
     *  "ResourceConfig instance does not contain any root resource classes"
     *  failure mode that Phase 3.2 surfaced.
     */
    private final class InputIterator implements Iterator<Entry> {
        private Entry next;
        private boolean exhausted;

        @Override
        public boolean hasNext() {
            if (exhausted) return false;
            if (next != null) return true;
            try {
                JarEntry je;
                while ((je = input.getNextJarEntry()) != null) {
                    if (je.isDirectory()) {
                        passThroughDirectoryEntry(je);
                        input.closeEntry();
                        continue;
                    }
                    if (isFiltered(je.getName())) {
                        input.closeEntry();
                        continue;
                    }
                    byte[] bytes = readAll(input);
                    long mtime = je.getTime();
                    input.closeEntry();
                    next = new Entry(je.getName(), bytes, mtime);
                    return true;
                }
            } catch (IOException e) {
                throw new RuntimeException("read error while iterating jar entries", e);
            }
            exhausted = true;
            return false;
        }

        @Override
        public Entry next() {
            if (!hasNext()) throw new NoSuchElementException();
            Entry e = next;
            next = null;
            return e;
        }

        /** Write an empty directory marker to the output JAR.  Time is
         *  copied from the input so reproducible builds stay reproducible. */
        private void passThroughDirectoryEntry(JarEntry je) throws IOException {
            ZipEntry copy = new ZipEntry(je.getName());
            if (je.getTime() > 0) copy.setTime(je.getTime());
            output.putNextEntry(copy);
            output.closeEntry();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
