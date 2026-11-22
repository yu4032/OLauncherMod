package com.yu4032.olaunchermod;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceMarginConfigTest {
    @Test public void nativePaddingIsUntouchedByDefault() {
        int[] nativePadding = {32, 80, 40, 96};
        assertArrayEquals(nativePadding,
                WorkspaceMarginConfig.DEFAULT.adjust(nativePadding, 2f, 1000, 800));
        assertArrayEquals(new int[]{32,80,40,96}, nativePadding);
    }

    @Test public void positiveLeftAndNegativeRightMoveWorkspaceRight() {
        WorkspaceMarginConfig margin = WorkspaceMarginConfig.parse("20 0 -20 0");
        assertArrayEquals(new int[]{60,80,0,96},
                margin.adjust(new int[]{20,80,40,96}, 2f, 1300, 900));
    }

    @Test public void topAndBottomCanShiftGridWithoutChangingDatabaseCoordinates() {
        WorkspaceMarginConfig margin = WorkspaceMarginConfig.parse("0 -10 0 30");
        assertArrayEquals(new int[]{20,50,20,130},
                margin.adjust(new int[]{20,70,20,70}, 2f, 1200, 900));
    }

    @Test public void negativePaddingNeverBecomesNegative() {
        assertArrayEquals(new int[]{0,0,0,0},
                WorkspaceMarginConfig.parse("-160 -160 -160 -160")
                        .adjust(new int[]{5,5,5,5}, 2f, 1600, 1200));
    }

    @Test public void extremeMarginsLeaveRoomForGrid() {
        int[] result = WorkspaceMarginConfig.parse("160 160 160 160")
                .adjust(new int[]{150,120,150,120}, 3f, 600, 800);
        assertTrue(result[0] + result[2] <= (int)(600 * 0.72f));
        assertTrue(result[1] + result[3] <= (int)(800 * 0.72f));
        assertTrue(600 - result[0] - result[2] >= 168);
    }

    @Test public void noMutationOfInputEvenWhenClamped() {
        int[] nativePadding = {50,60,70,80};
        WorkspaceMarginConfig.parse("150 150 150 150")
                .adjust(nativePadding, 2f, 700, 800);
        assertArrayEquals(new int[]{50,60,70,80}, nativePadding);
    }

    @Test public void parsesSignedValuesInFixedOrder() {
        WorkspaceMarginConfig settings = WorkspaceMarginConfig.parse("-4 25 8 -12");
        assertEquals("-4 25 8 -12", settings.serialize());
        assertFalse(settings.isDefault());
        assertEquals("0 0 0 0", WorkspaceMarginConfig.DEFAULT.serialize());
    }

    @Test public void rejectsInvalidOrOversizedInputs() {
        for (String bad : new String[]{"1 2 3", "1 2 3 4 5", "161 0 0 0",
                "-161 0 0 0", "1 O 3 4", ""}) {
            try {
                WorkspaceMarginConfig.parse(bad);
                fail("Accepted invalid margin config: " + bad);
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void independentOfOrientationAndNativeGridSize() {
        WorkspaceMarginConfig margins = WorkspaceMarginConfig.parse("12 5 15 8");
        assertArrayEquals(new int[]{44,90,50,96},
                margins.adjust(new int[]{20,80,20,80}, 2f, 1600, 1000));
        assertArrayEquals(new int[]{44,90,50,96},
                margins.adjust(new int[]{20,80,20,80}, 2f, 1000, 1600));
    }
}
