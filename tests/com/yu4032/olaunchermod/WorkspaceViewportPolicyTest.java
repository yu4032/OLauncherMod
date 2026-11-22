package com.yu4032.olaunchermod;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceViewportPolicyTest {
    @Test public void portraitEightRowsFitRemainingHeight() {
        WorkspaceViewportPolicy.Fit fit = WorkspaceViewportPolicy.portraitFit(
                1600, 150, 250, 8, 6, true);
        assertEquals(1200, fit.childHeight);
        assertEquals(144, fit.maxCellHeight);
        assertTrue(fit.reduced);
    }

    @Test public void landscapePreservesValidatedGrid() {
        WorkspaceViewportPolicy.Fit fit = WorkspaceViewportPolicy.portraitFit(
                800, 130, 130, 4, 8, false);
        assertEquals(800, fit.childHeight);
        assertFalse(fit.reduced);
    }

    @Test public void portraitRecomputesEveryMeasurement() {
        WorkspaceViewportPolicy.Fit first = WorkspaceViewportPolicy.portraitFit(
                1600, 150, 250, 8, 0, true);
        WorkspaceViewportPolicy.Fit second = WorkspaceViewportPolicy.portraitFit(
                1600, 180, 300, 8, 0, true);
        assertEquals(1200, first.childHeight);
        assertEquals(1120, second.childHeight);
        assertEquals(140, second.maxCellHeight);
    }

    @Test public void zeroAndNegativePaddingDoNotReduce() {
        assertEquals(1500, WorkspaceViewportPolicy.portraitFit(
                1500, -20, 0, 8, 0, true).childHeight);
        assertFalse(WorkspaceViewportPolicy.portraitFit(
                1500, 0, 0, 8, 0, true).reduced);
    }

    @Test public void extremePaddingDoesNotBreakMeasurements() {
        WorkspaceViewportPolicy.Fit fit = WorkspaceViewportPolicy.portraitFit(
                500, 300, 300, 8, 10, true);
        assertEquals(500, fit.childHeight);
        assertFalse(fit.reduced);
    }

    @Test public void largeNativeBorderSpacingIsBounded() {
        WorkspaceViewportPolicy.Fit fit = WorkspaceViewportPolicy.portraitFit(
                800, 100, 100, 8, 200, true);
        assertEquals(600, fit.childHeight);
        assertTrue(fit.maxCellHeight >= 1);
    }

    @Test public void fixedCellIsReleasedOnlyWhenTooTall() {
        assertTrue(WorkspaceViewportPolicy.fixedCellOverflows(175, 140));
        assertFalse(WorkspaceViewportPolicy.fixedCellOverflows(120, 140));
        assertFalse(WorkspaceViewportPolicy.fixedCellOverflows(0, 140));
    }

    @Test public void noChangesToFourRowPortraitBase() {
        assertEquals(1400, WorkspaceViewportPolicy.portraitFit(
                1400, 100, 100, 4, 0, true).childHeight);
    }
}
