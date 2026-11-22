package com.yu4032.olaunchermod;

import java.util.List;

/**
 * Canonical workspace occupation snapshot used by drag/reorder. Coordinates
 * come from the currently bound views, NOT database rows or stale mOccupied.
 *
 * The result only updates occupancy bookkeeping; it never moves or deletes an
 * icon. Intentional folders retain the native folder/drop handling.
 */
final class WorkspaceOccupancy {
    static final class Region {
        final int x, y, width, height;
        Region(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    static final class Snapshot {
        final boolean[][] cells; // [column][row], matching GridOccupancy
        final int itemCount;
        final int overlapCount;

        Snapshot(boolean[][] cells, int itemCount, int overlapCount) {
            this.cells = cells;
            this.itemCount = itemCount;
            this.overlapCount = overlapCount;
        }
    }

    static Snapshot from(int columns, int rows, List<Region> regions) {
        if (columns <= 0 || rows <= 0 || columns > 32 || rows > 32
                || regions == null) {
            throw new IllegalArgumentException("invalid workspace dimensions");
        }
        boolean[][] occupied = new boolean[columns][rows];
        int overlaps = 0;
        for (Region r : regions) {
            if (r == null || r.width <= 0 || r.height <= 0 || r.x < 0
                    || r.y < 0 || r.width > columns || r.height > rows
                    || r.x > columns - r.width || r.y > rows - r.height) {
                throw new IllegalArgumentException("invalid child cell region");
            }
            for (int x = r.x; x < r.x + r.width; x++) {
                for (int y = r.y; y < r.y + r.height; y++) {
                    if (occupied[x][y]) overlaps++;
                    occupied[x][y] = true;
                }
            }
        }
        return new Snapshot(occupied, regions.size(), overlaps);
    }

    static boolean matches(boolean[][] actual, boolean[][] expected) {
        if (!sameShape(actual, expected)) return false;
        for (int x = 0; x < expected.length; x++) {
            for (int y = 0; y < expected[x].length; y++) {
                if (actual[x][y] != expected[x][y]) return false;
            }
        }
        return true;
    }

    static void restore(boolean[][] occupied, boolean[][] temporary,
            Snapshot snapshot) {
        if (snapshot == null || !sameShape(occupied, snapshot.cells)
                || !sameShape(temporary, snapshot.cells)) {
            throw new IllegalArgumentException("occupancy grid size mismatch");
        }
        // Validate BOTH matrices before copying either. Never alter the
        // coordinates of a bound view during a grid/occupancy repair.
        for (int x = 0; x < snapshot.cells.length; x++) {
            System.arraycopy(snapshot.cells[x], 0, occupied[x], 0,
                    snapshot.cells[x].length);
            System.arraycopy(snapshot.cells[x], 0, temporary[x], 0,
                    snapshot.cells[x].length);
        }
    }

    private static boolean sameShape(boolean[][] actual, boolean[][] expected) {
        if (actual == null || expected == null || actual.length != expected.length) {
            return false;
        }
        for (int x = 0; x < expected.length; x++) {
            if (actual[x] == null || expected[x] == null
                    || actual[x].length != expected[x].length) return false;
        }
        return true;
    }

    private WorkspaceOccupancy() {}
}
