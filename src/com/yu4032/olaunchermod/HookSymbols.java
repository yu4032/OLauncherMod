package com.yu4032.olaunchermod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves Java/ART symbols by names and descriptors, never by memory offsets.
 *
 * Obfuscated method names can change across Launcher releases. A structural
 * fallback is permitted only when exactly one non-synthetic method in the
 * known owner matches the complete signature. Ambiguity fails closed.
 *
 * Class owners must still be identified for each APK. No method descriptor
 * alone can safely identify an arbitrary obfuscated class in the entire dex.
 */
final class HookSymbols {
    private HookSymbols() {}

    /**
     * Core workspace capabilities, deliberately independent of model loading,
     * rotation and the optional arrangement helper. This separation is what
     * prevents an unrelated symbol rename from disabling the visible grid.
     */
    static void requireVisualGridMethods(Class<?> idpClass, Class<?> cellClass)
            throws ReflectiveOperationException {
        method(idpClass, "setColumnsAndRows", void.class, false, false,
                int.class, int.class);
        method(cellClass, "setGridSize", void.class, false, false,
                int.class, int.class);
    }

    /**
     * Resolve an owner from a small list of class names verified in archived
     * and currently installed APK bytecode. Never scan unrelated dex classes.
     */
    static Class<?> classByAnyName(ClassLoader loader, String... knownNames)
            throws ClassNotFoundException {
        if (loader == null || knownNames == null || knownNames.length == 0) {
            throw new IllegalArgumentException("missing known owner names");
        }
        ClassNotFoundException last = null;
        for (String owner : knownNames) {
            if (owner == null || owner.isEmpty()) {
                throw new IllegalArgumentException("blank owner name");
            }
            try {
                return Class.forName(owner, false, loader);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw new ClassNotFoundException("No verified owner found among "
                + Arrays.toString(knownNames), last);
    }

    static Method method(Class<?> owner, String name, Class<?> result,
            boolean staticMethod, boolean allowUniqueFallback,
            Class<?>... parameters) throws ReflectiveOperationException {
        if (owner == null || name == null || result == null || parameters == null) {
            throw new IllegalArgumentException("null symbol specification");
        }

        Class<?> current = owner;
        while (current != null) {
            Method exact = null;
            try {
                exact = current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                // Search only the direct class hierarchy, not arbitrary dex members.
            }
            if (exact != null) {
                if (!compatible(exact, result, staticMethod, parameters)) {
                    throw new NoSuchMethodException("Incompatible named symbol: "
                            + exact + " expected result=" + result.getName()
                            + " static=" + staticMethod);
                }
                exact.setAccessible(true);
                return exact;
            }
            current = current.getSuperclass();
        }

        if (!allowUniqueFallback) {
            throw new NoSuchMethodException("Missing named symbol: " + owner.getName()
                    + "#" + name + Arrays.toString(parameters));
        }

        // Only the supplied class is searched. Searching its superclasses for
        // unnamed methods could silently hook a framework method instead.
        List<Method> matches = new ArrayList<>();
        for (Method candidate : owner.getDeclaredMethods()) {
            if (candidate.isBridge() || candidate.isSynthetic()) continue;
            if (compatible(candidate, result, staticMethod, parameters)) {
                matches.add(candidate);
            }
        }
        if (matches.size() != 1) {
            throw new NoSuchMethodException("Unresolved/ambiguous signature: "
                    + owner.getName() + "#" + name + Arrays.toString(parameters)
                    + " -> " + result.getName() + " static=" + staticMethod
                    + " matches=" + matches.size() + " " + matches);
        }
        Method resolved = matches.get(0);
        resolved.setAccessible(true);
        return resolved;
    }

    private static boolean compatible(Method method, Class<?> result,
            boolean staticMethod, Class<?>[] parameters) {
        return method.getReturnType() == result
                && Modifier.isStatic(method.getModifiers()) == staticMethod
                && Arrays.equals(method.getParameterTypes(), parameters);
    }

    static Field field(Class<?> owner, String name, Class<?> type,
            boolean staticField, boolean allowUniqueFallback)
            throws ReflectiveOperationException {
        if (owner == null || name == null || type == null) {
            throw new IllegalArgumentException("null field specification");
        }
        try {
            Field exact = owner.getDeclaredField(name);
            if (!compatible(exact, type, staticField)) {
                throw new NoSuchFieldException("Incompatible named field: " + exact);
            }
            exact.setAccessible(true);
            return exact;
        } catch (NoSuchFieldException e) {
            if (!allowUniqueFallback || (e.getMessage() != null
                    && e.getMessage().startsWith("Incompatible"))) {
                throw e;
            }
        }
        List<Field> matches = new ArrayList<>();
        for (Field candidate : owner.getDeclaredFields()) {
            if (candidate.isSynthetic()) continue;
            if (compatible(candidate, type, staticField)) matches.add(candidate);
        }
        if (matches.size() != 1) {
            throw new NoSuchFieldException("Unresolved/ambiguous field: "
                    + owner.getName() + "#" + name + " -> " + type.getName()
                    + " matches=" + matches.size());
        }
        Field resolved = matches.get(0);
        resolved.setAccessible(true);
        return resolved;
    }

    private static boolean compatible(Field field, Class<?> type,
            boolean staticField) {
        return field.getType() == type
                && Modifier.isStatic(field.getModifiers()) == staticField;
    }
}
