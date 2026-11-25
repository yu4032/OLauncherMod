package com.yu4032.olaunchermod;

/** Vertical bounds in DragLayer coordinates, after the Launcher lays out its siblings. */
final class WorkspaceViewportPolicy {
    static final class Fit {
        final int gridTop;
        final int gridBottom;
        final int indicatorTop;
        final int statusBarBottom;
        final int dockTop;

        Fit(int gridTop, int gridBottom, int indicatorTop, int statusBarBottom, int dockTop) {
            this.gridTop = gridTop;
            this.gridBottom = gridBottom;
            this.indicatorTop = indicatorTop;
            this.statusBarBottom = statusBarBottom;
            this.dockTop = dockTop;
        }

        int height() {
            return gridBottom - gridTop;
        }
    }

    static final class InitialFit {
        private Fit fit;

        Fit current() {
            return fit;
        }

        void capture(Fit candidate) {
            if (fit == null && candidate.height() > 0) fit = candidate;
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
        return new Fit(top, Math.min(bottom, fittedIndicatorTop), fittedIndicatorTop,
                statusBarBottom, dockTop);
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
