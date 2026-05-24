/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock down the log4j2 ConsoleAppender injection behaviour.
 *
 * <p>Test fixtures are synthetic JSON strings shaped like Ubiquiti's
 * stock {@code log4j2.json} -- no Ubiquiti bytes in the test sources.
 */
class Log4jConfigRewriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String STOCK_CONFIG_WITHOUT_CONSOLE_APPENDER = "{"
            + " \"configuration\": {"
            + "   \"name\": \"Test\","
            + "   \"appenders\": {"
            + "     \"appender\": ["
            + "       { \"name\": \"ServerLogAppender\", \"type\": \"RollingFile\", \"fileName\": \"logs/server.log\" },"
            + "       { \"name\": \"ErrorLogAppender\",  \"type\": \"RollingFile\", \"fileName\": \"logs/error.log\"  }"
            + "     ]"
            + "   },"
            + "   \"loggers\": {"
            + "     \"root\": {"
            + "       \"level\": \"warn\","
            + "       \"AppenderRef\": ["
            + "         { \"ref\": \"ServerLogAppender\" },"
            + "         { \"ref\": \"ConsoleAppender\" },"
            + "         { \"ref\": \"ErrorLogAppender\" }"
            + "       ]"
            + "     }"
            + "   }"
            + " }"
            + "}";

    @Test
    void addsConsoleAppenderToStockUbiquitiShape() throws Exception {
        byte[] input = STOCK_CONFIG_WITHOUT_CONSOLE_APPENDER.getBytes();
        byte[] output = Log4jConfigRewriter.addConsoleAppenderIfMissing(input);

        // Output bytes differ from input (rewrite happened).
        // We don't compare byte-for-byte because pretty-print whitespace
        // changes, but the rewritten output should be longer.
        assertTrue(output.length > input.length, "output should be larger after appender added");

        // Parse and verify the ConsoleAppender entry was added.
        JsonNode root = MAPPER.readTree(output);
        JsonNode appenders = root.path("configuration").path("appenders").path("appender");
        assertTrue(appenders.isArray());
        assertEquals(3, appenders.size(), "should have ServerLog + ErrorLog + ConsoleAppender");

        JsonNode consoleAppender = null;
        for (JsonNode a : appenders) {
            if ("ConsoleAppender".equals(a.path("name").asText())) {
                consoleAppender = a;
                break;
            }
        }
        assertNotNull(consoleAppender, "ConsoleAppender must be present after rewrite");
        assertEquals("Console",    consoleAppender.path("type").asText());
        assertEquals("SYSTEM_OUT", consoleAppender.path("target").asText());
        assertNotNull(consoleAppender.path("patternLayout").path("pattern").asText());
        assertTrue(consoleAppender.path("patternLayout").path("pattern").asText().contains("%m"),
                "pattern must include %m (the message)");
    }

    @Test
    void idempotent_alreadyHasConsoleAppender_returnsInputUnchanged() throws Exception {
        // Shape with an existing ConsoleAppender definition: the rewriter
        // must leave the bytes untouched.
        String alreadyHas = "{"
                + " \"configuration\": {"
                + "   \"appenders\": {"
                + "     \"appender\": ["
                + "       { \"name\": \"ServerLogAppender\", \"type\": \"RollingFile\" },"
                + "       { \"name\": \"ConsoleAppender\",   \"type\": \"Console\", \"target\": \"SYSTEM_OUT\" }"
                + "     ]"
                + "   }"
                + " }"
                + "}";
        byte[] input = alreadyHas.getBytes();
        byte[] output = Log4jConfigRewriter.addConsoleAppenderIfMissing(input);

        // The contract is reference-equality for the no-op path; the rewriter
        // returns the input array unchanged so callers can cheaply detect
        // "no rewrite happened" without a byte-compare.
        assertSame(input, output,
                "no-op path must return the exact input reference, not a copy");
    }

    @Test
    void unexpectedShape_returnsInputUnchanged() throws Exception {
        // A config that doesn't match the expected configuration.appenders.appender
        // shape (e.g. log4j XML rendered as JSON without an appenders block):
        // the rewriter must not crash and must not mangle the input.
        String unexpected = "{ \"configuration\": { \"name\": \"Test\" } }";
        byte[] input = unexpected.getBytes();
        byte[] output = Log4jConfigRewriter.addConsoleAppenderIfMissing(input);
        assertSame(input, output,
                "unexpected shape must be a no-op (safer than mangling)");
    }

    @Test
    void appenderName_matchesRootLoggerAppenderRef() {
        // The whole point of this rewrite is that Ubiquiti's root logger
        // references "ConsoleAppender" by exact string.  Lock in the name
        // we add so a future refactor can't break the link.
        assertEquals("ConsoleAppender", Log4jConfigRewriter.APPENDER_NAME);
    }
}
