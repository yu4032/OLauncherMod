package com.yu4032.olaunchermod;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceMarginConfigTest {
    @Test public void defaultsDoNotChangeNativePadding() {
        int[] source = {32, 80, 40, 96};
        assertArrayEquals(source, WorkspaceMarginConfig.DEFAULT.adjust(
                source, 2f, 1000, 800, false));
        assertArrayEquals(source, WorkspaceMarginConfig.DEFAULT.adjust(
                source, 2f, 800, 1000, true));
    }

    @Test public void landscapeHasSymmetricHorizontalInsets() {
        WorkspaceMarginConfig config = WorkspaceMarginConfig.parse("20 0 -20 0 0 0");
        assertArrayEquals(new int[]{60,80,80,96},
                config.adjust(new int[]{20,80,40,96}, 2f, 1300, 900, false));
    }

    @Test public void portraitValuesAreIndependent() {
        WorkspaceMarginConfig config = WorkspaceMarginConfig.parse("20 10 30 -15 -5 8");
        assertArrayEquals(new int[]{60,100,80,156},
                config.adjust(new int[]{20,80,40,96}, 2f, 1300, 900, false));
        assertArrayEquals(new int[]{0,70,10,112},
                config.adjust(new int[]{20,80,40,96}, 2f, 900, 1300, true));
    }

    @Test public void formatRoundTripsSixValues() {
        String text = "3 -7 12 -8 20 9";
        assertEquals(text, WorkspaceMarginConfig.parse(text).serialize());
    }

    @Test public void legacyFourValueFormatIsExactBeforeUserEdits() {
        WorkspaceMarginConfig config = WorkspaceMarginConfig.parse("20 5 -10 15");
        assertTrue(config.isLegacy());
        assertTrue(config.isLegacyAsymmetric());
        assertEquals("20 5 -10 15", config.serialize());
        assertArrayEquals(new int[]{60,90,20,126},
                config.adjust(new int[]{20,80,40,96}, 2f, 1300, 900, true));
    }

    @Test public void legacyEditsConvertToCanonicalOrientationFormat() {
        WorkspaceMarginConfig config = WorkspaceMarginConfig.parse("20 5 -10 15")
                .with(false, WorkspaceMarginConfig.TOP, 16);
        assertFalse(config.isLegacy());
        assertEquals("5 16 15 5 5 15", config.serialize());
    }

    @Test public void resetOnlyOneOrientation() {
        WorkspaceMarginConfig config = WorkspaceMarginConfig.parse("5 7 9 11 13 15")
                .resetOrientation(false);
        assertEquals("0 0 0 11 13 15", config.serialize());
    }

    @Test public void preventsNegativePadding() {
        assertArrayEquals(new int[]{0,0,0,0},
                WorkspaceMarginConfig.parse("-600 -600 -600 0 0 0")
                    .adjust(new int[]{5,5,5,5}, 2f, 1600, 1200, false));
    }

    @Test public void clampsExcessWithoutMutatingInput() {
        int[] original = {150,120,150,120};
        int[] result = WorkspaceMarginConfig.parse("600 600 600 0 0 0")
                .adjust(original, 3f, 600, 800, false);
        assertTrue(result[0] + result[2] <= (int)(600 * 0.72f));
        assertTrue(result[1] + result[3] <= (int)(800 * 0.72f));
        assertArrayEquals(new int[]{150,120,150,120}, original);
    }

    @Test public void acceptsLiquidDockRangeEndpoints() {
        assertEquals("-600 600 0 -600 600 0",
                WorkspaceMarginConfig.parse("-600 600 0 -600 600 0").serialize());
    }

    @Test public void rejectsInvalidFormats() {
        for (String bad : new String[]{"", "1 2 3", "1 2 3 4 5",
                "601 0 0 0 0 0", "-601 0 0 0", "a b c d e f"}) {
            try {
                WorkspaceMarginConfig.parse(bad);
                fail("accepted invalid format: " + bad);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void validatesIncrementAndAxis() {
        try {
            WorkspaceMarginConfig.DEFAULT.with(true, 3, 0);
            fail("invalid axis accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            WorkspaceMarginConfig.DEFAULT.with(false, 0, 601);
            fail("invalid value accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
