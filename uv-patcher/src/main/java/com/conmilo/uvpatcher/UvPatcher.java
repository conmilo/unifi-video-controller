/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 *
 * Phase 3 runtime JAR rewriter -- see uv-patcher/README.md.
 */
package com.conmilo.uvpatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point.  Parses CLI args, loads the rename spec, runs the airvision
 * identifier rewriter (which also rewrites the two Tomcat 9 Bootstrap
 * call sites in-place; see {@link BootstrapCallSiteRewriter}).  Exit codes
 * documented in uv-patcher/README.md.
 *
 * Usage:
 *   java -jar uv-patcher.jar \
 *       --target  &lt;input.jar&gt; \
 *       --spec    &lt;spec.json&gt; \
 *       --output  &lt;output.jar&gt;
 *
 * The patcher writes a one-line audit summary to stderr per renamed class
 * so a reviewer can verify the rewrite from `docker logs`.
 */
public final class UvPatcher {

    public static final int EXIT_OK              = 0;
    public static final int EXIT_UNKNOWN_IDENT   = 1;
    public static final int EXIT_IO_ERROR        = 2;
    public static final int EXIT_BAD_SPEC        = 3;
    public static final int EXIT_BAD_ARGS        = 4;

    /** Version string surfaced in Manifest "Patched-By" header. */
    public static final String VERSION = "1.0.0";

    public static void main(String[] argv) {
        try {
            Args args = Args.parse(argv);
            RenameSpec spec = RenameSpec.load(args.spec);
            int exit = new AirvisionIdentifierRewriter(spec).run(args.target, args.output);
            System.exit(exit);
        } catch (BadArgsException e) {
            System.err.println("uv-patcher: " + e.getMessage());
            System.err.println(USAGE);
            System.exit(EXIT_BAD_ARGS);
        } catch (BadSpecException e) {
            System.err.println("uv-patcher: bad spec: " + e.getMessage());
            System.exit(EXIT_BAD_SPEC);
        } catch (UnknownIdentifierException e) {
            System.err.println("uv-patcher: " + e.getMessage());
            System.err.println("  (extend the rename spec or investigate upstream airvision.jar changes)");
            System.exit(EXIT_UNKNOWN_IDENT);
        } catch (IOException e) {
            System.err.println("uv-patcher: I/O error: " + e.getMessage());
            System.exit(EXIT_IO_ERROR);
        } catch (RuntimeException e) {
            System.err.println("uv-patcher: unexpected error: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(EXIT_IO_ERROR);
        }
    }

    private static final String USAGE =
            "Usage: uv-patcher --target <input.jar> --spec <spec.json> --output <output.jar>";

    /** Trivial CLI args parser; no third-party dep needed. */
    static final class Args {
        Path target;
        Path spec;
        Path output;

        static Args parse(String[] argv) throws BadArgsException {
            Args a = new Args();
            List<String> rest = Arrays.asList(argv);
            for (int i = 0; i < rest.size(); i++) {
                String arg = rest.get(i);
                switch (arg) {
                    case "--target":
                        a.target = requirePath(rest, ++i, "--target");
                        break;
                    case "--spec":
                        a.spec = requirePath(rest, ++i, "--spec");
                        break;
                    case "--output":
                        a.output = requirePath(rest, ++i, "--output");
                        break;
                    case "-h":
                    case "--help":
                        System.out.println(USAGE);
                        System.exit(EXIT_OK);
                        break;
                    default:
                        throw new BadArgsException("unknown argument: " + arg);
                }
            }
            if (a.target == null) throw new BadArgsException("--target is required");
            if (a.spec   == null) throw new BadArgsException("--spec is required");
            if (a.output == null) throw new BadArgsException("--output is required");
            if (!Files.isRegularFile(a.target)) {
                throw new BadArgsException("--target does not exist or is not a file: " + a.target);
            }
            if (!Files.isRegularFile(a.spec)) {
                throw new BadArgsException("--spec does not exist or is not a file: " + a.spec);
            }
            return a;
        }

        private static Path requirePath(List<String> rest, int i, String flag) throws BadArgsException {
            if (i >= rest.size()) {
                throw new BadArgsException(flag + " requires a value");
            }
            return Path.of(rest.get(i));
        }
    }

    static final class BadArgsException extends Exception {
        BadArgsException(String msg) { super(msg); }
    }

    static final class BadSpecException extends Exception {
        BadSpecException(String msg) { super(msg); }
    }

    static final class UnknownIdentifierException extends RuntimeException {
        UnknownIdentifierException(String msg) { super(msg); }
    }

    private UvPatcher() {}
}
