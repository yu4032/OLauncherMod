package com.yu4032.olaunchermod;

/** Pure grid-axis policy: configured x/y are LANDSCAPE, never current-orientation coordinates. */
final class GridOrientationPolicy {
    static final int PORTRAIT = 1;
    static final int LANDSCAPE = 2;

    static int[] resolve(int landscapeColumns, int landscapeRows,
            int displayWidth, int displayHeight, int configOrientation,
            int nativeColumns, int nativeRows) {
        if (landscapeColumns <= 0 || landscapeRows <= 0) {
            return new int[]{nativeColumns, nativeRows};
        }
        boolean portrait;
        if (displayWidth > 0 && displayHeight > 0 && displayWidth != displayHeight) {
            portrait = displayHeight > displayWidth;
        } else if (configOrientation == PORTRAIT) {
            portrait = true;
        } else if (configOrientation == LANDSCAPE) {
            portrait = false;
        } else {
            // A startup fallback only. Never guess that the static uh9.c()
            // feature predicate represents the device's physical rotation.
            portrait = nativeColumns > 0 && nativeRows > 0
                    && nativeColumns < nativeRows;
        }
        return portrait
                ? new int[]{landscapeRows, landscapeColumns}
                : new int[]{landscapeColumns, landscapeRows};
    }

    private GridOrientationPolicy() {}
}
