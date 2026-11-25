package com.yu4032.olaunchermod;

import java.util.regex.Pattern;

public record IndicatorActivityConfig(boolean enabled, String component) {
    static final String FILE_NAME = "launcher_indicator_activity";
    static final IndicatorActivityConfig DEFAULT = new IndicatorActivityConfig(false, "");

    private static final String ASSISTANT_ACTION = "heytap.intent.action.NEW_AI_CHAT_HOME_ENTRANCE";
    private static final String ASSISTANT_PACKAGE = "com.heytap.speechassist";
    private static final String PACKAGE_SEGMENT = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String CLASS_SEGMENT = "[\\p{L}$_][\\p{L}\\p{N}$_]*";
    private static final Pattern COMPONENT = Pattern.compile(PACKAGE_SEGMENT
            + "(?:\\." + PACKAGE_SEGMENT + ")*/\\.?" + CLASS_SEGMENT
            + "(?:\\." + CLASS_SEGMENT + ")*");

    public IndicatorActivityConfig {
        if (component == null || (component.isEmpty() ? enabled : !validComponent(component))) {
            throw new IllegalArgumentException("Invalid indicator activity component");
        }
    }

    static boolean validComponent(String component) {
        return component.length() <= 1024 && COMPONENT.matcher(component).matches();
    }

    static IndicatorActivityConfig parse(String raw) {
        if (raw == null || raw.length() > 1026) return DEFAULT;
        String[] parts = raw.trim().split(" +", 2);
        if (parts.length != 2 || !validComponent(parts[1])) return DEFAULT;
        return switch (parts[0]) {
            case "1" -> new IndicatorActivityConfig(true, parts[1]);
            case "0" -> new IndicatorActivityConfig(false, parts[1]);
            default -> DEFAULT;
        };
    }

    String serialize() {
        return component.isEmpty() ? "0" : (enabled ? "1 " : "0 ") + component;
    }

    String targetFor(String action, String packageName) {
        return enabled && ASSISTANT_ACTION.equals(action) && ASSISTANT_PACKAGE.equals(packageName)
                ? component : null;
    }
}
