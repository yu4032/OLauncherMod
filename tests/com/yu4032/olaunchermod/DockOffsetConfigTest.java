package com.yu4032.olaunchermod;

import static org.junit.Assert.*;

import org.junit.Test;

public class DockOffsetConfigTest {
    @Test
    public void parseAndSerializeRoundTrip() {
        DockOffsetConfig cfg = DockOffsetConfig.parse("18 -24");
        assertEquals(18, cfg.get(false));
        assertEquals(-24, cfg.get(true));
        assertEquals("18 -24", cfg.serialize());
        assertFalse(cfg.isDefault());
        assertTrue(DockOffsetConfig.DEFAULT.isDefault());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOutOfRangeOffset() {
        DockOffsetConfig.parse("301 0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongArity() {
        DockOffsetConfig.parse("10");
    }

    @Test
    public void positiveMovesDownNegativeMovesUpWithinBounds() {
        DockOffsetConfig cfg = DockOffsetConfig.of(10, -20);
        // density = 3.0 -> +10dp = 30px down -> bottomMargin decreases 82 -> 52
        assertEquals(52, cfg.adjustBottomMargin(82, 3f, 252, 1904, false));
        // portrait -20dp = -60px -> bottomMargin increases 82 -> 142
        assertEquals(142, cfg.adjustBottomMargin(82, 3f, 252, 3040, true));

        // Clamped so Dock cannot disappear completely below or above screen
        DockOffsetConfig extremeDown = DockOffsetConfig.of(300, 0);
        assertEquals(-126, extremeDown.adjustBottomMargin(82, 3f, 252, 1904, false));

        DockOffsetConfig extremeUp = DockOffsetConfig.of(-300, 0);
        assertEquals(857, extremeUp.adjustBottomMargin(82, 3f, 252, 1904, false));
    }

    @Test
    public void orientationEditsAndResetsAreIndependent() {
        DockOffsetConfig cfg = DockOffsetConfig.DEFAULT
                .with(false, 12)
                .with(true, -16);
        assertEquals("12 -16", cfg.serialize());
        assertEquals("0 -16", cfg.resetOrientation(false).serialize());
        assertEquals("12 0", cfg.resetOrientation(true).serialize());
    }
}
