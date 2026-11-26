package com.yu4032.olaunchermod;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses, validates, serializes and resolves per-package desktop icon replacements.
 *
 * <p>Supported mapping targets:
 * <ul>
 *   <li>{@code pkg:<packageName>} to borrow another installed app's launcher icon</li>
 *   <li>relative or absolute PNG path pointing inside {@code iconfix/icons/}</li>
 * </ul>
 */
public record IconReplacementConfig(Map<String, String> entries) {
    public static final String RELATIVE_CONF = "iconfix/iconfix.conf";
    public static final String RELATIVE_ICONS_DIR = "iconfix/icons";
    public static final IconReplacementConfig EMPTY =
            new IconReplacementConfig(Collections.emptyMap());

    private static final String PACKAGE_REGEX =
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*";
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(PACKAGE_REGEX);

    public IconReplacementConfig {
        LinkedHashMap<String, String> clean = new LinkedHashMap<>();
        if (entries != null) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String pkg = entry.getKey() == null ? "" : entry.getKey().trim();
                String target = entry.getValue() == null ? "" : entry.getValue().trim();
                if (isValidPackageName(pkg) && isValidTarget(pkg, target)) {
                    clean.put(pkg, target);
                }
            }
        }
        entries = Collections.unmodifiableMap(clean);
    }

    public static boolean isValidPackageName(String pkg) {
        return pkg != null && !pkg.isEmpty() && pkg.length() <= 255
                && PACKAGE_PATTERN.matcher(pkg).matches();
    }

    public static boolean isValidTarget(String pkg, String target) {
        if (target == null || target.isEmpty() || target.length() > 1024) return false;
        for (int i = 0; i < target.length(); i++) {
            char c = target.charAt(i);
            if (c == '\n' || c == '\r' || c == '\'' || c == '\0') return false;
        }
        if (target.startsWith("pkg:")) {
            String borrowed = target.substring(4).trim();
            return !borrowed.equals(pkg) && isValidPackageName(borrowed);
        }
        return !target.contains("..");
    }

    public static IconReplacementConfig parse(String raw) {
        if (raw == null || raw.isEmpty()) return EMPTY;
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String pkg = trimmed.substring(0, eq).trim();
            String val = trimmed.substring(eq + 1).trim();
            if (isValidPackageName(pkg) && isValidTarget(pkg, val)) {
                map.put(pkg, val);
            }
        }
        return map.isEmpty() ? EMPTY : new IconReplacementConfig(map);
    }

    public String serialize() {
        if (entries.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return sb.toString();
    }

    public String targetFor(String pkg) {
        return pkg == null ? null : entries.get(pkg);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public IconReplacementConfig withEntry(String pkg, String target) {
        if (!isValidPackageName(pkg) || !isValidTarget(pkg, target)) {
            throw new IllegalArgumentException("Invalid icon replacement: " + pkg + " -> " + target);
        }
        LinkedHashMap<String, String> next = new LinkedHashMap<>(entries);
        next.put(pkg, target);
        return new IconReplacementConfig(next);
    }

    public IconReplacementConfig withoutPackage(String pkg) {
        if (pkg == null || !entries.containsKey(pkg)) return this;
        LinkedHashMap<String, String> next = new LinkedHashMap<>(entries);
        next.remove(pkg);
        return next.isEmpty() ? EMPTY : new IconReplacementConfig(next);
    }

    public static String safeIconFileName(String pkg) {
        return pkg.replaceAll("[^A-Za-z0-9._]", "_") + ".png";
    }

    /**
     * Resolves an image file path across boot-safe internal dirs before external storage.
     * Supports relative {@code iconfix/icons/foo.png} as well as legacy absolute paths
     * under {@code /storage/emulated/0/.../iconfix/icons/foo.png}.
     */
    public static File resolveImageFile(String target, List<File> baseDirs) {
        if (target == null || target.isEmpty() || target.startsWith("pkg:")) return null;
        String baseName = new File(target).getName();
        if (baseDirs != null && !baseName.isEmpty() && !target.contains("..")) {
            for (File dir : baseDirs) {
                if (dir == null) continue;
                File candidate = new File(new File(dir, RELATIVE_ICONS_DIR), baseName);
                if (candidate.isFile() && candidate.canRead()) {
                    return candidate;
                }
                if (!target.startsWith("/")) {
                    File rel = new File(dir, target);
                    if (rel.isFile() && rel.canRead()) {
                        return rel;
                    }
                }
            }
        }
        File direct = new File(target);
        if (direct.isAbsolute() && direct.isFile() && direct.canRead()) {
            return direct;
        }
        return null;
    }
}
