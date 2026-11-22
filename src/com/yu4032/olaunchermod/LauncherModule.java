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
    private long lastRead;

    private synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastRead < 2000L) return;
        lastRead = now;
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
        Method save = helper.getDeclaredMethod(
                "saveLayoutInfo", Context.class, int.class, int.class);
        save.setAccessible(true);
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
        if (oldColumns < 1 || oldRows < 1
                || newColumns < oldColumns || newRows < oldRows
                || (newColumns == oldColumns && newRows == oldRows)) return false;

        File file = context.getDatabasePath(targetDb);
        if (!file.isFile()) return false;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            try (Cursor cursor = db.rawQuery(
                    "SELECT cellX,cellY,spanX,spanY FROM singledesktopitems WHERE container=-100",
                    null)) {
                while (cursor.moveToNext()) {
                    if (cursor.isNull(0) || cursor.isNull(1)) return false;
                    int x = cursor.getInt(0);
                    int y = cursor.getInt(1);
                    int w = Math.max(1, cursor.getInt(2));
                    int h = Math.max(1, cursor.getInt(3));
                    if (x < 0 || y < 0
                            || x + w > newColumns || y + h > newRows) return false;
                }
            }
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
            Method portrait = rotation.getDeclaredMethod("d");
            portrait.setAccessible(true);
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
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                    new File(dir, "launcher_grid_status"))) {
                output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            log(Log.INFO, TAG, "grid state: " + value.replace('\n', ' '));
        } catch (Throwable error) {
            log(Log.WARN, TAG, "unable to record actual grid: " + error);
        }
    }

    private void ensureWorkspaceGrid(Object cell, Context context,
            ClassLoader loader, String stage) {
        if (!isWorkspaceCell(cell) || requestedColumns <= 0 || requestedRows <= 0) return;
        try {
            int[] expected = effectiveWorkspaceGrid(loader);
            int[] before = actualGrid(cell);
            if (before[0] != expected[0] || before[1] != expected[1]) {
                Method set = cell.getClass().getMethod(
                        "setGridSize", int.class, int.class);
                set.invoke(cell, expected[0], expected[1]);
            }
            int[] after = actualGrid(cell);
            recordGridState(context, stage, loader, after[0], after[1]);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "workspace grid validation failed: " + error);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!"com.android.launcher".equals(param.getPackageName())) return;
        ClassLoader loader = param.getDefaultClassLoader();
        refresh();

        try {
            Class<?> idp = Class.forName(
                    "com.android.launcher3.InvariantDeviceProfile", false, loader);
            Method method = idp.getDeclaredMethod("setColumnsAndRows", int.class, int.class);
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
            Method init = ext.getDeclaredMethod(
                    "injectPreInitGridExt", Context.class, option);
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
                            Method set = profile.getClass().getMethod(
                                    "setColumnsAndRows", int.class, int.class);
                            set.invoke(profile, requestedColumns, requestedRows);
                            x = integer(profile, "getNumColumns");
                            y = integer(profile, "getNumRows");
                        }
                        if (x != target[0] || y != target[1]) {
                            log(Log.ERROR, TAG, "IDP axes mismatch after init: "
                                    + x + "x" + y + " expected " + target[0] + "x" + target[1]);
                        }
                        Class<?> config = Class.forName("com.android.launcher.c6", false, loader);
                        java.lang.reflect.Field xField = config.getDeclaredField("f1273g");
                        java.lang.reflect.Field yField = config.getDeclaredField("h");
                        xField.setAccessible(true);
                        yField.setAccessible(true);
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


        // c6.b(Context) is the normal-mode source of native grid dimensions.
        // Its result also feeds RotationLayoutUtils, so keeping it in sync
        // prevents a later orientation pass from restoring the stock 6-column grid.
        try {
            Class<?> gridConfig = Class.forName("com.android.launcher.c6", false, loader);
            Method read = gridConfig.getDeclaredMethod("b", Context.class);
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

        // Workspace pages are initialized from the IDP inside CellLayout's
        // constructor. Correct the actual grid after OplusCellLayout is ready,
        // before workspace items are bound to the page.
        try {
            Class<?> page = Class.forName("com.android.launcher3.OplusCellLayout", false, loader);
            for (java.lang.reflect.Constructor<?> constructor : page.getDeclaredConstructors()) {
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
            Method set = page.getDeclaredMethod("setGridSize", int.class, int.class);
            hook(set).intercept(chain -> {
                refresh();
                Object cell = chain.getThisObject();
                if (!isWorkspaceCell(cell) || requestedColumns <= 0) return chain.proceed();
                int[] effective = effectiveWorkspaceGrid(loader);
                Object result = chain.proceed(new Object[]{effective[0], effective[1]});
                int[] actual = actualGrid(cell);
                recordGridState(((android.view.View) cell).getContext(),
                        "setGridSize_2", loader, actual[0], actual[1]);
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
            Method set = base.getDeclaredMethod("setGridSize",
                    int.class, int.class, boolean.class);
            hook(set).intercept(chain -> {
                refresh();
                Object cell = chain.getThisObject();
                if (!isWorkspaceCell(cell) || requestedColumns <= 0) return chain.proceed();
                int[] effective = effectiveWorkspaceGrid(loader);
                boolean relayout = (Boolean) chain.getArgs().get(2);
                Object result = chain.proceed(new Object[]{
                        effective[0], effective[1], relayout});
                int[] actual = actualGrid(cell);
                recordGridState(((android.view.View) cell).getContext(),
                        "setGridSize_3", loader, actual[0], actual[1]);
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
            Method set = page.getDeclaredMethod("setGridCellSize",
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
            Method compact = cell.getDeclaredMethod(
                    "fillUpEmptyCell", int[].class, int[].class, boolean.class, boolean.class);
            hook(compact).intercept(chain -> {
                refresh();
                if (requestedColumns > 0 && requestedRows > 0 && keepEmptySlots) {
                    return null;
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "free-placement compaction hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "free-placement hook failed: " + error);
        }

        try {
            Class<?> migration = Class.forName(
                    "com.android.launcher3.model.GridSizeMigrationTaskV2", false, loader);
            Method migrate = migration.getDeclaredMethod(
                    "migrateGridIfNeeded", Context.class);
            hook(migrate).intercept(chain -> {
                refresh();
                try {
                    if (preserveExpansion((Context) chain.getArgs().get(0), loader)) {
                        return Boolean.TRUE;
                    }
                } catch (Throwable error) {
                    log(Log.WARN, TAG, "native migration retained: " + error);
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "expansion-safe migration hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "migration hook failed: " + error);
        }
    }
}
