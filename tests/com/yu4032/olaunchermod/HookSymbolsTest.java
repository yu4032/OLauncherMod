package com.yu4032.olaunchermod;

import org.junit.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

public class HookSymbolsTest {
    static class Fixture {
        public static String named(int x) { return String.valueOf(x); }
        private static String relocated(boolean value) { return String.valueOf(value); }
        private int instance(int x) { return x; }
        public static int overloaded(int x) { return x; }
        public static int overloaded(boolean b) { return b ? 1 : 0; }
        public static int anotherBoolean(boolean b) { return b ? 2 : 0; }
        private static String one = "cache";
        private static String two = "another";
    }

    static class Unique {
        private static String renamed = "ok";
        private static String methodAfterRename(int value) { return "ok"; }
    }

    static class Parent {
        protected int inherited(int a) { return a; }
    }
    static class Child extends Parent {}

    @Test public void resolvesByNameAndSignature() throws Exception {
        Method m = HookSymbols.method(Fixture.class, "named", String.class, true,
                false, int.class);
        assertEquals("named", m.getName());
    }

    @Test public void supportsUniqueRenamedMethod() throws Exception {
        Method m = HookSymbols.method(Unique.class, "previousName", String.class,
                true, true, int.class);
        assertEquals("methodAfterRename", m.getName());
    }

    @Test public void supportsUniqueRenamedField() throws Exception {
        Field f = HookSymbols.field(Unique.class, "previousName", String.class,
                true, true);
        assertEquals("renamed", f.getName());
    }

    @Test public void refusesAmbiguousFallback() throws Exception {
        try {
            HookSymbols.method(Fixture.class, "missing", int.class, true,
                    true, boolean.class);
            fail("expected ambiguity");
        } catch (NoSuchMethodException expected) { }
    }

    @Test public void refusesWrongStaticness() throws Exception {
        try {
            HookSymbols.method(Fixture.class, "instance", int.class, true,
                    true, int.class);
            fail("expected rejection");
        } catch (NoSuchMethodException expected) { }
    }

    @Test public void refusesWrongReturnTypeForNamedMethod() throws Exception {
        try {
            HookSymbols.method(Fixture.class, "named", int.class, true,
                    true, int.class);
            fail("expected rejection");
        } catch (NoSuchMethodException expected) { }
    }

    @Test public void refusesMultipleStringFields() throws Exception {
        try {
            HookSymbols.field(Fixture.class, "missing", String.class, true, true);
            fail("expected ambiguity");
        } catch (NoSuchFieldException expected) { }
    }

    @Test public void respectsNoFallbackFlag() throws Exception {
        try {
            HookSymbols.method(Unique.class, "wrong", String.class, true,
                    false, int.class);
            fail("expected missing name");
        } catch (NoSuchMethodException expected) { }
    }

    @Test public void supportsInheritedExactMethod() throws Exception {
        Method m = HookSymbols.method(Child.class, "inherited", int.class, false,
                false, int.class);
        assertEquals(Parent.class, m.getDeclaringClass());
    }

    @Test public void neverGuessesAnUnnamedSuperclassMethod() throws Exception {
        try {
            HookSymbols.method(Child.class, "renamed", int.class, false, true,
                    int.class);
            fail("expected no match");
        } catch (NoSuchMethodException expected) { }
    }
}
