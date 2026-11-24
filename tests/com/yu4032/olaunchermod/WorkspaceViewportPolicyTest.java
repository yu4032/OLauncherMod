package com.yu4032.olaunchermod;

import org.junit.Test;
import static org.junit.Assert.*;

public class WorkspaceViewportPolicyTest {
    @Test public void statusBarAndDockBoundTheGridWithoutWorkspacePadding() {
        WorkspaceViewportPolicy.Fit fit =
                WorkspaceViewportPolicy.fit(0, 1600, 100, 1350, 1350, 0, 0);
        assertEquals(100, fit.gridTop);
        assertEquals(1350, fit.gridBottom);
    }

    @Test public void movesIndicatorEntirelyAboveDockAndFitsEightRows() {
        WorkspaceViewportPolicy.Fit fit =
                WorkspaceViewportPolicy.fit(0, 1600, 100, 1350, 1390, 40, 0);
        assertEquals(1310, fit.indicatorTop);
        assertEquals(fit.indicatorTop, fit.gridBottom);
        int height = fit.gridBottom - fit.gridTop;
        int cellHeight = WorkspaceViewportPolicy.maxCellHeight(height, 8, 8, 6);
        assertEquals(145, cellHeight);
        assertTrue(8 * cellHeight + 7 * 6 + 8 <= height);
    }

    @Test public void clampsIndicatorThatOnlyPartlyOverlapsDock() {
        assertEquals(1310, WorkspaceViewportPolicy.fit(
                0, 1600, 100, 1350, 1340, 40, 0).indicatorTop);
    }

    @Test public void addsClearanceWhenIndicatorAlreadyTouchesDock() {
        WorkspaceViewportPolicy.Fit fit =
                WorkspaceViewportPolicy.fit(0, 1600, 100, 1350, 1310, 40, 8);
        assertEquals(1302, fit.indicatorTop);
        assertEquals(8, 1350 - (fit.indicatorTop + 40));
        assertEquals(1302, fit.gridBottom);
    }

    @Test public void preservesIndicatorWhenAlreadyAboveDock() {
        WorkspaceViewportPolicy.Fit fit =
                WorkspaceViewportPolicy.fit(0, 1600, 100, 1350, 1280, 40, 0);
        assertEquals(1280, fit.indicatorTop);
        assertEquals(1280, fit.gridBottom);
    }

    @Test public void honorsCustomWorkspaceMarginsInsidePhysicalBounds() {
        WorkspaceViewportPolicy.Fit fit =
                WorkspaceViewportPolicy.fit(145, 1160, 100, 1350, 1280, 40, 0);
        assertEquals(145, fit.gridTop);
        assertEquals(1160, fit.gridBottom);
        assertEquals(1280, fit.indicatorTop);
    }

    @Test public void recomputesFromDockAndStatusBarAfterRotation() {
        WorkspaceViewportPolicy.Fit first =
                WorkspaceViewportPolicy.fit(0, 1920, 72, 1680, 1740, 40, 0);
        WorkspaceViewportPolicy.Fit second =
                WorkspaceViewportPolicy.fit(0, 1920, 84, 1610, 1740, 40, 0);
        assertEquals(72, first.gridTop);
        assertEquals(1640, first.gridBottom);
        assertEquals(84, second.gridTop);
        assertEquals(1570, second.gridBottom);
    }

    @Test public void reportsInfeasibleGapSizeWithoutInventingSmallerNativeGaps() {
        assertTrue(WorkspaceViewportPolicy.maxCellHeight(100, 8, 8, 40) < 1);
    }
}
