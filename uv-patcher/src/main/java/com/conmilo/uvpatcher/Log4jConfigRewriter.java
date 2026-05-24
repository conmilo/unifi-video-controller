/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * Inject a {@code ConsoleAppender} definition into airvision.jar's
 * {@code log4j2.json} configuration if one is not already present.
 *
 * <p>Background: Ubiquiti's stock {@code log4j2.json} (shipped inside
 * {@code airvision.jar}) declares the {@code root} logger to reference
 * {@code ServerLogAppender}, {@code ConsoleAppender}, and
 * {@code ErrorLogAppender}, but the {@code appenders.appender[]} array only
 * defines the two file-based appenders and never the
 * {@code ConsoleAppender}.  On every JVM startup log4j2 emits
 *
 * <pre>
 *   ERROR Unable to locate appender "ConsoleAppender" for logger config "root"
 * </pre>
 *
 * <p>and silently runs without console output.  For containerised
 * deployments, console logging is the natural fit (it's what
 * {@code docker logs} surfaces), so this rewriter adds a real
 * {@code Console} appender targeting {@code SYSTEM_OUT}.  Once the
 * appender is defined, the existing {@code AppenderRef} in the root
 * logger picks it up automatically -- no logger surgery needed.
 *
 * <p>Idempotency: if an appender already named {@code ConsoleAppender}
 * is present (whether added by an earlier patcher pass or by a future
 * Ubiquiti config fix), the method returns the input unchanged.
 */
public final class Log4jConfigRewriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Name of the appender we add.  Must match the existing AppenderRef. */
    public static final String APPENDER_NAME = "ConsoleAppender";

    /** Default pattern for the console output.  Compact ISO-8601-ish; file
     *  appenders keep their own UnixTime-prefixed pattern (we don't touch
     *  those).  {@code %c{1.}} prints e.g. {@code c.u.a.s.AirvisionMain}. */
    public static final String DEFAULT_PATTERN =
            "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%c{1.}] %m%n";

    private Log4jConfigRewriter() {}

    /**
     * Return the rewritten JSON bytes if {@code ConsoleAppender} was added;
     * return the input bytes unchanged if the appender was already present
     * or if the JSON shape is unexpected (we never mangle a config we don't
     * recognise -- safer to no-op than to break logging).
     */
    public static byte[] addConsoleAppenderIfMissing(byte[] inputBytes) throws IOException {
        JsonNode root = MAPPER.readTree(inputBytes);

        // Navigate configuration.appenders.appender[] (the standard log4j2
        // JSON layout).
        JsonNode configuration = root.path("configuration");
        JsonNode appendersHolder = configuration.path("appenders");
        JsonNode appenderArray = appendersHolder.path("appender");

        if (!configuration.isObject() || !appendersHolder.isObject() || !appenderArray.isArray()) {
            // Shape doesn't match the Ubiquiti config we know how to extend.
            // Bail out rather than risk producing an invalid config.
            return inputBytes;
        }

        // Idempotency: skip if an entry already exists with our appender's
        // name.  The check is conservative (exact-string match on the "name"
        // field) -- if a future Ubiquiti config defines a ConsoleAppender
        // with a different shape, we leave their version alone.
        for (JsonNode appender : appenderArray) {
            if (APPENDER_NAME.equals(appender.path("name").asText(""))) {
                return inputBytes;
            }
        }

        // Build the new appender:
        //   {
        //     "name": "ConsoleAppender",
        //     "type": "Console",
        //     "target": "SYSTEM_OUT",
        //     "patternLayout": { "pattern": "..." }
        //   }
        ObjectNode newAppender = MAPPER.createObjectNode();
        newAppender.put("name",   APPENDER_NAME);
        newAppender.put("type",   "Console");
        newAppender.put("target", "SYSTEM_OUT");
        ObjectNode layout = MAPPER.createObjectNode();
        layout.put("pattern", DEFAULT_PATTERN);
        newAppender.set("patternLayout", layout);

        ((ArrayNode) appenderArray).add(newAppender);

        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }
}
