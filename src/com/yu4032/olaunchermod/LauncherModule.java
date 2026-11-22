package com.yu4032.olaunchermod;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

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

    private volatile int requestedColumns;
    private volatile int requestedRows;
    private volatile boolean keepEmptySlots = true;
    // A layout change requires a fresh Launcher process, never a mid-drag
    // configuration swap between live views, loader and database.
    private boolean configInitialized;
    private volatile String lastRotationGridLog = "";
    private volatile String lastGridReport = "";
    private volatile String lastLoaderBoundsReport = "";
    private final ThreadLocal<Boolean> migrationResetPending = new ThreadLocal<>();

    private synchronized void refresh() {
        if (configInitialized) return;
        configInitialized = true;
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

        Class<?> helper = Class.forName(
                "com.android.launcher.togglebar.LayoutSettingsHelper", false, loader);
        Method save = HookSymbols.method(helper, "saveLayoutInfo",
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
        if (sourceDb == null || sourceDb.isEmpty()
                || !Objects.equals(sourceDb, targetDb)) return false;
        if (integer(source, "getNumHotseat") != integer(target, "getNumHotseat")) return false;
        if (integer(source, "getDeviceType") != integer(target, "getDeviceType")) return false;

        int oldColumns = integer(source, "getColumns");
        int oldRows = integer(source, "getRows");
        int newColumns = integer(target, "getColumns");
        int newRows = integer(target, "getRows");
        File file = context.getDatabasePath(targetDb);
        if (!file.isFile()) return false;

        java.util.List<GridIntegrity.Placement> placements = new java.util.ArrayList<>();
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT screen,cellX,cellY,spanX,spanY,vCellX,vCellY "
                            + "FROM singledesktopitems WHERE container=-100", null)) {
                while (cursor.moveToNext()) {
                    if (cursor.isNull(0) || cursor.isNull(1) || cursor.isNull(2)
                            || cursor.isNull(3) || cursor.isNull(4)) return false;
                    int portraitX = cursor.isNull(5) ? -1 : cursor.getInt(5);
                    int portraitY = cursor.isNull(6) ? -1 : cursor.getInt(6);
                    placements.add(new GridIntegrity.Placement(
                            cursor.getInt(0), cursor.getInt(1), cursor.getInt(2),
                            cursor.getInt(3), cursor.getInt(4), portraitX, portraitY));
                }
            }
        }
        if (!GridIntegrity.canPreserveExpansion(
                oldColumns, oldRows, newColumns, newRows, placements)) {
            log(Log.WARN, TAG, "expansion requires native migration: "
                    + oldColumns + "x" + oldRows + " -> "
                    + newColumns + "x" + newRows
                    + "; records=" + placements.size());
            return false;
        }

        stateClass.getMethod("writeToPrefs", Context.class).invoke(target, context);
        log(Log.INFO, TAG, "preserved icon coordinates on expansion "
                + oldColumns + "x" + oldRows + " -> " + newColumns + "x" + newRows);
        return true;
    }


    // On ColorOS tablets the native orientation helper swaps the persisted
    // landscape grid while in portrait. Match that exact rule at every layer.
    private int[] effectiveWorkspaceGrid(ClassLoader loader) {
        int x = requestedColumns;
        int y = requestedRows;
        if (x <= 0 || y <= 0) return new int[]{0, 0};
        try {
            Class<?> rotation = Class.forName("p5.c", false, loader);
            Method portrait = HookSymbols.method(rotation, "d", boolean.class,
                    true, true);
            if (x > y && Boolean.TRUE.equals(portrait.invoke(null))) {
                return new int[]{y, x};
            }
        } catch (Throwable error) {
            log(Log.WARN, TAG, "rotation helper unavailable; using stored grid: " + error);
        }
        return new int[]{x, y};
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
            int[] effective = effectiveWorkspaceGrid(loader);
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
            Context context = (Context) app.getMethod("getAppContext").invoke(null);
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

    private void ensureWorkspaceGrid(Object cell, Context context,
            ClassLoader loader, String stage) {
        if (!isWorkspaceCell(cell) || requestedColumns <= 0 || requestedRows <= 0) return;
        try {
            int[] expected = effectiveWorkspaceGrid(loader);
            int[] before = actualGrid(cell);
            if (before[0] != expected[0] || before[1] != expected[1]) {
                Method set = HookSymbols.method(cell.getClass(), "setGridSize",
                        void.class, false, false, int.class, int.class);
                set.invoke(cell, expected[0], expected[1]);
            }
            int[] after = actualGrid(cell);
            recordGridState(context, stage, loader, after[0], after[1]);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace grid validation failed: " + error);
        }
    }

    /**
     * A hard failure is justified only for the TWO methods responsible for
     * displaying the actual workspace grid. All loader, rotation, arrangement
     * and migration symbols are OPTIONAL capabilities: their hooks are resolved
     * in independent try/catch blocks below. Requiring them all here caused
     * debug-6 to skip even the previously working 8x4 workspace hooks.
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
            log(Log.ERROR, TAG, "essential workspace methods unavailable; "
                    + "no custom grid hooks installed: " + error);
            return false;
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!"com.android.launcher".equals(param.getPackageName())) return;
        ClassLoader loader = param.getDefaultClassLoader();
        refresh();
        if (requestedColumns > 0 && !preflightVisualGridSymbols(loader)) return;

        try {
            Class<?> idp = Class.forName(
                    "com.android.launcher3.InvariantDeviceProfile", false, loader);
            Method method = HookSymbols.method(idp, "setColumnsAndRows",
                    void.class, false, false, int.class, int.class);
            hook(method).intercept(chain -> {
                refresh();
                if (requestedColumns > 0 && requestedRows > 0) {
                    return chain.proceed(new Object[]{requestedColumns, requestedRows});
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
                            x = integer(profile, "getNumColumns");
                            y = integer(profile, "getNumRows");
                        }
                        if (x != target[0] || y != target[1]) {
                            log(Log.ERROR, TAG, "IDP axes mismatch after init: "
                                    + x + "x" + y + " expected " + target[0] + "x" + target[1]);
                        }
                        Class<?> config = Class.forName("com.android.launcher.c6", false, loader);
                        java.lang.reflect.Field xField = HookSymbols.field(
                                config, "f1273g", int.class, true, false);
                        java.lang.reflect.Field yField = HookSymbols.field(
                                config, "h", int.class, true, false);
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
            Class<?> rotation = Class.forName("p5.c", false, loader);
            Class<?> pair = Class.forName("kotlin.Pair", false, loader);
            java.lang.reflect.Constructor<?> pairCtor = pair.getConstructor(
                    Object.class, Object.class);
            java.lang.reflect.Field landscapeCache = HookSymbols.field(
                    rotation, "a", pair, true, true);
            Method dimensions = HookSymbols.method(rotation, "a", pair,
                    true, true, int.class);
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

        // Failsafe: an otherwise in-bounds workspace row must not be permanently
        // deleted solely because the loader saw a transient overlap. Keep the
        // row for recovery, but do not pretend that it was bound successfully.
        try {
            Class<?> cursorClass = Class.forName(
                    "com.android.launcher3.model.OplusLoaderCursor", false, loader);
            Method mark = HookSymbols.method(cursorClass, "markDeleted",
                    void.class, false, false, String.class);
            hook(mark).intercept(chain -> {
                refresh();
                String reason = (String) chain.getArgs().get(0);
                if (!"Item position overlap".equals(reason) || requestedColumns == 0) {
                    return chain.proceed();
                }
                try {
                    Cursor current = (Cursor) chain.getThisObject();
                    int container = current.getInt(
                            current.getColumnIndexOrThrow("container"));
                    int x = current.getInt(current.getColumnIndexOrThrow("cellX"));
                    int y = current.getInt(current.getColumnIndexOrThrow("cellY"));
                    int spanX = current.getInt(current.getColumnIndexOrThrow("spanX"));
                    int spanY = current.getInt(current.getColumnIndexOrThrow("spanY"));
                    if (!GridIntegrity.shouldRetainRejectedItem(reason,
                            container, x, y, spanX, spanY,
                            requestedColumns, requestedRows)) {
                        return chain.proceed();
                    }
                    int id = current.getInt(current.getColumnIndexOrThrow("_id"));
                    log(Log.ERROR, TAG, "preserved rejected workspace DB row "
                            + id + " at " + x + "," + y
                            + " span=" + spanX + "x" + spanY
                            + "; investigate loader occupancy");
                    return null;
                } catch (Throwable error) {
                    log(Log.ERROR, TAG, "layout-only deletion guard unavailable: "
                            + error);
                    return chain.proceed();
                }
            });
            log(Log.INFO, TAG, "layout-only deletion guard installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "layout-only deletion guard failed: " + error);
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
            Class<?> gridConfig = Class.forName("com.android.launcher.c6", false, loader);
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

        // The arrangement selector controls OplusLoaderCursor, drag/drop,
        // and the UI. The prior fillUpEmptyCell hook only addressed the UI.
        try {
            Class<?> arrangement = Class.forName(
                    "com.android.launcher.layout.LayoutUtilsManager", false, loader);
            Method initMode = HookSymbols.method(arrangement, "b",
                    void.class, true, true, Context.class);
            Method setMode = HookSymbols.method(arrangement, "e",
                    void.class, true, true, boolean.class);
            hook(initMode).intercept(chain -> {
                Object result = chain.proceed();
                refresh();
                if (requestedColumns > 0) {
                    Context context = (Context) chain.getArgs().get(0);
                    SharedPreferences prefs = context.getSharedPreferences(
                            OPLUS_PREFS, Context.MODE_PRIVATE);
                    final String previousKey = "olaunchermod_previous_compact";
                    if (keepEmptySlots) {
                        SharedPreferences.Editor editor = prefs.edit();
                        if (!prefs.contains(previousKey)) {
                            editor.putBoolean(previousKey,
                                    prefs.getBoolean("layout_is_compact", true));
                        }
                        editor.putBoolean("layout_is_compact", false).apply();
                    } else if (prefs.contains(previousKey)) {
                        boolean previous = prefs.getBoolean(previousKey, true);
                        prefs.edit().putBoolean("layout_is_compact", previous)
                                .remove(previousKey).apply();
                    }
                    try {
                        setMode.invoke(null, prefs.getBoolean("layout_is_compact", true));
                    } catch (Throwable error) {
                        log(Log.ERROR, TAG, "unable to restore arrangement mode: " + error);
                    }
                }
                return result;
            });
            Method isCompact = HookSymbols.method(arrangement, "c",
                    boolean.class, true, true);
            hook(isCompact).intercept(chain -> {
                refresh();
                return requestedColumns > 0 && keepEmptySlots
                        ? Boolean.FALSE : chain.proceed();
            });
            log(Log.INFO, TAG, "native FREE arrangement hooks installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "native FREE arrangement hooks failed: " + error);
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
            Class<?> helperClass = Class.forName("j5.i", false, loader);
            Class<?> pairClass = Class.forName("kotlin.Pair", false, loader);
            Constructor<?> pairConstructor = pairClass.getConstructor(
                    Object.class, Object.class);
            Method effective = HookSymbols.method(helperClass, "I",
                    pairClass, false, true, pairClass);
            log(Log.INFO, TAG, "resolved model loader boundary symbol: " + effective);
            hook(effective).intercept(chain -> {
                Object nativeResult = chain.proceed();
                if (requestedColumns <= 0 || requestedRows <= 0) {
                    return nativeResult;
                }
                try {
                    int[] canonical = GridIntegrity.canonicalLoaderBounds(
                            requestedColumns, requestedRows, 0, 0);
                    Object result = pairConstructor.newInstance(
                            Integer.valueOf(canonical[0]),
                            Integer.valueOf(canonical[1]));
                    recordLoaderBounds(loader, nativeResult,
                            canonical[0], canonical[1]);
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
                int[] effective = effectiveWorkspaceGrid(loader);
                Object result = chain.proceed(new Object[]{effective[0], effective[1]});
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
                int[] effective = effectiveWorkspaceGrid(loader);
                boolean relayout = (Boolean) chain.getArgs().get(2);
                Object result = chain.proceed(new Object[]{
                        effective[0], effective[1], relayout});
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
                int[] effective = effectiveWorkspaceGrid(loader);
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
    }
}
