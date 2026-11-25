package com.yu4032.olaunchermod;

import org.junit.Test;

import static org.junit.Assert.*;

public class IndicatorActivityConfigTest {
    private static final String COMPONENT = "com.android.settings/com.android.settings.Settings$WifiSettingsActivity";
    private static final String BREENO_ACTION = "heytap.intent.action.NEW_AI_CHAT_HOME_ENTRANCE";
    private static final String BREENO_PACKAGE = "com.heytap.speechassist";

    @Test
    public void enabledConfigOnlyReplacesTheBreenoIndicatorIntent() {
        IndicatorActivityConfig config = IndicatorActivityConfig.parse("1 " + COMPONENT);
        assertEquals(COMPONENT, config.targetFor(BREENO_ACTION, BREENO_PACKAGE));
        assertNull(config.targetFor("android.search.action.DOCK_SEARCH", BREENO_PACKAGE));
        assertNull(config.targetFor(BREENO_ACTION, "com.example.other"));
        assertNull(config.targetFor(null, null));
    }

    @Test
    public void disabledConfigRemembersTheSelection() {
        IndicatorActivityConfig config = IndicatorActivityConfig.parse("0 " + COMPONENT);
        assertFalse(config.enabled());
        assertEquals(COMPONENT, config.component());
        assertEquals("0 " + COMPONENT, config.serialize());
        assertNull(config.targetFor(BREENO_ACTION, BREENO_PACKAGE));
    }

    @Test
    public void missingOrMalformedSettingsKeepNativeBehavior() {
        for (String raw : new String[]{null, "", "1", "true " + COMPONENT,
                "1 com.android.settings", "1 " + COMPONENT + "';id", "1 /Bad.Class"}) {
            assertEquals(IndicatorActivityConfig.DEFAULT, IndicatorActivityConfig.parse(raw));
        }
    }
}
