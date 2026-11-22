package com.yu4032.olaunchermod;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, side-effect-free checks for preserving an existing desktop layout
 * while enlarging the grid. No coordinates are modified by this class.
 */
final class GridIntegrity {
    static final class Placement {
        final int screen;
        final int x;
        final int y;
        final int spanX;
        final int spanY;
        final int portraitX;
        final int portraitY;

        Placement(int screen, int x, int y, int spanX, int spanY,
                int portraitX, int portraitY) {
            this.screen = screen;
            this.x = x;
            this.y = y;
            this.spanX = spanX;
            this.spanY = spanY;
            this.portraitX = portraitX;
            this.portraitY = portraitY;
        }
    }

    private static boolean inside(int x, int y, int width, int height,
            int cols, int rows) {
        return x >= 0 && y >= 0 && width > 0 && height > 0
                && width <= cols && height <= rows
                && x <= cols - width && y <= rows - height;
    }

    private static boolean occupy(Set<String> cells, int screen,
            int x, int y, int w, int h) {
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                if (!cells.add(screen + ":" + (x + dx) + ":" + (y + dy))) {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean canPreserveExpansion(int oldCols, int oldRows,
            int newCols, int newRows, List<Placement> placements) {
        if (oldCols < 1 || oldRows < 1 || newCols < oldCols || newRows < oldRows
                || (oldCols == newCols && oldRows == newRows)
                || placements == null) return false;

        Set<String> horizontal = new HashSet<>();
        Set<String> vertical = new HashSet<>();
        for (Placement p : placements) {
            if (p.screen < 0 || !inside(p.x, p.y, p.spanX, p.spanY,
                    newCols, newRows)
                    || !occupy(horizontal, p.screen, p.x, p.y, p.spanX, p.spanY)) {
                return false;
            }

            // Portrait coordinates may be absent (-1,-1) in older databases;
            // an incomplete pair is inconsistent and must use native migration.
            if (p.portraitX == -1 && p.portraitY == -1) continue;
            if (!inside(p.portraitX, p.portraitY, p.spanX, p.spanY,
                    newRows, newCols)
                    || !occupy(vertical, p.screen, p.portraitX, p.portraitY,
                            p.spanX, p.spanY)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Narrow fallback for the loader's model/layout mismatch. This does not
     * make a rejected item visible; it prevents irreversible row deletion.
     */
    static boolean shouldRetainRejectedItem(String reason, int container,
            int x, int y, int spanX, int spanY, int cols, int rows) {
        return "Item position overlap".equals(reason)
                && container == -100
                && inside(x, y, spanX, spanY, cols, rows);
    }

    /**
     * Widget loader failures often reflect temporarily unavailable providers
     * or stale orientation/spans during grid changes. Preserve the DB row for
     * recovery instead of committing an irreversible deletion.
     *
     * This does NOT bind a widget whose provider genuinely cannot be found.
     * Other item types and unrelated deletion reasons remain native.
     */
    static boolean shouldRetainWidgetLoaderRow(int container, int itemType,
            String reason, boolean customGridEnabled) {
        if (!customGridEnabled || container != -100 || reason == null
                || (itemType != 5 && itemType != 99)) return false;
        return reason.startsWith("Item position overlap")
                || reason.startsWith("Widget has invalid size")
                || reason.startsWith("Unrestored widget removed")
                || reason.startsWith("Deleting widget that isn't installed anymore");
    }

    /**
     * Canonical grid bounds for persisted horizontal cellX/cellY coordinates.
     * Callers may offer a stale IDP grid (such as 6x4 or rotated 4x8).
     */
    static int[] canonicalLoaderBounds(int configuredColumns, int configuredRows,
            int nativeColumns, int nativeRows) {
        if (configuredColumns >= 1 && configuredRows >= 1) {
            return new int[]{configuredColumns, configuredRows};
        }
        return new int[]{nativeColumns, nativeRows};
    }

    static boolean isCanonicalMigrationTarget(int configuredColumns,
            int configuredRows, int targetColumns, int targetRows) {
        return configuredColumns > 0 && configuredRows > 0
                && configuredColumns == targetColumns
                && configuredRows == targetRows;
    }

    private GridIntegrity() {
    }
}
