/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) conmilo / unifi-video-controller modernization fork.
 */
package com.conmilo.uvpatcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lock down the deterministic rename rules.  Any change to these test
 * expectations is a Phase 3 contract change and needs an explicit decision.
 */
class ReservedNamesTest {

    // ---- class simple names ----------------------------------------------

    @Test
    void classSimpleNameReservedWordTriggersZPrefix() {
        assertTrue(ReservedNames.isIllegalClassSimpleName("super"));
        assertEquals("ZSuper", ReservedNames.escapeClassSimpleName("super"));

        assertTrue(ReservedNames.isIllegalClassSimpleName("return"));
        assertEquals("ZReturn", ReservedNames.escapeClassSimpleName("return"));

        assertTrue(ReservedNames.isIllegalClassSimpleName("int"));
        assertEquals("ZInt", ReservedNames.escapeClassSimpleName("int"));

        assertTrue(ReservedNames.isIllegalClassSimpleName("null"));
        assertEquals("ZNull", ReservedNames.escapeClassSimpleName("null"));
    }

    @Test
    void classSimpleNameJavaLangTypeNameTriggersZPrefix() {
        assertTrue(ReservedNames.isIllegalClassSimpleName("Object"));
        assertEquals("ZObject", ReservedNames.escapeClassSimpleName("Object"));

        assertTrue(ReservedNames.isIllegalClassSimpleName("String"));
        assertEquals("ZString", ReservedNames.escapeClassSimpleName("String"));
    }

    @Test
    void legalClassSimpleNamesArePassedThrough() {
        for (String legal : new String[]{
                "Main", "F", "OoOO", "o0OO", "A$1", "ZSuper", "ZObject"}) {
            assertFalse(ReservedNames.isIllegalClassSimpleName(legal), legal);
            assertEquals(legal, ReservedNames.escapeClassSimpleName(legal));
        }
    }

    // ---- package segments ------------------------------------------------

    @Test
    void packageSegmentReservedWordTriggersZPrefixLowercase() {
        assertTrue(ReservedNames.isIllegalPackageSegment("super"));
        assertEquals("Zsuper", ReservedNames.escapePackageSegment("super"));

        assertTrue(ReservedNames.isIllegalPackageSegment("class"));
        assertEquals("Zclass", ReservedNames.escapePackageSegment("class"));

        assertTrue(ReservedNames.isIllegalPackageSegment("new"));
        assertEquals("Znew", ReservedNames.escapePackageSegment("new"));

        assertTrue(ReservedNames.isIllegalPackageSegment("return"));
        assertEquals("Zreturn", ReservedNames.escapePackageSegment("return"));
    }

    @Test
    void packageSegmentJavaLangNameIsNotIllegalAsPackage() {
        // Object / String are illegal as CLASS simple names only; as a
        // package segment they're fine.  airvision doesn't use them as
        // packages either way, but be explicit about the contract.
        assertFalse(ReservedNames.isIllegalPackageSegment("Object"));
        assertEquals("Object", ReservedNames.escapePackageSegment("Object"));
    }

    @Test
    void legalPackageSegmentsArePassedThrough() {
        for (String legal : new String[]{"com", "ubnt", "airvision", "A", "oOOO"}) {
            assertFalse(ReservedNames.isIllegalPackageSegment(legal), legal);
            assertEquals(legal, ReservedNames.escapePackageSegment(legal));
        }
    }

    // ---- member names ----------------------------------------------------

    @Test
    void memberNameReservedWordTriggersZPrefix() {
        assertTrue(ReservedNames.isIllegalMemberName("new"));
        assertEquals("znew", ReservedNames.escapeMemberName("new"));

        assertTrue(ReservedNames.isIllegalMemberName("void"));
        assertEquals("zvoid", ReservedNames.escapeMemberName("void"));

        assertTrue(ReservedNames.isIllegalMemberName("return"));
        assertEquals("zreturn", ReservedNames.escapeMemberName("return"));

        assertTrue(ReservedNames.isIllegalMemberName("int"));
        assertEquals("zint", ReservedNames.escapeMemberName("int"));

        assertTrue(ReservedNames.isIllegalMemberName("super"));
        assertEquals("zsuper", ReservedNames.escapeMemberName("super"));
    }

    @Test
    void memberNameLiteralDotTriggersUnderscoreReplacement() {
        assertTrue(ReservedNames.isIllegalMemberName("new.super"));
        assertEquals("new_super", ReservedNames.escapeMemberName("new.super"));

        assertTrue(ReservedNames.isIllegalMemberName("foo.bar.baz"));
        assertEquals("foo_bar_baz", ReservedNames.escapeMemberName("foo.bar.baz"));
    }

