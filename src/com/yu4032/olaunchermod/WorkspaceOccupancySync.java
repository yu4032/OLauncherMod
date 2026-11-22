package com.yu4032.olaunchermod;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuild the real workspace occupancy after CellLayout.setGridSize() has
 * replaced mOccupied and mTmpOccupied with new, EMPTY matrices.
 *
 * The original ColorOS drag and squeeze/reorder implementation remains
 * responsible for moving icons. Only its bookkeeping is synchronized with
 * the views that already exist. Never move views or change their DB positions.
 */
final class WorkspaceOccupancySync {
    private WorkspaceOccupancySync() {}

    static String afterGridReset(Object cell, ClassLoader loader) throws Exception {
        Class<?> base = Class.forName("com.android.launcher3.CellLayout", false, loader);

        // During an active drag, a view may be intentionally unoccupied. Do
        // not reoccupy that cell or reset temporary reorder preview state.
        Field dragging = HookSymbols.field(base, "mDragging", boolean.class,
                false, false);
        if (dragging.getBoolean(cell)) {
            return "skipped active drag";
        }
        Method dirty = HookSymbols.method(base, "isItemPlacementDirty",
                boolean.class, false, false);
        if (Boolean.TRUE.equals(dirty.invoke(cell))) {
            return "skipped active reorder preview";
        }

        int columns = ((Number) HookSymbols.method(base, "getCountX",
                int.class, false, false).invoke(cell)).intValue();
        int rows = ((Number) HookSymbols.method(base, "getCountY",
                int.class, false, false).invoke(cell)).intValue();

        ViewGroup views = (ViewGroup) HookSymbols.method(
                base, "getShortcutsAndWidgets",
                Class.forName("com.android.launcher3.ShortcutAndWidgetContainer",
                        false, loader),
                false, false).invoke(cell);

        Class<?> lpClass = Class.forName(
                "com.android.launcher3.celllayout.CellLayoutLayoutParams",
                false, loader);
        Field cellX = HookSymbols.field(lpClass, "cellX", int.class, false, false);
        Field cellY = HookSymbols.field(lpClass, "cellY", int.class, false, false);
        Field width = HookSymbols.field(lpClass, "cellHSpan", int.class, false, false);
        Field height = HookSymbols.field(lpClass, "cellVSpan", int.class, false, false);
        Field locked = HookSymbols.field(lpClass, "isLockedToGrid",
                boolean.class, false, false);

        List<WorkspaceOccupancy.Region> regions = new ArrayList<>();
        for (int i = 0; i < views.getChildCount(); i++) {
            View view = views.getChildAt(i);
            Object params = view.getLayoutParams();
            if (!lpClass.isInstance(params)) {
                throw new IllegalStateException(
                        "workspace child has unsupported layout params");
            }
            if (!locked.getBoolean(params)) continue;
            regions.add(new WorkspaceOccupancy.Region(cellX.getInt(params),
                    cellY.getInt(params), width.getInt(params),
                    height.getInt(params)));
        }
        WorkspaceOccupancy.Snapshot snapshot =
                WorkspaceOccupancy.from(columns, rows, regions);

        Object nativeOccupied = HookSymbols.field(base, "mOccupied",
                Class.forName("com.android.launcher3.util.GridOccupancy", false, loader),
                false, false).get(cell);
        Object nativeTemporary = HookSymbols.field(base, "mTmpOccupied",
                nativeOccupied.getClass(), false, false).get(cell);
        boolean[][] normal = (boolean[][]) nativeOccupied.getClass()
                .getField("cells").get(nativeOccupied);
        boolean[][] temporary = (boolean[][]) nativeTemporary.getClass()
                .getField("cells").get(nativeTemporary);

        if (WorkspaceOccupancy.matches(normal, snapshot.cells)
                && WorkspaceOccupancy.matches(temporary, snapshot.cells)) {
            return null;
        }

        WorkspaceOccupancy.restore(normal, temporary, snapshot);
        return "restored occupied + temporary " + columns + "x" + rows
                + ", bound children=" + snapshot.itemCount
                + ", existing overlaps=" + snapshot.overlapCount;
    }
}
