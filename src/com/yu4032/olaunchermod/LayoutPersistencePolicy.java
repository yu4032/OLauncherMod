package com.yu4032.olaunchermod;

/**
 * Policy for non-destructive grid changes.
 *
 * Preserving a layout database during expansion is distinct from validating
 * whether all of its coordinates can already be displayed. A stale portrait
 * coordinate or unsupported widget span must not trigger a migration which
 * might delete that widget.
 */
final class LayoutPersistencePolicy {
    static boolean preserveOnExpansion(int oldCols, int oldRows,
            int newCols, int newRows,
            boolean sameDatabase, boolean sameHotseat, boolean sameDeviceType) {
        return sameDatabase && sameHotseat && sameDeviceType
                && oldCols > 0 && oldRows > 0 && newCols > 0 && newRows > 0
                && newCols >= oldCols && newRows >= oldRows
                && (newCols > oldCols || newRows > oldRows);
    }

    static boolean forceFree(int columns, int rows, boolean keepEmptySlots) {
        return columns > 0 && rows > 0 && keepEmptySlots;
    }

    private LayoutPersistencePolicy() {}
}
