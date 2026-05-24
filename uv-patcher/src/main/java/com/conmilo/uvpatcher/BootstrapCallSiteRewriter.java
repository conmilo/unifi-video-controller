/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM {@link ClassVisitor} that rewrites airvision's two
 * {@code Bootstrap.setCatalina{Base,Home}(String)} call sites into the
 * equivalent {@code System.setProperty("catalina.{base,home}", arg)}
 * sequence -- which is exactly what the old {@code TomcatBootstrapShim}
 * injected as instance-method bodies on the Tomcat 9 Bootstrap class.
 *
 * <p>Rewriting the caller instead of injecting a shim into the callee
 * gives us two structural wins:
 *
 * <ul>
 *   <li>{@code tomcat-embed-core-9.0.118.jar} is no longer modified at
 *       runtime; the running container's copy matches Maven Central's
 *       published bytes byte-for-byte.</li>
 *   <li>The fix is root-cause (rewrite the dangling instance-method call)
 *       rather than symptomatic (inject a no-op method so the dangling
 *       call links).</li>
 * </ul>
 *
 * <h2>The rewrite, in bytecode</h2>
 *
 * <p>Before (compiled from {@code bootstrap.setCatalinaBase(value)}):
 *
 * <pre>
 *   ...                                          ; stack: ..., Bootstrap_ref, String_arg
 *   INVOKEVIRTUAL org/apache/catalina/startup/Bootstrap.setCatalinaBase
 *                 (Ljava/lang/String;)V          ; stack: ...
 * </pre>
 *
 * <p>After (compiled from {@code System.setProperty("catalina.base", value)}
 * with the unused {@code Bootstrap} receiver discarded):
 *
 * <pre>
 *   ...                                          ; stack: ..., Bootstrap_ref, String_arg
 *   SWAP                                         ; stack: ..., String_arg, Bootstrap_ref
 *   POP                                          ; stack: ..., String_arg
 *   LDC "catalina.base"                          ; stack: ..., String_arg, "catalina.base"
 *   SWAP                                         ; stack: ..., "catalina.base", String_arg
 *   INVOKESTATIC java/lang/System.setProperty
 *                 (Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
 *                                                ; stack: ..., previous_value
 *   POP                                          ; stack: ...
 * </pre>
 *
 * <p>Peak stack during the replacement is two slots on top of whatever
 * was below -- identical to the peak the original {@code INVOKEVIRTUAL}
 * required for its two arguments -- so the surrounding method's
 * {@code max_stack} attribute remains valid and we do not request
 * {@link org.objectweb.asm.ClassWriter#COMPUTE_MAXS}.  The replacement
 * is straight-line code (no branches), so existing stack-map frames at
 * any later branch target also remain valid.
 *
 * <p>This visitor is a no-op for any class that does not call either
 * targeted method.  In practice (CFR decompile of airvision.jar v3.10.13),
 * exactly one class -- {@code com/ubnt/common/oOOO/A} -- contains the
 * two call sites, both in its {@code <init>}.  The visitor is run over
 * every class in the JAR rather than gated on a class allowlist so that
 * a future airvision build which moves or duplicates the calls is
 * still handled without spec changes.
 *
 * <h2>Why a no-op {@code System.setProperty}</h2>
 *
 * <p>By the time airvision's Guice bootstrap runs {@code A.<init>},
 * Tomcat 9's {@code Bootstrap} static initialiser has already read
 * {@code catalina.base} / {@code catalina.home} (or fallen back to
 * {@code user.dir}, which the unifi-video init script sets to
 * {@code /usr/lib/unifi-video} by {@code cd}'ing there before
 * {@code exec}'ing jsvc).  Setting the property after the fact has no
 * effect on Tomcat -- the value has already been latched into static
 * fields.  We emit {@code System.setProperty} anyway (rather than
 * eliding the calls entirely) so that the rewritten bytecode preserves
 * the source-level intent of airvision's original code; a maintainer
 * disassembling the patched class sees what the author meant, not just
 * an unexplained pop.
 */
public final class BootstrapCallSiteRewriter extends ClassVisitor {

    static final String BOOTSTRAP_INTERNAL_NAME =
            "org/apache/catalina/startup/Bootstrap";
    static final String SET_CATALINA_BASE_NAME  = "setCatalinaBase";
    static final String SET_CATALINA_HOME_NAME  = "setCatalinaHome";
    static final String ONE_STRING_VOID_DESC    = "(Ljava/lang/String;)V";

    static final String CATALINA_BASE_PROPERTY  = "catalina.base";
    static final String CATALINA_HOME_PROPERTY  = "catalina.home";

    static final String SYSTEM_INTERNAL_NAME    = "java/lang/System";
    static final String SET_PROPERTY_NAME       = "setProperty";
    static final String SET_PROPERTY_DESC =
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

    private int rewritesDone;

    public BootstrapCallSiteRewriter(ClassVisitor delegate) {
        super(Opcodes.ASM9, delegate);
    }

    /** Total number of {@code Bootstrap.setCatalina*} calls rewritten so
     *  far across every class this visitor has been driven over.  The
     *  airvision pass surfaces this in its boot-log summary line. */
    public int rewritesDone() {
        return rewritesDone;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return mv == null ? null : new CallSiteMethodVisitor(mv);
    }

    private final class CallSiteMethodVisitor extends MethodVisitor {

        CallSiteMethodVisitor(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && BOOTSTRAP_INTERNAL_NAME.equals(owner)
                    && ONE_STRING_VOID_DESC.equals(descriptor)
                    && (SET_CATALINA_BASE_NAME.equals(name)
                        || SET_CATALINA_HOME_NAME.equals(name))) {
                String property = SET_CATALINA_BASE_NAME.equals(name)
                        ? CATALINA_BASE_PROPERTY
                        : CATALINA_HOME_PROPERTY;
                emitSetPropertyReplacement(property);
                rewritesDone++;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        private void emitSetPropertyReplacement(String propertyName) {
            // See class-level javadoc for the stack diagram.
            super.visitInsn(Opcodes.SWAP);
            super.visitInsn(Opcodes.POP);
            super.visitLdcInsn(propertyName);
            super.visitInsn(Opcodes.SWAP);
            super.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    SYSTEM_INTERNAL_NAME,
                    SET_PROPERTY_NAME,
                    SET_PROPERTY_DESC,
                    false);
            super.visitInsn(Opcodes.POP);
        }
    }
}
