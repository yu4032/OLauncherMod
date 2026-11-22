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
                if (requestedColumns > 0 && requestedRows > 0) {
                    try {
                        synchronizeOplusGrid((Context) chain.getArgs().get(0), loader);
                    } catch (Throwable error) {
                        log(Log.ERROR, TAG, "persistent grid sync failed: " + error);
                    }
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "persistent grid hook installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "persistent grid hook failed: " + error);
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
