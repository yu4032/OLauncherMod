package com.yu4032.olaunchermod;

import org.junit.Test;
import static org.junit.Assert.*;

public class LayoutPersistencePolicyTest {
    @Test public void expansionDoesNotRequirePerfectPortraitCoordinates() {
        // Existing 6x4 layout with widgets should remain intact on 8x4,
        // even if one widget's portrait placement needs later repair.
        assertTrue(LayoutPersistencePolicy.preserveOnExpansion(
                6, 4, 8, 4, true, true, true));
    }

    @Test public void rejectsShrinkAndUnchangedGrid() {
        assertFalse(LayoutPersistencePolicy.preserveOnExpansion(
                8, 4, 6, 4, true, true, true));
        assertFalse(LayoutPersistencePolicy.preserveOnExpansion(
                8, 4, 8, 4, true, true, true));
    }

    @Test public void rejectsCrossDatabaseOrDeviceMigration() {
        assertFalse(LayoutPersistencePolicy.preserveOnExpansion(
                6, 4, 8, 4, false, true, true));
        assertFalse(LayoutPersistencePolicy.preserveOnExpansion(
                6, 4, 8, 4, true, false, true));
        assertFalse(LayoutPersistencePolicy.preserveOnExpansion(
                6, 4, 8, 4, true, true, false));
    }

    @Test public void rejectsOrientationSwapAsExpansion() {
        assertFalse(LayoutPersistencePolicy.preserveOnExpansion(
                8, 4, 4, 8, true, true, true));
    }

    @Test public void freeModeRequiresExplicitOption() {
        assertTrue(LayoutPersistencePolicy.forceFree(8, 4, true));
        assertFalse(LayoutPersistencePolicy.forceFree(8, 4, false));
        assertFalse(LayoutPersistencePolicy.forceFree(0, 0, true));
    }
}
