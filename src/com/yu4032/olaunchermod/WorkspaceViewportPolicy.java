package com.yu4032.olaunchermod;

/**
 * ColorOS 17.5.5 PagedView.onMeasure() subtracts mInsets.top/bottom
 * but NOT its own top/bottom View padding from the height sent to pages.
 * In portrait that over-allocates eight CellLayout rows by the padding.
 *
 * All values are pixels, taken from the actual measured child spec and the
 * Workspace instance. Never use the full DisplayMetrics screen height.
 */
final class WorkspaceViewportPolicy {
    static final class Fit {
        final int childHeight;
        final int maxCellHeight;
        final boolean reduced;
        Fit(int childHeight, int maxCellHeight, boolean reduced) {
            this.childHeight = childHeight;
            this.maxCellHeight = maxCellHeight;
            this.reduced = reduced;
        }
    }

    static Fit portraitFit(int measuredChildHeight, int topPadding,
            int bottomPadding, int rows, int borderY, boolean portrait) {
        if (!portrait || rows < 6 || measuredChildHeight <= 0) {
            return new Fit(measuredChildHeight, Integer.MAX_VALUE, false);
        }
        int top = Math.max(0, topPadding);
        int bottom = Math.max(0, bottomPadding);
        // Avoid integer overflow or an invalid zero-sized LayoutParams.
        long available = (long) measuredChildHeight - top - bottom;
        if (available < rows) {
            // The screen has insufficient space even for 1 pixel per row.
            // Preserve native measurement rather than corrupting layout state.
            return new Fit(measuredChildHeight, Integer.MAX_VALUE, false);
        }
        int fit = (int) available;
        int gaps = Math.max(0, borderY);
        long inner = available - (long) (rows - 1) * gaps;
        if (inner < rows) {
            // Native border gaps are too large to fit this row count.
            gaps = (int) Math.max(0, (available - rows) / Math.max(1, rows - 1));
            inner = available - (long) (rows - 1) * gaps;
        }
        int maxCell = (int) Math.max(1L, inner / rows);
        return new Fit(fit, maxCell, fit < measuredChildHeight);
    }

    static boolean fixedCellOverflows(int fixedCellHeight, int fittedCellHeight) {
        return fixedCellHeight > 0 && fixedCellHeight > fittedCellHeight;
    }

    private WorkspaceViewportPolicy() {}
}
