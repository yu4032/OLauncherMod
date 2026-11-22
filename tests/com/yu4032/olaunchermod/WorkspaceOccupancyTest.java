package com.yu4032.olaunchermod;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class WorkspaceOccupancyTest {
    private static WorkspaceOccupancy.Region r(int x, int y, int w, int h) {
        return new WorkspaceOccupancy.Region(x, y, w, h);
    }

    @Test public void restoresBoundIconsInColumnsSevenAndEight() {
        WorkspaceOccupancy.Snapshot snapshot = WorkspaceOccupancy.from(8, 4,
                Arrays.asList(r(0, 0, 1, 1), r(6, 1, 1, 1), r(7, 3, 1, 1)));
        boolean[][] actual = new boolean[8][4];
        boolean[][] temporary = new boolean[8][4];
        assertFalse(WorkspaceOccupancy.matches(actual, snapshot.cells));
        WorkspaceOccupancy.restore(actual, temporary, snapshot);
        assertTrue(actual[6][1]);
        assertTrue(actual[7][3]);
        assertTrue(WorkspaceOccupancy.matches(actual, snapshot.cells));
        assertTrue(WorkspaceOccupancy.matches(temporary, snapshot.cells));
        assertFalse(actual[7][2]);
    }

    @Test public void filledCellsAreNotAvailableForNewDrop() {
        WorkspaceOccupancy.Snapshot snapshot = WorkspaceOccupancy.from(8, 4,
                Arrays.asList(r(2, 2, 1, 1), r(7, 0, 1, 1)));
        assertTrue(snapshot.cells[2][2]);
        assertTrue(snapshot.cells[7][0]);
        assertFalse(snapshot.cells[6][0]);
    }

    @Test public void widgetSpanReservesEveryGridCell() {
        WorkspaceOccupancy.Snapshot snapshot = WorkspaceOccupancy.from(8, 4,
                Collections.singletonList(r(5, 1, 3, 2)));
        for (int x = 5; x <= 7; x++)
            for (int y = 1; y <= 2; y++) assertTrue(snapshot.cells[x][y]);
        assertFalse(snapshot.cells[4][1]);
    }

    @Test public void detectsPreexistingOverlapsWithoutMovingIcons() {
        WorkspaceOccupancy.Snapshot snapshot = WorkspaceOccupancy.from(8, 4,
                Arrays.asList(r(6, 0, 2, 1), r(7, 0, 1, 1)));
        assertEquals(1, snapshot.overlapCount);
        assertEquals(2, snapshot.itemCount);
        assertTrue(snapshot.cells[7][0]);
    }

    @Test public void excludesNonGridDecorationsAtCollectionLayer() {
        WorkspaceOccupancy.Snapshot snapshot = WorkspaceOccupancy.from(8, 4,
                Collections.singletonList(r(2, 2, 1, 1)));
        assertEquals(1, snapshot.itemCount);
        assertFalse(snapshot.cells[0][0]);
    }

    @Test public void rejectsOutOfBoundsIcons() {
        try {
            WorkspaceOccupancy.from(8, 4,
                    Collections.singletonList(r(8, 1, 1, 1)));
            fail("must reject icon beyond last column");
        } catch (IllegalArgumentException expected) {}
    }

    @Test public void rejectsOutOfBoundsWidgets() {
        try {
            WorkspaceOccupancy.from(8, 4,
                    Collections.singletonList(r(7, 1, 2, 2)));
            fail("must reject widget spanning outside desktop");
        } catch (IllegalArgumentException expected) {}
    }

    @Test public void refusesPartialRestoreWhenOneMatrixIsWrongSize() {
        WorkspaceOccupancy.Snapshot snapshot = WorkspaceOccupancy.from(8, 4,
                Collections.singletonList(r(7, 2, 1, 1)));
        boolean[][] normal = new boolean[8][4];
        boolean[][] wrong = new boolean[6][4];
        try {
            WorkspaceOccupancy.restore(normal, wrong, snapshot);
            fail("must validate both before mutation");
        } catch (IllegalArgumentException expected) {}
        assertFalse(normal[7][2]);
    }

    @Test public void resizingFromSixToEightRestoresPreviouslyBoundIcons() {
        WorkspaceOccupancy.Snapshot snapshot = WorkspaceOccupancy.from(8, 4,
                Arrays.asList(r(5, 0, 1, 1), r(7, 0, 1, 1)));
        boolean[][] old = new boolean[6][4];
        boolean[][] fresh = new boolean[8][4];
        assertFalse(WorkspaceOccupancy.matches(old, snapshot.cells));
        WorkspaceOccupancy.restore(fresh, new boolean[8][4], snapshot);
        assertTrue(fresh[5][0]);
        assertTrue(fresh[7][0]);
    }

    @Test public void emptyPageRemainsEmpty() {
        WorkspaceOccupancy.Snapshot snapshot = WorkspaceOccupancy.from(8, 4,
                new ArrayList<>());
        assertTrue(WorkspaceOccupancy.matches(new boolean[8][4], snapshot.cells));
    }
}
