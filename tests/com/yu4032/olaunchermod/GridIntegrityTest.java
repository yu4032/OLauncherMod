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

    @Test public void canonicalLoaderBoundsOverrideStaleSixColumns() {
        assertArrayEquals(new int[]{8, 4},
                GridIntegrity.canonicalLoaderBounds(8, 4, 6, 4));
    }

    @Test public void canonicalLoaderBoundsAreLandscapeEvenForPortraitIdp() {
        assertArrayEquals(new int[]{8, 4},
                GridIntegrity.canonicalLoaderBounds(8, 4, 4, 8));
    }

    @Test public void nativeLoaderBoundsPassThroughWithoutConfiguration() {
        assertArrayEquals(new int[]{6, 4},
                GridIntegrity.canonicalLoaderBounds(0, 0, 6, 4));
    }

    @Test public void destructiveMigrationRequiresCanonicalTarget() {
        assertFalse(GridIntegrity.isCanonicalMigrationTarget(8, 4, 6, 4));
        assertFalse(GridIntegrity.isCanonicalMigrationTarget(8, 4, 4, 8));
        assertTrue(GridIntegrity.isCanonicalMigrationTarget(8, 4, 8, 4));
    }

    @Test public void protectsWidgetFromTemporaryProviderGap() {
        assertTrue(GridIntegrity.shouldRetainWidgetLoaderRow(-100, 5,
                "Deleting widget that isn't installed anymore: null", true));
        assertTrue(GridIntegrity.shouldRetainWidgetLoaderRow(-100, 99,
                "Unrestored widget removed: test", true));
    }

    @Test public void protectsWidgetSpanAndPlacementDuringGridChanges() {
        assertTrue(GridIntegrity.shouldRetainWidgetLoaderRow(-100, 5,
                "Widget has invalid size: 0x2", true));
        assertTrue(GridIntegrity.shouldRetainWidgetLoaderRow(-100, 5,
                "Item position overlap", true));
    }

    @Test public void preservesNativeUnrelatedWidgetCleanup() {
        assertFalse(GridIntegrity.shouldRetainWidgetLoaderRow(-100, 5,
                "unflatten component name error", true));
        assertFalse(GridIntegrity.shouldRetainWidgetLoaderRow(-100, 5,
                "Unrestored widget removed: missing", false));
        assertFalse(GridIntegrity.shouldRetainWidgetLoaderRow(-101, 5,
                "Widget has invalid size: 0x2", true));
        assertFalse(GridIntegrity.shouldRetainWidgetLoaderRow(-100, 0,
                "Widget has invalid size: 0x2", true));
        assertFalse(GridIntegrity.shouldRetainWidgetLoaderRow(-100, 4,
                "Widget has invalid size: 0x2", true));
    }

    @Test public void allowsPortraitCellsAndWidgets() {
        assertTrue(GridIntegrity.canPreserveExpansion(6, 4, 8, 4,
                Arrays.asList(new GridIntegrity.Placement(0, 1, 1, 2, 2, 0, 3),
                        new GridIntegrity.Placement(0, 7, 0, 1, 1, 3, 0))));
    }
}
