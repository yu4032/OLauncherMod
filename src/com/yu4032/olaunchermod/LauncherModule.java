package com.yu4032.olaunchermod;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Rect;
import android.graphics.Point;
import android.view.ViewGroup;
import android.util.Log;
import android.view.View;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Keeps ColorOS Launcher grid settings consistent with its persisted grid state.
 * The module does not rewrite desktop item coordinates.
 */
public final class LauncherModule extends XposedModule {
    private static final String TAG = "OLauncherMod";
    private static final String CONFIG_FILE =
            "/storage/emulated/0/Android/data/com.android.launcher/files/launcher_grid";
    private static final String OPLUS_PREFS = "com.android.launcher_unique_shared_prefs";
    private static final String MARGINS_FILE =
            "/storage/emulated/0/Android/data/com.android.launcher/files/launcher_workspace_margins";

    private volatile int requestedColumns;
    private volatile int requestedRows;
    private volatile boolean keepEmptySlots = true;
    private volatile WorkspaceMarginConfig workspaceMargins = WorkspaceMarginConfig.DEFAULT;
    private volatile String lastMarginReport = "";
    private volatile String lastViewportReport = "";
    // A layout change requires a fresh Launcher process, never a mid-drag
    // configuration swap between live views, loader and database.
    private boolean configInitialized;
    private volatile String lastRotationGridLog = "";
    private volatile String lastGridReport = "";
    private volatile String lastLoaderBoundsReport = "";
    private volatile boolean freeModeQueryObserved;
    private final ThreadLocal<Boolean> migrationResetPending = new ThreadLocal<>();

