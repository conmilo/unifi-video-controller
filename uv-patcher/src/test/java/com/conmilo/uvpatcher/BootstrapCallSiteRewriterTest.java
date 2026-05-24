/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks down {@link BootstrapCallSiteRewriter}'s bytecode-level contract:
 *
 * <ol>
 *   <li>Every {@code INVOKEVIRTUAL org/apache/catalina/startup/Bootstrap.
 *       setCatalina{Base,Home}(Ljava/lang/String;)V} is replaced with the
 *       equivalent {@code System.setProperty(...)} call.</li>
 *   <li>The rewrite counter ({@link BootstrapCallSiteRewriter#rewritesDone})
 *       reports the exact number of call sites rewritten.</li>
 *   <li>A second pass over an already-rewritten class is a no-op (zero new
 *       rewrites).</li>
 *   <li>Calls that LOOK similar but don't match the target owner / name /
 *       descriptor tuple are left untouched.</li>
 *   <li>The rewritten class loads under the JVM bytecode verifier without
 *       {@code VerifyError} AND running it side-effects
 *       {@code System.setProperty("catalina.{base,home}", ...)} with the
 *       arguments the original {@code Bootstrap.setCatalina*} call passed.</li>
 * </ol>
 *
 * <p>All fixture classes are synthesised in memory via ASM -- no compile
 * step, no Tomcat dependency, no airvision bytecode in the test resources.
 */
class BootstrapCallSiteRewriterTest {

    private static final String BOOTSTRAP_INTERNAL = "org/apache/catalina/startup/Bootstrap";
    private static final String FIXTURE_INTERNAL   = "uvpatcher_test/CallerFixture";
    private static final String FIXTURE_BINARY     = "uvpatcher_test.CallerFixture";
    private static final String BOOTSTRAP_BINARY   = "org.apache.catalina.startup.Bootstrap";

    private String savedCatalinaBase;
    private String savedCatalinaHome;

    @BeforeEach
    void captureGlobalPropertyState() {
        savedCatalinaBase = System.getProperty("catalina.base");
        savedCatalinaHome = System.getProperty("catalina.home");
        System.clearProperty("catalina.base");
        System.clearProperty("catalina.home");
    }

    @AfterEach
    void restoreGlobalPropertyState() {
        restoreProperty("catalina.base", savedCatalinaBase);
        restoreProperty("catalina.home", savedCatalinaHome);
    }

    @Test
    void rewritesReplaceBootstrapCallsWithSetProperty() {
        byte[] rewritten = applyRewriter(buildFixtureClass()).bytes;
        InstructionAudit audit = auditInstructions(rewritten);

        assertEquals(0, audit.bootstrapInvokeVirtuals,
                "every Bootstrap.setCatalina* INVOKEVIRTUAL must be replaced");
        assertEquals(4, audit.systemSetPropertyInvokeStatics,
                "four new System.setProperty INVOKESTATICs must be present"
                        + " (2 per method, 2 methods)");
        assertTrue(audit.ldcStrings.contains("catalina.base"),
                "LDC of \"catalina.base\" must be emitted");
        assertTrue(audit.ldcStrings.contains("catalina.home"),
                "LDC of \"catalina.home\" must be emitted");
    }

    @Test
    void counterMatchesNumberOfRewrites() {
        Result r = applyRewriter(buildFixtureClass());
        assertEquals(4, r.rewritesDone,
                "fixture has 2 methods x 2 calls each = 4 rewrites expected");
    }

    @Test
    void secondPassIsNoOp() {
        byte[] firstPass = applyRewriter(buildFixtureClass()).bytes;

        // Audit the first pass to lock down the expected post-rewrite shape.
        InstructionAudit firstAudit = auditInstructions(firstPass);
        assertEquals(0, firstAudit.bootstrapInvokeVirtuals);
        assertEquals(4, firstAudit.systemSetPropertyInvokeStatics);

        // Second pass must find nothing to do.
        Result second = applyRewriter(firstPass);
        assertEquals(0, second.rewritesDone,
                "second pass must find zero remaining Bootstrap.setCatalina* call sites");

        InstructionAudit secondAudit = auditInstructions(second.bytes);
        assertEquals(0, secondAudit.bootstrapInvokeVirtuals);
        assertEquals(4, secondAudit.systemSetPropertyInvokeStatics);
    }

    @Test
    void similarButUnrelatedCallsArePreserved() {
        Result r = applyRewriter(buildClassWithSimilarButUnrelatedCalls());
        assertEquals(0, r.rewritesDone,
                "non-matching call sites must NOT be counted as rewrites");

        InstructionAudit audit = auditInstructions(r.bytes);
        assertEquals(0, audit.bootstrapInvokeVirtuals,
                "no Bootstrap calls should exist in this fixture in the first place");
        assertEquals(0, audit.systemSetPropertyInvokeStatics,
                "no System.setProperty calls should be inserted into unrelated classes");
        assertFalse(audit.ldcStrings.contains("catalina.base"),
                "no spurious \"catalina.base\" LDC should appear");
    }

    @Test
    void rewrittenClassPassesVerifierAndSetsSystemProperties() throws Exception {
        Map<String, byte[]> defs = new HashMap<>();
        defs.put(BOOTSTRAP_BINARY, buildStubBootstrapClass());
        defs.put(FIXTURE_BINARY,   applyRewriter(buildFixtureClass()).bytes);

        ClassLoader cl = new InMemoryClassLoader(defs, getClass().getClassLoader());
        Class<?> bootstrap = cl.loadClass(BOOTSTRAP_BINARY);
        Class<?> fixture   = cl.loadClass(FIXTURE_BINARY);

        Object bootstrapInst = bootstrap.getDeclaredConstructor().newInstance();

        // Instance constructor: rewritten calls should hit System.setProperty.
        fixture.getDeclaredConstructor(bootstrap, String.class, String.class)
                .newInstance(bootstrapInst, "/tmp/uv-ctor-base", "/tmp/uv-ctor-home");
        assertEquals("/tmp/uv-ctor-base", System.getProperty("catalina.base"));
        assertEquals("/tmp/uv-ctor-home", System.getProperty("catalina.home"));

        // Static method path: same rewrite, different invocation site.
        Method invoke = fixture.getDeclaredMethod("invoke", bootstrap, String.class, String.class);
        invoke.invoke(null, bootstrapInst, "/tmp/uv-static-base", "/tmp/uv-static-home");
        assertEquals("/tmp/uv-static-base", System.getProperty("catalina.base"));
        assertEquals("/tmp/uv-static-home", System.getProperty("catalina.home"));
    }

    // ---- helpers ----------------------------------------------------------

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static Result applyRewriter(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassWriter writer = new ClassWriter(0);
        BootstrapCallSiteRewriter rewriter = new BootstrapCallSiteRewriter(writer);
        reader.accept(rewriter, ClassReader.EXPAND_FRAMES);
        return new Result(writer.toByteArray(), rewriter.rewritesDone());
    }

    /**
     * Synthesises:
     *
     * <pre>
     *   public class uvpatcher_test.CallerFixture {
     *     public CallerFixture(Bootstrap b, String base, String home) {
     *       super();
     *       b.setCatalinaBase(base);
     *       b.setCatalinaHome(home);
     *     }
     *     public static void invoke(Bootstrap b, String base, String home) {
     *       b.setCatalinaBase(base);
     *       b.setCatalinaHome(home);
     *     }
     *   }
     * </pre>
     *
     * Two methods × two call sites = four total {@code Bootstrap.setCatalina*}
     * call sites for the rewriter to find.
     */
    private static byte[] buildFixtureClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                FIXTURE_INTERNAL,
                null,
                "java/lang/Object",
                null);

        String ctorDesc = "(L" + BOOTSTRAP_INTERNAL + ";Ljava/lang/String;Ljava/lang/String;)V";
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        emitBootstrapCall(ctor, /*receiverLocal*/ 1, /*argLocal*/ 2, "setCatalinaBase");
        emitBootstrapCall(ctor, /*receiverLocal*/ 1, /*argLocal*/ 3, "setCatalinaHome");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        String invokeDesc = "(L" + BOOTSTRAP_INTERNAL + ";Ljava/lang/String;Ljava/lang/String;)V";
        MethodVisitor invoke = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke", invokeDesc, null, null);
        invoke.visitCode();
        emitBootstrapCall(invoke, /*receiverLocal*/ 0, /*argLocal*/ 1, "setCatalinaBase");
        emitBootstrapCall(invoke, /*receiverLocal*/ 0, /*argLocal*/ 2, "setCatalinaHome");
        invoke.visitInsn(Opcodes.RETURN);
        invoke.visitMaxs(0, 0);
        invoke.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitBootstrapCall(MethodVisitor mv, int receiverLocal,
                                          int argLocal, String methodName) {
        mv.visitVarInsn(Opcodes.ALOAD, receiverLocal);
        mv.visitVarInsn(Opcodes.ALOAD, argLocal);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BOOTSTRAP_INTERNAL,
                methodName, "(Ljava/lang/String;)V", false);
    }

    /**
     * Class whose method bodies LOOK similar to the bootstrap call sites
     * (Object receiver, String argument, INVOKEVIRTUAL) but reference a
     * different owner / method.  None of these should be rewritten.
     */
    private static byte[] buildClassWithSimilarButUnrelatedCalls() {
        String otherInternal = "uvpatcher_test/OtherClass";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, otherInternal, null,
                "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "doThings", "(Ljava/lang/Object;Ljava/lang/String;)V", null, null);
        mv.visitCode();
        // Object.toString() -- same INVOKEVIRTUAL shape, different owner.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object",
                "toString", "()Ljava/lang/String;", false);
        mv.visitInsn(Opcodes.POP);
        // StringBuilder.append(String) -- One-String-arg INVOKEVIRTUAL but wrong owner.
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder",
                "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Minimum-viable {@code org.apache.catalina.startup.Bootstrap} stub --
     * a public class with a default constructor and two no-op instance
     * methods matching airvision's expected signatures.  Lets the JVM
     * load the rewritten fixture without pulling in real Tomcat.
     */
    private static byte[] buildStubBootstrapClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, BOOTSTRAP_INTERNAL, null,
                "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        for (String name : new String[] { "setCatalinaBase", "setCatalinaHome" }) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name,
                    "(Ljava/lang/String;)V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static InstructionAudit auditInstructions(byte[] classBytes) {
        InstructionAudit audit = new InstructionAudit();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String mdesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL
                                && BOOTSTRAP_INTERNAL.equals(owner)
                                && ("setCatalinaBase".equals(mname)
                                    || "setCatalinaHome".equals(mname))) {
                            audit.bootstrapInvokeVirtuals++;
                        }
                        if (opcode == Opcodes.INVOKESTATIC
                                && "java/lang/System".equals(owner)
                                && "setProperty".equals(mname)) {
                            audit.systemSetPropertyInvokeStatics++;
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            audit.ldcStrings.add((String) value);
                        }
                    }
                };
            }
        }, 0);
        return audit;
    }

    private static final class Result {
        final byte[] bytes;
        final int rewritesDone;

        Result(byte[] bytes, int rewritesDone) {
            this.bytes = bytes;
            this.rewritesDone = rewritesDone;
        }
    }

    private static final class InstructionAudit {
        int bootstrapInvokeVirtuals;
        int systemSetPropertyInvokeStatics;
        final Set<String> ldcStrings = new HashSet<>();
    }

    /** Minimum-viable in-memory ClassLoader for the runtime-smoke test. */
    private static final class InMemoryClassLoader extends ClassLoader {
        private final Map<String, byte[]> defs;

        InMemoryClassLoader(Map<String, byte[]> defs, ClassLoader parent) {
            super(parent);
            this.defs = defs;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = defs.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
