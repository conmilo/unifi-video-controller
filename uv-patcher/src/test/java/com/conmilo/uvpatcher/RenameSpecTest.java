/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Loads the committed {@code airvision-renames.json} from the classpath and
 * verifies the shape the rewriter depends on.  Catches the "I changed the
 * JSON and broke the patcher contract" failure mode cheaply, without
 * needing a JAR fixture.
 *
 * <p>Post-Phase-3-auto-discovery: the spec no longer carries explicit class
 * / method / field rename maps.  Those are computed by {@link ReservedNames}
 * at scan time.  The test surface here is the residual configuration (jar
 * filename renames + jar filename additions + manifest action flags).
 *
 * <p>Phase 3.5 / v3.10.13-15: the dual-mode design collapsed when the
 * Tomcat 9 Bootstrap instance-method shim was retired in favour of the
 * in-place call-site rewrite implemented in {@link BootstrapCallSiteRewriter}.
 */
class RenameSpecTest {

    @Test
    void airvisionSpecLoadsWithExpectedContents() throws Exception {
        RenameSpec spec = loadResource("/airvision-renames.json");

        // Manifest actions all enabled (including the Phase 3.1
        // log4j2 ConsoleAppender injection).
        assertTrue(spec.rewriteClassPath());
        assertTrue(spec.stripPerEntryDigests());
        assertTrue(spec.addPatchedByLine());
        assertTrue(spec.addLog4jConsoleAppender());

        // jarFilenameRenames is the lib/ filename mapping; spot-check a few.
        assertEquals("jackson-databind-2.12.7.2.jar",
                spec.jarFilenameRenames().get("jackson-databind-2.7.4.jar"));
        assertEquals("owasp-java-html-sanitizer-20260101.1.jar",
                spec.jarFilenameRenames().get("owasp-java-html-sanitizer-r239.jar"));
        assertEquals("tomcat-embed-core-9.0.118.jar",
                spec.jarFilenameRenames().get("tomcat-embed-core.jar"));
        // Empty-string => remove from Class-Path entirely.
        assertEquals("", spec.jarFilenameRenames().get("tomcat-embed-logging-juli.jar"));
        assertEquals("", spec.jarFilenameRenames().get("tomcat-embed-logging-log4j.jar"));

        // Phase 5 (v3.10.13-19): BouncyCastle 1.60 -> 1.84.
        // The jdk15on artifacts retire; bcprov + bcpkix get the jdk18on
        // rename + version bump, bcprov-ext + bctls get dropped entirely
        // (rename to empty == remove from Class-Path).  See the Phase 5
        // changelog block in airvision-renames.json for the audit trail.
        assertEquals("bcprov-jdk18on-1.84.jar",
                spec.jarFilenameRenames().get("bcprov-jdk15on-160.jar"));
        assertEquals("bcpkix-jdk18on-1.84.jar",
                spec.jarFilenameRenames().get("bcpkix-jdk15on-160.jar"));
        assertEquals("", spec.jarFilenameRenames().get("bcprov-ext-jdk15on-160.jar"),
                "bcprov-ext discontinued at 1.78.1 + airvision doesn't use ext classes");
        assertEquals("", spec.jarFilenameRenames().get("bctls-jdk15on-160.jar"),
                "bctls unused -- airvision registers BouncyCastleProvider only, not JsseProvider");

        // _comment keys must NOT leak into the active map.
        assertFalse(spec.jarFilenameRenames().containsKey("_comment"),
                "comment keys must not be present as real entries");

        // Phase 3.2 + Phase 5: 7 JAXB/JAF runtime JARs + 1 bcutil (BC 1.71+
        // moved its shared utility classes into a separate Maven artifact;
        // bcpkix-jdk18on's pom hard-depends on it).
        assertTrue(spec.jarFilenameAdditions().contains("jaxb-api-2.3.1.jar"),
                "JAXB API must be added to airvision Class-Path");
        assertTrue(spec.jarFilenameAdditions().contains("javax.activation-1.2.0.jar"),
                "JAF must be added to airvision Class-Path");
        assertTrue(spec.jarFilenameAdditions().contains("bcutil-jdk18on-1.84.jar"),
                "bcutil (BC 1.71+ transitive dep) must be added to airvision Class-Path");
        assertEquals(8, spec.jarFilenameAdditions().size(),
                "JAXB + JAF (7) + bcutil (1) = 8 additions on Java 21 with BC 1.84");
    }

    private static RenameSpec loadResource(String resourcePath) throws Exception {
        try (InputStream in = RenameSpecTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                fail("classpath resource not found: " + resourcePath);
            }
            Path tmp = Files.createTempFile("uv-patcher-spec-test-", ".json");
            try {
                Files.write(tmp, in.readAllBytes());
                return RenameSpec.load(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
    }
}
