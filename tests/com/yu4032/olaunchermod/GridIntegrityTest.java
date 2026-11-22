package com.yu4032.olaunchermod;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class GridIntegrityTest {
    private static GridIntegrity.Placement at(int x, int y) {
        return new GridIntegrity.Placement(0, x, y, 1, 1, -1, -1);
    }

    @Test public void allowsIconsInNewColumns() {
        assertTrue(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Arrays.asList(at(0, 0), at(6, 1), at(7, 3))));
    }

    @Test public void preservesEmptySpacesWithoutReflow() {
        assertTrue(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Arrays.asList(at(1, 0), at(7, 3))));
    }

    @Test public void rejectsShrink() {
        assertFalse(GridIntegrity.canPreserveExpansion(8, 4, 6, 4,
                Collections.singletonList(at(0, 0))));
    }

    @Test public void rejectsUnchangedSize() {
        assertFalse(GridIntegrity.canPreserveExpansion(8, 4, 8, 4,
                Collections.singletonList(at(0, 0))));
    }

    @Test public void rejectsOverflowingWidget() {
        assertFalse(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Collections.singletonList(new GridIntegrity.Placement(
                        0, 7, 2, 2, 2, -1, -1))));
    }

    @Test public void rejectsHorizontalCollision() {
        assertFalse(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Arrays.asList(at(7, 0), at(7, 0))));
    }

    @Test public void allowsSameCellOnSeparateScreens() {
        assertTrue(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Arrays.asList(at(7, 0),
                    new GridIntegrity.Placement(1, 7, 0, 1, 1, -1, -1))));
    }

    @Test public void rejectsPortraitOverflow() {
        assertFalse(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Collections.singletonList(new GridIntegrity.Placement(
                        0, 0, 0, 1, 1, 4, 0))));
    }

    @Test public void rejectsPortraitCollision() {
        assertFalse(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Arrays.asList(new GridIntegrity.Placement(0, 0, 0, 1, 1, 1, 1),
                        new GridIntegrity.Placement(0, 7, 0, 1, 1, 1, 1))));
    }

    @Test public void rejectsIncompletePortraitCoordinate() {
        assertFalse(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Collections.singletonList(new GridIntegrity.Placement(
                        0, 7, 0, 1, 1, 0, -1))));
    }

    @Test public void rejectsNegativeSpans() {
        assertFalse(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Collections.singletonList(new GridIntegrity.Placement(
                        0, 0, 0, -1, 1, -1, -1))));
    }

    @Test public void rejectsOverflowingIntegerCoordinates() {
        assertFalse(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Collections.singletonList(new GridIntegrity.Placement(
                        0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 1, -1, -1))));
    }

    @Test public void protectsValidInBoundsRowFromLayoutOnlyDeletion() {
        assertTrue(GridIntegrity.shouldRetainRejectedItem(
                "Item position overlap", -100, 7, 1, 1, 1, 8, 4));
    }

    @Test public void doesNotProtectTrulyOutOfBoundsRow() {
        assertFalse(GridIntegrity.shouldRetainRejectedItem(
                "Item position overlap", -100, 8, 1, 1, 1, 8, 4));
    }

    @Test public void doesNotProtectOtherDeletionReasons() {
        assertFalse(GridIntegrity.shouldRetainRejectedItem(
                "Invalid or null intent", -100, 7, 1, 1, 1, 8, 4));
    }

    @Test public void doesNotProtectFolderItemOrHotseat() {
        assertFalse(GridIntegrity.shouldRetainRejectedItem(
                "Item position overlap", -101, 7, 1, 1, 1, 8, 4));
        assertFalse(GridIntegrity.shouldRetainRejectedItem(
                "Item position overlap", 100, 7, 1, 1, 1, 8, 4));
    }

    @Test public void allowsPortraitCellsAndWidgets() {
        assertTrue(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Arrays.asList(new GridIntegrity.Placement(0, 1, 1, 2, 2, 0, 3),
                        new GridIntegrity.Placement(0, 7, 0, 1, 1, 3, 0))));
    }
}
