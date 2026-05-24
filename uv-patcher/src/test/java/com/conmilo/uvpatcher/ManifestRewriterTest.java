/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestRewriterTest {

    @Test
    void noRenamesLeavesClassPathUnchanged() {
        Map<String, String> renames = new LinkedHashMap<>();
        assertEquals(
                "foo.jar bar.jar baz.jar",
                ManifestRewriter.rewriteClassPathValue("foo.jar bar.jar baz.jar", renames));
    }

    @Test
    void singleRenameAppliesInPlace() {
        Map<String, String> renames = new LinkedHashMap<>();
        renames.put("commons-io-2.6.jar", "commons-io-2.18.0.jar");
        assertEquals(
                "airvision.jar commons-io-2.18.0.jar log4j-core-2.1.jar",
                ManifestRewriter.rewriteClassPathValue(
                        "airvision.jar commons-io-2.6.jar log4j-core-2.1.jar", renames));
    }

    @Test
    void emptyReplacementRemovesToken() {
        Map<String, String> renames = new LinkedHashMap<>();
        renames.put("tomcat-embed-logging-juli.jar", "");
        renames.put("tomcat-embed-logging-log4j.jar", "");
        assertEquals(
                "airvision.jar tomcat-embed-core.jar",
                ManifestRewriter.rewriteClassPathValue(
                        "airvision.jar tomcat-embed-logging-juli.jar "
                                + "tomcat-embed-logging-log4j.jar tomcat-embed-core.jar",
                        renames));
    }

    @Test
    void renameAndRemovalsCompose() {
        Map<String, String> renames = new LinkedHashMap<>();
        renames.put("jackson-databind-2.7.4.jar", "jackson-databind-2.12.7.2.jar");
        renames.put("tomcat-embed-logging-juli.jar", "");
        renames.put("log4j-core-2.1.jar", "log4j-core-2.17.2.jar");
        assertEquals(
                "jackson-databind-2.12.7.2.jar log4j-core-2.17.2.jar tomcat-embed-core.jar",
                ManifestRewriter.rewriteClassPathValue(
                        "jackson-databind-2.7.4.jar tomcat-embed-logging-juli.jar "
                                + "log4j-core-2.1.jar tomcat-embed-core.jar",
                        renames));
    }

    @Test
    void collapsesMultipleWhitespace() {
        Map<String, String> renames = new LinkedHashMap<>();
        assertEquals(
                "a.jar b.jar c.jar",
                ManifestRewriter.rewriteClassPathValue("a.jar   b.jar\n c.jar", renames));
    }

    // ---- Phase 3.2 jarFilenameAdditions tests ----------------------------

    @Test
    void emptyAdditionsListIsNoOp() {
        assertEquals(
                "airvision.jar log4j-core-2.1.jar",
                ManifestRewriter.appendClassPathEntries(
                        "airvision.jar log4j-core-2.1.jar", Collections.emptyList()));
    }

    @Test
    void appendsNewEntriesToEndOfClassPath() {
        List<String> additions = Arrays.asList(
                "jaxb-api-2.3.1.jar", "javax.activation-1.2.0.jar");
        assertEquals(
                "airvision.jar log4j-core-2.1.jar jaxb-api-2.3.1.jar javax.activation-1.2.0.jar",
                ManifestRewriter.appendClassPathEntries(
                        "airvision.jar log4j-core-2.1.jar", additions));
    }

    @Test
    void skipsAdditionAlreadyPresent() {
        // Idempotency: re-running the patcher must not duplicate JAR entries
        // (the manifest Patched-By header check should short-circuit before
        // we get here, but defend at this layer too).
        List<String> additions = Arrays.asList(
                "jaxb-api-2.3.1.jar", "javax.activation-1.2.0.jar");
        assertEquals(
                "airvision.jar jaxb-api-2.3.1.jar javax.activation-1.2.0.jar",
                ManifestRewriter.appendClassPathEntries(
                        "airvision.jar jaxb-api-2.3.1.jar", additions));
    }

    @Test
    void appendsToEmptyClassPathWithoutLeadingSpace() {
        List<String> additions = Collections.singletonList("jaxb-api-2.3.1.jar");
        assertEquals(
                "jaxb-api-2.3.1.jar",
                ManifestRewriter.appendClassPathEntries("", additions));
    }
}