    @Test
    void memberNameSpecialNamesArePreserved() {
        // <init> and <clinit> contain '<' and '>' which would otherwise be
        // illegal, but the JVM Spec carves them out as special names.
        assertFalse(ReservedNames.isIllegalMemberName("<init>"));
        assertEquals("<init>", ReservedNames.escapeMemberName("<init>"));

        assertFalse(ReservedNames.isIllegalMemberName("<clinit>"));
        assertEquals("<clinit>", ReservedNames.escapeMemberName("<clinit>"));
    }

    @Test
    void legalMemberNamesArePassedThrough() {
        for (String legal : new String[]{
                "toString", "equals", "hashCode", "doSomething",
                "_internal", "field1", "CONSTANT_VALUE", "znew"}) {
            assertFalse(ReservedNames.isIllegalMemberName(legal), legal);
            assertEquals(legal, ReservedNames.escapeMemberName(legal));
        }
    }

    // ---- whole-internal-name escape --------------------------------------

    @Test
    void internalNameWithReservedPackageAndReservedClassIsRewritten() {
        assertEquals("com/ubnt/A/Zsuper/oOOO/ZObject",
                ReservedNames.escapeInternalName("com/ubnt/A/super/oOOO/Object"));
        assertEquals("com/ubnt/A/Zsuper/oOOO/ZString",
                ReservedNames.escapeInternalName("com/ubnt/A/super/oOOO/String"));
        assertEquals("com/ubnt/A/Zsuper/oOOO/ZSuper",
                ReservedNames.escapeInternalName("com/ubnt/A/super/oOOO/super"));
    }

    @Test
    void internalNameInnerClassPropagatesOuterRename() {
        // The inner class encoding is Outer$Inner.  When the outer simple
        // name is reserved, the rename must propagate into the inner entry.
        assertEquals("com/ubnt/A/Zsuper/oOOO/ZString$o",
                ReservedNames.escapeInternalName("com/ubnt/A/super/oOOO/String$o"));
        // Inner whose simple name is NOT reserved still moves to the new
        // package because of the 'super' segment.
        assertEquals("com/ubnt/A/Zsuper/oOOO/o0OO$o",
                ReservedNames.escapeInternalName("com/ubnt/A/super/oOOO/o0OO$o"));
    }

    @Test
    void internalNameDeepPackageWithMultipleReservedSegments() {
        // com/ubnt/airvision/service/return/super.class:
        //   - 'return' is a reserved package segment -> Zreturn
        //   - 'super' is the class simple name -> ZSuper
        assertEquals("com/ubnt/airvision/service/Zreturn/ZSuper",
                ReservedNames.escapeInternalName("com/ubnt/airvision/service/return/super"));

        // com/ubnt/airvision/service/class/A/Object.class:
        //   - 'class' is a reserved package segment -> Zclass
        //   - 'Object' is the class simple name -> ZObject
        assertEquals("com/ubnt/airvision/service/Zclass/A/ZObject",
                ReservedNames.escapeInternalName("com/ubnt/airvision/service/class/A/Object"));
    }

    @Test
    void internalNameCleanInputPassesThroughUnchanged() {
        for (String clean : new String[]{
                "com/ubnt/airvision/Main",
                "org/apache/catalina/startup/Bootstrap",
                "com/ubnt/A/oOoO/Foo"}) {
            assertEquals(clean, ReservedNames.escapeInternalName(clean));
        }
    }

    @Test
    void internalNameJdkObjectIsRewrittenByPureFunction() {
        // The pure escape function treats "Object" as JLS-illegal as a class
        // simple name and rewrites it.  This is intentional: the function is
        // context-free.  IdentifierRemapper compensates for this by only
        // applying the rewrite when the class was actually present in the
        // input JAR (i.e. JDK references stay untouched because they were
        // not in the scan-pass result).
        assertEquals("java/lang/ZObject",
                ReservedNames.escapeInternalName("java/lang/Object"));
    }

    @Test
    void escapeIsIdempotent() {
        // Re-applying the escape rule to an already-escaped name should be
        // a no-op (the result is no longer reserved).
        String once = ReservedNames.escapeInternalName("com/ubnt/A/super/oOOO/Object");
        String twice = ReservedNames.escapeInternalName(once);
        assertEquals(once, twice);

        once = ReservedNames.escapeMemberName("new.super");
        twice = ReservedNames.escapeMemberName(once);
        assertEquals(once, twice);

        once = ReservedNames.escapeMemberName("new");
        twice = ReservedNames.escapeMemberName(once);
        assertEquals(once, twice);
    }
}
