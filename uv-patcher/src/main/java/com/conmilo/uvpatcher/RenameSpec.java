/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import com.conmilo.uvpatcher.UvPatcher.BadSpecException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed view of {@code airvision-renames.json}.
 *
 * <p>Phase 3 auto-discovery: the spec NO LONGER enumerates the obfuscated
 * classes / methods / fields.  The patcher discovers them dynamically by
 * scanning the JAR with ASM and applying deterministic {@link ReservedNames}
 * escape rules.  The spec retains configuration that is NOT computable
 * from class file contents alone:
 *
 * <ul>
 *   <li>{@code jarFilenameRenames}: lib/ filename normalisation for the
 *       Class-Path attribute rewrite.</li>
 *   <li>{@code jarFilenameAdditions}: extra entries to append to the
 *       Class-Path attribute (Java 11+ JAXB / JAF runtime libs).</li>
 *   <li>{@code manifestActions}: rewrite Class-Path? strip per-entry
 *       digests? add Patched-By header? inject log4j ConsoleAppender?</li>
 * </ul>
 *
 * <p>Phase 3.5 / v3.10.13-15: the previous dual-mode design
 * ({@code "mode": "identifier-rewrite"} vs {@code "mode": "bootstrap-shim"})
 * collapsed back to a single rewrite pass when the Tomcat 9 Bootstrap
 * instance-method shim was retired in favour of rewriting airvision's two
 * dangling call sites in-place; see {@link BootstrapCallSiteRewriter} and
 * the CHANGELOG entry for v3.10.13-15.
 *
 * <p>Keys beginning with {@code _} are treated as inline comments and
 * ignored (they let the spec carry maintainer notes alongside the data).
 */
public final class RenameSpec {

    private final Map<String, String> jarFilenameRenames;
    private final List<String> jarFilenameAdditions;
    private final boolean rewriteClassPath;
    private final boolean stripPerEntryDigests;
    private final boolean addPatchedByLine;
    private final boolean addLog4jConsoleAppender;

    private RenameSpec(Builder b) {
        this.jarFilenameRenames      = Collections.unmodifiableMap(b.jarFilenameRenames);
        this.jarFilenameAdditions    = Collections.unmodifiableList(b.jarFilenameAdditions);
        this.rewriteClassPath        = b.rewriteClassPath;
        this.stripPerEntryDigests    = b.stripPerEntryDigests;
        this.addPatchedByLine        = b.addPatchedByLine;
        this.addLog4jConsoleAppender = b.addLog4jConsoleAppender;
    }

    public Map<String, String> jarFilenameRenames() { return jarFilenameRenames; }
    public List<String> jarFilenameAdditions()      { return jarFilenameAdditions; }
    public boolean rewriteClassPath()               { return rewriteClassPath; }
    public boolean stripPerEntryDigests()           { return stripPerEntryDigests; }
    public boolean addPatchedByLine()               { return addPatchedByLine; }
    public boolean addLog4jConsoleAppender()        { return addLog4jConsoleAppender; }

    public static RenameSpec load(Path specFile) throws IOException, BadSpecException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try {
            root = mapper.readTree(Files.readAllBytes(specFile));
        } catch (IOException e) {
            throw new IOException("cannot read spec " + specFile + ": " + e.getMessage(), e);
        }

        Builder b = new Builder();
        readStringMap(root, "jarFilenameRenames", b.jarFilenameRenames);
        readStringList(root, "jarFilenameAdditions", b.jarFilenameAdditions);

        JsonNode manifest = root.get("manifestActions");
        if (manifest != null) {
            b.rewriteClassPath        = manifest.path("rewriteClassPath").asBoolean(false);
            b.stripPerEntryDigests    = manifest.path("stripPerEntryDigests").asBoolean(false);
            b.addPatchedByLine        = manifest.path("addPatchedByLine").asBoolean(false);
            b.addLog4jConsoleAppender = manifest.path("addLog4jConsoleAppender").asBoolean(false);
        }

        return new RenameSpec(b);
    }

    private static void readStringMap(JsonNode root, String key, Map<String, String> out)
            throws BadSpecException {
        JsonNode n = root.get(key);
        if (n == null) return;
        if (!n.isObject()) {
            throw new BadSpecException("'" + key + "' must be an object");
        }
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (e.getKey().startsWith("_")) continue;
            if (!e.getValue().isTextual()) {
                throw new BadSpecException(
                        "'" + key + "." + e.getKey() + "' must be a string");
            }
            out.put(e.getKey(), e.getValue().asText());
        }
    }

    private static void readStringList(JsonNode root, String key, List<String> out)
            throws BadSpecException {
        JsonNode n = root.get(key);
        if (n == null) return;
        if (!n.isArray()) {
            throw new BadSpecException("'" + key + "' must be an array of strings");
        }
        for (JsonNode entry : n) {
            if (!entry.isTextual()) {
                throw new BadSpecException(
                        "'" + key + "' array entries must be strings (got: " + entry + ")");
            }
            out.add(entry.asText());
        }
    }

    private static final class Builder {
        Map<String, String> jarFilenameRenames   = new LinkedHashMap<>();
        List<String>        jarFilenameAdditions = new ArrayList<>();
        boolean rewriteClassPath;
        boolean stripPerEntryDigests;
        boolean addPatchedByLine;
        boolean addLog4jConsoleAppender;
    }
}
