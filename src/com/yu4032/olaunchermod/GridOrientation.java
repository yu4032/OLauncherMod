package com.yu4032.olaunchermod;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

/** Read the actual DEFAULT display orientation, not ColorOS's feature flags. */
final class GridOrientation {
    static int[] current(Context context, int landscapeColumns, int landscapeRows,
            int nativeColumns, int nativeRows) {
        int width = 0;
        int height = 0;
        int orientation = Configuration.ORIENTATION_UNDEFINED;
        if (context != null) {
            try {
                DisplayManager manager = (DisplayManager)
                        context.getSystemService(Context.DISPLAY_SERVICE);
                Display display = manager != null
                        ? manager.getDisplay(Display.DEFAULT_DISPLAY) : null;
                if (display != null) {
                    Point realSize = new Point();
                    display.getRealSize(realSize);
                    width = realSize.x;
                    height = realSize.y;
                }
            } catch (Throwable ignored) {
                // The configuration is the fallback on unconventional displays.
            }
            try {
                orientation = context.getResources().getConfiguration().orientation;
            } catch (Throwable ignored) { }
        }
        return GridOrientationPolicy.resolve(landscapeColumns, landscapeRows,
                width, height, orientation, nativeColumns, nativeRows);
    }

    private GridOrientation() {}
}
