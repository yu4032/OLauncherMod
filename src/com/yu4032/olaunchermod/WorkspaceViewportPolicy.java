package com.yu4032.olaunchermod;

/** Vertical bounds in DragLayer coordinates, after the Launcher lays out its siblings. */
final class WorkspaceViewportPolicy {
    static final class Fit {
        final int gridTop;
        final int gridBottom;
        final int indicatorTop;

        Fit(int gridTop, int gridBottom, int indicatorTop) {
            this.gridTop = gridTop;
            this.gridBottom = gridBottom;
            this.indicatorTop = indicatorTop;
        }
    }

    static Fit fit(int pageTop, int pageBottom, int statusBarBottom, int dockTop,
            int indicatorTop, int indicatorHeight, int indicatorGap) {
        int top = Math.max(pageTop, statusBarBottom);
        int bottom = Math.min(pageBottom, dockTop);
        // Keep native indicator spacing when it fits. When it extends below
        // the Dock boundary, its entire view (including the tap target) moves.
        int fittedIndicatorTop = indicatorHeight > 0
                ? Math.min(indicatorTop, dockTop - indicatorHeight - indicatorGap) : dockTop;
        return new Fit(top, Math.min(bottom, fittedIndicatorTop), fittedIndicatorTop);
    }

    static int maxCellHeight(int pageHeight, int cellPadding, int rows, int rowGap) {
        return (pageHeight - cellPadding - (rows - 1) * Math.max(0, rowGap)) / rows;
    }

    static int folderTitleOffset(int previewBottom, int textTop, int textBottom,
            int folderBottom, int gap) {
        return Math.max(0, Math.min(previewBottom + gap - textTop, folderBottom - textBottom));
    }

    private WorkspaceViewportPolicy() {}
}