    private synchronized void refresh() {
        if (configInitialized) return;
        configInitialized = true;
        // Independent of launcher_grid: users may adjust native margins
        // without enabling a custom grid.
        try (BufferedReader margins = new BufferedReader(new FileReader(MARGINS_FILE))) {
            String line = margins.readLine();
            if (line != null) workspaceMargins = WorkspaceMarginConfig.parse(line);
        } catch (java.io.FileNotFoundException ignored) {
            workspaceMargins = WorkspaceMarginConfig.DEFAULT;
        } catch (Exception error) {
            log(Log.ERROR, TAG, "invalid workspace margins, retaining native geometry: "
                    + error);
            workspaceMargins = WorkspaceMarginConfig.DEFAULT;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(CONFIG_FILE))) {
            String line = reader.readLine();
            if (line == null) return;
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2 || parts.length > 3) return;
            int columns = Integer.parseInt(parts[0]);
            int rows = Integer.parseInt(parts[1]);
            // This Launcher build parses DeviceGridState axes as single digits.
            if (columns >= 4 && columns <= 8 && rows >= 3 && rows <= 8) {
                requestedColumns = columns;
                requestedRows = rows;
                keepEmptySlots = parts.length < 3 || !"0".equals(parts[2]);
            }
        } catch (Exception e) {
            // Missing configuration keeps the Launcher on its stock grid.
            if (requestedColumns != 0) {
                log(Log.WARN, TAG, "configuration read failed: " + e);
            }
        }
    }

    /**
     * OplusInvariantDeviceProfile.injectPreInitGridExt reads the persistent
     * layout_cellcount_x/y values. Set them before it initializes the IDP.
     */
    private void synchronizeOplusGrid(Context context, ClassLoader loader) throws Exception {
        if (requestedColumns == 0 || requestedRows == 0) return;

        SharedPreferences prefs = context.getSharedPreferences(OPLUS_PREFS, Context.MODE_PRIVATE);
        if (prefs.getInt("layout_cellcount_x", -1) == requestedColumns
                && prefs.getInt("layout_cellcount_y", -1) == requestedRows) return;

        // 17.5.5 R8 moved LayoutSettingsHelper.saveLayoutInfo to zv5.o.
        // Both symbol pairs were verified from the respective APK dex tables.
        Class<?> helper = HookSymbols.classByAnyName(loader,
                "zv5", "com.android.launcher.togglebar.LayoutSettingsHelper");
        String saveName = "zv5".equals(helper.getName())
                ? "o" : "saveLayoutInfo";
        Method save = HookSymbols.method(helper, saveName,
                void.class, true, false, Context.class, int.class, int.class);
        save.invoke(null, context, requestedColumns, requestedRows);
        log(Log.INFO, TAG, "persistent grid synchronized to "
                + requestedColumns + "x" + requestedRows);
    }

    private static int integer(Object object, String method) throws Exception {
        return ((Number) object.getClass().getMethod(method).invoke(object)).intValue();
    }

    private static String string(Object object, String method) throws Exception {
        return (String) object.getClass().getMethod(method).invoke(object);
    }

    /**
     * On a grid expansion, old coordinates can be kept when every item fits,
     * database identity, hotseat capacity and device type stay unchanged.
     * All other migrations remain ColorOS Launcher's responsibility.
     */
    private boolean preserveExpansion(Context context, ClassLoader loader) throws Exception {
        if (requestedColumns == 0 || requestedRows == 0) return false;
        Class<?> app = Class.forName("com.android.launcher3.LauncherAppState", false, loader);
        Object idp = app.getMethod("getIDP", Context.class).invoke(null, context);
        if (idp == null) return false;

        Class<?> idpClass = Class.forName(
                "com.android.launcher3.InvariantDeviceProfile", false, loader);
        Class<?> stateClass = Class.forName(
                "com.android.launcher3.model.DeviceGridState", false, loader);
        Constructor<?> sourceCtor = stateClass.getConstructor(Context.class);
        Constructor<?> targetCtor = stateClass.getConstructor(idpClass);
        Object source = sourceCtor.newInstance(context);
        Object target = targetCtor.newInstance(idp);

        String sourceDb = string(source, "getDbFile");
        String targetDb = string(target, "getDbFile");
        boolean sameDatabase = sourceDb != null && !sourceDb.isEmpty()
                && Objects.equals(sourceDb, targetDb);
        boolean sameHotseat = integer(source, "getNumHotseat")
                == integer(target, "getNumHotseat");
        boolean sameDevice = integer(source, "getDeviceType")
                == integer(target, "getDeviceType");
        int oldColumns = integer(source, "getColumns");
        int oldRows = integer(source, "getRows");
        int newColumns = integer(target, "getColumns");
        int newRows = integer(target, "getRows");
        if (!LayoutPersistencePolicy.preserveOnExpansion(oldColumns, oldRows,
                newColumns, newRows, sameDatabase, sameHotseat, sameDevice)) {
            return false;
        }

        File file = context.getDatabasePath(targetDb);
        if (!file.isFile()) return false;
        // Record data anomalies, but never respond to a widget's old portrait
        // coordinates by invoking the native destructive/repacking migration.
        // All existing coordinates remain unchanged during pure expansion.
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            java.util.List<GridIntegrity.Placement> placements = new java.util.ArrayList<>();
            try (Cursor cursor = db.rawQuery(
                    "SELECT screen,cellX,cellY,spanX,spanY,vCellX,vCellY "
                            + "FROM singledesktopitems WHERE container=-100", null)) {
                while (cursor.moveToNext()) {
                    if (cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2)
                            || cursor.isNull(3) || cursor.isNull(4)) continue;
                    placements.add(new GridIntegrity.Placement(
                            cursor.getInt(0), cursor.getInt(1), cursor.getInt(2),
                            cursor.getInt(3), cursor.getInt(4),
                            cursor.isNull(5) ? -1 : cursor.getInt(5),
                            cursor.isNull(6) ? -1 : cursor.getInt(6)));
                }
            }
            if (!GridIntegrity.canPreserveExpansion(
                    oldColumns, oldRows, newColumns, newRows, placements)) {
                log(Log.WARN, TAG, "preserving existing layout despite incompatible "
                        + "portrait coordinates/overlaps; never migrate widgets on "
                        + "pure expansion, records=" + placements.size());
            }
        } catch (Throwable error) {
            // A missing optional diagnostic column must not enable a
            // destructive migration on an otherwise same-DB grid expansion.
            log(Log.WARN, TAG, "expansion diagnostics unavailable: " + error);
        }

        try {
            stateClass.getMethod("writeToPrefs", Context.class).invoke(target, context);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "migration metadata update failed; retaining "
                    + "original DB records: " + error);
        }
        log(Log.INFO, TAG, "preserved workspace DB without reflow on expansion "
                + oldColumns + "x" + oldRows + " -> " + newColumns + "x" + newRows);
        return true;
    }


    // LauncherApplication.q is set in attachBaseContext on 17.5.5,
    // before the IDP and model loader start.
    private Context launcherContext(ClassLoader loader) {
        try {
            Class<?> app = Class.forName(
                    "com.android.common.LauncherApplication", false, loader);
            Object value = HookSymbols.field(app, "q", Context.class,
                    true, false).get(null);
            return value instanceof Context ? (Context) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Stored 8x4 is a LANDSCAPE profile. The current orientation must come
     * from the real display/configuration, NOT uh9.c(): that method is a
     * tablet-layout feature predicate and may remain false in portrait.
     */
    private int[] effectiveWorkspaceGrid(ClassLoader loader) {
        return effectiveWorkspaceGrid(launcherContext(loader), 0, 0);
    }

    private int[] effectiveWorkspaceGrid(Context context,
            int nativeColumns, int nativeRows) {
        return GridOrientation.current(context,
                requestedColumns, requestedRows, nativeColumns, nativeRows);
    }

    /**
     * The 17.5.5 IDP's setColumnsAndRows can swap only if uh9.c() allows it.
     * Make the resulting fields agree with the physical display independently
     * of that feature flag. No database positions or widget spans are written.
     */
    private void synchronizeIdpAxes(Object profile, ClassLoader loader) {
        if (requestedColumns <= 0 || requestedRows <= 0 || profile == null) return;
        try {
            int[] expected = effectiveWorkspaceGrid(loader);
            int actualX = integer(profile, "getNumColumns");
            int actualY = integer(profile, "getNumRows");
            if (actualX == expected[0] && actualY == expected[1]) return;
            Class<?> idp = Class.forName(
                    "com.android.launcher3.InvariantDeviceProfile", false, loader);
            HookSymbols.field(idp, "mNumColumns", int.class, false, false)
                    .setInt(profile, expected[0]);
            HookSymbols.field(idp, "mNumRows", int.class, false, false)
                    .setInt(profile, expected[1]);
            log(Log.INFO, TAG, "corrected IDP orientation "
                    + actualX + "x" + actualY + " -> "
                    + expected[0] + "x" + expected[1]);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "IDP orientation correction failed: " + error);
        }
    }

    private boolean isWorkspaceCell(Object instance) {
        // Exact class deliberately excludes Hotseat and Folder CellLayouts.
        if (instance == null || !instance.getClass().getName().equals(
                "com.android.launcher3.OplusCellLayout")) return false;
        try {
            return Boolean.TRUE.equals(instance.getClass().getMethod(
                    "isBelongToContainerType", int.class).invoke(instance, 0));
        } catch (Throwable error) {
            log(Log.WARN, TAG, "workspace type check failed: " + error);
            return false;
        }
    }

    private int[] actualGrid(Object cell) throws Exception {
        return new int[]{
                integer(cell, "getCountX"),
                integer(cell, "getCountY")
        };
    }

    /**
     * This is read by the settings Activity using its existing root reader.
     * It records the real CellLayout grid, not merely the configured dimensions.
     */
    private synchronized void recordGridState(Context context, String stage,
            ClassLoader loader, int actualX, int actualY) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            int[] effective = effectiveWorkspaceGrid(context, 0, 0);
            String value = "stage=" + stage + "\n"
                    + "configured_landscape=" + requestedColumns + "x" + requestedRows + "\n"
                    + "expected_workspace=" + effective[0] + "x" + effective[1] + "\n"
                    + "actual_workspace=" + actualX + "x" + actualY + "\n"
                    + "match=" + (actualX == effective[0] && actualY == effective[1]) + "\n";
            String identity = effective[0] + "x" + effective[1]
                    + "/" + actualX + "x" + actualY;
            if (identity.equals(lastGridReport)) return;
            lastGridReport = identity;
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                    new File(dir, "launcher_grid_status"))) {
                output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            log(Log.INFO, TAG, "grid state: " + value.replace('\n', ' '));
        } catch (Throwable error) {
            log(Log.WARN, TAG, "unable to record actual grid: " + error);
        }
    }

    /**
     * Record the actual boundary given to the ColorOS model loader. Unlike
     * CellLayout.getCountX(), this determines whether a saved row will survive
     * OplusLoaderCursor.checkItemPlacement() and the FindSpaceHelper passes.
     */
    private synchronized void recordLoaderBounds(ClassLoader loader,
            Object nativeBounds, int finalColumns, int finalRows) {
        String value = "native_loader_bounds=" + nativeBounds + "\n"
                + "effective_loader_bounds=" + finalColumns + "x" + finalRows + "\n"
                + "configured_landscape=" + requestedColumns + "x" + requestedRows + "\n";
        if (value.equals(lastLoaderBoundsReport)) return;
        lastLoaderBoundsReport = value;
        try {
            Class<?> app = Class.forName(
                    "com.android.common.LauncherApplication", false, loader);
            Context context;
            try {
                // 17.5.5 stores its application context in public static q.
                context = (Context) HookSymbols.field(app, "q",
                        Context.class, true, false).get(null);
            } catch (ReflectiveOperationException notVersion1755) {
                context = (Context) app.getMethod("getAppContext").invoke(null);
            }
            if (context != null && context.getExternalFilesDir(null) != null) {
                File path = new File(context.getExternalFilesDir(null),
                        "launcher_loader_status");
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(path)) {
                    output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "loader status persistence unavailable: " + error);
        }
        log(Log.INFO, TAG, "loader grid bounds: " + value.replace('\n', ' '));
    }

    private synchronized void recordViewportState(Context context,
            int incomingHeight, int availableHeight, int rows,
            int topPadding, int bottomPadding, int actualCellHeight) {
        String value = "orientation=portrait\n"
                + "paged_child_height_px=" + incomingHeight + "\n"
                + "workspace_padding_top_px=" + topPadding + "\n"
                + "workspace_padding_bottom_px=" + bottomPadding + "\n"
                + "remaining_grid_height_px=" + availableHeight + "\n"
                + "rows=" + rows + "\n"
                + "actual_cell_height_px=" + actualCellHeight + "\n";
        if (value.equals(lastViewportReport)) return;
        lastViewportReport = value;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(
                        new File(dir, "launcher_viewport_status"))) {
                    stream.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "workspace viewport report failed: " + error);
        }
        log(Log.INFO, TAG, "workspace viewport: " + value.replace('\n', ' '));
    }

    private synchronized void recordMarginState(Context context,
            WorkspaceMarginConfig margins, int[] original, int[] actual,
            boolean isPortrait) {
        String value = "orientation=" + (isPortrait ? "portrait" : "landscape") + "\n"
                + "configured_dp=" + margins.serialize() + "\n"
                + "native_padding_px=" + java.util.Arrays.toString(original) + "\n"
                + "actual_padding_px=" + java.util.Arrays.toString(actual) + "\n";
        if (value.equals(lastMarginReport)) return;
        lastMarginReport = value;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                        new File(dir, "launcher_margin_status"))) {
                    output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "workspace margin status unavailable: " + error);
        }
        log(Log.INFO, TAG, "workspace padding " + value.replace('\n', ' '));
    }

    private void syncWorkspaceOccupancy(Object cell, ClassLoader loader,
            String stage) {
        if (requestedColumns <= 0 || requestedRows <= 0
                || !isWorkspaceCell(cell)) return;
        try {
            String result = WorkspaceOccupancySync.afterGridReset(cell, loader);
            if (result != null) {
                if (result.contains("existing overlaps=")
                        && !result.endsWith("existing overlaps=0")) {
                    log(Log.ERROR, TAG, "workspace collision detected at "
                            + stage + ": " + result);
                } else if (!result.startsWith("skipped")) {
                    log(Log.INFO, TAG, "workspace occupancy repaired at "
                            + stage + ": " + result);
                }
            }
        } catch (Throwable error) {
            // Failed validation must not change view coordinates or the DB.
            log(Log.ERROR, TAG, "workspace occupancy synchronization failed at "
                    + stage + ": " + error);
        }
    }

    private void ensureWorkspaceGrid(Object cell, Context context,
            ClassLoader loader, String stage) {
        if (!isWorkspaceCell(cell) || requestedColumns <= 0 || requestedRows <= 0) return;
        try {
            int[] expected = effectiveWorkspaceGrid(context, 0, 0);
            int[] before = actualGrid(cell);
            if (before[0] != expected[0] || before[1] != expected[1]) {
                Method set = HookSymbols.method(cell.getClass(), "setGridSize",
                        void.class, false, false, int.class, int.class);
                set.invoke(cell, expected[0], expected[1]);
            }
            syncWorkspaceOccupancy(cell, loader, stage);
            int[] after = actualGrid(cell);
            recordGridState(context, stage, loader, after[0], after[1]);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace grid validation failed: " + error);
        }
    }

    /**
     * Diagnostics only. Core workspace methods are checked as a group for
     * visibility, but installation happens independently below. A missing
     * entry cannot suppress other valid bindings: that global early-return
     * is what regressed debug-6 from a working 8x4 workspace to stock 6x4.
     */
    private boolean preflightVisualGridSymbols(ClassLoader loader) {
        try {
            Class<?> idp = Class.forName(
                    "com.android.launcher3.InvariantDeviceProfile", false, loader);
            Class<?> cell = Class.forName(
                    "com.android.launcher3.OplusCellLayout", false, loader);
            HookSymbols.requireVisualGridMethods(idp, cell);
            log(Log.INFO, TAG, "visual grid symbols verified; "
                    + "installing workspace hooks independently of optional symbols");
            return true;
        } catch (Throwable error) {
            log(Log.WARN, TAG, "visual grid symbol probe incomplete; "
                    + "attempting individually resolved hooks: " + error);
            return false;
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!"com.android.launcher".equals(param.getPackageName())) return;
        ClassLoader loader = param.getDefaultClassLoader();
        refresh();
        // Diagnostics only: each hook below resolves and fails independently.
        // The previous global early-return disabled a working workspace grid.
        if (requestedColumns > 0) preflightVisualGridSymbols(loader);

        try {
            Class<?> idp = Class.forName(
                    "com.android.launcher3.InvariantDeviceProfile", false, loader);
            Method method = HookSymbols.method(idp, "setColumnsAndRows",
                    void.class, false, false, int.class, int.class);
            hook(method).intercept(chain -> {
                refresh();
                if (requestedColumns > 0 && requestedRows > 0) {
                    Object result = chain.proceed(
                            new Object[]{requestedColumns, requestedRows});
                    synchronizeIdpAxes(chain.getThisObject(), loader);
                    return result;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "IDP grid hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "IDP grid hook failed: " + error);
        }

        try {
            Class<?> ext = Class.forName(
                    "com.android.launcher3.OplusInvariantDeviceProfile", false, loader);
            Class<?> option = Class.forName(
                    "com.android.launcher3.InvariantDeviceProfile$GridOption", false, loader);
            Method init = HookSymbols.method(ext, "injectPreInitGridExt",
                    void.class, false, false, Context.class, option);
            hook(init).intercept(chain -> {
                refresh();
                Context context = (Context) chain.getArgs().get(0);
                if (requestedColumns > 0 && requestedRows > 0) {
                    try {
                        synchronizeOplusGrid(context, loader);
                    } catch (Throwable error) {
                        log(Log.ERROR, TAG, "persistent grid sync failed: " + error);
                    }
                }
                Object result = chain.proceed();
                // The original Oplus override may re-read native mode settings.
                // Validate the final IDP instead of assuming setColumnsAndRows won.
                if (requestedColumns > 0 && requestedRows > 0) {
                    try {
                        Object profile = chain.getThisObject();
                        int[] target = effectiveWorkspaceGrid(loader);
                        int x = integer(profile, "getNumColumns");
                        int y = integer(profile, "getNumRows");
                        if (x != target[0] || y != target[1]) {
                            Method set = HookSymbols.method(profile.getClass(),
                                    "setColumnsAndRows", void.class, false, false,
                                    int.class, int.class);
                            set.invoke(profile, requestedColumns, requestedRows);
                            synchronizeIdpAxes(profile, loader);
                            x = integer(profile, "getNumColumns");
                            y = integer(profile, "getNumRows");
                        }
                        if (x != target[0] || y != target[1]) {
                            log(Log.ERROR, TAG, "IDP axes mismatch after init: "
                                    + x + "x" + y + " expected " + target[0] + "x" + target[1]);
                        }
                        Class<?> config = HookSymbols.classByAnyName(loader,
                                "wjb", "com.android.launcher.c6");
                        boolean newConfig = "wjb".equals(config.getName());
                        java.lang.reflect.Field xField = HookSymbols.field(
                                config, newConfig ? "h" : "f1273g",
                                int.class, true, false);
                        java.lang.reflect.Field yField = HookSymbols.field(
                                config, newConfig ? "i" : "h",
                                int.class, true, false);
                        xField.setInt(null, x);
                        yField.setInt(null, y);
                        log(Log.INFO, TAG, "IDP final grid " + x + "x" + y
                                + ", configured " + requestedColumns + "x" + requestedRows);
                    } catch (Throwable error) {
                        log(Log.ERROR, TAG, "IDP final grid check failed: " + error);
                    }
                }
                return result;
            });
            log(Log.INFO, TAG, "persistent grid hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "persistent grid hook failed: " + error);
        }


        // RotationLayoutUtils caches the landscape grid independently of IDP.
        // During startup OplusLoaderCursor.checkItemPlacement calls
        // ScreenRotationHelper.I(...), which may return p5.c.a(0) rather than
        // the incoming IDP dimensions. If that cache is still 6x4, cells in
        // columns 7/8 fail placement validation and LoaderCursor.commitDeleted
        // PERMANENTLY deletes their database records. Keep the cache and both
        // orientation-specific accessors on the same configured dimensions.
        try {
            Class<?> rotation = HookSymbols.classByAnyName(loader, "uh9", "p5.c");
            Class<?> pair = Class.forName("kotlin.Pair", false, loader);
            java.lang.reflect.Constructor<?> pairCtor = pair.getConstructor(
                    Object.class, Object.class);
            java.lang.reflect.Field landscapeCache = HookSymbols.field(
                    rotation, "a", pair, true, true);
            Method dimensions = HookSymbols.method(rotation, "a", pair,
                    true, false, int.class);
            log(Log.INFO, TAG, "resolved rotation symbol: " + dimensions);
            hook(dimensions).intercept(chain -> {
                refresh();
                if (requestedColumns == 0 || requestedRows == 0) {
                    return chain.proceed();
                }
                try {
                    int orientation = (Integer) chain.getArgs().get(0);
                    // p5.c.a(0) = landscape; p5.c.a(1) = portrait.
                    Object horizontal = pairCtor.newInstance(
                            requestedColumns, requestedRows);
                    landscapeCache.set(null, horizontal);
                    Object effective = orientation == 1
                            ? pairCtor.newInstance(requestedRows, requestedColumns)
                            : horizontal;
                    String fingerprint = requestedColumns + "x" + requestedRows;
                    if (!fingerprint.equals(lastRotationGridLog)) {
                        lastRotationGridLog = fingerprint;
                        log(Log.INFO, TAG, "rotation/loader bounds synchronized: "
                                + fingerprint + " (portrait "
                                + requestedRows + "x" + requestedColumns + ")");
                    }
                    return effective;
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "rotation grid override failed: " + error);
                    return chain.proceed();
                }
            });
            log(Log.INFO, TAG, "rotation and loader grid bounds hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "rotation and loader grid hook failed: " + error);
        }

        // Protect desktop widget records from irreversible cleanup due
        // transient provider/size/placement failures while using a custom grid.
        // Keep the row available for recovery; never claim it was displayed.
        try {
            Class<?> cursorClass = Class.forName(
                    "com.android.launcher3.model.OplusLoaderCursor", false, loader);
            Method mark = HookSymbols.method(cursorClass, "markDeleted",
                    void.class, false, false, String.class);
            hook(mark).intercept(chain -> {
                String reason = (String) chain.getArgs().get(0);
                if (requestedColumns <= 0 || requestedRows <= 0) {
                    return chain.proceed();
                }
                try {
                    Cursor current = (Cursor) chain.getThisObject();
                    int container = current.getInt(
                            current.getColumnIndexOrThrow("container"));
                    if (container != -100) return chain.proceed();
                    int id = current.getInt(current.getColumnIndexOrThrow("_id"));
                    int itemType = current.getInt(
                            current.getColumnIndexOrThrow("itemType"));
                    int x = current.getInt(current.getColumnIndexOrThrow("cellX"));
                    int y = current.getInt(current.getColumnIndexOrThrow("cellY"));
                    int spanX = current.getInt(current.getColumnIndexOrThrow("spanX"));
                    int spanY = current.getInt(current.getColumnIndexOrThrow("spanY"));
                    boolean widget = GridIntegrity.shouldRetainWidgetLoaderRow(
                            container, itemType, reason, true);
                    boolean overlap = GridIntegrity.shouldRetainRejectedItem(
                            reason, container, x, y, spanX, spanY,
                            requestedColumns, requestedRows);
                    if (widget || overlap) {
                        log(Log.ERROR, TAG, "preserved rejected workspace DB row id="
                                + id + ", type=" + itemType + ", pos=" + x + "," + y
                                + ", span=" + spanX + "x" + spanY
                                + ", reason=" + reason);
                        return null;
                    }
                    if (itemType == 4 || itemType == 99) {
                        log(Log.ERROR, TAG, "widget loader requested native deletion "
                                + "id=" + id + ", reason=" + reason);
                    }
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "layout deletion guard unable to inspect row: "
                            + error);
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "widget and position loader deletion guard installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "widget and position loader deletion guard failed: "
                    + error);
        }

        // Observe placement failures; never bypass validation or suppress
        // unrelated database deletions. A real collision must still be rejected.
        try {
            Class<?> cursor = Class.forName(
                    "com.android.launcher3.model.OplusLoaderCursor", false, loader);
            Class<?> itemClass = Class.forName(
                    "com.android.launcher3.model.data.ItemInfo", false, loader);
            Class<?> screens = Class.forName(
                    "com.android.launcher3.util.IntArray", false, loader);
            Method placement = HookSymbols.method(cursor, "checkItemPlacement",
                    boolean.class, false, false, itemClass, screens);
            hook(placement).intercept(chain -> {
                Object result = chain.proceed();
                if (Boolean.FALSE.equals(result) && requestedColumns > 0) {
                    Object item = chain.getArgs().get(0);
                    try {
                        int container = itemClass.getField("container").getInt(item);
                        if (container == -100) {
                            int x = integer(item, "getCellX");
                            int y = integer(item, "getCellY");
                            int w = integer(item, "getSpanX");
                            int h = integer(item, "getSpanY");
                            log(Log.ERROR, TAG, "workspace loader rejected position "
                                    + x + "," + y + " span " + w + "x" + h
                                    + "; configured landscape "
                                    + requestedColumns + "x" + requestedRows);
                        }
                    } catch (Throwable error) {
                        log(Log.WARN, TAG, "workspace placement failure: " + error);
                    }
                }
                return result;
            });
            log(Log.INFO, TAG, "workspace loader diagnostics installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace loader diagnostics hook failed: " + error);
        }

        // Prevent the OS12 compatibility converter from rewriting an explicit
        // custom grid with a legacy 4x5 / 6x5 layout while c6.b is reading it.
        try {
            Class<?> legacy = Class.forName(
                    "com.android.launcher3.model.OplusUnionGridCompatHelper", false, loader);
            Method compat = HookSymbols.method(legacy, "updateGridIfNeeded",
                    void.class, true, false, Context.class, int[].class);
            hook(compat).intercept(chain -> {
                refresh();
                if (requestedColumns > 0 && requestedRows > 0) return null;
                return chain.proceed();
            });
            log(Log.INFO, TAG, "legacy grid compat hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "legacy grid compat hook failed: " + error);
        }

        // c6.b(Context) is the normal-mode source of native grid dimensions.
        // Its result also feeds RotationLayoutUtils, so keeping it in sync
        // prevents a later orientation pass from restoring the stock 6-column grid.
        try {
            Class<?> gridConfig = HookSymbols.classByAnyName(loader,
                    "wjb", "com.android.launcher.c6");
            // b(Context) and c(Context) both return int[]. A signature-only
            // fallback is ambiguous, so this entry must retain a known name.
            Method read = HookSymbols.method(gridConfig, "b", int[].class,
                    true, false, Context.class);
            hook(read).intercept(chain -> {
                Object result = chain.proceed();
                refresh();
                if (requestedColumns <= 0 || requestedRows <= 0) return result;
                return new int[]{requestedColumns, requestedRows};
            });
            log(Log.INFO, TAG, "native grid source hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "native grid source hook failed: " + error);
        }

        // Three independent hooks. The old combined try block resolved
        // b(Context) and e(boolean) BEFORE it installed c(). If either changed
        // across versions, the loader's COMPACT/FREE query remained stock,
        // and its COMPACT branch repacked 7th/8th column icons at startup.
        try {
            Class<?> arrangement = HookSymbols.classByAnyName(loader,
                    "com.android.launcher.layout.i",
                    "com.android.launcher.layout.LayoutUtilsManager");
            Method isCompact = HookSymbols.method(arrangement, "c",
                    boolean.class, true, true);
            hook(isCompact).intercept(chain -> {
                if (LayoutPersistencePolicy.forceFree(
                        requestedColumns, requestedRows, keepEmptySlots)) {
                    if (!freeModeQueryObserved) {
                        freeModeQueryObserved = true;
                        log(Log.INFO, TAG, "FREE loader mode ACTIVE; "
                                + "native compact repacking is disabled");
                    }
                    return Boolean.FALSE;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "FREE model query installed: " + isCompact);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "CRITICAL: FREE model query hook failed; "
                    + "startup compact reflow is still possible: " + error);
        }

        try {
            Class<?> arrangement = HookSymbols.classByAnyName(loader,
                    "com.android.launcher.layout.i",
                    "com.android.launcher.layout.LayoutUtilsManager");
            Method setMode = HookSymbols.method(arrangement, "e",
                    void.class, true, true, boolean.class);
            hook(setMode).intercept(chain -> {
                if (LayoutPersistencePolicy.forceFree(
                        requestedColumns, requestedRows, keepEmptySlots)) {
                    return chain.proceed(new Object[]{Boolean.FALSE});
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "FREE native mode setter installed: " + setMode);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "FREE native mode setter hook failed: " + error);
        }

        try {
            Class<?> arrangement = HookSymbols.classByAnyName(loader,
                    "com.android.launcher.layout.i",
                    "com.android.launcher.layout.LayoutUtilsManager");
            Method initMode = HookSymbols.method(arrangement, "b",
                    void.class, true, true, Context.class);
            hook(initMode).intercept(chain -> {
                Context context = (Context) chain.getArgs().get(0);
                if (requestedColumns > 0 && requestedRows > 0) {
                    SharedPreferences prefs = context.getSharedPreferences(
                            OPLUS_PREFS, Context.MODE_PRIVATE);
                    String previousKey = "olaunchermod_previous_compact";
                    if (keepEmptySlots) {
                        if (prefs.getBoolean("layout_is_compact", true)
                                || !prefs.contains(previousKey)) {
                            SharedPreferences.Editor editor = prefs.edit();
                            if (!prefs.contains(previousKey)) {
                                editor.putBoolean(previousKey,
                                        prefs.getBoolean("layout_is_compact", true));
                            }
                            editor.putBoolean("layout_is_compact", false).commit();
                        }
                    } else if (prefs.contains(previousKey)) {
                        boolean previous = prefs.getBoolean(previousKey, true);
                        prefs.edit().putBoolean("layout_is_compact", previous)
                                .remove(previousKey).commit();
                    }
                }
                // Native b() reads the preference and initializes the enum.
                return chain.proceed();
            });
            log(Log.INFO, TAG, "FREE layout preference initializer installed: " + initMode);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "FREE layout preference initializer failed: " + error);
        }

        // OplusLoaderCursor.commitUpdate() writes ONLY its private markUpdate()
        // queue, populated by COMPACT position changes (including vertical
        // compact arrangement). In FREE mode these commits must never rewrite
        // the user's persisted coordinates, even if another native caller
        // briefly forces COMPACT on an unsupported Launcher version.
        try {
            Class<?> cursor = Class.forName(
                    "com.android.launcher3.model.OplusLoaderCursor", false, loader);
            Method commit = HookSymbols.method(cursor, "commitUpdate",
                    void.class, false, false);
            hook(commit).intercept(chain -> {
                if (LayoutPersistencePolicy.forceFree(
                        requestedColumns, requestedRows, keepEmptySlots)) {
                    try {
                        java.lang.reflect.Field updates = HookSymbols.field(
                                cursor, "mUpdateItemList", java.util.ArrayList.class,
                                false, false);
                        java.util.ArrayList<?> list =
                                (java.util.ArrayList<?>) updates.get(chain.getThisObject());
                        if (list != null && !list.isEmpty()) {
                            log(Log.ERROR, TAG, "blocked " + list.size()
                                    + " automatic COMPACT coordinate DB updates "
                                    + "while FREE layout is configured");
                        }
                    } catch (Throwable error) {
                        log(Log.WARN, TAG, "position-update diagnostic failed: " + error);
                    }
                    return null;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "FREE startup coordinate write protection installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "FREE coordinate write protection failed: " + error);
        }

        // THIS is the shared, final boundary used by the real ColorOS
        // OplusStandardLoaderTask -> OplusBaseLoaderTask -> OplusLoaderCursor
        // path. On non-seamless-rotation configurations, I(Pair) returns
        // its input IDP values and never calls p5.c.a(0), so patching only
        // p5.c.a(0) does not protect columns 7 and 8 from model trimming.
        //
        // The same method is used by LoaderCursor and FindSpaceHelper.
        // It must return the canonical LANDSCAPE workspace dimensions: the
        // paired another-cell (portrait) coordinates use p5.c.a(1) instead.
        try {
            Class<?> helperClass = HookSymbols.classByAnyName(loader,
                    "yn9", "j5.i");
            Class<?> pairClass = Class.forName("kotlin.Pair", false, loader);
            Constructor<?> pairConstructor = pairClass.getConstructor(
                    Object.class, Object.class);
            Method effective = HookSymbols.method(helperClass,
                    "yn9".equals(helperClass.getName()) ? "v" : "I",
                    pairClass, false, false, pairClass);
            log(Log.INFO, TAG, "resolved model loader boundary symbol: " + effective);
            hook(effective).intercept(chain -> {
                Object nativeResult = chain.proceed();
                if (requestedColumns <= 0 || requestedRows <= 0) {
                    return nativeResult;
                }
                try {
                    int nativeX = ((Number) pairClass.getMethod("c")
                            .invoke(nativeResult)).intValue();
                    int nativeY = ((Number) pairClass.getMethod("d")
                            .invoke(nativeResult)).intValue();
                    int[] physical = effectiveWorkspaceGrid(
                            launcherContext(loader), nativeX, nativeY);
                    Object result = pairConstructor.newInstance(
                            Integer.valueOf(physical[0]),
                            Integer.valueOf(physical[1]));
                    recordLoaderBounds(loader, nativeResult,
                            physical[0], physical[1]);
                    return result;
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "final loader bounds override failed: " + error);
                    return nativeResult;
                }
            });
            log(Log.INFO, TAG, "final model/loader grid bounds hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "final model/loader grid bounds hook failed: " + error);
        }

        // ColorOS 17.5.5 Workspace.setInsets() obtains native padding from
        // DeviceProfile.mWorkspaceParam.l5c.h() and assigns it to PagedView.
        // Adjust the Workspace view AFTER its native insets processing so
        // widget sheets, Hotseat, and the shared DeviceProfile remain untouched.
        // This changes layout coordinates, not item cellX/cellY or the DB.
        try {
            Class<?> workspace = Class.forName(
                    "com.android.launcher3.Workspace", false, loader);
            Method setInsets = HookSymbols.method(workspace, "setInsets",
                    void.class, false, false, Rect.class);
            hook(setInsets).intercept(chain -> {
                Object result = chain.proceed();
                WorkspaceMarginConfig margins = workspaceMargins;
                if (!margins.isDefault() && chain.getThisObject() instanceof View) {
                    try {
                        View view = (View) chain.getThisObject();
                        int[] original = {view.getPaddingLeft(), view.getPaddingTop(),
                                view.getPaddingRight(), view.getPaddingBottom()};
                        android.util.DisplayMetrics metrics =
                                view.getResources().getDisplayMetrics();
                        int width = view.getWidth() > 0 ? view.getWidth() : metrics.widthPixels;
                        int height = view.getHeight() > 0 ? view.getHeight() : metrics.heightPixels;
                        // Use the same physical-display orientation source as
                        // the 17.5.5 IDP/Loader grid fix. Settings remain
                        // landscape/portrait-specific, not rotated numerically.
                        int[] axes = GridOrientation.current(view.getContext(),
                                8, 4, 8, 4);
                        boolean isPortrait = axes[0] == 4;
                        int[] adjusted = margins.adjust(original, metrics.density,
                                width, height, isPortrait);
                        if (!java.util.Arrays.equals(original, adjusted)) {
                            view.setPadding(adjusted[0], adjusted[1],
                                    adjusted[2], adjusted[3]);
                        }
                        recordMarginState(view.getContext(), margins, original,
                                adjusted, isPortrait);
                    } catch (Throwable error) {
                        log(Log.ERROR, TAG, "workspace margin application failed: " + error);
                    }
                }
                return result;
            });
            log(Log.INFO, TAG, "workspace padding customization installed"
                    + " (17.5.5 Workspace.setInsets)");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace padding customization hook failed: " + error);
        }

        // ColorOS 17.5.5 PagedView.onMeasure subtracts the OS mInsets
        // but *not* Workspace's top/bottom padding from child MeasureSpecs.
        // With portrait 4x8, native pages can still be measured as if the
        // icon grid occupied the full screen, overflowing the reserved
        // status/search area and Hotseat. Fit the PAGE before native measures
        // cells, not by scaling already-laid-out icons or modifying DB rows.
        try {
            Class<?> page = Class.forName(
                    "com.android.launcher3.OplusCellLayout", false, loader);
            Class<?> base = Class.forName(
                    "com.android.launcher3.CellLayout", false, loader);
            Method onMeasure = HookSymbols.method(page, "onMeasure",
                    void.class, false, false, int.class, int.class);
            Method getWorkspace = HookSymbols.method(base, "getWorkspace",
                    Class.forName("com.android.launcher3.Workspace", false, loader),
                    false, false);
            java.lang.reflect.Field borderField = HookSymbols.field(
                    base, "mBorderSpace", Point.class, false, false);
            java.lang.reflect.Field fixedHeight = HookSymbols.field(
                    base, "mFixedCellHeight", int.class, false, false);
            java.lang.reflect.Field fixedWidth = HookSymbols.field(
                    base, "mFixedCellWidth", int.class, false, false);
            hook(onMeasure).intercept(chain -> {
                Object cell = chain.getThisObject();
                if (!isWorkspaceCell(cell) || requestedColumns == 0) {
                    return chain.proceed();
                }
                try {
                    ViewGroup workspace = (ViewGroup) getWorkspace.invoke(cell);
                    if (workspace == null) return chain.proceed();
                    int rows = integer(cell, "getCountY");
                    if (rows < 6) return chain.proceed();
                    int[] axes = effectiveWorkspaceGrid(workspace.getContext(), 0, 0);
                    if (axes[0] >= axes[1]) return chain.proceed();
                    int widthSpec = (Integer) chain.getArgs().get(0);
                    int heightSpec = (Integer) chain.getArgs().get(1);
                    if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.EXACTLY) {
                        return chain.proceed();
                    }
                    Point borders = (Point) borderField.get(cell);
                    int borderY = borders == null ? 0 : borders.y;
                    int originalHeight = View.MeasureSpec.getSize(heightSpec);
                    WorkspaceViewportPolicy.Fit fit = WorkspaceViewportPolicy.portraitFit(
                            originalHeight, workspace.getPaddingTop(),
                            workspace.getPaddingBottom(), rows, borderY, true);
                    int storedFixedHeight = fixedHeight.getInt(cell);
                    if (WorkspaceViewportPolicy.fixedCellOverflows(
                            storedFixedHeight, fit.maxCellHeight)) {
                        // Native CellLayout.onMeasure skips recalculation when
                        // both fixed-cell dimensions are nonzero. Release its
                        // outdated fixed dimensions only for an oversized grid.
                        fixedHeight.setInt(cell, 0);
                        fixedWidth.setInt(cell, 0);
                    }
                    Object result = fit.reduced
                            ? chain.proceed(new Object[]{widthSpec,
                                    View.MeasureSpec.makeMeasureSpec(
                                            fit.childHeight, View.MeasureSpec.EXACTLY)})
                            : chain.proceed();
                    if (fit.reduced) {
                        recordViewportState(workspace.getContext(), originalHeight,
                                fit.childHeight, rows, workspace.getPaddingTop(),
                                workspace.getPaddingBottom(), integer(cell, "getCellHeight"));
                    }
                    return result;
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "workspace viewport fit failed: " + error);
                    return chain.proceed();
                }
            });
            log(Log.INFO, TAG, "17.5.5 workspace portrait viewport-fit installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace viewport-fit hook unavailable: " + error);
        }

        // Workspace pages are initialized from the IDP inside CellLayout's
        // constructor. Correct the actual grid after OplusCellLayout is ready,
        // before workspace items are bound to the page.
        try {
            Class<?> page = Class.forName("com.android.launcher3.OplusCellLayout", false, loader);
            for (java.lang.reflect.Constructor<?> constructor : page.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length == 0
                        || !Context.class.isAssignableFrom(parameters[0])
                        || constructor.isSynthetic()) continue;
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    refresh();
                    if (chain.getArgs().size() > 0 && chain.getArgs().get(0) instanceof Context) {
                        ensureWorkspaceGrid(chain.getThisObject(),
                                (Context) chain.getArgs().get(0), loader, "page_created");
                    }
                    return result;
                });
            }
            log(Log.INFO, TAG, "workspace constructor hooks installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace constructor hooks failed: " + error);
        }

        // This 2-argument override recalculates Oplus cell widths. Scope it to
        // genuine workspace pages, never folder or Hotseat grids.
        try {
            Class<?> page = Class.forName("com.android.launcher3.OplusCellLayout", false, loader);
            Method set = HookSymbols.method(page, "setGridSize",
                    void.class, false, false, int.class, int.class);
            hook(set).intercept(chain -> {
                refresh();
                Object cell = chain.getThisObject();
                if (!isWorkspaceCell(cell) || requestedColumns <= 0) return chain.proceed();
                int[] effective = effectiveWorkspaceGrid(
                        ((android.view.View) cell).getContext(), 0, 0);
                Object result = chain.proceed(new Object[]{effective[0], effective[1]});
                // The inherited CellLayout setter creates empty occupancy
                // matrices, including when existing children were already bound.
                syncWorkspaceOccupancy(cell, loader, "setGridSize_2");
                try {
                    int[] actual = actualGrid(cell);
                    recordGridState(((android.view.View) cell).getContext(),
                            "setGridSize_2", loader, actual[0], actual[1]);
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "2-arg workspace diagnostic failed: " + error);
                }
                return result;
            });
            log(Log.INFO, TAG, "workspace 2-arg grid hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace 2-arg grid hook failed: " + error);
        }

        // Native LayoutSettingsHelper and rotation code also call the inherited
        // 3-argument method, bypassing the 2-argument override.
        try {
            Class<?> base = Class.forName("com.android.launcher3.CellLayout", false, loader);
            Method set = HookSymbols.method(base, "setGridSize",
                    void.class, false, false,
                    int.class, int.class, boolean.class);
            hook(set).intercept(chain -> {
                refresh();
                Object cell = chain.getThisObject();
                if (!isWorkspaceCell(cell) || requestedColumns <= 0) return chain.proceed();
                int[] effective = effectiveWorkspaceGrid(
                        ((android.view.View) cell).getContext(), 0, 0);
                boolean relayout = (Boolean) chain.getArgs().get(2);
                Object result = chain.proceed(new Object[]{
                        effective[0], effective[1], relayout});
                // Model drag/drop and the card squeeze algorithm consult
                // mOccupied; restore it from bound children after grid reset.
                syncWorkspaceOccupancy(cell, loader, "setGridSize_3");
                try {
                    int[] actual = actualGrid(cell);
                    recordGridState(((android.view.View) cell).getContext(),
                            "setGridSize_3", loader, actual[0], actual[1]);
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "3-arg workspace diagnostic failed: " + error);
                }
                return result;
            });
            log(Log.INFO, TAG, "workspace 3-arg grid hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace 3-arg grid hook failed: " + error);
        }

        // Rotation's setGridCellSize updates cell dimensions before calling
        // the base 3-arg grid setter. Rewrite both axes at this earlier entry.
        try {
            Class<?> page = Class.forName("com.android.launcher3.OplusCellLayout", false, loader);
            Method set = HookSymbols.method(page, "setGridCellSize",
                    void.class, false, false,
                    int.class, int.class, int.class, int.class);
            hook(set).intercept(chain -> {
                refresh();
                if (!isWorkspaceCell(chain.getThisObject()) || requestedColumns <= 0) {
                    return chain.proceed();
                }
                int[] effective = effectiveWorkspaceGrid(
                        ((android.view.View) chain.getThisObject()).getContext(), 0, 0);
                Object[] args = chain.getArgs().toArray();
                args[0] = effective[0];
                args[1] = effective[1];
                return chain.proceed(args);
            });
            log(Log.INFO, TAG, "workspace rotation grid hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace rotation grid hook failed: " + error);
        }

        // This override performs ColorOS workspace compact/reflow, distinct from
        // ordinary drag-and-drop relocation. Never override generic setGridSize.
        try {
            Class<?> cell = Class.forName("com.android.launcher3.OplusCellLayout", false, loader);
            Method compact = HookSymbols.method(cell, "fillUpEmptyCell",
                    void.class, false, false,
                    int[].class, int[].class, boolean.class, boolean.class);
            hook(compact).intercept(chain -> {
                refresh();
                if (requestedColumns > 0 && requestedRows > 0 && keepEmptySlots
                        && isWorkspaceCell(chain.getThisObject())) {
                    return null;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "free-placement compaction hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "free-placement hook failed: " + error);
        }

        // LoaderTask.loadWorkspace calls create_empty_db immediately after a
        // failed migration. Guard only that one failure on the same thread;
        // never suppress a user-initiated reset or another caller's request.
        try {
            Class<?> settings = Class.forName(
                    "com.android.launcher3.LauncherSettings$Settings", false, loader);
            Method call = HookSymbols.method(settings, "call",
                    android.os.Bundle.class, true, false,
                    android.content.ContentResolver.class, String.class, String.class);
            hook(call).intercept(chain -> {
                if ("create_empty_db".equals(chain.getArgs().get(1))
                        && Boolean.TRUE.equals(migrationResetPending.get())
                        && requestedColumns > 0) {
                    migrationResetPending.remove();
                    log(Log.ERROR, TAG, "prevented destructive launcher DB reset "
                            + "after failed custom-grid migration");
                    return new android.os.Bundle();
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "migration-failure DB reset guard installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "migration-failure DB reset guard failed: " + error);
        }

        try {
            Class<?> migration = Class.forName(
                    "com.android.launcher3.model.GridSizeMigrationTaskV2", false, loader);
            Method migrate = HookSymbols.method(migration, "migrateGridIfNeeded",
                    boolean.class, true, false, Context.class);
            hook(migrate).intercept(chain -> {
                refresh();
                migrationResetPending.remove();
                try {
                    Context context = (Context) chain.getArgs().get(0);
                    if (requestedColumns > 0 && requestedRows > 0) {
                        Class<?> app = Class.forName(
                                "com.android.launcher3.LauncherAppState", false, loader);
                        Object idp = app.getMethod("getIDP", Context.class).invoke(null, context);
                        if (idp == null) {
                            log(Log.ERROR, TAG, "skip destructive migration: IDP unavailable");
                            return Boolean.TRUE;
                        }
                        int actualX = integer(idp, "getNumColumns");
                        int actualY = integer(idp, "getNumRows");
                        if (!GridIntegrity.isCanonicalMigrationTarget(
                                requestedColumns, requestedRows, actualX, actualY)) {
                            // Migration writes coordinates from the IDP to
                            // singledesktopitems. A transient 6-column or
                            // portrait 4x8 IDP is NOT a canonical 8x4
                            // migration target. Do not let it repack the DB.
                            log(Log.ERROR, TAG, "skip destructive migration: IDP "
                                    + actualX + "x" + actualY + " != landscape "
                                    + requestedColumns + "x" + requestedRows);
                            return Boolean.TRUE;
                        }
                    }
                    if (preserveExpansion(context, loader)) {
                        return Boolean.TRUE;
                    }
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "native migration retained: " + error);
                }
                Object result = chain.proceed();
                if (Boolean.FALSE.equals(result) && requestedColumns > 0) {
                    migrationResetPending.set(Boolean.TRUE);
                    log(Log.ERROR, TAG, "native grid migration failed; "
                            + "blocking automatic create_empty_db on this loader thread");
                }
                return result;
            });
            log(Log.INFO, TAG, "expansion-safe migration hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "migration hook failed: " + error);
        }

        // A separate two-argument overload is used for some Launcher paths.
        // Preview migration (non-null second argument) remains completely
        // native. Only same-DB expansion with a null profile may be skipped.
        try {
            Class<?> migration = Class.forName(
                    "com.android.launcher3.model.GridSizeMigrationTaskV2", false, loader);
            Class<?> profile = Class.forName(
                    "com.android.launcher3.OplusInvariantDeviceProfile", false, loader);
            Method migrate = HookSymbols.method(migration, "migrateGridIfNeeded",
                    boolean.class, true, false, Context.class, profile);
            hook(migrate).intercept(chain -> {
                if (chain.getArgs().get(1) == null && requestedColumns > 0) {
                    try {
                        if (preserveExpansion((Context) chain.getArgs().get(0), loader)) {
                            return Boolean.TRUE;
                        }
                    } catch (Throwable error) {
                        log(Log.WARN, TAG, "secondary migration guard failed: " + error);
                    }
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "secondary non-preview expansion guard installed");
        } catch (Throwable error) {
            log(Log.WARN, TAG, "secondary migration overload unavailable: " + error);
        }
    }
}
